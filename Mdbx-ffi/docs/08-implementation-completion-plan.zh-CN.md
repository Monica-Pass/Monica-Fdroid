# MDBX 实现补完计划

版本：`MDBX2 / MDBX-2`

本文是执行文档。它把原始设计清单、现有 `mdbx/` Rust 实现、后续 Monica Android 接入放到同一张路线图里。后续开发应优先更新本文，再按本文推进代码。

## 1. 不可妥协原则

MDBX 必须坚持：

- 本地优先：所有创建、修改、删除、搜索、冲突处理都能离线完成。
- 4ever：格式公开、自描述、自校验、长期可读，新增能力必须保留兼容路径。
- Collection 优先：MDBX1 的 `project` 保持一等物理主容器；MDBX2 通过 Collection Interface 服务密码、邮件、收藏和文件领域，ObjectRecord 不允许退化成无归属平铺记录。
- attachment 一等化：附件从 v1 起进入 schema、历史、恢复和完整性模型。
- 类 Git 历史：每个本地变更必须产生 commit，commit DAG、device head、tombstone、conflict 都是核心格式的一部分。
- 因果冲突检测：不能只靠时间戳；同一秘密字段并发修改必须显式冲突。
- 安全默认：加密、认证、完整性验证失败必须失败，不能静默回退。
- 增量写入：常规小改动只更新相关行和追加历史，不能逻辑上重写全库。

## 2. 当前实现状态

`mdbx/` 已经具备这些基础：

- Rust workspace：`mdbx-core`、`mdbx-crypto`、`mdbx-storage`、`mdbx-sync`、`mdbx-ffi`、`mdbx-cli`。
- SQLite + WAL + foreign key + secure_delete 基线。
- v1 schema 覆盖 `projects`、`entries`、`attachments`、`attachment_chunks`、`commits`、`commit_parents`、`device_heads`、`branches`、`object_versions`、`tombstones`、`snapshots`、`key_epochs`、`conflicts`、`unlock_methods`、`project_tags`。
- project、entry、attachment repo 支持创建、更新、软删除和 tombstone。
- attachment 支持 inline/chunked 内容、chunk hash、整体 hash、改名不改内容。
- Tiga 解析支持 entry > project > global。
- Unlock storage core 支持密码/PIN/security key/password_security_key，Argon2id 参数按 Tiga 区分，密码做 Unicode NFC；CLI 当前只支持密码/PIN 解锁，真实 FIDO/WebAuthn/security-key 交互仍是客户端后续边界。
- KDBX JSON import/export 具备基本回环；这是互操作 JSON 中间表示，不是完整二进制 `.kdbx` 解析/写入。
- conflict detector 支持 JSON 三方字段合并。
- snapshot、recovery 与 benchmark harness 已形成可验证实现；benchmark 发布报告覆盖 encrypted/compatibility 双模式。

上一轮已补强：

- 新增 history/integrity 子密钥。
- commit `changed_object_ids_ct` 在有 keyring 时加密。
- commit `integrity_tag` 不再写 `X'00'`，改为 HMAC-SHA-256 或测试模式 SHA-256。
- 已解锁状态下 project/entry/attachment 解密失败不再吞掉。
- 增加密文篡改回归测试。
- recovery health check 已接入 commit integrity tag 重算校验，能发现 commit 元数据篡改。
- 已解锁 vault 仍兼容旧的明文 commit history payload。
- Tiga global/project/entry 写入接口已改为 tracked mutation，必须带 `CommitContext` 并产生 commit。
- entry 移动到其他 project、复制到其他 project 已成为一等 API，并产生可追溯 commit parent 链。
- entry 本地 create/update/move/copy/delete 会写入 `object_versions` 行快照，用于后续因果三方合并。
- sync apply 已能在非快进分叉场景消费 `mdbx-storage/state-v1`，对 entry payload 做 base/local/incoming 三方字段合并。
- entry 不同 payload 字段并发修改会自动产生 merge commit；同一 payload 字段并发修改会生成 unresolved conflict，并记录具体字段名。

本轮安全与文档对齐已补强：

