# MDBX 客户端接入指南

本文面向准备在其他客户端接入 Monica MDBX 的实现者。

目标不是重复完整 schema 规范，而是回答三个问题：

- 一个客户端怎样才算“正确接入 MDBX”。
- 用户界面必须提供哪些管理能力。
- 哪些实现捷径会破坏同步、历史、快照或跨客户端一致性。

更底层的格式规范请同时阅读：

- `docs/01-product-spec.zh-CN.md`
- `docs/02-storage-sync-spec.zh-CN.md`
- `docs/03-security-spec.zh-CN.md`
- `docs/06-sqlite-schema-v1.zh-CN.md`

## 1. 接入边界

MDBX 不是“把密码表塞进一个 SQLite 文件”。

一个合格客户端必须把 MDBX 当成完整 vault 格式处理，包括：

- vault 元信息
- unlock / key epoch
- Tiga 安全模式
- 项目、文件夹、条目、附件
- tombstone 删除链路
- commit DAG
- object version
- snapshot
- conflict
- sync state
- 诊断与维护入口

客户端可以只做“只读浏览器”，但只要支持写入，就必须维护历史、删除标记、快照和冲突元数据。

## 2. 推荐接入层级

### 2.1 L0：只读查看器

只读查看器 MAY 只实现：

- 打开 `.mdbx` 文件
- 解锁 vault
- 读取项目 / 文件夹 / 条目
- 读取附件元数据
- 显示当前 head 状态

只读查看器 MUST NOT：

- 修改 SQLite 表
- 清理 tombstone
- 生成 commit
- 伪造快照
- 自动修复冲突

只读查看器 SHOULD 显示“只读模式”，避免用户误以为修改会保存。

### 2.2 L1：基础读写客户端

基础读写客户端 MUST 实现：

- 创建 vault
- 打开 / 解锁 vault
- 新增、修改、删除条目
- 新增、修改、删除文件夹或项目容器
- 写入 tombstone
- 为每次用户级变更生成 commit
- 更新 object version
- 更新 device head / branch head
- 维护基本快照
- 刷新本地显示缓存

基础读写客户端 MUST NOT 逐条对象创建不必要的 commit。

例如用户一次批量移动 100 条密码到 MDBX，应该是一个用户级操作。实现 SHOULD 生成一个 batch commit，并在 commit 的 changed object list 中记录全部对象，而不是生成 100 个独立 commit 和 100 个自动快照。

### 2.3 L2：同步客户端

同步客户端 MUST 额外实现：

- sync state 读取和写入
- commit DAG 合并
- parent commit 校验
- 并发修改检测
- conflict 记录
- 三方合并或字段级合并
- tombstone 防复活
- 附件 chunk / external hash ref 校验
- 上传待处理写入
- 下载后重放或应用远端状态

同步客户端 MUST NOT 只按更新时间覆盖整库。

### 2.4 L3：完整 Monica 兼容客户端

完整客户端 SHOULD 实现：

- Monica 本地分类 / 快捷文件夹语义映射
- 嵌套文件夹创建、移动、复制
- 快照结构预览
- 当前版本与快照版本结构对比
- 提交历史详情
- 字段级变更展示
- 冲突合并页面
- 数据库诊断 / 维护页面
- WebDAV / OneDrive / 本地外部文件兼容
- 后台预加载当前选中 vault，但不能一次性预加载所有 vault

## 3. 推荐代码入口

当前 Rust workspace 按职责拆分：

- `crates/mdbx-core`
  - 核心领域类型。
- `crates/mdbx-crypto`
  - 加密、KDF、密钥材料处理。
- `crates/mdbx-sync`
  - 同步 payload / object payload 模型。
- `crates/mdbx-storage`
  - SQLite schema、vault 初始化、repo、搜索、快照、冲突、恢复。
- `crates/mdbx-ffi`
  - 面向非 Rust 客户端的通用 UniFFI facade；需要跨语言能力时应优先扩展这一边界，而不是回退到客户端侧 SQL。

客户端 SHOULD 优先通过 storage / repo API 写入，而不是直接拼 SQL。

使用 `mdbx-ffi` 时，应把它视为 Vault、Collection Profile 和 ObjectRecord 操作的客户端边界。领域 Adapter 在修改 profiled Collection 前注册当前进程实际提供的 ExtensionCapabilityId；缺少 Adapter 时继续保留未知密文，不得伪造能力绕过写入限制。如果客户端需要通过 FFI 使用 tag、attachment、sync、conflict、snapshot 或 diagnostics，应该新增明确的 facade 方法和测试，而不是让客户端直接写对应 SQLite 表。

### 3.1 有界 Collection 发现

vault 重开后，顶层导航应使用 `get_collection_summary`、`list_collection_summaries` 和 `list_deleted_collection_summaries`。这些方法无需记住 Collection ID，也不会读取完整 Project，就能发现密码、书签、邮箱、Steam 以及未来 Adapter 的 Collection。每页最多 200 个无 payload 摘要，不透明游标最多 4096 bytes；游标绑定 active/deleted 查询，发生本地或同步 metadata 变化后必须丢弃并从第一页刷新。

