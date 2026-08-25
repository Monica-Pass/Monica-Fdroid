# Android 接入 MDBX2 实施文档

本文档面向 **Android 客户端实现方**。目标是让你在不阅读 Rust 源码的前提下，
通过 `mdbx-ffi` 把 MDBX2 引擎接入 Android 应用。

配套文档：

- `docs/android/README.zh-CN.md`：Monica for Android 当前的 MDBX 1.0 接入现状
  （纯 Kotlin `MdbxVaultStore`，尚未使用 FFI）。
- `crates/mdbx-ffi/README.zh-CN.md`：跨语言边界的权威定义。本文档与其冲突时，
  以 FFI README 为准。
- `CLIENT_INTEGRATION_GUIDE.zh-CN.md`：与语言无关的客户端接入契约。
- `docs/09-mdbx2-compatibility.zh-CN.md`：MDBX1 / MDBX2 兼容性规则。

本文档中所有方法名、结构体字段、常量数值均取自当前仓库代码。**如果本文档没有
列出某个能力，就说明它当前不通过 FFI 暴露**，请阅读第 12 节。

---

## 1. 先理解三件事

### 1.1 MDBX2 不是一个"文件格式库"，而是一个带策略的本地对象数据库

vault 是一个 SQLite 文件，但客户端 **不得** 把它当 SQLite 用。所有读写都必须
经过 FFI。引擎负责：加密、提交图（commit DAG）、冲突、墓碑与因果确认、
key epoch、Tiga 安全策略、格式迁移。这些不变量无法在 Kotlin 侧重建。

### 1.2 `mdbx-ffi` 是 Core profile

`crates/mdbx-ffi/Cargo.toml` 中：

```toml
mdbx-storage = { path = "../mdbx-storage", default-features = false, features = ["core"] }
```

因此 Android 侧 **没有**：KDBX 导入导出、派生搜索索引、文件系统 blob store、
基准测试。不要在 Android 需求里规划这些能力。

### 1.3 打开 vault 会自动升级格式

`VaultConnection::open` 内部先做 preflight，再执行 `upgrade_to_latest`。
也就是说，**只要调用 `open_vault`，MDBX1 vault 就会被就地升级为 MDBX2**，
升级后 `min_writer_version = MDBX-2`，旧版 MDBX1 客户端再也不能写入该文件。

这是一条 **单向边界**。如果你的产品需要"用户确认后再升级"，必须走第 4 节的
受控迁移流程，而不是直接调用 `open_vault`。

---

## 2. 构建与打包

### 2.1 目标 ABI

Android 需要为每个 ABI 各构建一份动态库。推荐使用 `cargo-ndk`：

```bash
cargo install cargo-ndk
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
```

```bash
cargo ndk \
  -t arm64-v8a \
  -t armeabi-v7a \
  -t x86_64 \
  -o android/src/main/jniLibs \
  build -p mdbx-ffi --release
```

`crates/mdbx-ffi/Cargo.toml` 中 `crate-type = ["staticlib", "cdylib", "rlib"]`，
产物名为 `libmdbx_ffi.so`。

最终 jniLibs 布局：

```text
src/main/jniLibs/
  arm64-v8a/libmdbx_ffi.so
  armeabi-v7a/libmdbx_ffi.so
  x86_64/libmdbx_ffi.so
```

`x86_64` 仅用于模拟器，可在 release 变体中排除。

### 2.1.1 发布资产打包（不重新构建）

发布前使用仓库中的打包脚本从已有的 `target/android-jniLibs` 读取库文件，
不会重新运行 Cargo 或重新编译：

```powershell
pwsh -File scripts/package-mdbx2-android-release.ps1
```

脚本会在 `target/release-assets/` 生成固定命名的发布资产：

```text
libmdbx_ffi_arm64-v8a.so
libmdbx_ffi_armeabi-v7a.so
libmdbx_ffi_x86_x64.so
mdbx2-android-release.zip
```

其中 `x86_x64` 是对外资产的历史命名，压缩包内部仍保持 Android 标准的
`x86_64/` ABI 目录和 `libmdbx_ffi.so` 文件名。脚本只会覆盖上述发布资产，
不会修改 `jniLibs` 中供 Android/Gradle 使用的规范文件。

### 2.2 生成 Kotlin 绑定

MDBX2 使用 **UniFFI 0.31.1 proc-macro 模式**（`uniffi::setup_scaffolding!()`），
仓库中 **没有 `.udl` 文件**。因此必须用 library mode 从已构建的动态库生成绑定：

```bash
cargo install uniffi --version 0.31.1 --locked --features cli
```

```bash
uniffi-bindgen generate \
  --language kotlin \
  --out-dir android/src/main/java \
  target/aarch64-linux-android/release/libmdbx_ffi.so
```

要点：

- 位置参数是 **已编译的 cdylib 路径**，不是源码路径。
- 只需从任意一个 ABI 的产物生成一次，绑定与 ABI 无关。
- 生成的 Kotlin 文件是 **构建产物**。建议纳入 Gradle 构建步骤自动生成，
  而不是手工提交并手工修改。任何手改都会在下次升级引擎时丢失，
  并可能与 Rust 侧签名不一致导致运行时崩溃。

### 2.3 运行时依赖

生成的 Kotlin 绑定依赖 JNA。在 `build.gradle.kts` 中加入：

```kotlin
dependencies {
    implementation("net.java.dev.jna:jna:5.14.0@aar")
}
```

### 2.4 版本锁定

引擎版本、UniFFI 版本、绑定文件三者必须同源。建议在 CI 中：

1. 构建 `mdbx-ffi`；
2. 生成绑定；
3. 校验生成结果与仓库中的绑定一致（若选择提交绑定）；
4. 不一致即失败。

引擎侧 CI 使用 **Rust 1.86.0**（钉死在 `.github/workflows/ci.yml`，仓库中
没有 `rust-toolchain.toml`）。构建 `mdbx-ffi` 时建议使用同一版本，避免
编译器版本差异导致的行为差异。注意 UniFFI 必须是 `0.31.1`，与
`crates/mdbx-ffi/Cargo.toml` 中的依赖版本严格一致，否则生成的绑定与
cdylib 的 scaffolding 不匹配。

---

## 3. 能力发现

在打开任何 vault 之前，可以先查询本次构建实际启用了哪些能力。这是一个
**顶层函数，不需要 vault**：

```kotlin
val manifest = mdbxBuildCapabilityManifest()
```

`MdbxBuildCapabilityManifest` 字段：

