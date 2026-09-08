# MDBX3 运行时架构与 SO 体积设计

## 1. 架构目标

MDBX3 继续生成一个自包含的 `libmdbx_ffi.so`。内部结构需要同时满足四项要求：

1. 旧 UniFFI bindings 可以调用全部既有能力。
2. 安全、存储、同步和恢复规则只有一套实现。
3. 冷门能力不会增加启动阶段的初始化和常驻内存。
4. 编译器和链接器能够识别无用代码、重复代码和只用于开发的代码。

## 2. 分层结构

```text
MDBX2-compatible UniFFI ABI
            |
            v
Compatibility Facade
            |
            v
Vault Runtime
   |        |        |
   v        v        v
Storage   Security   Sync
   |        |        |
   +--------+--------+
            |
            v
SQLite, Crypto, Blob Provider
```

### 2.1 Compatibility Facade

Compatibility Facade 保留 `mdbx_ffi` namespace、现有函数、Record、Enum 和 Error。该 Module 只负责参数校验、版本适配和结果转换，不拥有 SQL、密钥、commit 或同步状态。

新 API 采用附加函数和附加类型。已有类型保持冻结，避免重新生成 bindings。

### 2.2 Vault Runtime

Vault Runtime 负责连接生命周期、会话、设备身份、reader generation、single writer 和长操作协调。所有公开调用通过同一个 Runtime 进入 storage、Tiga 和 sync，避免 FFI facade 分别维护状态。

现有 `Mutex<VaultConnection>` 在第一阶段保持兼容实现。替换为读快照与 single writer 前，需要证明以下语义：

1. 写入后由同一 `MdbxVault` 发起的读取可以观察新状态。
2. key epoch 轮换会使旧 reader generation 失效。
3. session 过期立即影响全部 reader。
4. Tiga override 和 revocation 不会被读缓存延迟。
5. snapshot、restore、sync apply 和普通写入保持互斥要求。

### 2.3 Storage Module

Storage Module 继续拥有 SQLite schema、迁移、事务、repo、commit、object version、snapshot、tombstone、conflict 和 state delta。Compatibility Facade 和领域 Adapter 均不得拥有 raw SQL 写权限。

### 2.4 Security Module

Security Module 继续拥有 KDF、AEAD、keyring、key epoch、Tiga 求值、授权审计、rollback anchor 和 content manifest。为了缩减二进制而合并加密 domain、降低 KDF 参数或跳过审计均属于禁止变化。

### 2.5 Sync Module

Sync Module 继续拥有 bundle codec、capability negotiation、commit inventory、delta paging、resume、认证和压缩协商。MDBX3 的 checkpoint 与分段 bootstrap 优化必须保持 MDBX2 peer 回退行为。

### 2.6 Adapter Seam

通用对象核心只保存不透明 payload。Steam、邮件、书签和其他领域语义继续位于 Adapter。一个产品可以把需要的 Adapter 静态链接进同一个 SO，也可以让客户端实现纯领域解释；两种方式都不能授予 Adapter raw SQL、密钥或 Tiga 权限。

## 3. 构建配置

MDBX3 不采用整个 workspace 统一 `opt-level = "z"`。建议建立经过基准选择的生产 profile：

| 组件 | 优化目标 | 建议候选 |
|---|---|---|
| `mdbx-crypto` | 吞吐和固定时间实现 | `opt-level = 3` |
| `mdbx-storage` | SQL、序列化和事务热区 | `opt-level = 3` |
| `mdbx-sync` | 编解码、哈希和压缩热区 | `opt-level = 3` |
| `mdbx-core` | 共享类型和策略 | 分别比较 `s` 与 `3` |
| `mdbx-ffi` | 边界转换和冷代码 | `opt-level = "s"` |
| 领域 Adapter | 解析器占用 | 分别比较 `z` 与 `s` |