`MdbxCollectionSummary` 包含 Collection ID、标题、可选 CollectionProfile 类型/版本、group/icon 引用、收藏/归档状态、附件计数、head commit、删除状态和更新时间，不包含 `summary_ct` 或 CollectionProfile payload。调用 `default_presentation_metadata_limits` 获取固定展示契约：标题明文 64 KiB、label name 明文 512 bytes、group/icon 引用 4096 UTF-8 bytes。新界面应继续分页 Object 和 Label 摘要，不应把完整 list 方法作为默认导航路径。

Object 导航应使用 `list_object_summaries(collection_id, object_type_id,
page_size, cursor)` 获取 active 页。deleted Object 提供两个 additive 路径：
`list_deleted_object_summaries` 按 Collection 查询，
`list_all_deleted_object_summaries` 查询全局 tombstone。两者返回相同的无
payload `MdbxObjectSummary`，页大小为 1 到 200。游标是不透明的，并绑定
active/deleted 状态、Collection 范围和可选 ObjectTypeId；不能把 active 游标传给
deleted 方法，也不能用 offset 自行重建。CLI `entry deleted` 也使用全局有界路径。
完整 `list_objects`/`list_deleted_entries` 仍作为显式 payload 流程保留，满足 MDBX1
兼容，不会删除或重新解释。

附件导航应调用 `list_attachment_summaries(collection_id, object_id, page_size, cursor)`：`object_id = None` 表示整个 Collection，传入 Object ID 表示一个 Object；tombstone 使用 `list_deleted_attachment_summaries`，单个元数据使用 `get_attachment_summary`。`default_attachment_presentation_limits` 返回文件名 4096 bytes、media type 512 bytes、共享 128 KiB 密文信封预留、每页 200 条和游标 4096 bytes 的契约。这些页面不会读取 attachment chunk 或 external blob payload，因此即使内容损坏，列表页仍可打开。游标是 live position，发生 metadata 变化后必须丢弃并从第一页重新开始。

没有 Profile 的 MDBX1 Collection 仍然有效，类型/版本字段为空。旧标题、label name 或引用超出固定展示限制时，只会在有界摘要路径返回 resource-limit error；完整兼容 API 仍可用于显式 repair/export，不能删除或重新解释其行为。

### 3.2 有界冲突队列导航

冲突管理页应调用 `list_unresolved_conflict_summaries`，可选传入核心对象类型
（`project`、`entry`、`attachment`、`object-relation`、`object-label` 或
`object-label-assignment`），页大小使用 1 到 200，并且只把同一查询返回的游标传回去。
启动时调用 `default_conflict_summary_limits` 发现 200 条/页、游标 4096 bytes、
字段 JSON 64 KiB、256 条路径和单路径 4096 bytes 的固定契约。

`MdbxConflictSummary` 只用于队列导航与选择，包含稳定对象/commit 身份、有界冲突字段、
解决状态和创建时间；它不授予明文披露权限，也不能替代 typed resolution 方法。游标是按
`created_at DESC, conflict_id DESC` 排序的 live keyset position；同步、冲突解决或其他 metadata
变更后必须丢弃，不能拿到另一个类型过滤器复用。旧行字段 JSON 损坏或超限时，有界页面可以
失败关闭；完整 `list_unresolved_conflicts` 仍可用于显式 repair/export。

### 3.3 有界 Snapshot 导航

快照管理页应使用 `get_snapshot_summary(snapshot_id)` 获取只含元数据的详情，使用
`list_snapshot_summaries(page_size, cursor)` 获取列表。启动时调用
`default_snapshot_summary_limits` 发现固定契约：每页 1 到 200 条、游标最多 4096
bytes、每个 UTF-8 元数据文本字段最多 4096 bytes。返回的游标只能传回同一个 list 查询；
它是按 `created_at DESC, snapshot_id DESC` 排序的 live keyset position，不是快照 token
或授权凭据。创建、清理、同步或其他 metadata 变化后必须丢弃游标并从第一页重新开始。

`MdbxSnapshotSummary` 包含 snapshot ID、base commit ID、摘要 hash、创建时间/设备以及
`snapshot_ciphertext_bytes`。这个字节数只是存储大小投影，不证明加密 payload 有效、可解密
或已通过完整性校验。摘要路径不会选择、解密、反序列化或验证 `snapshot_ct`，因此损坏或很大
的 payload 不会阻塞导航页。用户选中快照后，如果需要结构预览、完整性校验、导出或恢复，
再显式调用既有完整 snapshot API，并单独处理其认证结果。

CLI 的 `snapshot list` 已使用这条有界路径。既有 `SnapshotRepo` 完整读取、创建、校验和恢复
方法继续供 MDBX1 客户端及显式 recovery/repair 流程使用；摘要路径的 resource-limit error
不会删除、迁移或重新解释底层 snapshot 行。

当前导出 API、JSON payload 契约、UniFFI binding 生成命令、iOS 打包注意事项和扩展 facade 的规则见 `crates/mdbx-ffi/README.zh-CN.md`。

