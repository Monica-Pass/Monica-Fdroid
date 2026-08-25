# MDBX Android 接入文档

语言：[简体中文](README.zh-CN.md) | [English](README.md)

本文记录 Monica for Android 当前接入 MDBX 1.0 的实际结构，供后续 Android 客户端、Monica Android 维护者和 MDBX FFI 迁移工作参考。

这不是 MDBX 格式规范。格式规则仍以 [`docs/`](../README.zh-CN.md) 为准；通用客户端边界见 [`CLIENT_INTEGRATION_GUIDE.zh-CN.md`](../../CLIENT_INTEGRATION_GUIDE.zh-CN.md)；跨语言 UniFFI 边界见 [`crates/mdbx-ffi/README.zh-CN.md`](../../crates/mdbx-ffi/README.zh-CN.md)。

## 接入原则

Android 端必须遵守 MDBX 的准则：

- 4ever And 4ever：旧测试版 vault 必须长期可读。
- 数据安全优先：准备、导入、同步、冲突解决失败时不能留下半写状态。
- Sky 灵活但不代表不安全：默认不强制 key file 或硬件密钥，用户成本不能被无故抬高。
- UI 和 ViewModel 不直接拼写 MDBX 底层表。
- 密码、TOTP、Note、Document、Card、Passkey 移动到 MDBX folder 后必须同时保留 `mdbxDatabaseId` 和 `mdbxFolderId`。
- 本地、WebDAV、OneDrive 都通过 working copy 写入，再按来源 flush。

## 当前实现边界

Monica for Android 当前没有直接使用 `mdbx-ffi`。它在 Android 代码内实现了一个 app-facing facade：

- `MdbxRepository`
  - 用户可见 MDBX 操作的接口边界。
  - 声明 folder、entry、secure item、passkey、attachment、tags/search、history、snapshot、sync bundle、conflict、diagnostics 等能力。
- `MdbxVaultStore`
  - 当前 Android 端的 SQLite facade 实现。
  - 负责 MDBX 1.0 元数据、旧库兼容准备、写锁、事务、commit/object version/tombstone/snapshot/conflict/sync state、working copy flush。
- `MdbxViewModel`
  - 负责 UI 状态、创建/打开本地和远程 vault、导入回 Room、主动预加载当前 vault。
  - 不应承载底层 MDBX 表规则。
- `LocalMdbxDatabase`
  - Room 侧的本地索引，不是 MDBX vault 本体。
  - 记录 `filePath`、`workingCopyPath`、`sourceType`、`sourceId`、Tiga、unlock method、sync status 等。
- `MdbxFileSource`
  - WebDAV / OneDrive 文件源抽象。
  - 负责远程读写，不负责解释 MDBX 内部结构。

后续如果把 Android 切到 `mdbx-ffi`，推荐先让 `MdbxRepository` 保持稳定，把 `MdbxVaultStore` 内部逐步替换为 FFI 调用，而不是让 UI 直接调用 FFI 或 SQL。

## 关键代码位置

在 Monica for Android 仓库中：

- `app/src/main/java/takagi/ru/monica/data/LocalMdbxDatabase.kt`
  - Room 索引、来源类型、Tiga、unlock method、sync status。
- `app/src/main/java/takagi/ru/monica/repository/MdbxRepository.kt`
  - Android 端 MDBX facade。
- `app/src/main/java/takagi/ru/monica/repository/MdbxVaultStore.kt`
  - Android 端 MDBX SQLite 实现。
- `app/src/main/java/takagi/ru/monica/viewmodel/MdbxViewModel.kt`
  - 创建、打开、远程连接、导入 Room、诊断、sync、snapshot、conflict UI 状态。
- `app/src/main/java/takagi/ru/monica/utils/MdbxFileSource.kt`
  - MDBX 文件源抽象。
- `app/src/main/java/takagi/ru/monica/utils/WebDavMdbxFileSource.kt`
  - WebDAV 远程文件源。
- `app/src/main/java/takagi/ru/monica/utils/OneDriveMdbxFileSource.kt`
  - OneDrive 远程文件源。
- `app/src/main/java/takagi/ru/monica/ui/screens/MdbxManagerScreen.kt`
  - MDBX 1.0 管理首页和高级面板。
- `docs/MDBX_1_ANDROID_ACCEPTANCE.md`
  - Android 当前验收证据和真机验收矩阵。

## 数据模型映射

Android 端维护两层状态：

1. MDBX vault 文件
   - 真正的项目、条目、附件、commit、tombstone、snapshot、conflict、sync state 都在 `.mdbx` 文件内。
2. Android Room 索引
   - `local_mdbx_databases` 只记录本机如何找到和同步这个 vault。
   - `PasswordEntry`、`SecureItem`、`PasskeyEntry` 中的 `mdbxDatabaseId` / `mdbxFolderId` 是 Android UI 和本地列表的索引字段。

写入规则：

- 新增或移动密码到 MDBX 时，Room 行和 MDBX 项目都必须更新。
- folder 目标为 root 时不要把 `"root"` 当成普通 folder id 固化到 Room。
- 非 root folder 必须进入 payload、project/object_index 元数据，并在导入回 Room 时恢复。
- 批量移动、复制、删除应该按用户操作合并，而不是让 UI 每条数据制造一套不相关历史。

## Vault 创建与打开

新建 Android MDBX 1.0 vault 必须写入：

