# MDBX 存储与同步规范

版本：`MDBX-1-DRAFT`

本文定义 MDBX 的单文件容器策略、内部持久化规则、增量更新行为、同步模型、附件存储行为。

## 1. 容器策略

MDBX 应优先采用单一便携的 `.mdbx` 文件作为用户可见的 vault 载体。

在 `.mdbx` 文件内部，首选引擎为：

- `SQLite + 自定义加密层`

`LMDB` 可以在后续探索，但当前推荐以 SQLite 为基线，因为它在工具生态、可移植性、恢复工具、schema 演进支持方面更成熟。

### Vault 创建生命周期

vault 创建必须以原子方式占用一个尚不存在的文件路径。已有普通文件、SQLite 数据库、MDBX vault 或同名 SQLite WAL 与 SHM 文件必须被拒绝，且原内容保持不变。客户端可以先检查文件是否存在以提供清晰提示，最终判断仍由 storage 的排他占用操作完成。

在 schema、vault metadata、genesis commit、初始 branch、device head、初始 key epoch 与首个解锁方法全部成功前，创建状态保持为 pending。上述任一步骤失败时，必须先关闭 SQLite 连接，再删除本次创建产生的主数据库、WAL 与 SHM 文件。已有 vault 的读取与升级必须使用 open 和 migration 接口，禁止通过 create 接口处理。

### 已有 Vault 打开生命周期

open 与显式升级必须先通过只读 SQLite 连接检查文件。只读预检需要确认 `vault_meta` 已初始化、MDBX 格式代际受支持、critical extension 均可识别，随后才允许建立可写连接、切换 WAL、执行迁移或兼容清理。

可写连接必须使用不带 SQLite 创建权限的读写标志。路径缺失或 SQLite 数据库尚未初始化时返回错误，原文件状态保持不变。foreign key 与 busy timeout 等连接级设置可以在迁移前应用；持久 WAL、secure delete 与旧明文索引清理只能在身份验证和事务迁移成功后应用。

### 可移植备份生命周期

可移植备份是从活动 vault 生成的、事务一致且可以独立打开的单个 `.mdbx` 文件。storage 必须使用 SQLite online backup API 或等价的数据库快照机制，使仍然只存在于源 WAL 中的已提交页面进入备份。WAL 活跃时仅复制源主文件无法构成完整备份。

备份必须先写入目标目录内的临时文件，再转换为非 WAL 日志模式，执行 SQLite 完整性检查，并确认其属于受支持且已经初始化的 MDBX vault。目标 format、schema metadata 与 `vault_id` 必须和源文件一致。临时文件在发布前必须执行文件同步，发布操作必须禁止覆盖已有文件。

目标主文件及其同名 `-wal`、`-shm` 文件必须全部尚未存在。任一目标文件已经存在时，操作返回错误并保留其内容。成功生成的可移植备份没有必需的旁路文件，可以继续使用源 vault 的现有解锁方式独立打开。

上述保证由 storage facade 统一提供。已经打开的 Rust vault 调用 `BackupService::create_portable_copy`；客户端可控迁移在建立可写连接前调用只读的 `BackupService::create_portable_copy_path`。UniFFI 分别提供 `MdbxVault.create_backup` 与顶层 `create_portable_backup`。参考 CLI 的 `mdbx backup <output>` 使用只读文件路径接口，因此无需解锁凭据，也不会触发自动迁移。

只读文件路径备份必须在结果中保留受支持的 MDBX1、MDBX1 draft 或 MDBX2 代际。源主数据库与 WAL 的持久字节必须保持不变。读取活动 WAL 源时，SQLite 可以更新现有 SHM 协调文件中的临时 read mark；SHM 可以重建，也不会进入可移植结果。

## 2. 内部存储目标

内部布局必须支持以下能力：

- 追加友好写入
- 局部更新
- 崩溃恢复
- 附件元数据存储
- 附件二进制内容的间接存储
- 版本历史
- 冲突检测元数据
- 后续迁移钩子

## 3. 最小逻辑表集合

最小逻辑 schema 必须预留至少以下记录类：