Monica for Android 的当前 MDBX 1.0 接入样板见 `docs/android/README.zh-CN.md`。它记录 Android 端如何在 `MdbxRepository` / `MdbxVaultStore` 边界内处理 Room 索引、working copy、WebDAV、OneDrive、旧测试版 vault 和后续 FFI 迁移。

新建 Android 客户端通过 `mdbx-ffi` 接入 MDBX2 的实施文档见 `docs/android/MDBX2_ANDROID_INTEGRATION.zh-CN.md`。它记录构建与 Kotlin binding 生成、迁移编排、有界读取、批量写入、Tiga 授权，以及当前尚未通过 FFI 暴露的能力清单。

除非正在实现底层库，否则客户端代码 SHOULD NOT 直接写这些表：

- `commits`
- `commit_parents`
- `object_versions`
- `tombstones`
- `tombstone_acknowledgements`
- `snapshots`
- `key_epochs`
- `conflicts`
- `device_heads`
- `branches`
- `project_tags`
- `collection_profiles`

直接写这些表很容易制造“看起来保存成功，但其他客户端数量不一致、删除链路错误、历史爆炸、快照不可回滚”的问题。

Android 接入时尤其要避免把 MDBX 当成普通 Room 表集合。entry/project/attachment 的创建、编辑、删除、移动、复制应走 repo/storage API；用户可见 tag 修改应走 tracked tag API；conflict 解决应走 entry/project/attachment 专用 resolution API。只更新 `conflicts.resolution` 或直接改 `project_tags` 都不算完成写入，因为它会跳过 commit、object version、device head、branch head 或 sync state。

当前 storage core 的安全边界不要求默认强制硬件密钥，也不增加额外解锁步骤。Sky 是灵活便携但仍然安全的 Tiga 模式，适合网盘同步和多设备恢复优先场景；硬件密钥可以作为 Multi/Power 的增强能力，而不是 Sky 便携性的反面。

## 4. 写入规则

### 4.1 用户级操作对应 commit

commit 粒度应该按“用户意图”划分，而不是按“内部对象数量”划分。

MUST 合并成单个 commit 的典型操作：

- 批量删除
- 批量移动
- 批量复制
- 批量导入
- 从 KDBX 导入一个文件夹
- 从 Monica 本地迁移一组条目
- 文件夹及其子项一起移动

MAY 拆成多个 commit 的操作：

- 用户明确分多次保存
- 长事务被用户中断后继续
- 客户端为了内存限制分批提交，并且 UI 明确显示为多批操作

MDBX2 写入客户端 SHOULD 在用户动作开始时生成稳定的 `operation_id`，并通过
`CommitOperation` / `CommitContext::create_operation_commit` 提交。网络超时或进程恢复后
必须复用同一个 `operation_id`；storage core 会幂等返回原 commit。不得对内容不同的请求
复用同一个 ID。

完整初始请求应作为不可变重试状态保存。在调用结果确定前，客户端需要保留原命令列表、
message、parents、分支选择条件和可选 `intent_hash`；根据当前 UI 状态重新构造的请求可能已经
变化，此时会被拒绝。客户端无需计算或持久化数据库中的 `request_hash`。当前 storage core
会在 mutation 前生成版本化身份，并在每次重试时比较，即使没有显式 `intent_hash` 也适用。

`CommitOperation` 还应明确提供 `operation_kind`、目标 `branch_name`、对象类型、动作和字段
摘要。storage core 负责原子分配设备 `local_seq`、合并 parent 向量时钟、写入旧 `commits`
兼容投影，并同步更新 device head 和指定 branch head。客户端不得自行计算 `MAX(local_seq)+1`。

对于编辑器自动保存、批量移动、批量导入等场景，客户端 SHOULD 使用
`CommitContext::run_operation` 包住一次完整用户动作。闭包中的多次 `ProjectRepo`、`EntryRepo`
或 `AttachmentRepo` 写入会共享一条 commit；闭包失败会整组回滚，重试已完成的 operation
只返回原 commit，不再次执行写入。事务边界应覆盖一个有限的用户动作，不应跨越整个编辑器
页面生命周期。用户明确点击两次“保存”时，应生成两个 operation，而不是无限追加到同一个事务。

UniFFI 客户端应通过 `execute_write_operation` 或对应的指定分支方法提交有界多对象变更。
兼容方法默认最多接受 256 条命令、单条 JSON payload 1 MiB、全部 JSON payload 8 MiB，
序列化 operation intent 16 MiB。受控客户端可以调用新增的 `*_with_limits` 方法，但显式
limits 仍不能超过编译期硬上限。资源校验和流式 intent 哈希会在获取 vault 写锁及启动
SQLite 事务前完成。更大的导入必须拆分为多个新 operation ID；重试某一批时复用该批原
operation ID 和完整命令列表。operation 命令同时接受 MDBX1 类型名和
`com.monica.mail.message` 等 namespaced ObjectTypeId。

#### Adapter payload schema 迁移

MDBX 文件格式迁移与领域 payload 迁移采用不同接口。MDBX1、SQLite schema 和字段密文格式升级始终调用 storage core 迁移器，客户端不得自行转换。ObjectTypeId 的领域 payload 由对应 Adapter 解释。

