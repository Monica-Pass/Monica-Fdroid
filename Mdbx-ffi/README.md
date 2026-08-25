# MDBX

语言：[简体中文](README.md) | [English](README.en.md)

这个目录包含 Monica MDBX 的 Rust workspace 和实现接入说明。

MDBX 是 Monica 的本地优先高级加密数据库核心。它为经过认证和版本化的 Collection、ObjectRecord 与二进制内容提供长期存储、类 Git 逻辑历史、同步冲突处理、快照恢复和 Tiga 安全策略。密码管理、网页收藏、邮件、Steam `mafile` 等属于可裁剪的领域 Adapter。

当前格式代际为 **MDBX2**，存储值为 `MDBX-2`。MDBX2 可自动、事务性地升级 `MDBX-1` 与 `MDBX-1-DRAFT` vault；兼容契约见 `docs/09-mdbx2-compatibility.zh-CN.md`。

规范性文档在 `docs/`。

MDBX 的准则是 **4ever And 4ever**：旧 vault 必须长期可读，新增能力必须尽量保留兼容路径，数据安全永远优先于一时方便。

## Workspace 结构

- `crates/mdbx-core`
  - 核心领域类型。
- `crates/mdbx-crypto`
  - 加密、KDF、密钥材料处理。
- `crates/mdbx-sync`
  - 同步 payload 和 object payload 模型。
- `crates/mdbx-storage`
  - SQLite schema、vault 初始化、repo、搜索、快照、冲突、恢复、sync state。
- `crates/mdbx-ffi`
  - 基于 UniFFI 的通用跨语言边界，暴露 Vault、Collection Profile 和 ObjectRecord 操作；客户端特定 payload 语义仍由各领域 Adapter 负责。
- `crates/mdbx-cli`
  - 本地测试和开发用 CLI。
- crate-local `tests/`
  - 兼容性、crypto vector、并发、恢复场景跟随各 crate 放置。

## 本目录文档

- `CLIENT_INTEGRATION_GUIDE.md`
  - 英文版客户端接入指南。
- `CLIENT_INTEGRATION_GUIDE.zh-CN.md`
  - 中文版客户端接入指南。
- `crates/mdbx-ffi/README.md` / `crates/mdbx-ffi/README.zh-CN.md`
  - 面向非 Rust 客户端的 UniFFI 边界参考。
- `docs/android/README.zh-CN.md` / `docs/android/README.md`
  - Monica for Android 当前 MDBX 1.0 接入结构、working copy、Room 索引和后续 FFI 迁移建议。
- `docs/android/MDBX2_ANDROID_INTEGRATION.zh-CN.md`
  - 面向 Android 客户端实现方的 MDBX2 `mdbx-ffi` 接入实施文档。

## 规范文档

修改存储行为前必须先读 `docs/`：

- `docs/README.md` / `docs/README.zh-CN.md`
- `docs/01-product-spec.zh-CN.md`
- `docs/02-storage-sync-spec.zh-CN.md`
- `docs/03-security-spec.zh-CN.md`
- `docs/06-sqlite-schema-v1.zh-CN.md`
- `docs/09-mdbx2-compatibility.zh-CN.md`

`docs/` 定义格式和产品约束；Rust workspace 负责实现这些约束，并提供实际客户端接入说明。

## 客户端支持等级

MDBX 支持需要明确标注：

- **只读支持**
  - 打开、解锁 vault。
  - 显示文件夹、条目、附件元数据。
  - 不写表、不写 commit、不写 tombstone、不写 snapshot、不处理 conflict。
- **基础读写支持**
  - 创建和编辑条目、文件夹。
  - 正确维护 commit、object version、tombstone、snapshot、branch head、device head。
- **同步支持**
  - 合并 commit DAG，保留 tombstone，检测 conflict，安全应用 sync state。
- **完整 Monica 兼容支持**
  - 提供必备管理面板、诊断、快照结构预览、字段级历史、支持 MDBX 文件夹的新建/移动/复制流程。