| 字段 | 含义 |
| --- | --- |
| `profile` | 构建 profile |
| `engineVersion` | 引擎版本 |
| `storageProfile` | 存储 profile（Android 上为 core） |
| `enabledStorageCapabilityIds` | 已启用存储能力 ID |
| `disabledOptionalStorageCapabilityIds` | 已关闭的可选存储能力 ID |
| `syncProfile` | 同步 profile |
| `syncProtocolVersion` | 同步协议版本 |
| `enabledSyncCapabilityIds` | 已启用同步能力 ID |
| `disabledOptionalSyncCapabilityIds` | 已关闭的可选同步能力 ID |

UI 应该按 manifest 决定功能可见性，**不要硬编码功能开关**。18 项强制存储能力
（`mdbx.storage.*`）在任何合法构建中都会出现，包括
`authenticated-encryption`、`bounded-sync-state`、`collection-profiles`、
`commit-history`、`conflicts`、`external-blob-*`、`generic-metadata`、
`generic-objects`、`key-epochs`、`mdbx1-compatibility`、`payload-migrations`、
`recovery`、`snapshots`、`synchronization`、`tiga-policy`。

---

## 4. 迁移编排（最关键的一节）

### 4.1 三个顶层函数

```kotlin
fun inspectVaultMigration(path: String): MdbxMigrationInfo
fun createPortableBackup(sourcePath: String, destination: String): MdbxBackupInfo
fun upgradeVault(path: String): MdbxMigrationInfo
```

`MdbxMigrationInfo`：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `initialized` | `Boolean` | 文件是否是已初始化的 vault |
| `formatVersion` | `String?` | 当前格式版本 |
| `schemaVersion` | `UInt?` | 当前 schema 版本 |
| `minReaderVersion` | `String?` | 最低读取者版本 |
| `minWriterVersion` | `String?` | 最低写入者版本 |
| `requiresUpgrade` | `Boolean` | 是否需要升级 |
| `unknownCriticalExtensions` | `Boolean` | 存在未知关键扩展 |
| `targetFormatVersion` | `String` | 本次构建的目标格式版本 |
| `targetSchemaVersion` | `UInt` | 本次构建的目标 schema 版本 |

`inspectVaultMigration` 以 **只读** 方式打开文件，不需要口令，不会修改任何字节。
当检测到需要升级时，它还会执行迁移前完整性校验。

### 4.2 强制流程

```text
inspectVaultMigration(path)
        │
        ├── initialized == false ──────────────► 引导创建新 vault
        │
        ├── unknownCriticalExtensions == true ─► 拒绝打开，提示升级 App
        │
        ├── requiresUpgrade == false ──────────► 直接 openVault
        │
        └── requiresUpgrade == true
                │
                ├─► 向用户展示：当前版本 → 目标版本，并明确告知
                │   "升级后旧版本客户端将无法再写入此 vault"
                │
                ├─► 用户确认后：createPortableBackup(path, backupPath)
                │
                ├─► upgradeVault(path)
                │
                └─► openVault(path, password, deviceId)
```

**不要** 在未取得用户同意的情况下直接调用 `openVault` 升级已有的 MDBX1 vault。
一旦 `minWriterVersion` 变成 `MDBX-2`，用户在其他设备上的旧版 App 会立即变成
只读，这是不可逆的产品级影响。

### 4.3 `createPortableBackup` 为什么必须用它

`createPortableBackup` 不是 `File.copy`。它：

1. 以只读方式打开源库（无需凭据）；
2. 执行 SQLite 在线备份；
3. 强制目标库 `journal_mode = DELETE`；
4. 运行 `quick_check(1)`；
5. 校验目标库的迁移信息与 `vault_id` 与源库一致；
6. 确认目标不存在 `-wal` / `-shm` 边车文件；
7. `fsync` 后原子落地（目标已存在则拒绝覆盖）。

产出是 **单文件、无边车、可直接上传的可移植副本**。

vault 运行时是 WAL 模式（`journal_mode=WAL; secure_delete=ON`）。因此
**只复制主 `.mdbx` 文件是错误的**，会丢失 WAL 中尚未 checkpoint 的数据。
任何"导出/上传/分享 vault"的入口都必须走 `createPortableBackup`
或 vault 实例上的 `createBackup(destination)`。

---

## 5. 创建、打开与解锁

### 5.1 顶层入口

```kotlin
fun createVault(path: String, password: String, deviceId: String): MdbxVault
fun createVaultWithTigaMode(path: String, password: String, deviceId: String, mode: MdbxTigaMode): MdbxVault

fun openVault(path: String, password: String, deviceId: String): MdbxVault
fun openVaultWithSecurityKey(path: String, keyMaterial: ByteArray, deviceId: String): MdbxVault
fun openVaultWithPasswordSecurityKey(path: String, password: String, keyMaterial: ByteArray, deviceId: String): MdbxVault
```

`createVault` 默认使用 `MdbxTigaMode.MULTI`。创建过程是 **失败自清理** 的：
若中途失败，不会留下半初始化的文件。

### 5.2 `deviceId` 必须稳定

`deviceId` 参与提交署名、设备头（device head）与墓碑确认证明。它必须：

- 在同一设备上跨进程、跨重装保持稳定（存入应用私有存储或 keystore）；
- 在不同设备上唯一；
- **绝不** 使用会变化的值（如每次启动生成的 UUID）。

`deviceId` 漂移会导致墓碑无法被确认，进而阻塞清理，并在提交历史中产生
虚假的"新设备"。

### 5.3 vault 句柄

`MdbxVault` 是一个 UniFFI object，内部持有 `Mutex<VaultConnection>`。它是
线程安全的，但所有调用会在互斥锁上串行化。因此：

- **一个 vault 文件对应一个 `MdbxVault` 实例**，在应用内单例持有；
- 所有 FFI 调用放在 IO 调度器上，**绝不在主线程调用**；
- 不要为并发而创建多个实例去打开同一个文件。

`vault.info()` 返回 `VaultInfo { vaultId, deviceId }`。

### 5.4 存储位置

vault 必须放在应用私有目录（`context.filesDir` 或 `context.noBackupFilesDir`）。
路径以 **绝对路径字符串** 传给 FFI。

不要把 vault 放在 `context.cacheDir`（系统会清理），也不要依赖 Android
自动备份（`allowBackup`）来备份 vault，请用 `createPortableBackup`。

---

## 6. 保留 working copy 与远端同步模型

`docs/android/README.zh-CN.md` 记录的现有模型 **必须保留**：