客户端先注册 CollectionProfile 所需的 ExtensionCapabilityId，并保持已认证的活动 vault 会话，再调用 `PayloadMigrationRepo::create_plan` 或 UniFFI 的 `create_payload_migration_plan`。这两个兼容入口使用保守的 Standard 设备上下文；能够报告真实设备保证的客户端应调用 `create_payload_migration_plan_with_device_context` 和 `execute_payload_migration_with_device_context`。创建计划会在载入或解密源 payload 前执行 `MigratePayload` Tiga 管理授权，因此 Multi/Power 的新鲜认证、因素和设备要求都会生效。

计划包含有界解密 payload，只能留在受保护的进程内存中，不得写入日志、缓存、文件或同步元数据。计划披露审计通过 `plan_id` 关联且不引用 commit。Adapter 必须为每个计划对象生成一次输出，再调用 `PayloadMigrationRepo::execute` 或对应 UniFFI 方法。执行会重新授权、复核全部绑定，并在同一事务中更新整批对象、生成一条幂等 commit 和一条 commit 关联审计。任何拒绝、对象/Profile/分支并发变化、能力缺失、畸形输出或资源超限都会保持整批原状。完全相同的已完成计划重试只返回原 commit，不生成第二条成功审计；`remaining_count` 大于零时，以新计划继续下一批。

#### Steam mafile Adapter

Steam mafile 支持是可选能力。集成 `mdbx-adapter-steam` 的客户端应把
`extension_profile()` 注册到进程内 ExtensionProfile registry，并在确实可以执行
用户可见写入时单独激活 `com.monica.steam.store`。Profile 注册不是权限，也不能
替代 Tiga 授权。

需要存储映射的客户端还应集成可独立裁剪的 `mdbx-adapter-steam-storage`。其中的注册 helper
只注册进程内描述符；客户端仍需单独激活 capability，并通过普通 tracked storage API 创建或
更新持久化 CollectionProfile。

客户端 MUST 把每个 mafile 当作不可信 JSON。存储导入应调用
`SteamMaFileImportPlan::prepare`，不得在 UI 中重新实现解析器、UUID 映射或
create/update 判断树。桥接层把单文档解析交给纯 Adapter；默认契约为输入 1 MiB、深度 32、
聚合字段 512、每个数组 512 项、聚合节点 8,192、单个字符串/键 64 KiB、字符串/键聚合
1 MiB。Adapter 拒绝重复键，错误只返回不泄漏值的静态类别，并在规范 JSON 中保留未知字段。
低端设备可以降低上限，但不能提高硬上限。

规范 JSON 字节作为 `com.monica.steam.mafile` ObjectTypeId 的不透明 payload 写入。对象
ID 由桥接层把 Adapter 的命名空间隔离 identity 投影为确定性的 RFC variant、version-8
UUID；不得使用账号名、标题、路径、secret 或列表位置派生。桥接层按 UUID 排序并拒绝重复
identity，只通过不含 payload 的 summary 判断 create、update 或 restore-then-update。已有
对象必须属于目标 Collection，并保持精确 ObjectTypeId 与 payload schema version；本批输入
缺失某对象不代表删除。

导入默认最多 128 份文档、源字节聚合 8 MiB；硬上限为 2,048 份和 64 MiB，单文档与通用
write limits 仍分别生效。request 和 prepared plan 都含敏感明文，必须留在受保护进程内存中，
不得记录源字节或规范字节。一次成功批次只产生一条 commit，包括 restore-then-update。执行
结果不确定时必须重试同一份 prepared plan；重新读取文件或在 vault 状态变化后重建 plan 是
新的规划动作，不能伪装成旧计划的幂等重试。

纯 Adapter 不执行 Steam 网络请求、Android 集成、token 刷新或 storage 写入；storage bridge
只准备并执行通用 MDBX 写入。裁剪任意一层后，已有 Steam 对象仍可作为不透明记录进行同步、
备份、恢复和诊断；客户端不得删除或重新解释这些对象。

### 4.2 删除必须走 tombstone

删除对象时 MUST：

- 标记对象 deleted 或移除当前可见索引
- 写入 tombstone
- 写入 commit
- 写入 object version
- 更新 device head

同步客户端 MUST 使用 tombstone 防止旧客户端或远端旧状态把已删除对象复活。

客户端 MUST NOT 只从当前列表里删掉行。

tombstone acknowledgement 属于 storage core 管理的同步证据。客户端不得插入、覆盖或预先
合并 acknowledgement 行。原始认证 commit 与 state payload 必须交给 storage apply；storage
会记录删除设备和接收设备的证据，验证 observed commit 因果包含删除，并保留最强证明。

### 4.3 文件夹和路径

客户端 MUST 保留文件夹稳定 ID，而不是只依赖标题或路径字符串。

嵌套文件夹 MUST 保留 parent 关系。进入 `a/b/c` 时，面包屑或路径显示必须能恢复完整链路，而不是只显示 `a/c`。

文件夹列表展示 SHOULD：

- 文件夹排在普通项目前面。
- 同级项目保持稳定排序。
- 嵌套层级使用缩进或线条指示。
- 折叠 / 展开状态只影响 UI，不应改变存储结构。