- `format_version = MDBX-1`
- `release_label = MDBX-1.0`
- Android capability flags，例如 `android-official-1.0`、`sky-portable`、`tiga-selectable`、`legacy-test-compatible`

打开旧测试版 vault 时：

- `MDBX-1-DRAFT` 必须保持可读。
- 准备流程必须是 additive preparation。
- 可以补齐 `release_label`、capability flags、缺失的凭据材料。
- 不能破坏旧数据，不能强制用户新增 key file 或硬件密钥。

Android 当前的创建/打开路径覆盖：

- 本地内部文件。
- 本地外部 / SAF 文件。
- WebDAV 远程文件。
- OneDrive 远程文件。

## Working Copy 与远程同步

远程 vault 不应直接边读边写远端文件。Android 当前模式是：

1. 下载或创建本地 working copy。
2. 所有 SQLite 写入发生在 working copy。
3. 写入持有 per-vault lock。
4. SQLite 写入、commit、object version、head、tombstone 等一起成功或一起回滚。
5. 写入完成后 checkpoint working copy。
6. 根据 `sourceType` flush 到本地外部文件、WebDAV 或 OneDrive。
7. 更新 `lastSyncStatus` / `lastSyncError`。

实现必须避免：

- 写锁释放后才 flush 同一个 working copy。
- 只更新 Room 行但没有写 MDBX vault。
- 只写 MDBX vault 但没有更新 Android 可见索引。
- WebDAV 和 OneDrive 都当作同一种 source 展示或处理。

## Tiga 与解锁

Android 端暴露三档 Tiga：

- `POWER`
  - 最高防护，适合更强 KDF 参数和更少便利性。
- `MULTI`
  - 平衡默认。
- `SKY`
  - 灵活、低使用成本、适合网盘和多设备便携，但仍然安全。

解锁方式当前包括：

- master password
- key file
- master password + key file
- device key

默认体验要求：

- 新建 Sky vault 不要求 key file。
- 不默认强制硬件密钥。
- 旧 vault 准备不引入额外解锁提示。
- 硬件密钥可以作为 Multi/Power 增强能力，不能让 Sky 变成“不安全”的代名词。

## UI 与管理入口

完整 Android 接入应提供：

- MDBX 1.0 管理首页。
- 本地 / WebDAV / OneDrive 新建与打开。
- 数据库详情与诊断。
- folder / structure 管理。
- 移动 / 复制目标选择。
- 冲突管理。
- 提交历史与 diff。
- snapshot 创建、预览、恢复。
- tags/search。
- pending sync 和 flush 操作。

入口规则：

- “MDBX 1.0 数据库管理”应进入管理首页。
- 不应自动跳进上次打开的某个库详情页。
- 开发者工具可以隐藏在高级面板，普通用户不应直接看到 raw sync bundle 或底层 chunk 调试。

## 测试与验收

Android 接入至少保留这些测试层：

- JVM guard test
  - `MdbxAndroidIntegrationGuardTest`
  - 锁住关键源码边界，防止 UI 绕过 facade、folder id 丢失、旧库兼容被破坏。
- Instrumentation test
  - `MdbxVaultStoreInstrumentedCompatibilityTest`
  - 用真实 SQLite 验证旧库 additive preparation、新 Sky vault、核心 facade 写读、坏包回滚、冲突检测和解决。
- 真机或模拟器手动验收
  - 见 Monica Android 仓库的 `docs/MDBX_1_ANDROID_ACCEPTANCE.md`。

运行设备测试前必须确认目标设备是专用测试设备或一次性 AVD。不要在用户日常使用的 AVD 上运行会安装、卸载、清数据或 instrumentation 的命令。

## 不允许回退

- 不允许把 `release_label = MDBX-1.0` 换成破坏旧读取器的底层 token。
- 不允许旧测试版 vault 因缺少新增表而直接拒绝最小读取。
- 不允许默认要求 key file 或硬件密钥。
- 不允许 sync bundle 缺少 `project_tags` 时清空本地 tags。
- 不允许 Passkey / TOTP / Note / Document / Card 移动到 MDBX folder 后只保存 databaseId。
- 不允许 UI、ViewModel 或 Room DAO 直接维护 MDBX commit/tombstone/snapshot/conflict/project_tags 表。
- 不允许远程 flush 失败时报告用户写入完全成功。

## 后续 FFI 迁移建议

如果 Android 后续切到 `mdbx-ffi`：

1. 保持 `MdbxRepository` 作为 Android app-facing facade。
2. 先把 create/open/unlock/project/entry 的内部实现替换为 FFI。
3. 再扩展 FFI 的 tags、attachment、sync bundle、conflict、snapshot、diagnostics 方法。
4. 每扩展一个 FFI 方法，补 Rust FFI smoke test 和 Android guard test。
5. Room 索引字段仍只作为 Android UI 缓存，不应成为 MDBX 格式事实来源。
6. 迁移期间必须继续打开 `MDBX-1-DRAFT` 和 Android 已创建的 `MDBX-1.0` vault。

迁移目标不是把 SQLite 细节搬到 Kotlin，而是把更多格式规则收回 MDBX Rust workspace，同时保持 Android 低使用成本和完整管理能力。

具体实施步骤、真实导出方法签名、限额常量、Tiga 授权流程和当前尚未通过 FFI
暴露的能力清单见 `docs/android/MDBX2_ANDROID_INTEGRATION.zh-CN.md`。注意上面
第 3 步提到的 sync bundle 目前 **不在** FFI 导出面中，迁移范围需据此收窄。