1. 从远端（WebDAV / OneDrive / 本地文件）下载或创建 working copy；
2. 所有写入只作用于 working copy；
3. 每个 vault 一把锁，禁止并发写入同一文件；
4. 提交或回滚是原子的；
5. 提交后 checkpoint；
6. 按 `sourceType` 回刷到远端；
7. 更新 `lastSyncStatus` / `lastSyncError`。

接入 FFI 后唯一的变化是：**working copy 上的读写从 Kotlin SQL 换成 FFI 调用**。
回刷到远端时上传的应当是 `createBackup` 产出的可移植副本，而不是 WAL 状态下的
主文件。

---

## 7. 读取：一律走有界接口

### 7.1 分页契约

所有 summary 列表接口都是 keyset 分页：

```kotlin
fun listObjectSummaries(
    collectionId: String,
    objectTypeId: String?,
    pageSize: UInt,
    cursor: String?,
): MdbxObjectSummaryPage
```

```kotlin
data class MdbxObjectSummaryPage(
    val items: List<MdbxObjectSummary>,
    val nextCursor: String?,
)
```

`pageSize` 合法范围是 **1 到 200**（对象、集合、附件、冲突、快照、
对象元数据 summary 均为 200；提交历史为 100）。超出范围会返回错误，
不会被静默截断。`nextCursor` 为 `null` 表示已到末页。

### 7.2 summary 不含明文

`MdbxObjectSummary` 只包含展示元数据，**不包含 payload**：

```kotlin
data class MdbxObjectSummary(
    val objectId: String,
    val collectionId: String,
    val objectTypeId: String,
    val title: String,
    val payloadSchemaVersion: UInt,
    val headCommitId: String,
    val deleted: Boolean,
    val updatedAt: String,
)
```

列表页、搜索结果页、导航页 **只能用 summary**。这既是性能要求，也是安全要求：
列表渲染不应触发解密与 Tiga 授权。

### 7.3 常用读取接口

| 用途 | 方法 |
| --- | --- |
| 集合列表 | `listCollectionSummaries(pageSize, cursor)` |
| 集合内对象列表 | `listObjectSummaries(collectionId, objectTypeId, pageSize, cursor)` |
| 单个对象元数据 | `getObjectSummary(objectId)` |
| 已删除对象 | `listDeletedObjectSummaries(collectionId, objectTypeId, pageSize, cursor)` / `listAllDeletedObjectSummaries(objectTypeId, pageSize, cursor)` |
| 附件列表 | `listAttachmentSummaries(collectionId, objectId, pageSize, cursor)` |
| 提交历史 | `listCommitHistory(pageSize, cursor)`、`listCommitHistoryV2(pageSize, cursor)` |
| 分支 | `listBranches()` |
| 快照 | `listSnapshotSummaries(pageSize, cursor)` |
| 未解决冲突 | `listUnresolvedConflictSummaries(objectType, pageSize, cursor)` |

`listObjects`、`listEntries`、`getObject` 等一次性返回完整 payload 的接口
属于 **MDBX1 兼容路径**。新 Android 界面不应使用它们；它们会把整集合的明文
读进内存。

---

## 8. 揭示明文：必须经过 Tiga 授权

### 8.1 揭示接口

```kotlin
fun revealObject(objectId: String): MdbxObjectDisclosureResult
fun revealObjectWithLimits(objectId: String, limits: MdbxObjectDisclosureLimits): MdbxObjectDisclosureResult
fun revealObjectWithDeviceContext(objectId: String, device: MdbxDeviceContext): MdbxObjectDisclosureResult
fun revealObjectWithDeviceContextAndLimits(objectId: String, device: MdbxDeviceContext, limits: MdbxObjectDisclosureLimits): MdbxObjectDisclosureResult
```

```kotlin
data class MdbxObjectDisclosureResult(
    val `object`: MdbxObjectRecord?,   // 未授权时为 null
    val authorization: MdbxAuthorizationDecision,
)
```

**Android 应当始终使用 `WithDeviceContext` 变体。** 不带 device context 的
重载使用一个保守的 Standard 设备画像，会低估或高估真实设备能力。

关系与标签也有对应的 `revealObjectRelation*` / `revealObjectLabel*` 四变体。

### 8.2 `MdbxDeviceContext` 必须如实填写

```kotlin
data class MdbxDeviceContext(
    val assurance: MdbxDeviceAssurance,              // UNKNOWN / STANDARD / TRUSTED_HARDWARE
    val secureClipboardAvailable: Boolean,
    val screenCaptureProtectionAvailable: Boolean,
    val secureTempFilesAvailable: Boolean,
)
```

Android 侧建议映射：

| 字段 | 判定依据 |
| --- | --- |
| `assurance` | 通过 StrongBox / TEE 支持的 Keystore 判定；有硬件支持填 `TRUSTED_HARDWARE`，普通设备填 `STANDARD`，无法判定填 `UNKNOWN` |
| `secureClipboardAvailable` | API 33+ 可设置 `ClipDescription.EXTRA_IS_SENSITIVE` 时为 `true` |
| `screenCaptureProtectionAvailable` | 能设置 `WindowManager.LayoutParams.FLAG_SECURE` 时为 `true` |
| `secureTempFilesAvailable` | 能在应用私有目录创建临时文件且会可靠清理时为 `true` |

**不要为了让操作通过而虚报能力。** 引擎依赖这些字段做安全决策；虚报会让
Tiga 策略形同虚设，且不会有任何报错提示你。

### 8.3 授权判定

```kotlin
data class MdbxAuthorizationDecision(
    val outcome: MdbxAuthorizationOutcome,        // ALLOW / ALLOW_WITH_CONSTRAINTS / REQUIRE_FRESH_AUTHENTICATION / REQUIRE_ADDITIONAL_FACTOR / DENY
    val reasons: List<MdbxAuthorizationReason>,
    val constraints: List<MdbxAuthorizationConstraint>,
    val auditRequired: Boolean,
)
```

UI 处理规则：

| outcome | UI 行为 |
| --- | --- |
| `ALLOW` | 正常展示 |
| `ALLOW_WITH_CONSTRAINTS` | 展示，但必须执行 `constraints`（如剪贴板 TTL、禁止截屏） |
| `REQUIRE_FRESH_AUTHENTICATION` | 拉起生物识别 / 口令再验证，成功后重试 |
| `REQUIRE_ADDITIONAL_FACTOR` | 引导补充第二因子（如安全密钥） |
| `DENY` | 明确拒绝，用 `reasons` 解释原因，不提供绕过入口 |