- 规范目录已统一为 `docs/`，README、接入指南和规范索引已改为新路径。
- 新写入密文使用 `MDBXAE1\0 || commitment || nonce || ciphertext` committed AEAD envelope；解密继续兼容 legacy `nonce || ciphertext`。
- RNG/key/nonce 生成失败必须失败，不允许退回全零 key、确定性 nonce 或占位秘密。
- Tiga 存储/API 名称对齐为 `sky`、`multi`、`power`；`Power Type`、`Multi Type`、`Sky Type` 只作为兼容显示名。
- Sky 被定义为灵活便携但仍然安全，适合网盘同步和恢复优先场景；Multi 建议安全密钥但保留便携恢复；Power 要求密码 + 安全密钥组合解锁才能完整满足策略。
- `unlock_methods.method_type` 明确支持 `password_security_key`。
- 全文搜索只允许使用解锁会话内的临时索引；持久 FTS 不得保存解密后的 project title 或 secret-bearing text。
- `mdbx-cli` 已接入 `health`、`benchmark`、`import-kdbx-json`、`export-kdbx-json`；已有 `snapshot create/list/restore` 与 `sync bundle/apply`。
- 配置过 unlock method 的 vault 在 `mdbx-cli` 普通操作中必须传入 `--unlock-password` 或 `--unlock-pin`，否则拒绝执行，防止生产入口静默走 storage legacy/test 明文兼容路径。
- `mdbx-ffi` 已进入 workspace，作为非 Rust 客户端的通用 UniFFI 边界；当前覆盖 vault/project/generic entry、完整 Tiga2 策略读取与逐操作授权、真实设备能力、迁移检查/显式升级、显式 Tiga 创建、安全密钥材料解锁、组合因素整改、已解锁状态下重设主密码，以及 Collection/Object/Attachment/Conflict/Snapshot 的有界导航。后续 tag、sync、diagnostics 等跨语言能力应继续扩展 facade，而不是让客户端直接写表。
- UniFFI 读取已区分 metadata-only selection 与 Tiga disclosure：`get_object_summary`/`list_object_summaries`、`list_deleted_object_summaries`、`list_all_deleted_object_summaries`、relation/label/assignment summary 页面、`list_unresolved_conflict_summaries` 和 `list_snapshot_summaries` 不读取业务 payload；冲突摘要还限制字段 JSON、路径数和游标，并绑定对象类型过滤，snapshot 摘要只投影 `length(snapshot_ct)`。`reveal_object*` 仅在 Entry 决定允许后返回 `ObjectRecord`；`reveal_object_relation*` 同时保留 source/target Entry 决定；`reveal_object_label*` 继承 collection Project 决定。非允许结果保留类型化原因与约束且不含 payload；旧完整读取继续作为兼容接口。
- 初始化 `key_epochs.wrapped_epoch_key_ct` 不再写固定 `X'00'` 占位；初始化阶段使用 `mdbx-init-marker-v1` 随机兼容标记，配置或变更 unlock method 后会绑定 `mdbx-active-key-epoch-v1` active epoch wrapping。随机 key rotation、retirement、历史读取、同步合并、Tiga 授权与 UniFFI 已闭环。
- snapshot payload 已包含 `attachment_chunks`，恢复时可重建 inline/chunked 附件内容；旧的 metadata-only snapshot 仍通过默认空 chunk 列表保持兼容。
- `external-hash-ref` 已通过通用 `EncryptedBlobStore` 接口实现；默认 CLI 使用 `<vault>.blobs` 内容寻址目录，core 裁剪构建保留引用格式和 Provider 接口并移除文件系统实现。数据库、snapshot 和同步状态携带加密引用，Blob 本体由 Provider 负责传输与保留。
- 外部 Blob 生命周期管理已包含分页 Provider 清单、现行附件与 snapshot 引用审计、缺失与损坏检测、固定时间边界、计划凭证、TIGA 授权和可重试垃圾回收。维护请求写入单条安全审计，不产生逐 Blob commit。
- schema 11 已增加 `collection_profiles`：Collection 可声明不可变的命名空间类型、版本化加密配置、允许的 ObjectTypeId 和写入所需 ExtensionCapabilityId。MDBX1 project 没有 profile 时保持旧行为；Profile mutation 原子推进 project commit、clock、head 和 ObjectVersion。
- 连接级扩展能力只存在于当前进程。缺少领域适配器时仍可读取、同步、快照、恢复和检查未知密文；Project、ObjectRecord、Relation、Label、Assignment、Attachment 和冲突解决等用户修改会返回缺失能力。同步状态升级到 v2，同时继续读取 v1。
- project/attachment 本地 mutation 与 sync incoming state 已记录 `object_versions` 行快照；非快进 sync apply 已支持 project 字段级合并、attachment 元数据字段合并和附件内容组保守合并。
- project/attachment conflict resolution 已补齐 repo 写回 API：local-wins、incoming-wins、custom row 会生成 merge commit、推进对象 head、记录 object version 后再标记 resolved；attachment incoming-wins 在缺少本地内容材料时拒绝，避免伪造内容。
- project、entry、attachment 的高风险用户可见 mutation 已包进原子事务，commit、对象行、head、object version 和 tombstone/chunk 写入会一起成功或一起回滚。
- `project_tags` 已分类为用户可见元数据：tracked tag API 会产生 project 级 commit；sync state 携带每个 project 的完整 tag 集合；临时 FTS/search index 保持解锁会话内临时状态，不进入历史。
- 未知非关键字段兼容矩阵已覆盖 MDBX1 `vault_meta`/`projects`/`entries` 附加列自动升级、schema 10 Tiga 策略表重建，以及 complete sync-state 顶层扩展的有界 decode、事务 apply、持久化、collect 和重新编码。旧 peer 缺少扩展键不会删除本地值；严格 delta/bundle 记录仍拒绝未知字段。
- UniFFI 通用原子 operation 已覆盖 project、ObjectRecord、Relation、Label 和 Assignment；Attachment 另有受命令数、单项字节数、总字节数和 chunk 大小限制的原子批量 operation。
- schema 16 已为影响格式、解密、key epoch 与 Tiga 的 `vault_meta` header 建立统一 HMAC；受保护 mutation 由 trigger 先失效旧标签并在同一事务重新封签，解锁与 health check 都会拒绝篡改。MDBX1/早期 MDBX2 升级保持原数据，首次成功解锁后从 `pending` 建立标签。
- tombstone acknowledgement 现在由统一 storage Module 验证并合并：observed commit 必须因果包含删除 commit，后代证据单调推进，祖先证据无法回退，并发证据与接收顺序无关，确认时间保留较晚值。同步、永久清理资格和 recovery 共用 CommitGraph Module；health check 会报告非因果证明。