- `projects`
- `entries`
- `attachments`
- `attachment_chunks`
- `commits`
- `commit_parents`
- `device_heads`
- `branches`
- `tombstones`
- `snapshots`
- `key_epochs`
- `conflicts`
- `unlock_methods`
- `object_versions`
- `project_tags`

MVP 可以暂时不实现某些辅助索引或次级表，但绝不能省略 `projects` 或 `attachments`。

## 4. Project 导向的 schema 规则

`projects` 表是强制存在的。
`entries` 表必须带有 `project_id` 外键或等效引用。

这意味着：

- 每个密码类秘密都必须归属于某个 project
- 查询必须能够先取 project，再取其子 entry
- 同步和合并逻辑必须保持 project 归属不丢失

## 5. Attachment schema 规则

`attachments` 表从版本 1 起就是强制的。
即使 MVP 只部分启用分块存储，`attachment_chunks` 表也应从版本 1 起预留。

schema 必须支持：

- 附件归属于 project
- 附件可选归属于某个具体 entry
- 内容 hash
- 分块二进制数据或外部内容引用
- 软删除或 tombstone 机制
- 完整性校验

## 5.1 Object payload 与大型内容边界

`entries.payload_ct` 是通用 ObjectRecord 的有界结构化数据平面，适合密码字段、书签属性、邮件头与规范化小正文、联系人、`mafile` 文档和领域 Adapter 的版本化 JSON。对象可以通过稳定 attachment/blob ID 引用大型内容，但不能把任意大小的二进制或原始文档都塞进一条 payload。

策略授权的对象披露默认最多返回 8 MiB 明文，客户端可以在 1 byte 到 64 MiB 硬上限之间选择资源配置。storage 在加载 BLOB 前检查密文长度，并在认证解密后复核实际明文长度。该上限约束读取资源，不改变 MDBX1 完整记录兼容 API 或既有数据库字节。

超大邮件正文、原始 MIME/EML、网页归档、文件与媒体内容应使用 `attachments` / `attachment_chunks` 或 encrypted blob provider。此路径必须支持有界分块、流式传输、内容 hash、归属关系和生命周期；普通对象编辑不得重写这些大型内容。

## 5.2 通用元数据选择边界

大型客户端页面遍历 relation、label 和 label assignment 时，必须使用有界摘要投影。relation 与 label 摘要不得查询其加密 payload 列；label 摘要可以解密经过验证的显示名称，assignment 摘要只包含稳定 ID 与因果元数据。

每页只能包含 1 到 200 项，按更新时间与稳定 ID 降序执行 keyset 分页，并返回绑定方向、owner、collection 和可选 relation-kind 过滤条件的版本化不透明游标。按 ID 查询 relation/label 摘要时保留删除状态，列表页只返回 active row。完整记录 repo 继续作为兼容及显式 payload 接口，不得作为 collection 或关系图的默认遍历路径。

### 5.2.1 附件元数据导航

附件列表和已删除列表必须使用无 payload 的 `AttachmentSummary` 投影。该投影可以认证文件名和可选 media type，但绝不能选择 `attachment_chunks.chunk_ct` 或 `external_uri_ct`；损坏的二进制内容或不可用的外部 provider 不能阻塞只读元数据页面。Collection 页和 Object 页使用同一个 1 到 200 项的 keyset 契约，deleted 页使用独立且绑定查询的游标；按 ID 查询的摘要保留删除状态。

文件名固定最多 4096 个 UTF-8 bytes，media type 固定最多 512 bytes。SQL 必须先检查密文长度，并在物化 BLOB 前通过条件投影应用共享的 128 KiB 信封预留。storage 必须认证解密有界字段，再复核精确明文字节长度和 UTF-8；原有完整 attachment repo 继续作为 MDBX1 兼容以及显式内容/repair 路径。

## 5.3 通用元数据披露边界

显式读取 relation payload 时，必须同时对 source Entry 与 target Entry 执行 `RevealSecret` 授权，并分别保留两个决定；显式读取 label payload 时，必须使用所属 collection 的 Project scope。该规则复用既有 scope，不能仅为了披露 payload 就把 Relation/Label 新增为持久化 scope 类型。