生产构建应比较 thin LTO 与 fat LTO，以每个 ABI 的真实数据决定选择。`codegen-units = 1`、关闭增量编译、section garbage collection 和外置调试符号属于候选设置。

FFI 完整构建保留 unwind 与 panic 捕获边界。`panic = "abort"` 只有在所有公开入口都证明无 panic 且进程终止行为经过客户端接受后才可采用；首个 MDBX3 版本不采用该设置。

## 4. SQLite 体积控制

Android 完整构建继续使用 bundled SQLite，避免设备系统 SQLite 差异，也避免增加第二个 MDBX 动态库。

实施阶段需要生成 SQLite capability manifest，至少记录：

1. SQLite 版本和 source ID。
2. compile options。
3. backup、WAL、FTS、trigger、foreign key 和 integrity check 能力。
4. 最大 page size、variable 数量和线程模式。
5. MDBX schema 与迁移实际使用的 SQL 功能。

每项 `SQLITE_OMIT_*` 候选都需要单独提交和 fixture 验证。一个提交只调整一组相关编译选项，便于比较体积与行为。

## 5. Feature 配置

首个 MDBX3 Android 兼容配置命名为 `mdbx3-android-full-v1`，能力集合必须至少等于当前 MDBX2 `mdbx-ffi` 的 manifest。当前 FFI 显式启用 storage `core` 与 `filesystem-blob-store`，并未自动包含 storage 默认的 KDBX binary、benchmark 和 derived search feature。

因此，“完整”以当前 FFI 公开能力为准，不把 CLI 专属功能强行加入 SO。未来产品配置需要使用新的稳定名称，例如：

```text
mdbx3-android-full-v1
mdbx3-android-mail-v1
mdbx3-android-bookmark-v1
mdbx3-native-universal-v1
```

每个配置仍然只生成一个 `libmdbx_ffi.so`，并通过 build capability manifest 报告实际模块。

发布 gate 还会把 MDBX2 `mdbx-ffi` profile 的启用和禁用 capability ID 作为冻结集合逐项比较。任何已发布能力从启用集合移出、或被错误加入禁用集合，都会使构建失败；LTO、strip 和链接优化不能绕过这项检查。

## 6. 体积预算

每个 ABI 使用当前 MDBX2 的正式 release、strip 后 SO 作为基准 `B0`。同时记录 raw ELF、strip 后 ELF、发布 ZIP、APK 内压缩值和安装后占用。

MDBX3 发布门禁如下：

1. strip 后 SO 不得大于对应 ABI 的 `B0`。
2. 第一阶段目标为 strip 后 SO 不高于 `0.80 * B0`。
3. APK 内压缩值目标为不高于 `0.85 * B0_apk`。
4. 未达到目标时必须保存 `cargo bloat`、link map 和 SQLite section 报告，继续按最大贡献项优化。
5. 任何功能、ABI、安全或性能验收失败时，体积收益不计入候选版本。

## 7. 体积测量规则

1. MDBX2 与 MDBX3 使用相同 Rust、NDK、linker、target、strip 工具和构建环境。
2. 每次测量从干净 release 产物开始。
3. 所有 ABI 分别报告，禁止用单个 ABI 代表全部平台。
4. debug symbols 单独归档，发布 SO 保留 build ID。
5. 禁止使用 UPX 等改变 ELF 运行时装载方式的压缩方案。
6. release 报告保存依赖树、重复 crate 版本和最大符号列表。

## 8. 体积优化顺序

1. 排除 test、benchmark、CLI 和生成器代码。
2. 消除重复 crate 版本和重复序列化实现。
3. 缩小错误格式化、调试文本和未使用泛型实例。
4. 比较 LTO、codegen units 和各 crate 优化级别。
5. 审核 UniFFI scaffolding 和 DTO 重复转换。
6. 审核 SQLite 编译能力。
7. 最后评估领域 Adapter 的产品配置。

该顺序优先减少重复与开发代码，保护用户能力和数据语义。