## 3. 主要差距

### 3.1 格式与恢复

- `key_epochs.wrapped_epoch_key_ct` 已不再使用固定全零占位；初始化 marker 与真实 active epoch wrapping 已分离并有验证，随机 key rotation、retirement、跨 epoch 历史读取、同步合并、Tiga 授权与 UniFFI 已闭环。
- MDBX2 已加入 `MDBX-1` / `MDBX-1-DRAFT` 自动事务迁移、schema migration 记录、最低 reader/writer 版本和未知 critical extension 写入拒绝。
- snapshot 已覆盖 project、entry、attachment metadata、project tags 和 active `attachment_chunks`；旧 snapshot 缺少新增字段时保持现有兼容数据。
- 已冻结由真实 `MDBX1.0` tag 生成的 release golden vault，以及由同一历史 reader 固化 marker 的 `MDBX-1-DRAFT` golden vault；两者共享只读 inspection、schema 1 升级、原凭据解锁、project/entry/tag/attachment/snapshot、commit/object-version 身份保留和幂等回归。旧 `MDBX1.0` CLI 可读取兼容投影，但不得作为 MDBX2 writer；后续真实发布版本仍需按相同规则继续追加 fixture。未知字段保留已覆盖当前可验证的物理迁移，以及 complete sync-state 的 apply/collect 回环。

### 3.2 同步与冲突

- `mdbx-sync` 协商和 bundle 已有；Rust core 已支持 `mdbx-storage/state-v1` 对象状态 payload，并能在 fast-forward apply 时落地 `projects`、`entries`、`attachments`、`attachment_chunks`。
- entry payload 已接入字段级 base commit 查找、不同字段自动合并、同字段 unresolved conflict。
- entry conflict resolution 已有 storage core 写回路径：`local-wins`/`incoming-wins` 会生成 merge commit、推进 entry head、记录 object version，并标记 conflict resolved。
- 已补充“同一秘密字段并发修改必须冲突”和“不同字段并发修改自动合并”的 storage 回归测试。
- 已补充删除/修改并发回归：远端删除/本地修改会保留 tombstone 并产生 `deleted` conflict；本地删除/远端修改不会复活 entry。
- project/attachment 字段级三方合并已完成：project 不同字段并发修改会自动写 merge commit，同字段并发修改会产生字段级 conflict；attachment 元数据可字段级合并，双方同时改内容且内容不同会产生 `content_hash` conflict，只有 incoming 改内容时才替换 incoming chunks。
- `custom/manual merge` 在 Rust storage core 已有显式 merged payload/row API；Android 合并编辑器尚未接入。