scope 路由可以查询稳定的 endpoint/collection ID，但不得查询 payload 密文。路由、全部 scope 求值、拒绝或成功审计、删除状态检查、资源门禁和认证解密必须位于同一个 immediate transaction。任一必需 scope 不允许时，结果返回全部 scoped decision 且 payload 为空；不得检查删除状态、调用 `length(payload_ct)`、载入 BLOB 或解密。relation 的关联决定使用同一个无 commit 的 operation ID。

relation 与 label 披露共同使用 8 MiB 默认明文上限、64 MiB 硬上限、带 128 KiB 信封预留的载入前密文长度门禁，以及解密后的实际明文复核。只有真正返回明文时才能续期活动会话。原有完整 metadata 读取继续保持字节/API 兼容，不能被重新宣称为策略感知接口。

## 6. 写入路径要求

日常小修改绝不能在逻辑层面导致整个 vault 内容被全量重写。

符合规范的写入路径应尽量做到：

1. 只更新发生变化的 project 或 entry 行
2. 追加一条 commit 或 oplog 记录
3. 更新轻量级 head 元数据
4. 不触碰无关附件行
5. 不触碰无关的大型二进制页面

## 7. WAL 与追加策略

首选实现应使用 SQLite WAL 模式或等价的追加友好策略。

设计目标：

- 小修改产生小写入增量
- 支持区域同步的云盘工具可以只传播较小差异
- 压缩/整理是明确且低频的动作

实现必须清楚说明在断电或崩溃场景下如何保证耐久性。

## 8. Commit 与历史模型

MDBX 必须维护类 Git 的逻辑历史。

最低要求：

- 每次本地变更都产生一个 commit 式历史记录
- commit 记录一个或多个父 commit
- 设备本地顺序单调递增
- 并发历史在合并前也必须可表达

一个 commit 记录最好包含：

- commit ID
- device ID
- 本地序列号
- 父 commit ID 列表
- 变更对象引用
- 精确且经过认证的 commit kind
- 精确且经过认证的 change scope
- 时间戳
- 可选的 merge 元数据
- 完整性数据

稳定 ChangeScope 值为 `project`、`entry`、`attachment`、`object-relation`、
`object-label`、`object-label-assignment`、`vault-meta`、`key-epoch`、`multi`、
`snapshot`、`branch`。只有一个有限 operation 确实跨越多个已知对象族时才使用 `multi`。
reader 与 writer 遇到未知 scope 时必须拒绝，不能转换为 `multi`，因为 kind 和 scope 都是
commit 完整性输入。新的本地 CommitOperation 必须在启动写事务前验证两类值。

每条新的 `CommitOperation` 必须在执行 repository mutation 前保存不可变的初始请求身份。
现有 `request_hash` BLOB 使用版本 1 编码：八字节 `MDBXORI1` 前缀后接现有 32 字节规范请求
摘要。摘要在稳定分支解析完成后生成。commit 聚合可以更新最终 commit 元数据和加密变更摘要，
但必须始终保留这份 40 字节初始身份。

重试处理必须先验证 operation 完整性，再使用已经保存的元数据。版本化身份必须与提交请求
精确匹配，即使 `intent_hash` 为空也适用。现有未标记 32 字节摘要继续作为 legacy 输入：直接
operation 与显式 legacy intent 仍然精确比较；没有显式 intent 的旧聚合 operation 保留历史
重试语义，因为早期 writer 可能已经用最终聚合摘要覆盖其初始摘要。其他长度以及前缀未知的
40 字节值必须 fail closed。同步层必须把两种已识别表示作为经过认证的不透明字节精确保留，
不得规范化或重算。

incoming `commit_id` 已经存在时，设备 ID、本地序列、精确 kind 与 scope、加密变更对象 ID、
vector clock、可选 message、创建时间、完整性标签和规范 parent 集合必须与本地记录完全一致，
随后才能应用任何 payload。首次插入使用活动连接 keyring 验证标签；重放则把 incoming 完整
身份和标签与本地已经接受的字节精确比较，不得重新解释或生成历史 commit 元数据。