`reasons` 共 11 种：`SESSION_MISSING`、`SESSION_EXPIRED`、
`AUTHENTICATION_STALE`、`INSUFFICIENT_AUTHENTICATION_FACTORS`、
`SECURITY_KEY_REQUIRED`、`DEVICE_ASSURANCE_INSUFFICIENT`、
`SECURE_CLIPBOARD_UNAVAILABLE`、`SCREEN_CAPTURE_PROTECTION_UNAVAILABLE`、
`OPERATION_DISABLED`、`POLICY_WEAKENING_NOT_AUTHORIZED`、
`POLICY_EXCEPTION_INVALID`。每一种都应有对应的可操作文案。

`constraints` 是 `MdbxAuthorizationConstraint { kind, seconds }`，
`kind` 共 5 种，必须逐条落实：

| kind | Android 实现 |
| --- | --- |
| `CLEAR_CLIPBOARD_AFTER_SECONDS` | 按 `seconds` 定时清空剪贴板 |
| `EXCLUDE_CLIPBOARD_HISTORY` | 标记敏感剪贴项，排除出剪贴板历史 |
| `PREVENT_SCREEN_CAPTURE` | 对当前窗口设置 `FLAG_SECURE` |
| `NO_PLAINTEXT_PERSISTENCE` | 明文只留在内存，禁止写盘、禁止进 savedInstanceState |
| `USE_SECURE_TEMPORARY_FILES` | 临时文件必须落在应用私有目录并在使用后删除 |

`auditRequired == true` 时，该操作必须被记录，客户端不得静默跳过。

### 8.4 预授权

对于非揭示类的敏感操作（复制、导出、打印、创建快照、后台访问等），先询问：

```kotlin
fun authorizeTigaOperation(
    scope: MdbxTigaScope,
    operation: MdbxTigaOperation,
    device: MdbxDeviceContext,
): MdbxAuthorizationDecision
```

`MdbxTigaScope { scopeType, scopeId }`，`scopeType` 取
`VAULT` / `PROJECT` / `ENTRY` / `ATTACHMENT`；除 `VAULT` 外必须提供 `scopeId`。

`MdbxTigaOperation` 共 19 种，与 UI 动作的对应关系：

| 操作 | 典型 UI 触发点 |
| --- | --- |
| `REVEAL_SECRET` | 点击"显示密码" |
| `COPY_SECRET` | 复制到剪贴板 |
| `EXPORT_DATA` | 导出 / 分享 |
| `PRINT_DATA` | 打印 |
| `DECRYPT_ATTACHMENT` | 打开附件 |
| `CREATE_SNAPSHOT` / `RESTORE_SNAPSHOT` | 快照管理 |
| `CHANGE_UNLOCK_METHODS` | 修改解锁方式 |
| `CHANGE_SECURITY_POLICY` | 修改安全档位 |
| `CHANGE_RECOVERY_METHODS` | 修改恢复方式 |
| `ROTATE_KEY_EPOCH` | 轮换密钥 |
| `DELETE_AUDIT_RECORDS` | 清理审计 |
| `MANAGE_DELETED_OBJECT_RETENTION` | 回收站保留策略 |
| `MANAGE_SNAPSHOT_RETENTION` | 快照保留策略 |
| `PURGE_DELETED_OBJECT` | 永久删除 |
| `BACKGROUND_ACCESS` | 应用退到后台后继续访问 |
| `SYNC_CIPHERTEXT` | 后台同步密文 |
| `CREATE_PLAINTEXT_CACHE` | 建立明文缓存 |
| `MIGRATE_PAYLOAD` | payload 迁移 |

### 8.5 用策略驱动 UI

```kotlin
fun resolveTigaPolicy(scope: MdbxTigaScope): MdbxResolvedTigaPolicy
```

`MdbxResolvedTigaPolicy` 的字段几乎可以 1:1 映射到 Android 行为：

| 字段 | Android 落地方式 |
| --- | --- |
| `idleTimeoutSecs` | 无操作自动锁定计时器 |
| `maxLifetimeSecs` | 会话最长存活，到期强制重新解锁 |
| `lockOnBackground` | `onStop` 时立即锁定 |
| `revealRequiresFreshAuth` | 揭示前拉起 BiometricPrompt |
| `freshAuthWindowSecs` | "新鲜认证"有效窗口 |
| `clipboardAllowed` / `clipboardTtlSecs` | 是否允许复制、多少秒后清空剪贴板 |
| `copyRequiresFreshAuth` | 复制前再认证 |
| `secureClipboardRequired` | 必须标记敏感剪贴板，否则禁用复制 |
| `screenCaptureProtectionRequired` | 强制 `FLAG_SECURE` |
| `exportAllowed` / `printAllowed` | 导出、打印入口可见性 |
| `persistentPlaintextCacheAllowed` | 是否允许把明文写入本地缓存（通常为否） |
| `attachmentTempFilesAllowed` | 是否允许把附件落临时文件 |
| `lockedCiphertextSyncAllowed` | 锁定状态下能否后台同步密文 |
| `minimumDeviceAssurance` | 低于此保障级别的设备直接降级功能 |
| `auditLevel` | 审计写入粒度 |

还有 `policyVersion`、`profile`、`compliance`、`exceptionId`、`warnings`、
`minimumAuthFactors`、`securityKeyRequired` 等字段用于设置页展示。

**不要在 Kotlin 里硬编码超时时间和剪贴板策略**，一律从 `resolveTigaPolicy`
读取，否则 vault 的安全档位对 Android 无效。

### 8.6 安全档位与会话

```kotlin
fun setTigaProfile(mode: MdbxTigaMode, weakeningReason: String?, exceptionExpiresAtUnixSecs: Long?, device: MdbxDeviceContext)
fun activeSessionInfo(): MdbxSessionInfo?
fun listUnlockMethods(): List<...>
fun assessTigaUnlockPolicy(): ...
fun listSecurityAuditEvents(limit: UInt): List<...>
fun listSecurityAuditEventsV2(limit: UInt): List<MdbxSecurityAuditEventV2>
fun rotateKeyEpoch(device: MdbxDeviceContext): MdbxKeyEpochRotationResult
```

`MdbxTigaMode` 为 `SKY` / `MULTI` / `POWER`（安全强度递增）。
**从高档位降到低档位时，`weakeningReason` 必须非空**，否则调用失败。UI 必须
让用户输入或选择一个明确理由，不要用占位字符串糊弄。

`MdbxSessionInfo { sessionId, unlockMethod, authenticatedAtUnixSecs, lastActivityAtUnixSecs }`
可用于在设置页展示当前会话状态。