### 3.3 变更历史覆盖

- 搜索类临时索引已明确不写 commit；用户可见 tag 修改已有 tracked API。仍需为未来新增维护操作逐项分类是否属于用户可见历史。
- project、entry、attachment、conflict resolution、tracked tag、Tiga mutation 和 snapshot restore 已进入原子事务。UniFFI operation API 已为 project、通用 object、relation、label、assignment 和 attachment 提供有界、原子、幂等的批量写入边界。

### 3.4 性能与增量

- benchmark harness 已形成 release 发布报告；CLI 默认使用正式 Multi password、verified keyring 与 `MDBXFE2` 字段加密，并保留 compatibility 参照模式。
- 小修改、附件改名、附件替换、snapshot、compaction 和真实 sync delta envelope 均已输出时延与操作相关字节数。
- 有界完整状态保留为首次同步和旧 peer 回退；commit/delta 双 checkpoint、bundle v4 分段与恢复链已提供大规模增量传输。
- bundle v5/v6 已在现有 bincode 2 payload 上增加可裁剪、可协商的有界 zstd；CLI 默认保持未压缩 v3/v4，显式选择才输出压缩格式。没有引入第二套 MessagePack 序列化。
- bundle v7-v10 已增加基于 vault integrity subkey 的 opt-in transport HMAC；`authenticated-bundle-v1` 与 zstd、原四项增量能力分别协商，CLI 默认与旧 peer 回退仍保持 v1-v6。
- external-hash-ref 已具备有界、可恢复、密文校验的 Provider 间 Blob 传输；默认文件系统 Provider 使用带 owner/expiry 的持久 lease，另一实例的清理会观察活动 lease。
- CollectionProfile 已提供实例级领域契约；邮件、收藏夹和 Steam 的实际 Adapter、派生索引及 payload migration 仍需分别实现。
- `mdbx-build-capabilities-v1` 已把强制 storage 不变量、可裁剪 storage 模块和已编译 sync 能力变成稳定运行时清单；UniFFI 与 `mdbx capabilities --json` 可在打开 vault 前查询。该清单不替代 Adapter 注册、critical extension 校验或 sync 协商。

### 3.5 安全

- Keyring、Argon2id/HKDF 输出、vault/epoch 解包结果的自动内存清零基线已完成；明文驻留最小化、密钥文件、真实硬件密钥协议、生物识别封装仍需扩展。
- vault header 元数据 HMAC 基线已完成；外部 rollback anchor 已通过 storage、CLI 和
  UniFFI 提供，用于检测 append-only inventory 回退；显式 O(vault-size) vault content
  manifest v2 已覆盖主 schema、未知扩展表、generated/hidden columns 和 typed rows 的
  精确检查点，同时继续验证 v1 token；ADR-0022 的 opt-in 增量 sparse Merkle root 已实现，
  通过 sync-delta seam 原子更新，并已覆盖 unlock、health、CLI、UniFFI 与 peer checkpoint。
- 加密上下文 AAD 已覆盖字段级；commit、bundle、snapshot、vault header、增量 root 和对端
  root checkpoint 的认证边界已经进入兼容性规范。外部 Provider 原始字节和未注册扩展表
  仍由各自 Provider 校验与显式 content manifest 覆盖，不能扩大增量 root 的声明范围。

### 3.6 Android 接入

- Android 侧当前不应把 MDBX 当作普通 Room 表的附属字段；最终应直接调用 MDBX 操作层。
- Android MDBX 管理页已有冲突队列和 local/incoming 解决入口；entry/project/attachment 冲突解决在 storage core 已收紧为写回 MDBX 历史，不再只改 `conflicts.resolution`。
- 新建、删除、移动、复制、分类、passkey、Bitwarden/KeePass 兼容路径都要映射到 MDBX project/entry/attachment/history。
- Android 管理页需要显示同步状态、device heads、unresolved conflicts、Tiga 状态、snapshot/health check。

## 4. 分阶段路线图

### P0：格式可信与恢复闭环

目标：让 `.mdbx` 文件能自校验、能发现历史篡改、能对损坏给出明确报告。

任务：