完整清单见 `CLIENT_INTEGRATION_GUIDE.zh-CN.md`。

## 必备用户管理面板

完整客户端应该提供：

- MDBX 格式管理首页
- 数据库详情页
- 文件夹 / 结构管理
- 移动 / 复制目标选择
- 冲突管理
- 提交历史
- 快照
- 快照结构预览
- 诊断 / 维护
- 解锁与安全

点击“MDBX 格式管理”应该进入 MDBX 管理首页，不应该自动进入上次打开的某个数据库详情页。

普通用户界面不应该暴露 raw sync bundle、benchmark、底层 chunk 调试等开发者工具。这些工具可以放在开发者模式。

## 开发命令

在本目录执行：

```powershell
cargo test
```

本地开发 CLI：

```powershell
cargo run -p mdbx-cli -- --help
```

## 提交门禁

`master` 的 push 和 pull request 由 `.github/workflows/ci.yml` 在 Linux、Rust
1.86.0 上执行以下八项，全部通过才算合格：

```text
cargo fmt --all -- --check
git diff --check <empty-tree> HEAD
cargo clippy --workspace --all-targets --all-features -- -D warnings
cargo test --workspace --no-fail-fast
cargo test -p mdbx-storage --no-default-features --features core
cargo check --workspace --all-targets
cargo check -p mdbx-cli --no-default-features --features core
cargo check -p mdbx-ffi --no-default-features
```

两项 `--no-default-features` 检查和 core profile 测试专门用于捕捉全 feature
构建会掩盖的裁剪构建破坏，不能因为 workspace 构建通过就跳过。

当前 `mdbx-cli` 是 Rust workspace 的开发/验证入口，已经覆盖：

- `init` / `unlock`
- project、entry、attachment 基础 CRUD
- `snapshot create/list/restore`
- `sync bundle/apply`
- `health`
- `capabilities` / `capabilities --json`
- `benchmark`
- `import-kdbx` / `export-kdbx`
- `import-kdbx-json` / `export-kdbx-json`

`import-kdbx` 读取 KDBX3/KDBX4，`export-kdbx` 写入使用 Argon2id 的 KDBX4。KDBX 密码仅从隐藏交互输入或 `--password-stdin` 获取；输出使用同目录临时文件发布，已有目标文件会被保留。`import-kdbx-json` / `export-kdbx-json` 继续提供原有 JSON 中间表示，命令和 feature 语义保持不变。配过解锁方式的 vault 在 CLI 普通操作中必须传入 `--unlock-password` 或 `--unlock-pin`；否则命令会拒绝继续，避免把生产写入静默降级到明文兼容路径。

`mdbx capabilities` 不打开 vault，报告当前二进制实际编译的 storage 与 sync 模块；`--json` 适合客户端安装检查。它只描述构建内容，不替代 Collection Adapter 注册、vault critical extension 检查或同步会话能力协商。

当前 CLI 还没有接入真实 FIDO/WebAuthn/security-key 交互，也没有生产级 session token / audit policy；硬件密钥在 storage core 中是 key material 抽象与策略测试，不应宣称为端到端硬件密钥客户端。

`mdbx-ffi` 提供通用 UniFFI 边界，适合非 Rust 客户端复用 MDBX 核心读写能力。它不是绕过 storage/repo 规则的低层 SQL 通道；新增跨端能力时应优先扩展 FFI facade，而不是让客户端直接写底层表。

导出方法、JSON payload 规则、binding 生成、iOS 打包注意事项和扩展规则见 `crates/mdbx-ffi/README.zh-CN.md`。

当前 Rust storage core 已验证的关键能力：