解锁方式管理：`setupLocalSecurityKeyUnlock`（及
`_withDeviceContext`）、`setupPasswordSecurityKeyUnlock`、
`removeUnlockMethod`、`resetMasterPassword`（及 `_withTigaMode`、
`_withTigaModeAndDeviceContext`）。

---

## 9. 写入：一次用户操作 = 一个提交

### 9.1 批量写入接口

```kotlin
fun executeWriteOperation(
    operationId: String,
    operationKind: String,
    commands: List<MdbxWriteCommand>,
): MdbxWriteOperationResult
```

另有 7 个变体：`executeWriteOperationWithLimits`、
`executeWriteOperationOnBranch`、`executeWriteOperationOnBranchWithLimits`，
以及 4 个 `executeCompositeWriteOperation*`（额外接收
`attachmentCommands: List<MdbxAttachmentBatchCommand>`，用于"对象改动 + 附件改动"
必须原子的场景）。

### 9.2 命令类型

`MdbxWriteCommand` 是一个 sealed class，共 14 种：

```text
CreateProject(projectId, title)
CreateEntry(entryId, projectId, entryType, title, payloadJson)
UpdateEntry(entryId, projectId, entryType, title, payloadJson)
DeleteEntry(entryId, projectId)
RestoreEntry(entryId, projectId)
MoveEntry(entryId, projectId, targetProjectId)
CreateObjectRelation(relationId, sourceObjectId, targetObjectId, relationKind, payloadJson, payloadSchemaVersion)
UpdateObjectRelation(relationId, relationKind, payloadJson, payloadSchemaVersion)
DeleteObjectRelation(relationId)
CreateObjectLabel(labelId, collectionId, name, payloadJson, payloadSchemaVersion)
UpdateObjectLabel(labelId, name, payloadJson, payloadSchemaVersion)
DeleteObjectLabel(labelId)
AssignObjectLabel(assignmentId, objectId, labelId)
RemoveObjectLabelAssignment(assignmentId)
```

`entryType` 合法取值：`login`、`note`、`totp`、`card`、`identity`、`passkey`、
`ssh-key`、`api-token`、`document-ref`。传入其他值会返回
`InvalidEntryType`。

### 9.3 为什么必须批量

单条便捷方法（`createEntry`、`updateEntry`、`deleteEntry`、`moveEntry` 等）
每次调用产生 **一个独立提交**。如果用户"多选 50 项并移动到另一个集合"，
逐条调用会产生 50 个提交，导致：提交历史被噪声淹没、同步载荷膨胀、
中途失败留下部分完成状态。

正确做法是把 50 条 `MoveEntry` 放进一个 `executeWriteOperation`，
得到 **一个** 提交。

单条便捷方法仅适用于真正的单条用户操作。

### 9.4 幂等重试

```kotlin
data class MdbxWriteOperationResult(
    val commitId: String,
    val alreadyCommitted: Boolean,
    val projectIds: List<String>,
    val entryIds: List<String>,
    val relationIds: List<String>,
    val labelIds: List<String>,
    val labelAssignmentIds: List<String>,
)
```

`operationId` 是幂等键。如果一次写入因进程被杀、设备断电等原因结果未知，
**用完全相同的 `operationId` 和 `commands` 重试**：

- 若上次实际已提交，返回 `alreadyCommitted = true` 和原来的 `commitId`，
  不会产生重复数据；
- 若上次未提交，正常执行。

`operationId` 应由客户端生成（UUID）并 **在重试前持久化**，否则幂等性无效。
`operationKind` 是一个描述性字符串（如 `"move-entries"`），用于审计与历史展示。

### 9.5 写入限额

```kotlin
fun defaultWriteOperationLimits(): MdbxWriteOperationLimits
```

```kotlin
data class MdbxWriteOperationLimits(
    val maxCommands: ULong,
    val maxPayloadBytesPerCommand: ULong,
    val maxPayloadBytes: ULong,
    val maxIntentBytes: ULong,
)
```

| 限额 | 默认值 | 硬上限 |
| --- | --- | --- |
| `maxCommands` | 256 | 4096 |
| `maxPayloadBytesPerCommand` | 1 MiB | 16 MiB |
| `maxPayloadBytes` | 8 MiB | 64 MiB |
| `maxIntentBytes` | 16 MiB | 128 MiB |

超过默认值时，客户端应 **自行分批**，而不是无脑调高上限。确需调高时用
`executeWriteOperationWithLimits`，且不得超过硬上限。

### 9.6 分支写入

`executeWriteOperationOnBranch*` 接收分支 ID。若产品没有分支概念，
只用非分支变体即可。

---

## 10. 附件

### 10.1 接口

```kotlin
fun createAttachmentWithContent(
    operationId: String,
    request: MdbxAttachmentCreateRequest,
    content: ByteArray,
    limits: MdbxAttachmentContentLimits,
): MdbxAttachmentWriteResult

fun replaceAttachmentContent(
    operationId: String,
    attachmentId: String,
    content: ByteArray,
    limits: MdbxAttachmentContentLimits,
): MdbxAttachmentWriteResult

fun renameAttachment(attachmentId: String, fileName: String, mediaType: String?): MdbxAttachmentRecord
fun deleteAttachment(attachmentId: String)
fun readAttachmentContent(attachmentId: String, maxPlaintextBytes: ULong): ByteArray
fun verifyAttachmentIntegrity(attachmentId: String): Boolean

fun executeAttachmentBatch(operationId: String, commands: List<MdbxAttachmentBatchCommand>): MdbxAttachmentBatchResult
fun executeAttachmentBatchWithLimits(operationId: String, commands: List<MdbxAttachmentBatchCommand>, limits: MdbxAttachmentBatchLimits): MdbxAttachmentBatchResult
```

`limits` 通过 `defaultAttachmentContentLimits()` 与
`defaultAttachmentBatchLimits()` 获取，不要手写数值。

```kotlin
data class MdbxAttachmentContentLimits(
    val chunkSize: ULong,
    val maxPlaintextBytes: ULong,
)

data class MdbxAttachmentWriteResult(
    val attachment: MdbxAttachmentRecord,
    val commitId: String,
    val alreadyCommitted: Boolean,   // 与写入操作一致的幂等重试语义
)
```

`MdbxAttachmentBatchCommand` 共 4 种：
`Create(attachmentId, projectId, entryId, fileName, mediaType, content)`、
`Replace(attachmentId, content)`、
`Rename(attachmentId, fileName, mediaType)`、
`Delete(attachmentId)`。