精确匹配后可以补收迟到的对象 payload 与 state-delta envelope。本地 commit 缺少 operation
行时，经过完整性验证的 incoming operation metadata 可以作为附加信息写入。operation ID 或
commit ID 任一方向已经存在映射时，两个 ID、operation kind、分支身份与名称、加密摘要、请求
身份、创建时间和完整性标签必须全部一致。旧 bundle 可以省略 operation metadata，但不得删除
或削弱本地已有记录。任何差异都必须在 payload mutation 前拒绝。

commit 尚未存在时，完整性验证成功后还必须在首条 SQL mutation 前执行结构预检。
`local_seq` 必须能够表示为 SQLite 有符号 64 位 INTEGER；`vector_clock` 必须解码为值为
无符号 64 位整数的 JSON object；每个 parent ID 只能出现一次。完整性计算会规范 parent
顺序，因此顺序可以不同；关系表无法保留重复次数，所以重复 parent 必须拒绝。legacy 空 clock
`{}` 继续有效。结构拒绝后不得留下 commit、inventory、parent、sequence、payload、tombstone、
device head 或 branch 状态变化。

`(device_id, local_seq)` 身份必须保持唯一。新 commit 使用已经分配给另一 commit ID 的设备
序列时，必须在插入前返回 validation error；既有唯一索引继续作为存储层保护。

device head 必须引用由同一设备创作的 commit，并表示该设备已经接受的最大本地序列。
排序依据是 `local_seq`，不是 DAG 祖先关系，因为同一设备可以在不同分支创作 commit，同时
继续使用一条全局设备序列。较高序列推进 head；迟到的较低序列保留在历史中，但不能让 head
回退；同一 commit 重放保持幂等。`last_seen_at` 保留较晚值，`revoked` 单调合并。health check
必须报告悬空、设备归属错误和序列落后的 head。

tombstone acknowledgement 必须构成因果证据。observed commit 必须存在；tombstone 带有
`delete_commit_id` 时，observed commit 必须等于该删除 commit 或位于其后代。对于同一
tombstone 与设备，后代证据推进祖先证据，祖先证据不能覆盖后代证据；两个有效并发证据先做
因果比较，再选择较大的 `(acknowledged_at, observed_commit_id)`。保存的确认时间必须保留
较晚值。没有 delete commit 的 legacy tombstone 仍要求 observed commit 存在，但不得虚构
历史因果祖先。

同步、冲突基点查找、acknowledgement 验证、永久清理资格和 health verification 使用的
commit parent 读取与祖先关系必须由 storage core 统一解释。时间戳与 device head 不能替代
commit 祖先关系。

## 9. 冲突检测

MDBX 必须基于因果元数据检测并发修改，不能只靠时间戳。

最低可接受机制包括：

- 版本向量
- 设备序列图
- 记录级修订血缘
- 必要时的字段级冲突标记

同一 project 内不同字段的并发修改，在安全时可以自动合并。
同一秘密字段的并发修改必须产生显式冲突。

### 9.1 密钥 epoch 状态

sync state 中的 key epoch 字段必须保持可选，使 MDBX1 和早期 MDBX2 payload 可以继续反序列化。字段存在时应包含 active epoch ID、按 ID 规范排序的全部 active 和 retired rows，以及状态完整性标签。

顺序历史中的 fast-forward 轮换采用 incoming active epoch。并发轮换对候选 epoch 使用激活时间与 epoch ID 的确定性顺序，所有合法 wrapper 取并集，未选中的候选转为 retired。相同 epoch ID 的 wrapper、profile、创建时间或激活时间发生改写时必须拒绝。

改变 epoch 状态需要经过验证解锁的可变连接。应用事务必须先验证状态标签与 wrapper，再写入依赖新 epoch 的对象密文；事务提交后刷新 active 与历史 epoch keyring。旧 payload 缺少该字段时不得清除或回退本地 epoch 状态。

### 9.2 事务级状态 Delta

超过 bootstrap floor 后，每个外层写事务应物化一个有界、不可变的状态 delta 批次。带关联 commit 的批次附着在最后一个关联 commit 上；没有 commit 的事务产生 auxiliary 批次，并且不得增加用户可见历史记录。