- recovery 验证 commit integrity tag。（已完成）
- health check 输出 commit tag mismatch、missing parent、dangling head、chunk mismatch。（已完成）
- snapshot 已使用认证密文并覆盖 attachment chunk 恢复；新解锁快照通过
  `snapshot-record-auth-v1` / `MDBXSN2` 额外绑定 base commit、创建时间和设备，旧 SHA
  snapshot 继续按原 AAD 恢复。（已完成）
- 整理“生产初始化不得保留固定占位密文”的测试边界。（已完成；固定 `X'00'` 已移除，初始化 marker、active epoch wrapping 与完整随机 rotation 已分离）
- vault header 元数据认证、mutation 原子重签与 health/unlock 验证。（已完成）

验收：

- `cargo fmt`
- `$env:CARGO_INCREMENTAL='0'; cargo test`
- 篡改 commit 字段会被 health check 报错。

### P1：所有 mutation 进入 commit 历史

目标：任何用户可见的新增、删除、移动、复制、Tiga 切换都能被同步和回放。

任务：

- Tiga setter 改为接收 `CommitContext` 或新增 tracked API。（已完成）
- repo mutation 使用事务包裹对象写入、commit 写入、head 更新。
- 分类/标签/搜索索引变更定义是否进入历史；用户可见语义必须进入历史。
- 移动 entry 到 project、复制 entry、恢复 tombstone 等操作形成一等 API。（entry 移动/复制已完成）

验收：

- 所有 repo 用户可见 mutation 都产生 commit。
- 崩溃注入不会留下“有 commit 无对象”或“有对象无 commit”的健康状态。

### P2：同步 apply 与冲突闭环

目标：多设备离线编辑后，通过文件/网盘同步可以安全合并。

任务：

- 实现 serialized commit 导出/导入。（CLI bundle 与 storage core apply 基础路径已完成）
- 构建 base commit 查找和 fast-forward 判断。（对象级 fast-forward 已完成；entry/project/attachment 字段级 base 已完成）
- 将 incoming commit apply 到 storage，必要时调用 conflict detector。（project/entry/attachment/chunk 状态落地已完成；非快进 state payload 已进入字段级 apply 流程）
- 同字段并发秘密修改生成 conflict；不同字段安全合并。（entry payload、project 字段、attachment 元数据/内容组已完成）
- conflict resolve 写回 commit，并更新 head。（entry `local-wins`/`incoming-wins`/`custom payload` 已完成）

验收：

- A/B 设备同字段并发修改产生 unresolved conflict。（storage 回归已覆盖）
- A/B 设备不同字段并发修改自动合并。（storage 回归已覆盖）
- entry conflict 选择 local-wins/incoming-wins 后会写入 merge commit 并更新 head。（storage 回归已覆盖）
- entry conflict custom payload 会写入 merge commit、替换 payload、记录 object version，并标记为 custom。（storage 回归已覆盖）
- project 不同字段并发修改会写双 parent merge commit，同字段并发修改会保留本地状态并记录具体字段 conflict。（storage 回归已覆盖）
- attachment metadata 与 incoming-only 内容更新可自动合并，双方同时替换内容会保留本地内容并记录 `content_hash` conflict。（storage 回归已覆盖）
- 删除与修改并发不会误复活 tombstone。（storage 回归已覆盖）
- tombstone acknowledgement 会拒绝删除前证明、保留较强后代证明，并让两个有效并发证明按规范规则合并。（storage 与 health 回归已覆盖）

### P3：附件与性能完成

目标：明显优于 KDBX 的小修改同步和大附件行为。

任务：

- external-hash-ref 模式已完成。
- 附件 Blob 内容寻址目录和可插拔加密 Blob Provider 已完成。
- 显式引用扫描和加密孤儿回收已完成。
- Provider 间 Blob 传输和跨进程 lease 协议已完成。
- metadata-only 更新不触碰 chunk。（已完成）
- benchmark 输出 delta size 和时延报告。（已完成；encrypted 为默认发布模式）
- 可选 zstd 压缩已完成；现有 bincode 2 继续作为唯一二进制 payload 序列化，避免重复协议栈。

验收：

- 小 entry 修改保存目标 `<100 ms`。
- 附件改名不改 chunk。
- 大附件不导致普通 entry 修改产生大 delta。

### P4：迁移与长期兼容

目标：KDBX 用户可迁移，MDBX 格式可长期维护。