```kotlin
data class MdbxAttachmentCreateRequest(
    val attachmentId: String,
    val projectId: String,
    val entryId: String?,
    val fileName: String,
    val mediaType: String?,
)
```

### 10.2 内存限额（Android 上尤其重要）

| 常量 | 默认值 | 硬上限 |
| --- | --- | --- |
| 分块大小 | 256 KiB | 4 MiB |
| 单附件明文 | 8 MiB | 64 MiB |
| 批量命令数 | 64 | 512 |
| 批量明文总量 | 32 MiB | 256 MiB |

`readAttachmentContent` 会把 **整个明文一次性读进内存** 并返回
`ByteArray`。在低端 Android 设备上，一个 64 MiB 的附件足以触发 OOM。

因此：

- UI 上传入口应限制单文件大小，建议不超过默认的 8 MiB；
- 超限时引擎返回 `ResourceLimit` 错误，客户端必须给出明确提示，
  **不要通过调高 `maxPlaintextBytes` 来"解决"**；
- 打开附件前先用 `authorizeTigaOperation(..., DECRYPT_ATTACHMENT, ...)` 检查；
- 若 `attachmentTempFilesAllowed == false`，禁止把附件明文写到临时文件
  （包括交给系统 Intent 打开），只能在内存中渲染。

### 10.3 附件与对象的原子性

若一次用户操作同时改动对象和附件（例如"编辑条目并替换封面图"），
必须用 `executeCompositeWriteOperation*`，保证两者落在同一个提交里。

---

## 11. 冲突与快照

### 11.1 冲突

```kotlin
fun listUnresolvedConflictSummaries(objectType: String?, pageSize: UInt, cursor: String?): ...
fun listUnresolvedConflicts(): List<MdbxConflictRecord>
fun resolveConflict(conflictId: String, choice: MdbxConflictChoice): ...
```

注意 `listUnresolvedConflicts()` **不接收分页参数**，会一次性返回全部未解决冲突。
冲突页请用 `listUnresolvedConflictSummaries` 的有界版本。

`MdbxConflictChoice` 为 `LOCAL_WINS` / `INCOMING_WINS`。

`MdbxConflictSummary` / `MdbxConflictRecord` 字段：`conflictId`、`objectType`、
`objectId`、`baseCommitId`、`localCommitId`、`incomingCommitId`、
`conflictingFields`、`resolution`、`createdAt`、`resolvedAt`。

`objectType` 取值：`Project`、`Entry`、`Attachment`、`ObjectRelation`、
`ObjectLabel`、`ObjectLabelAssignment`。

需要逐字段合并时，用对应的自定义解决接口：
`resolveEntryConflictCustomPayload`、`resolveProjectConflictCustom`、
`resolveAttachmentConflictCustom`、`resolveObjectRelationConflictCustom`、
`resolveObjectLabelConflictCustom`、`resolveObjectLabelAssignmentConflictCustom`。

UI 要求：`conflictingFields` 应逐字段展示给用户，不要只给"保留本地 / 保留远端"
两个按钮就了事。未解决的冲突会阻塞相关对象的墓碑清理。

### 11.2 快照

FFI 暴露的是 **摘要与自动快照生命周期**：

```kotlin
fun listSnapshotSummaries(pageSize: UInt, cursor: String?): MdbxSnapshotSummaryPage
fun getSnapshotSummary(snapshotId: String): MdbxSnapshotSummary?
fun getSnapshotLifecycle(snapshotId: String): MdbxSnapshotLifecycleSummary?
fun createAutomaticSnapshot(retentionEligibleAt: String, device: MdbxDeviceContext): MdbxSnapshotSummary
fun planAutomaticSnapshotPrune(keepLatest: UInt): MdbxSnapshotPrunePlan
fun pruneAutomaticSnapshots(planToken: String, keepLatest: UInt, device: MdbxDeviceContext): MdbxSnapshotPruneResult
```

`MdbxSnapshotSummary { snapshotId, baseCommitId, snapshotHash, snapshotCiphertextBytes, createdAt, createdByDeviceId }`。

修剪是 **两阶段** 的：先 `planAutomaticSnapshotPrune` 拿到 `planToken` 并展示
将被删除的内容，用户确认后再 `pruneAutomaticSnapshots`。不要跳过 plan 阶段。

**手动快照的创建与恢复不在 FFI 中**，见第 12 节。

### 11.3 完整性根

```kotlin
// 顶层函数，无需解锁
fun inspectVaultIntegrityRoot(path: String): MdbxIntegrityRootStatus

// vault 实例方法
fun integrityRootStatus(): MdbxIntegrityRootStatus
fun enableIntegrityRoot(): MdbxIntegrityRootStatus
fun verifyIntegrityRoot(): MdbxIntegrityRootVerification
fun rebuildIntegrityRoot(): MdbxIntegrityRootStatus
fun createIntegrityRootCheckpoint(): MdbxAuthenticatedStateRootCheckpoint
fun verifyIntegrityRootCheckpoint(checkpoint: MdbxAuthenticatedStateRootCheckpoint): MdbxIntegrityRootVerification
fun compareIntegrityRootCheckpoints(
    previous: MdbxAuthenticatedStateRootCheckpoint,
    candidate: MdbxAuthenticatedStateRootCheckpoint,
): MdbxIntegrityRootCheckpointRelation
```

注意 `inspectVaultIntegrityRoot` 不需要凭据，其返回的 `authenticated` 字段
**恒为 false**，不能用它证明 vault 未被篡改。真正的校验用
`verifyIntegrityRoot`。

### 11.4 健康检查与墓碑清理

```kotlin
fun healthCheck(): MdbxHealthCheckResult      // { healthy, issues }

fun findTombstoneByTarget(targetObjectId: String): MdbxTombstoneRecord?
fun evaluateTombstonePurgeEligibility(tombstoneId: String, now: String): MdbxTombstonePurgeEligibility
fun scheduleTombstonePurge(tombstoneId: String, purgeEligibleAt: String, device: MdbxDeviceContext): MdbxTombstonePurgeScheduleResult
fun purgeTombstone(tombstoneId: String, device: MdbxDeviceContext): MdbxPermanentPurgeReceipt

fun findPermanentPurgeReceiptByTombstone(tombstoneId: String): MdbxPermanentPurgeReceipt?
fun findPermanentPurgeReceiptByTarget(targetObjectType: String, targetObjectId: String): MdbxPermanentPurgeReceipt?
```