移动、复制、新建条目时 MUST 能选择 MDBX 文件夹目标，不应只能选择数据库根目录。

### 4.4 附件

附件是 MDBX 一等对象。

客户端 MUST：

- 保留附件 ID
- 保留 attachment 与 project / entry 的归属
- 校验 content hash
- 支持 chunk 元数据
- 区分嵌入、chunk、external hash ref

客户端 MUST NOT 在修改条目标题或密码时重写无关附件内容。

新的附件界面应使用上述有界摘要页。完整的 `get_attachment`、`list_attachments` 和 `list_deleted_attachments` 继续用于 MDBX1 兼容、显式 repair/export、内容读取和完整性校验。摘要路径返回 resource-limit error 只表示旧展示字段超出新 UI 契约，不表示附件内容已删除或迁移。

### 4.5 快照

快照用于恢复和结构对比，不是普通日志。

客户端 SHOULD：

- 支持手动快照
- 支持自动快照
- 支持清理自动快照
- 支持回滚快照，并要求二次确认
- 显示快照结构预览

批量操作 SHOULD 避免生成大量自动快照。

## 5. 必备用户管理面板

其他客户端只要允许用户管理 MDBX，就 SHOULD 提供以下面板。

### 5.1 MDBX 格式管理首页

用途：按存储位置管理 vault。

必须显示：

- 本地 MDBX
- WebDAV MDBX
- OneDrive / 云端 MDBX，如客户端支持
- 每类 vault 数量
- 创建 vault
- 打开已有 vault

进入“MDBX 格式管理”时 SHOULD 先进入管理首页，而不是自动跳进上次打开的某个数据库详情页。

可以记住用户当前使用的 vault 用于密码页预加载，但管理入口本身应保持中立。

### 5.2 数据库详情页

用途：对单个 vault 做常规管理。

必须显示：

- vault 名称
- 存储路径
- 存储类型
- Tiga 模式
- 是否默认
- 同步状态
- 健康状态
- 提交数量
- 快照数量
- tombstone 数量
- 附件数量与大小

必须提供：

- 同步
- 冲突管理
- 快照
- 提交历史
- 诊断 / 维护
- 删除 vault

普通用户界面 SHOULD NOT 暴露开发者高级工具，例如 raw bundle 导入导出、benchmark、底层 chunk 调试等。它们可以保留为开发者模式或内部工具。

### 5.3 文件夹 / 结构管理页

用途：管理 vault 内部组织结构。

必须支持：

- 根目录
- 嵌套文件夹
- 创建子文件夹
- 重命名文件夹
- 移动文件夹
- 删除文件夹
- 展开 / 折叠
- 面包屑路径
- 快捷状态栏

当用户在某个 MDBX 子文件夹里新建密码时，新建页面 SHOULD 默认选中该 MDBX 数据库和当前文件夹。

### 5.4 移动 / 复制目标选择页

用途：把条目移动或复制到其他分类或 vault。

推荐交互：

1. 先选择存储类别或数据库。
2. 再选择目标文件夹。
3. 最后确认操作。

必须支持 MDBX 文件夹目标。

选择目标后 SHOULD 收起多选菜单，并用快捷状态栏或后台任务状态显示进度。不要让用户以为操作还没开始。

### 5.5 冲突管理页

用途：处理并发编辑。

必须显示：

- 冲突对象标题
- 对象类型
- 本地版本
- 远端 / incoming 版本
- 冲突字段
- 创建时间
- 相关 commit

必须支持：

- 保留本地
- 使用远端
- 字段级合并，如客户端支持
- 合并后写入新 commit

冲突展示 SHOULD 使用字段化 diff，而不是把 JSON 或 SQL 当代码块丢给用户。

队列较大时，先用 `list_unresolved_conflict_summaries` 填充分页列表，用户选中一条后再读取
完整 typed state。解决必须调用现有 entry/project/attachment/relation/label/assignment 专用
方法；不能只改 `conflicts.resolution`，也不能把摘要页当成 mutation 或授权结果。遇到
resource-limit 或 malformed-row error 时，应显示需要 repair 的状态，而不是默认退回无界完整列表。

### 5.6 提交历史页

用途：解释“发生了什么变更”。

必须显示：

- commit 序号或短 ID
- commit 时间
- 设备 ID
- 操作类型
- 影响对象数量
- 变更摘要

点进详情后 SHOULD 显示字段级 unified diff 风格：

```text
标题:
-   null
+   example.com

用户名:
-   old@example.com
+   new@example.com
```

注意：这里是 unified diff 的结构，不是代码视图。UI 应解析字段名和字段值，降低普通用户理解成本。

删除对象 SHOULD 显示为“删除了密码条目 / 文件夹”，不应把“删除状态 true/false”作为主要字段变更展示。

客户端必须把 `commit_kind` 当作经过认证的协议数据。当前精确值为 `change`、`merge`、
`snapshot`、`key-rotation`、`move`、`copy`、`restore`、`multi`。客户端只能在
展示层本地化，必须保留原字符串，遇到未知值时绝不能按 `change` 回写。storage 与 bundle
reader 会拒绝未知值，UI fallback 不得掩盖完整性错误。