任务：

- KDBX import/export 覆盖 passkey、totp、ssh key、custom fields、attachments，并扩展到真实二进制 `.kdbx` 解析/写入。
- RFC 结构补齐：header、schema、crypto、commit、sync、snapshot、extensions。
- 兼容性测试矩阵已覆盖 MDBX1/DRAFT 自动升级、`MDBX1.0` release 与 DRAFT 真实磁盘 golden vault、旧 reader 对兼容投影的读取观察、未知 critical extension 拒绝、未知附加列保留、schema 重建字段保留，以及 complete sync-state 顶层未知字段的事务持久化与重编码；未来发布版本仍需按同样方式增加 fixture。
- MDBX-1 / MDBX-1-DRAFT 到 MDBX2 的顺序自动迁移已落地；后续代际必须继续通过 migration registry 逐代升级。
- CLI 已增加 `health`、`benchmark`、`snapshot`、`sync bundle/apply`、`import-kdbx-json`、`export-kdbx-json`；后续如要使用 `import-kdbx`/`export-kdbx` 名称，必须先实现真实二进制 `.kdbx` 支持。

验收：

- import/export roundtrip 报告。
- 旧 vault 打开测试。
- 未知非关键扩展保留测试。

### P5：Monica Android 接入

目标：Android 对 MDBX 的所有用户操作直接落到 MDBX 文件和 MDBX 历史。

任务：

- Android 定义 `MdbxRepository` 边界：所有新建、删除、移动、复制、分类、passkey 操作只通过它进入 MDBX。
- 新建页面、移动/复制页面、新建分类菜单支持选择 MDBX vault/project。
- 密码、TOTP、passkey、SSH、API token 映射到 MDBX entry type。
- Bitwarden/KeePass 兼容字段进入 payload 映射表。
- MDBX 管理页增加 health check、snapshot、device heads、sync status、conflict list、resolve action。
- 离线操作立即本地生效；同步后依据 commit DAG 合并。

验收：

- A 设备删除 entry，B 设备同步后消失；离线时 B 保留本地状态，联网/同步后按 commit 合并。
- 同一字段并发修改在 Android 管理页出现冲突处理入口。
- Android 选择 entry conflict 的“使用本地/使用传入”会写 merge commit、推进 entry head 并刷新列表；“标记解决”不得静默吞掉 entry 冲突。
- passkey 可存储并导入/导出到 Bitwarden/KeePass 兼容结构。

## 5. 工作规则

- 每个阶段先写测试或 health check，再改实现。
- 不允许引入“为了 UI 方便绕过 MDBX repo”的写入路径。
- 不允许把认证失败降级成 warning。
- 不允许把 Android Room 当 MDBX 的真源；Room 最多是索引/cache。
- 每个合并切片必须说明验收命令。

## 6. 当前起步切片

当前 P2 字段级合并切片已完成：