- MDBX-1 / MDBX-1-DRAFT 在可写打开时自动升级为 MDBX-2；迁移幂等，未知 critical extension 会拒绝写入。
- Tiga2 将 Sky/Multi/Power 展开为版本化运行时策略，覆盖会话、显示、剪贴板、导出、附件、恢复、设备保证和审计。
- Tiga2 策略覆盖默认只能加强；精确降级例外、授权决定和审计事件进入同步，冲突采用更严格结果。
- MDBX-1 和早期 MDBX-2 schema 2 原子升级到 schema 3；旧弱覆盖与不合规解锁配置进入整改状态，不会突然锁死用户。
- snapshot 创建和恢复为原子操作；恢复会重建精确 active set、tags 和 attachment chunks，并为所有受影响对象记录统一 restore head 与 object version。
- CLI sync bundle 只使用 storage core 的标准 state payload 和 `SyncApplyRepo`，不再维护第二套直接 SQL apply 路径。

- snapshot 会携带并恢复 active `attachment_chunks`，旧 metadata-only snapshot 仍保持兼容。
- entry、project、attachment 已记录 `object_versions` 行快照，用于非快进三方合并。
- entry/project 不同字段并发修改会写入双 parent merge commit；同字段修改会产生 unresolved conflict。
- attachment 元数据可字段级合并；双方同时替换内容时会保留本地内容并生成 `content_hash` conflict。
- entry/project/attachment conflict resolution 已有 repo 写回 API；解决冲突会写 merge commit、更新对象 head、记录 object version，再标记 conflict resolved。attachment incoming-wins 不会在缺少本地内容材料时伪造远端内容。
- project、entry、attachment 的高风险用户可见 mutation 已包裹为原子事务，commit、对象行、head、object version 会一起成功或一起回滚。
- `project_tags` 已进入 sync state；新 payload 会携带每个 project 的完整 tag 集合，旧 payload 缺少 tag 字段时不会清空本地标签。用户可见 tag 修改应使用 tracked tag API；会话临时搜索索引不进入历史。
- 初始化 key epoch 使用 `mdbx-init-marker-v1` 随机 marker；配置或变更 unlock method 后会绑定 `mdbx-active-key-epoch-v1` active epoch wrapping。完整 key rotation / retirement 仍是后续边界。
- 正式解锁后的新字段使用携带 epoch ID 的 `MDBXFE2` 外层 envelope；旧 `MDBXAE1` 密文继续读取，`field-key-epochs-v1` 关键扩展阻止旧 MDBX2 writer 覆盖新格式字段。

## 实现规则

除非正在修改 storage 层本身，否则客户端代码不要绕过 repo/storage API 直接写底层表。

兼容性和恢复能力属于实现要求，不是后续润色项。新增加密 envelope、表、索引、解锁方式和 Tiga 策略时，除非存在必须处理的关键安全问题，否则必须保持旧 vault 可读。

客户端代码不应该直接写：

- `commits`
- `commit_parents`
- `object_versions`
- `tombstones`
- `snapshots`
- `key_epochs`
- `conflicts`
- `device_heads`
- `branches`
- `project_tags`
- `tiga_policy_overrides`
- `tiga_policy_exceptions`
- `security_audit_events`

批量用户操作通常应该生成一个用户级 commit，而不是每个对象一个 commit。

Android 或其他客户端接入时，应通过 repo/storage API 处理 entry/project/attachment CRUD、tracked tag 修改和 conflict resolution。不要只更新 `conflicts.resolution`，也不要直接改 `project_tags` 后跳过 commit/sync state。

Monica for Android 的当前接入样板见 `docs/android/README.zh-CN.md`。

## 兼容性检查

宣称完整支持前，客户端至少应该确认：

- 能打开 Monica 创建的 MDBX vault。
- 能创建嵌套文件夹，并把嵌套文件夹作为目标。
- 批量移动、复制、删除会合并成用户级 commit。
- tombstone 能防止删除对象被复活。
- 两个客户端读取同一 vault 数量一致。
- 能检测并展示冲突。
- 能创建快照，回滚快照需要二次确认。
- 诊断页能显示同步、健康、历史、tombstone、附件、dangling head 状态。