`change_scope` 采用同样规则。精确值为 `project`、`entry`、`attachment`、
`object-relation`、`object-label`、`object-label-assignment`、`vault-meta`、
`key-epoch`、`multi`、`snapshot`、`branch`。`multi` 表示一次真实 operation 跨越
多个已知对象族，不是通用 fallback。客户端必须保留原字符串；未知 scope 应显示不支持数据
错误，不能替换后导出或写回。

### 5.7 快照页

用途：恢复和结构检查。

必须显示：

- 手动快照
- 自动快照
- 创建时间
- 创建设备
- 基准 commit
- 完整 / 增量标识
- 清理自动快照
- 创建快照
- 回滚快照

回滚快照 MUST 二次确认。

### 5.8 快照结构预览页

用途：像文件资源管理器一样查看快照结构。

必须支持：

- 文件夹显示
- 文件夹排在普通项目前面
- 展开 / 折叠
- 嵌套层级线条
- 当前路径标题
- 快照版本节点状态

横屏或宽屏模式 SHOULD 支持当前版本与快照版本并排对比：

- 左侧：当前版本
- 右侧：快照版本
- 中间用分割线即可，不需要厚重卡片包裹

### 5.9 诊断 / 维护页

用途：给用户和支持人员判断 vault 是否健康。

必须显示关键指标：

- 是否可读
- 同步状态
- 待同步数量
- 未解决冲突数
- commit 数
- snapshot 数
- tombstone 数
- entry 数
- folder / project 数
- 附件数量与大小
- 文件路径

必须显示高级细节：

- format version
- Tiga 默认模式
- active key epoch
- branch 数
- device head 数
- dangling parent
- dangling branch head
- dangling device head
- attachment chunk mismatch
- external hash ref 数量

必须提供维护操作：

- 刷新诊断
- 同步
- 上传待处理写入
- 校验附件 chunk
- 清理自动快照

诊断页 SHOULD 简洁，低频细节放在二级区域。不要把 benchmark、raw bundle、底层 payload 全部堆到普通用户面前。

### 5.10 解锁与安全页

必须支持：

- 密码解锁
- Tiga 模式显示
- Tiga 模式选择或 vault 默认模式说明
- 错误次数 / 锁定提示，如客户端实现
- 生物识别或系统凭据包装，如平台支持

客户端 MUST 明确区分：

- 用户看到的解锁方式
- 底层实际参与加密的 key material

Tiga 模式的解锁策略 SHOULD 按以下语义呈现：

- `Sky`：灵活便携，不代表不安全。客户端 MAY 使用密码、PIN、平台凭据包装或安全密钥作为解锁入口，但仍必须走 MDBX 的 KDF、AEAD 和 keyring 机制。适合需要频繁跨设备、网盘同步或恢复优先的 vault。
- `Multi`：默认平衡。客户端 SHOULD 建议用户添加安全密钥，但 MUST 保留清晰的可恢复路径，例如强密码。网盘中的 `.mdbx` 文件可以同步到新设备，新设备可通过已配置的便携解锁方式打开；如果安全密钥或等价平台凭据可用，也可以通过安全密钥方式打开。
- `Power`：最高防护。客户端 SHOULD 引导用户配置密码 + 安全密钥组合解锁方式。若仍保留独立密码或 PIN 解锁，客户端 SHOULD 明确提示这会降低 Power 模式对离线爆破的防护强度。

Tiga2 不只是模式显示。成功解锁后，客户端 MUST 保留 `VaultSession`，并为每次敏感操作提供真实的 `DeviceContext`。不得为了通过 Power 策略而伪造硬件保证、secure clipboard 或防截屏能力。

客户端拥有的操作必须先调用 `TigaService::authorize_operation`，并执行返回约束：

- 显示秘密：`RevealSecret`
- 复制秘密：`CopySecret`
- 附件明文处理：`DecryptAttachment`
- 后台访问：`BackgroundAccess`
- 锁定状态密文同步：`SyncCiphertext`

授权结果为 `Allow` 或 `AllowWithConstraints` 时才可继续。`RequireFreshAuthentication`、`RequireAdditionalFactor` 和 `Deny` 都不得通过 UI 确认框绕过。

存储拥有的高风险操作必须使用已授权 API：

- KDBX 导出：`KdbxExporter::export_all_authorized` / `export_one_authorized`
- 快照恢复：`SnapshotRepo::restore_snapshot_authorized`
- 解锁方式新增、修改、重置和删除：`UnlockService` 的 `*_authorized` 方法
- Tiga profile 与稀疏覆盖：`TigaService` 的 `*_authorized` 方法
- 数据密钥 epoch 轮换：Rust 使用 `KeyEpochService::rotate_authorized`，UniFFI 使用 `MdbxVault.rotate_key_epoch`

第一种解锁方式允许 bootstrap；已有解锁方式后，bootstrap API 必须拒绝。`remediation-required` 状态只允许用户完成解锁方式整改，不会放宽导出、显示或其他 Power 操作。