`evaluateTombstonePurgeEligibility` 返回的 `MdbxTombstonePurgeBlocker.code`
可能是：`retention-not-scheduled`、`retention-period-active`、
`invalid-retention-timestamp`、`missing-delete-commit`、
`delete-commit-missing`、`target-missing`、`target-not-deleted`、
`unresolved-conflict`、`device-has-not-acknowledged-delete`、
`dependent-objects-remain`、`unsupported-target-type`。

其中 `device-has-not-acknowledged-delete` 对 Android 尤其重要：它意味着
仍有已注册设备没有因果地确认这次删除。UI 应把它解释为
"等待其他设备同步"，而 **不是** 错误。

---

## 12. 当前 **不** 通过 FFI 暴露的能力

这是本文档最需要被认真对待的一节。以下能力在当前构建中 **没有** 对应的
FFI 方法：

| 能力 | 状态 |
| --- | --- |
| **同步 bundle 的构建与应用** | **未暴露**。FFI 中不存在 `build_bundle` / `apply_bundle` / 任何 bundle 导出接口 |
| 手动快照的创建、校验、导出、恢复 | 未暴露（仅有自动快照生命周期与摘要） |
| project 的更新与删除流程 | 未暴露 |
| 嵌套文件夹专用操作 | 未暴露 |
| tag | 未暴露 |
| 外部 Blob Provider 的传输与维护 | 未暴露 |
| diagnostics / maintenance 完整面 | 未暴露 |
| KDBX 导入导出 | Core profile 中不存在 |
| 派生搜索索引 | Core profile 中不存在 |
| 文件系统 blob store | Core profile 中不存在 |

FFI 中确实存在的同步能力只有三类 **会话对象**，用于点对点线路协商：
`MdbxSyncWireSession`、`MdbxBlobSyncSession`、`MdbxIntegrityRootSyncSession`，
由顶层函数 `createSyncWireSession(sessionId, maxPayloadBytes)`、
`createBlobSyncSession(deviceId)`、
`createIntegrityRootSyncSession(deviceId, checkpoint)` 创建，
方法覆盖 hello / hello-ack 编解码、blob manifest 分页、blob 分块传输与恢复。
**它们不能替代 bundle 级别的同步逻辑。**

### 12.1 直接后果

Android 当前基于 `MdbxVaultStore` 的 **bundle 同步逻辑无法在本阶段迁移到 FFI**。
迁移范围必须限定为：创建、打开、解锁、读取、写入、附件、冲突、
快照摘要、安全策略。bundle 同步暂时留在 Kotlin，或等待引擎侧新增
对应责任块后再迁移。

### 12.2 绝对禁止的绕过方式

**不得** 因为某能力未暴露就绕过 FFI 直接操作 SQLite。以下表 **禁止**
任何客户端直接写入：

```text
commits
commit_parents
object_versions
tombstones
tombstone_acknowledgements
snapshots
key_epochs
conflicts
device_heads
branches
project_tags
tiga_policy_overrides
tiga_policy_exceptions
security_audit_events
```

直接写入这些表会破坏提交图、因果确认与审计链，且破坏是静默的——
在其他设备同步时才会暴露，届时已无法修复。

同样 **不得** 在客户端用 SQL 重新实现格式迁移或兼容转换。格式迁移由
storage core 独占拥有。

### 12.3 墓碑确认的具体禁令

引擎最近强化了墓碑确认的因果性。客户端 **不得**：

- 按时间戳挑选"最新"的确认记录；
- 从 device head 推导确认；
- 把到达顺序当作删除观察证明；
- 重写 `observed_commit_id`。

确认证明必须由引擎产生。

---

## 13. 分阶段迁移计划

建议按以下顺序推进，每一阶段独立可发布、可回滚：

**阶段 0 — 打通构建**
构建 `mdbx-ffi` 三 ABI，生成 Kotlin 绑定，在一个 debug 变体里成功调用
`mdbxBuildCapabilityManifest()`。不接入任何业务。

**阶段 1 — 只读影子模式**
用 FFI 打开一份 working copy 的 **副本**，用 summary 接口读取集合与对象，
与现有 `MdbxVaultStore` 的读取结果做比对，记录差异。不改变现有写入路径。

**阶段 2 — 迁移编排**
接入 `inspectVaultMigration` → `createPortableBackup` → `upgradeVault`
的用户确认流程，替换现有的隐式升级行为。

**阶段 3 — 读取路径切换**
列表、详情元数据全面切到 summary + 分页接口。揭示明文切到
`revealObjectWithDeviceContext*`，并接入 Tiga 判定 UI。

**阶段 4 — 写入路径切换**
所有写入切到 `executeWriteOperation*`，实现 `operationId` 持久化与幂等重试。
批量操作合并为单提交。

**阶段 5 — 附件与冲突**
附件走 FFI 并落实内存限额；冲突页接入逐字段展示。

**阶段 6 — 安全策略驱动 UI**
超时、锁定、剪贴板、截屏保护全部由 `resolveTigaPolicy` 驱动，
移除 Kotlin 侧硬编码。

**阶段 7 — 同步**
等待引擎侧暴露 bundle 构建与应用后再迁移。在此之前保留现有 Kotlin 实现。

---

## 14. 验收清单

实现方在提交前应逐项自检：

**构建**

- [ ] 三个 ABI 的 `libmdbx_ffi.so` 均已产出并放入正确的 jniLibs 目录
- [ ] Kotlin 绑定由 `uniffi-bindgen` 生成，未手工修改
- [ ] CI 校验绑定与引擎版本一致

**生命周期**

- [ ] `deviceId` 跨进程、跨重装稳定
- [ ] 同一 vault 文件全局只有一个 `MdbxVault` 实例
- [ ] 所有 FFI 调用不在主线程
- [ ] vault 存放在应用私有目录

**迁移**

- [ ] 需要升级时先展示确认对话框，明确说明单向性
- [ ] 升级前调用 `createPortableBackup`
- [ ] `unknownCriticalExtensions == true` 时拒绝打开并提示升级 App
- [ ] 任何导出 / 上传都走 `createPortableBackup` 或 `createBackup`，
      不直接复制主文件

**读取**

- [ ] 列表页只用 summary，不触发解密
- [ ] `pageSize` 在 1–200 之间（提交历史 1–100）
- [ ] 正确处理 `nextCursor == null` 的末页语义
- [ ] 未使用 `listObjects` / `listEntries` 等 MDBX1 兼容全量接口

**安全**