接收端接受状态前，必须验证 vault 与批次身份、payload digest、逻辑行数、commit 归属和资源限制。所有关联 commit 必须可用。一个 serialized commit 上不得混用已识别 delta 与完整 sync state，也不得携带第二个 delta。commit 插入、稀疏状态应用、附件 chunk 替换、device-head 合并、授权删除、接收批次持久化和 incoming capture 清理必须全部提交或全部回滚。

delta 中的 tombstone 是稀疏集合，不得替换无关的本地 tombstone。引用均存在的 tombstone acknowledgement 行必须采用与本地删除及完整状态 apply 相同的因果单调合并；非因果证明会拒绝整个事务。缺失引用的行继续使用完整状态兼容行为并跳过。device-head 行与 commit 导入使用同一设备创作序列规则，device revocation 必须单调合并。物理删除对象或 tombstone 必须有匹配且经过认证的永久清理凭证。key epoch 变更只能通过经过验证解锁的可变 apply 路径完成；不可变兼容入口必须原子拒绝。

完整 sync state 继续承担首次 bootstrap 和旧 peer fallback。bundle v1-v3 格式保持不变。bundle v4 在取得成对 checkpoint 后携带有界 commit/delta inventory，以 transfer ID、segment index 和上一段 payload digest 绑定恢复链；接收端只有在整段持久应用后才能推进 checkpoint。同一段内的 commit 关联 delta 与 auxiliary delta 必须处于同一个数据库事务。客户端在同时交换两类 checkpoint 并保存 segment resume 状态之前，不得宣称已经实现增量收敛。

bundle v5 是 complete v3 逻辑 payload 的 zstd 表示，bundle v6 是 incremental v4 逻辑 payload 的 zstd 表示。两者在 20-byte header 区域依次记录压缩长度、未压缩 bincode 长度和 4 个零保留字节。trailer 始终是未压缩 bincode payload 的 SHA-256，因此 incremental resume chain 的身份不受压缩方式影响。writer 必须同时限制序列化输入与压缩输出，且不得缓存完整的最大尺寸逻辑 payload。reader 必须在分配前检查两个声明长度，并把流式解压输出限制为声明的未压缩长度加 1 byte。长度不符、超过配置上限的扩张、压缩流损坏、非零保留字节、hash 不符和尾随数据都必须失败。

完整 sync-state decoder 在解码并重新编码时会保留有界的未知非关键顶层字段。扩展键不得覆盖已定义字段，字段数量、编码字节数、键长度和嵌套深度均受限制。版本化 delta envelope 与 cursor token 仍属于严格协议记录，继续拒绝未知字段。

protocol-v2 peer 将 commit inventory paging、delta inventory paging、bundle v4 和 incremental resume 作为四项附加能力；只有四项均由双方协商成功时才选择 incremental v4。支持 paging 的 Hello 省略旧的完整 `known_commit_ids`，commit/delta page 改用有界 opaque checkpoint/cursor token。能力缺失或不完整时必须回退到有界完整状态。

`bundle-zstd-v1` 是独立可选能力，不属于上述四项增量契约。transport-neutral sender 只有在 codec 已编译且双方都声明该能力时才能选择 zstd。裁剪掉 codec 的构建继续支持 v1-v4，并对 v5/v6 返回明确的 unsupported-feature 错误。文件导出必须显式选择压缩；CLI `sync bundle --compression` 默认 `none`，apply 自动识别当前构建支持的版本。这样新 writer 不会静默向会拒绝新版本的旧 reader 发送 v5/v6。

## 10. 合并模型

MDBX 最好支持：

- fast-forward merge
- 针对非秘密文本字段的三方合并
- 对不安全合并生成 conflict 记录
- 后续由用户可视化解决合并冲突

当自动合并不安全时，系统必须保留双方结果。

## 11. 快照与恢复

MDBX 必须支持从逻辑损坏或同步中断中恢复。

最低要求：

- 历史 commit 可回放
- 可以周期性生成 snapshot
- snapshot 可以重建 project、entry、attachment 元数据，以及存在于数据库内的附件 chunk
- 局部损坏时最好仍能恢复剩余大部分数据

snapshot 是保存在 vault 内部的逻辑恢复点。可移植备份生成可独立打开的完整 vault 文件；sync bundle 在副本之间传输增量 commit 状态。三者用途不同，WAL 活跃时仅复制 SQLite 主文件也不能替代其中任何一种机制。