安全密钥参与解锁时，客户端 MUST NOT 把硬件密钥本体、challenge 响应、派生 key material 或可重放的等价材料写入 `.mdbx` 之外的日志、缓存或同步元数据。支持硬件密钥本身并不会让网盘存储变得不安全或不可用；是否便携取决于 vault 配置了哪些解锁路径。仅配置安全密钥且没有便携解锁方式的 vault 在新设备上需要同一把硬件密钥或等价平台凭据；客户端 SHOULD 在用户启用这种配置前说明恢复影响。

客户端 MUST NOT 把主密码、派生密钥、epoch key 写入日志。

## 6. 性能要求

### 6.1 启动和打开

客户端 SHOULD：

- 只预加载当前选中的 vault
- 避免同时打开所有配置过的 vault
- 对列表页使用 stale-while-revalidate 缓存
- 刷新时避免先清空列表再重新插入，造成闪空和排序跳变

如果用户管理十几个 MDBX vault，客户端 MUST NOT 启动时全部解锁、全部读历史、全部扫附件。

### 6.2 写入

客户端 SHOULD：

- 批量写入
- 单事务提交
- 单用户动作单 commit
- 写完后增量刷新 UI

客户端 SHOULD NOT：

- 每个条目单独打开 / 关闭 vault
- 每个条目单独生成快照
- 删除整张 UI cache 再重建

### 6.3 同步

同步 SHOULD 在后台执行，并通过状态栏或任务面板显示进度。

同步状态至少包括：

- 等待中
- 上传中
- 下载中
- 合并中
- 冲突待处理
- 完成
- 失败

完整同步状态由 storage core 施加独立资源限制。默认状态编码上限为 96 MiB、逻辑行数上限为 250,000；桌面端需要更大批次时，应同时为状态收集、状态解码和 apply 传入同一个 `SyncStateLimits`，仍受 512 MiB 和 2,000,000 行硬上限约束。超限或保留状态身份错误会使整个同步事务回滚。增量状态传输协议尚未进入当前格式，客户端应把资源限制错误显示为可重试的同步容量问题。

重传接收端可能已经保存的 commit 时，客户端必须精确保留原始 serialized commit 字段、
完整性标签和 parent 集合。后续 segment 可以附加之前缺少的对象 payload 或 state delta；较新
bundle 也可以补充旧 bundle 省略的 operation metadata。vector clock、时间、message、分类、
分支 metadata、变更摘要和请求身份都必须来自原始认证记录，不能根据应用当前状态重新生成。
相同 commit 或 operation ID 表示不同认证内容时，storage 会在应用迟到 payload 前拒绝。

首次接收 commit 时，认证不能替代结构验证。producer 必须让每个 parent ID 只出现一次，
把 vector clock 编码为值为无符号 64 位整数的 JSON object，并确保 `local_seq` 位于 SQLite
有符号 64 位 INTEGER 范围内。空 `{}` clock 继续作为 legacy 兼容表示。传输层不得自行去重
parent、修补 clock 文本或环绕 sequence，因为这些转换都会改变已经认证的 commit 含义。

device head 属于 storage core 管理的设备序列状态。客户端不能根据 branch 祖先关系推导 head，
也不能通过客户端 SQL 修改 `device_heads`。receiver 会验证引用 commit 是否由所声明设备创作，
并按 `local_seq` 推进，即使两个 commit 位于不同 branch 且互为 sibling。乱序到达的较低序列
仍是合法历史，但不能让 head 回退，本地 revocation 也会保留。出现 sequence reuse validation
error 时，应停止处理该同步输入并显示诊断，不能重新编号或重新生成 commit。

tombstone acknowledgement 属于 storage core 管理的因果证据。客户端不能按时间戳选择最新
行，不能根据 device head 推导确认，也不能在传输层重写 `observed_commit_id`。storage 会先
比较 commit 祖先关系，仅在两个有效证明并发时使用确认时间与 commit ID 决定规范行。出现
causal validation error 时，应把该同步输入报告为无效，不能改写本地证明后重试。

密钥 epoch 轮换的同步顺序属于安全不变量。客户端收到成功结果后，必须先传播 rotation commit 与 authenticated key epoch sync state，再上传或广播使用新 epoch 写入的 `MDBXFE2` 字段。接收端改变 epoch 状态时必须处于经过验证的解锁状态，并使用会刷新连接 keyring 的可变 apply 入口。旧 payload 缺少 key epoch state 时保留本地状态；并发轮换必须保留全部 wrapper，并接受 storage core 选出的 active epoch。

轮换调用本身代表一次新的安全管理动作，不使用普通 `operation_id` 幂等重试语义。响应状态未知时，客户端应先按返回 commit、commit history 或 Tiga 审计关联查询，再决定是否发起另一轮轮换。

## 7. 兼容性要求

### 7.1 format version

客户端打开 vault 时 MUST 检查 `format_version`。

遇到未知 critical extension MUST 拒绝写入，最多只读打开。

客户端可以负责迁移提示、升级前备份、进度和整改 UI，但格式转换必须调用 storage core。不得在 Android、iOS、桌面端分别实现一套 MDBX1 字段迁移。