- [ ] `MdbxDeviceContext` 如实反映设备能力，无虚报
- [ ] 五种 `MdbxAuthorizationOutcome` 均有对应 UI 分支
- [ ] 11 种 `MdbxAuthorizationReason` 均有可操作文案
- [ ] 超时、剪贴板 TTL、截屏保护均来自 `resolveTigaPolicy`
- [ ] 降低安全档位时强制用户提供 `weakeningReason`
- [ ] `persistentPlaintextCacheAllowed == false` 时不落任何明文缓存

**写入**

- [ ] 一次用户操作产生一个提交
- [ ] `operationId` 在发起前持久化，支持幂等重试
- [ ] 正确处理 `alreadyCommitted == true`
- [ ] 超过默认限额时自行分批，未擅自调高上限
- [ ] `entryType` 仅使用九种合法取值

**附件**

- [ ] 单附件大小有 UI 层限制
- [ ] `ResourceLimit` 错误有明确提示，未通过调高限额绕过
- [ ] `attachmentTempFilesAllowed == false` 时不落临时文件
- [ ] 对象与附件的原子改动使用 composite 接口

**冲突与清理**

- [ ] 冲突页逐字段展示 `conflictingFields`
- [ ] `device-has-not-acknowledged-delete` 展示为"等待同步"而非错误
- [ ] 快照修剪走两阶段 plan / prune

**边界**

- [ ] 未直接读写第 12.2 节列出的任何表
- [ ] 未在客户端实现格式迁移或兼容转换
- [ ] 未按时间戳或 device head 推导墓碑确认

---

## 15. 错误处理

`MdbxFfiError` 在 Kotlin 侧是一个异常层级，变体：

| 变体 | 含义与处理 |
| --- | --- |
| `Storage(message)` | 存储层错误，包含资源限额、完整性、IO 等，按 message 分类展示 |
| `Serialization(message)` | payload JSON 序列化失败，属于客户端 bug |
| `SyncProtocol(message)` | 同步协议错误 |
| `InvalidEntryType(entryType)` | 条目类型非法，属于客户端 bug |
| `InvalidObjectTypeId(objectTypeId)` | 对象类型 ID 非法 |
| `InvalidRelationKind(relationKind)` | 关系类型非法 |
| `InvalidCollectionTypeId(collectionTypeId)` | 集合类型 ID 非法 |
| `InvalidExtensionCapabilityId(capabilityId)` | 扩展能力 ID 非法 |
| `InvalidExtensionId(extensionId)` | 扩展 ID 非法 |
| `InvalidExtensionFeatureId(featureId)` | 扩展特性 ID 非法 |
| `InvalidConflictObjectType(objectType)` | 冲突对象类型非法 |
| `LockPoisoned` | 内部互斥锁中毒，说明此前有调用 panic；应重启 vault 句柄 |

`Invalid*` 系列全部是 **客户端参数错误**，应在开发期通过类型约束消灭，
不应在生产环境出现。`LockPoisoned` 表示引擎处于不可信状态，
必须关闭并重新打开 vault。

---

## 16. 时间与 ID 约定

- 所有时间字符串为 **ISO-8601 UTC**（如 `2026-07-26T10:30:00Z`）。
  以 `_unix_secs` 结尾的字段是 Unix 秒。
- 所有 ID 由客户端生成并保持稳定：`projectId`、`entryId`、`objectId`、
  `attachmentId`、`relationId`、`labelId`、`assignmentId`、`operationId`。
  **ID 一旦写入就不得变更**，重命名操作只改 `title`，不改 ID。
- `commitId`、`conflictId`、`snapshotId`、`epochId` 由引擎生成，
  客户端只读。

---

## 17. 扩展档案（可选）

```kotlin
fun registerExtensionProfile(profile: MdbxExtensionProfile): MdbxExtensionRegistration
fun replaceExtensionProfiles(profiles: List<MdbxExtensionProfile>)
fun getExtensionProfile(extensionId: String): MdbxExtensionProfile?
fun listExtensionProfiles(): List<MdbxExtensionProfile>
fun unregisterExtensionProfile(extensionId: String): MdbxExtensionProfile?
fun setExtensionCapabilities(capabilityIds: List<String>)
fun getCollectionProfile(collectionId: String): MdbxCollectionProfile?
fun setCollectionProfile(collectionId: String, collectionTypeId: String, payload: ByteArray, payloadSchemaVersion: UInt, allowedObjectTypeIds: List<String>, requiredCapabilityIds: List<String>): ...
fun createPayloadMigrationPlan(
    collectionId: String,
    objectTypeId: String,
    sourceSchemaVersion: UInt,
    targetSchemaVersion: UInt,
    maxItems: UInt,
    branchId: String?,
): MdbxPayloadMigrationPlan
fun createPayloadMigrationPlanWithDeviceContext(..., device: MdbxDeviceContext): MdbxPayloadMigrationPlan

fun executePayloadMigration(
    plan: MdbxPayloadMigrationPlan,
    outputs: List<MdbxPayloadMigrationOutput>,
): MdbxPayloadMigrationExecution
fun executePayloadMigrationWithDeviceContext(plan, outputs, device: MdbxDeviceContext): MdbxPayloadMigrationExecution
```

**重要**：扩展档案是 **进程内状态**，vault 重新打开后为空。如果你的应用依赖
自定义对象类型，必须在 **每次打开 vault 之后** 重新注册档案。不要假设它们
被持久化。

`collectionProfile` 与之不同，它是持久化的。

payload 迁移是两阶段的：先 `createPayloadMigrationPlan` 得到计划，
再 `executePayloadMigration` 执行。带 `_withDeviceContext` 的变体会走
`MIGRATE_PAYLOAD` 的 Tiga 授权。

---

## 18. 提问与反馈

实现过程中如果发现：

- 本文档描述的方法在生成的绑定中不存在；
- 某个业务必需的能力属于第 12 节的"未暴露"清单；
- 某个限额在真实设备上不合理；

请提出，由引擎侧新增对应责任块解决，**不要在客户端绕过 FFI 自行实现**。

引擎侧对 `master` 的每次改动都会在 GitHub Actions 上执行八项门禁
（`.github/workflows/ci.yml`）：格式、空白、`clippy -D warnings`、workspace
测试、storage core profile 测试，以及三项构建检查。其中
`cargo check -p mdbx-ffi --no-default-features` 专门保证 FFI 导出面在裁剪
构建下不被破坏。

这对你的意义是：**本文档引用的方法签名一旦在引擎侧被改动，CI 会先失败。**
所以你可以把某个已发布 tag 的绑定当作稳定契约；如果升级引擎版本后绑定
出现不兼容变化，那一定是引擎侧的显式决定，而不是意外漂移，届时会同步更新
本文档。