verified-unlocked writer 创建的新 snapshot 使用 `MDBXSN2` payload profile 和版本化
`hmac-sha256-v1` integrity descriptor。HMAC 在现有 snapshot commit 的同一事务内绑定
vault ID、snapshot ID、base commit、落盘密文摘要、创建时间和创建设备。首次写入该格式时
注册 critical extension `snapshot-record-auth-v1`；不理解该 profile 的 reader 必须拒绝
vault，不能套用旧 snapshot AAD 继续写。既有 64 位十六进制 SHA-256 snapshot 与原始
`payload` AAD 继续读取和恢复。锁定状态只能检查公开密文摘要与 descriptor 形状；解锁后
再执行 keyed metadata verification 和 payload authentication。

离线同步包读取器必须在分配内存和反序列化之前执行 payload 上限。bundle v3/v4 在头部记录未压缩 payload 长度，并拒绝非零保留字节和 payload hash 后的尾随数据。bundle v5/v6 分别对压缩输入和解压输出应用同一配置上限。bundle v1 与 v2 兼容读取器必须限制底层 reader 的读取量，禁止无界读取。资源配置可以选择更低的上限，协议硬上限始终生效。

## 12. 附件存储模式

即使 MVP 还未全部实现，MDBX 也必须先定义以下存储模式：

- `embedded-inline`
  - 小型二进制直接内嵌在附件载荷中

- `embedded-chunked`
  - 附件在数据库内按加密分块存储

- `external-hash-ref`
  - 数据库保存元数据，并以内容 hash 绑定外部 blob

默认建议：

- 小附件可以内嵌
- 大附件最好采用分块或内容寻址的外部引用

## 13. 附件更新规则

修改 project 元数据时，不能因此重写大附件内容。
修改 entry 字段时，不能因此重写无关附件内容。
附件改名必须只改元数据。

## 14. 网盘优化

MDBX 的目标是通过 Syncthing、Git、Nextcloud、WebDAV 包装层、Dropbox、OneDrive 等方式同步。

实现应尽量做到：

- 小修改只改很小区域
- 能追加写就尽量追加写，少做随机重写
- 整理操作只在阈值满足时触发
- 将附件主体与普通元数据编辑隔离

## 15. 性能目标

一个健康实现应追踪以下目标：

- 常见元数据保存低于 `100 ms`
- 打开 project 足够快，可用于交互式 UI
- 大型库搜索明显快于 KDBX
- 小修改在云盘上的 delta 通常保持在 `KB` 级别

这些是产品目标，必须有基准测试来跟踪。

## 16. 必需索引

存储引擎最好至少维护以下索引：

- project 标题
- project 标签归属
- project 分组归属
- project 下 entry 类型
- 最近修改时间
- attachment 归属
- tombstone 查找
- commit 血缘查找

全文搜索可以在已解锁会话中使用临时索引。持久 FTS 表不得保存解密后的 project 标题或其他带秘密语义的文本。

临时搜索索引不是用户可见历史，不应产生 commit。用户可见的 project tag 属于元数据，不是临时搜索状态：tracked tag mutation 应产生 project 级 commit，sync state 应携带每个 project 的完整 tag 集合，这样删除 tag、包括删除最后一个 tag，才能被安全重放。收到旧版不含 tag 字段的 sync payload 时，读取端必须保留本地 tag，不能把缺失字段解释为“清空所有 tag”。

## 17. 整理与压缩规则

compaction 可以重写较大部分内容，但必须满足：

- 显式触发或策略触发
- 中断后仍可恢复
- 日常小修改不依赖 compaction
- 不破坏附件完整性

## 18. 最小导出要求

存储层必须支持以下导出路径：

- 整库导出
- project 导出
- 带完整性校验的附件提取
- KDBX 导出桥接

## 19. 拒收规则

以下存储设计不符合规范：

- 没有一等 `projects` 结构
- 没有一等 `attachments` 结构
- 设计上普通小修改就必须重写整个 vault
- 不能表达并发历史
- 把任意存在的 commit 当作设备已经观察到后续删除的证明
- 不能说明中断后如何恢复
