# Monica Android 性能与 Rust 重构

状态：**Phase 1 运行时接入中**。Rust 已不再只是隔离实验：Android 通过批量 JNI 调用使用无明文列表核心；同时优先删除 Kotlin 列表热路径中的无用全量解密。当前分支仍需真实设备 A/B 数据后才能声称具体性能提升。

## 架构结论

不把 Compose、导航、生命周期、Room、Android Keystore、生物识别和系统服务整体改写成 Rust。这些边界继续由 Kotlin/Android 管理。Rust 负责适合批量处理、确定性、平台无关的算法，例如列表元数据搜索/投影、来源感知去重、解析与合并逻辑。

## 本轮已经落地的关键改动

### 1. 普通密码列表取消全量登录密码解密

旧路径在筛选/去重后，对每个可见 `PasswordEntry` 调用 `inspectSecretState(...).plainValueOrEmpty()`。卡片并不显示登录密码，因此这会把数据库失效、筛选切换和进入密码页都放大成 O(n) secret 解析。

新路径：

- 列表保留 Room 返回的原始密文，保证复制、移动、同步等显式命令仍有完整数据；
- 普通渲染不再把整表变成密码明文；
- 幽灵行过滤只对共享候选组解析 secret；单例条目零 secret 读取；
- 智能去重继续只解析候选重复组；
- 密码卡片、卡片展示字段和分组键均不读取登录密码。

这里特意**不把 `password` 简单清空**：批量复制/移动会使用选中条目的存储密文做按需解密，清空会破坏跨存储复制语义。

### 2. Rust 批量元数据搜索接入真实 Android 运行时

新增 `rust-jni` cdylib 和 Kotlin `RustPasswordListCore` facade。一次查询只跨 JNI 一次，传入：

- ID；
- 标题；
- 用户名；
- 网站；
- 应用名；
- 包名；
- 查询词。

不会传入：登录密码、TOTP secret、银行卡敏感字段或其他 credential secret。Rust 返回匹配 ID；native 不可用时 Kotlin 自动回退。

### 3. 首屏与维护任务分阶段

原 `PasswordViewModel.init` 会在进入页面时并发启动：

- KeePass 遗留绑定修复扫描；
- ownership conflict 修复；
- Bitwarden 离线 secret cache 全量预热。

Bitwarden 预热会遍历绑定条目并解析密码，这与首屏数据库/Compose 同时抢 CPU。新方案先让密码列表元数据、全量 UI lookup 和分类首次就绪，再给 UI 一个短暂 settling window，之后才启动维护和缓存预热。功能保留，但不再作为首内容的前置竞争者。

### 4. 发布链正式编译 Rust JNI

- `Android.yml`：Release 构建前为 `arm64-v8a` 和 `armeabi-v7a` 构建 `libmonica_rust_jni.so`；
- `Android-Preview.yml`：Preview/PR 构建同样带 Rust JNI，并监听 Rust 源码路径；
- `Rust-Core.yml`：同时执行 `rust-core` 与 `rust-jni` 的 rustfmt/Clippy，core 单测继续执行；
- R8 保留 JNI facade 的类名，避免静态 `Java_*` 导出在混淆后失联。

### 5. 密码库与 Monica 键盘复用元数据热路径

- 密码库直接消费 `allPasswordsForUi`，不再为列表展示解密登录密码；同一批密码条目只构建一次展示投影，并在首帧保留状态、完整快照和筛选阶段之间复用；
- Monica 键盘在当前解锁会话内缓存 Room 原始快照和展示投影，搜索、排序与数据库筛选不再反复读取整库；
- 键盘密码列表保留存储值，只有用户点击复制、插入或智能填充时才解析该条密码；
- 验证器、卡包和密码面板按需加载，验证器每秒更新时间码时不会连带重建密码与卡包列表；
- 锁定、数据库来源变化和强制刷新都会清空缓存，不跨解锁会话保留敏感数据。

## 安全边界

- Keystore、主密码、biometric/session 逻辑不迁入 Rust；
- JNI DTO 不含 credential secret；
- 随机化密文不能充当 secret 相等指纹；
- 如果未来引入 `secret_fingerprint`，只能使用写入/导入时产生的不可逆带密钥摘要；
- IME 独立进程依赖 Room multi-instance invalidation，该能力不能为了性能移除。

## 仍然值得继续的方向

### UI 状态扇出

`PasswordListContent` 仍然非常大，并同时订阅密码列表、全量 lookup、分类、搜索、认证、设置、KeePass/MDBX/Bitwarden、附件和传输状态。后续适合：

- 收敛成稳定的屏幕级 `PasswordListUiState`；
- 使用 lifecycle-aware Flow 收集；
- 将同步/对话框/选择状态拆到独立组合边界；
- 将仓库查询移出 Composable。

### Room 元数据 projection

`allPasswordsForUi` 目前仍由完整 `PasswordEntry` 映射并清空登录密码。长期应引入列表专用 projection/DTO，让 SQL 根本不读取不需要的敏感大字段，并减少对象复制和内存占用。

### 图标链

每张卡片仍可能涉及上传图标、Simple Icon、app icon、自动匹配与 favicon。应进一步形成单一来源状态机和稳定缓存，避免滚动时重复解析。

## Rust FFI 规则

- 每个快照/查询一次批量调用，禁止逐条 JNI；
- Compose State 与回调不跨 FFI；
- DTO 有明确边界，不包含密码；
- native 失败必须有 Kotlin 回退；
- Rust 结果必须保持输入顺序和现有搜索语义；
- 只有真实 profiling 证明有 CPU 热点时才继续把算法迁入 Rust。

## 验收门槛

代码层：

- Rust core 单测全绿；
- rustfmt/Clippy 全绿；
- Android JVM 单测全绿；
- Kotlin 编译和完整 APK 构建通过；
- APK 内实际包含对应 ABI 的 `libmonica_rust_jni.so`；
- 批量复制/移动、详情、搜索、分类、混合 KeePass/MDBX/Bitwarden 视图语义不回退。

设备层：

- 同一设备、同一数据库、同一配置做冷进程 A/B；
- 记录解锁到首内容、p50/p95/p99 帧时间、jank、Java/native heap 和 APK 体积；
- 在没有设备测量前不写“提升 X 倍”之类结论。

推荐控制组：同一 commit/buildType 做一份禁用 Rust runtime 的 APK，与启用 Rust 的 APK 放在等价新实例/克隆环境中比较；这样才能把 Rust 收益、启动维护调度收益和历史应用状态差异拆开。