客户端应先用 `inspect_migration_path` 或 UniFFI 的 `inspect_vault_migration` 做只读迁移检查。需要升级时，通过 `BackupService::create_portable_copy_path` 或 UniFFI 的 `create_portable_backup` 创建精确迁移前归档。备份发布且取得确认后，再调用 `upgrade_path` 或 UniFFI 的 `upgrade_vault`；两者都会委托同一个 storage-core 事务迁移器。`VaultConnection::open` 仍保留自动升级兼容路径，供简单调用方使用。

只读备份复制密文与现有解锁方式，不解密业务记录，因此无需用户凭据。结果保留源格式代际，并包含已经提交的 WAL 页面。需要保留 MDBX1 归档时，客户端必须在该步骤完成前避免调用自动 open。

### 7.2 ID 稳定性

客户端 MUST 保留以下 ID：

- vault ID
- device ID
- branch ID
- project / folder ID
- entry ID
- attachment ID
- commit ID
- snapshot ID

客户端 MUST NOT 用标题、路径、排序号重新生成对象 ID。

### 7.3 时间和排序

客户端 SHOULD 使用 ISO-8601 UTC 时间。

列表排序 SHOULD 稳定。刷新数据时不要让同一批项目因为重新导入而随机换序。

## 8. 最低测试清单

其他客户端接入前至少应通过这些场景：

- 创建 vault，关闭后重新打开。
- 在可写打开前备份带 WAL 的 MDBX1 vault，并确认源文件与备份仍报告 MDBX1。
- 显式升级源文件后，确认迁移前备份仍报告 MDBX1。
- 创建根目录条目。
- 创建嵌套文件夹中的条目。
- 在子文件夹里新建条目，目标仍是该 MDBX 文件夹。
- 批量移动 100 条到 MDBX，只产生一个用户级 commit。
- 批量删除 100 条，只产生一个用户级 commit，并写入 tombstone。
- 两个客户端打开同一 vault，数量一致。
- 一个客户端删除，另一个客户端同步后不会复活。
- observed commit 位于 delete commit 之前的 acknowledgement 必须拒绝。
- 以两种顺序应用后代与祖先 acknowledgement，均保留后代证明和较晚确认时间。
- 以两种顺序应用两个有效并发 acknowledgement，最终保存行必须相同。
- 先接收较高序列的 branch sibling，再接收迟到的较低序列，device head 仍保留较高序列。
- device-head 行指向其他设备创作的 commit 时必须拒绝，同时保留本地 revocation。
- 并发修改同一字段，产生冲突。
- 并发修改不同字段，可以自动合并或清晰提示。
- 创建手动快照。
- 清理自动快照。
- 回滚快照需要二次确认。
- 快照结构显示文件夹，并且文件夹排在条目前面。
- 附件 chunk 校验失败时能在诊断页看到。
- 打开 MDBX 格式管理首页，不自动跳进上次数据库详情页。
- 普通用户界面不暴露 raw 高级工具。
- 轮换后先同步 rotation commit 和 key epoch state，再同步新 epoch 密文；另一副本可以读取旧、新和并发 epoch 下的数据。
- 轮换授权拒绝时 active epoch 与 commit 数量保持不变。

## 9. 常见错误

### 9.1 只写当前表，不写历史表

结果：

- 提交历史空白
- 快照不可用
- 冲突无法判断
- 删除可能被复活

### 9.2 每条数据一个 commit

结果：

- 批量操作后历史暴涨
- 快照暴涨
- 同步变慢
- 管理页不可读

### 9.3 文件夹只按路径字符串保存

结果：

- 重名文件夹冲突
- 移动后路径断裂
- 面包屑显示错误
- 跨客户端选择目标失败

### 9.4 管理页自动跳进上次 vault

结果：

- 用户点击“格式管理”却看不到格式管理首页
- 用户误以为只能管理一个数据库
- 多 vault 场景混乱

正确做法：

- 密码页可以记住当前 vault。
- 格式管理入口应总是进入 MDBX 管理首页。
- 数据库详情页只能由用户明确点击进入。

### 9.5 把开发工具暴露给普通用户

结果：

- 用户看到 benchmark、raw bundle、chunk payload 后无法理解。
- 容易误操作导入错误 payload。
- 管理页信息噪音过高。

正确做法：

- 普通用户只看同步、冲突、快照、历史、诊断 / 维护。
- raw bundle、benchmark、底层 chunk 调试放到开发者模式。

## 10. 接入完成标准

一个客户端可以宣称“支持 Monica MDBX”，至少必须满足：

- 可以打开 Monica 创建的 MDBX vault。
- 可以正确显示文件夹、条目、附件元数据。
- 可以在嵌套文件夹中新建、移动、复制条目。
- 可以写入 commit、object version、tombstone。
- 可以显示提交历史。
- 可以显示和回滚快照。
- 可以检测并展示冲突。
- 可以显示诊断 / 维护页面。
- 批量操作不会制造大量无意义 commit。
- 两个客户端读同一 vault 时项目数量一致。

如果只满足读取，不满足写入历史链路，应标注为“MDBX 只读支持”，不能标注为完整支持。