- `object_versions` 存储 entry commit 快照。
- `object_versions` 同步记录 project/attachment 本地 mutation 与 incoming state 行快照。
- 非快进 sync apply 会消费 state payload，并对 entry payload、project 字段、attachment 元数据/内容组做三方合并。
- 不同字段并发修改自动合并并产生双 parent merge commit。
- 同字段并发修改生成 unresolved conflict。
- entry conflict 支持 `local-wins`/`incoming-wins` 写回 merge commit 并推进 head。
- Rust core 支持 `custom` merged payload 写回，用于后续 Android 手动合并编辑器。
- 删除/修改并发会生成 `deleted` conflict：远端 tombstone 不丢，本地删除不会被远端修改复活。
- snapshot 已恢复 attachment chunks；初始化 key epoch marker 与 active epoch wrapping 边界已收紧。
- CollectionProfile 已进入 schema migration、Project ObjectVersion、sync state v2、snapshot、health 和 UniFFI；旧 sync state v1 缺少 profile 时保留接收端现有描述。
- MDBX1 核心表未知附加列及其值已通过真实 `upgrade_to_latest` 路径验证；schema 10 重建 Tiga 策略表时会复制受限附加列，无法安全重建时在替换旧表前失败。
- complete sync-state 会有界保留未知非关键顶层字段并阻止覆盖已知键；字段数、编码字节、键长度和嵌套深度均有硬限制。schema 15 已覆盖 apply、同键更新、旧 peer omission、collect 与重新编码。
- Steam mafile 已拆成两层可裁剪 Adapter：`mdbx-adapter-steam` 负责有界解析、未知字段保留与稳定 identity；`mdbx-adapter-steam-storage` 负责稳定 UUID、批量排序、create/update/restore 规划、能力门禁和单 operation commit。两层都默认 feature 为空，storage/sync/CLI/FFI 不反向依赖。
- Steam bridge 默认每批 128 份、源字节聚合 8 MiB，硬上限 2,048 份和 64 MiB；只通过 ObjectSummaryRepo 判断已有状态，不读取 payload，不把输入缺失解释为删除。完全相同的 prepared plan 可幂等重试，重建 plan 属于新规划动作。
- Native OperationCoordinator 已在执行前确定 commit kind：普通写为 `change`、单独恢复为 `restore`、单独移动为 `move`，混合 repository kind 为 `multi`。restore-then-update 与 move 均有回归测试，避免一次用户动作被迫拆成多条 commit。
- commit kind 已统一为核心严格契约：`change`、`merge`、`snapshot`、`key-rotation`、`move`、`copy`、`restore`、`multi` 在 storage history、CLI、sync bundle 与 FFI 中精确往返；未知值 fail closed，不再静默降级为 `change`。旧四种 bincode 枚举序号保持不变。
- change scope 已统一为核心严格契约并补齐真实 `snapshot` 与 `branch` 范围；CLI 不再把未知值降级为 `multi`。CommitContext 在本地 operation 写事务前验证 kind/scope，活动 operation 聚合 repository commit 时也先验证，旧九种 bincode 枚举序号保持不变。
- CommitOperation 初始请求身份已与最终聚合摘要分离：新行在现有 `request_hash` BLOB 中保存 `MDBXORI1` 加 32 字节摘要，聚合重写保持该 40 字节身份不变；无显式 intent 的变化请求也会拒绝。旧 32 字节行继续读取和同步，旧聚合 operation 保留历史重试语义，未知编码 fail closed。
- 已存在 commit 的同步重放会在迟到 payload 应用前精确比较设备、序列、kind/scope、加密变更 ID、vector clock、message、时间、完整性标签和规范 parent 集合。operation ID 与 commit ID 保持一对一完整 metadata 映射；旧 bundle 仍可省略 operation，后续可以补入原始认证记录。
- 首次同步导入 commit 在完整性验证后、首条 SQL 前执行结构预检：拒绝重复 parent、畸形 vector clock 和超出 SQLite INTEGER 范围的 `local_seq`，并确认失败不留下 commit graph、sequence 或 branch 状态。legacy `{}` clock 继续兼容。
- device head 已统一为设备全局序列规则：commit 与 state-delta 导入都会验证 commit 创作设备，按 `local_seq` 跨 branch 单调推进，迟到低序列不能回退 head，`last_seen_at` 与 revocation 单调合并。序列复用在 INSERT 前返回 validation error；health check 会报告设备归属错误与序列落后的 legacy head。
- tombstone acknowledgement 已统一为因果证明规则：全部运行时写入使用 TombstoneAcknowledgement Module，删除前 commit 会被拒绝，祖先无法覆盖后代，后代推进时确认时间不会降低，并发有效证明按 `(acknowledged_at, observed_commit_id)` 规范选择。缺失引用的 complete-state 兼容行为保持不变，schema 8 migration backfill 保持原样。
- 已通过 `cargo test -p mdbx-storage repo::snapshot`、`cargo test -p mdbx-storage sync_apply`、`cargo test -p mdbx-storage init`、`cargo test -p mdbx-storage unlock`、`cargo test -p mdbx-storage recovery`。

下一刀建议：

- 每次发布新代际时继续固化对应 golden vault，并使用上一代 reader 二进制验证只读兼容投影；旧 writer 始终受 `min_writer_version` 边界约束。
- 为真实二进制 `.kdbx` 导入/导出选择经过审计的独立 Adapter；在此之前继续把现有能力明确标为 KDBX JSON 互操作，不得扩大宣称。
- 如需 CLI、UniFFI 或 Android 直接导入 mafile，新增入口必须复用现有 Steam storage bridge 的 limits、脱敏、Profile/capability 分离与 prepared-plan retry 契约，不得在客户端复制解析和数据库映射逻辑。
- 生产客户端继续补齐真实硬件密钥、受保护会话与默认脱敏输出；这些平台能力不能由 storage build manifest 伪造。
