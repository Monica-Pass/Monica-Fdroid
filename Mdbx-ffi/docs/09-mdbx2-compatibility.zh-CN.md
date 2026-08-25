# MDBX2 兼容与迁移规范

版本：`MDBX-2`

本文定义第二代 MDBX（产品名 `MDBX2`）对上一代 vault 的兼容、自动升级和写入安全规则。

## 1. 兼容承诺

- MDBX2 实现 MUST 读取并升级 `MDBX-1`。
- MDBX2 实现 MUST 读取并升级历史测试格式 `MDBX-1-DRAFT`。
- 升级 MUST 保留稳定 ID、密文、commit DAG、object version、tombstone、snapshot、key epoch 和附件内容。
- 升级失败 MUST 保持原 vault 的 `format_version` 和数据不变。
- schema 迁移 MUST NOT 隐式执行 key rotation 或全库重新加密。

这里的“兼容上一代”首先保证新实现读取旧数据。已经发布的旧二进制无法理解未来关键语义，因此不能承诺它们可以安全写入任意 MDBX2 vault。

## 2. 版本字段

MDBX2 在 `vault_meta` 中增加：

- `schema_version`
  - 当前内部 schema 序号；当前版本为 `17`。vault header HMAC 在 `16` 引入，snapshot lifecycle 在 `17` 引入。
- `min_reader_version`
  - 可以读取当前 vault 的最低格式代际。
- `min_writer_version`
  - 可以安全写入当前 vault 的最低格式代际。

MDBX-1 自动升级后使用：

```text
format_version    = MDBX-2
schema_version    = 17
min_reader_version = MDBX-1
min_writer_version = MDBX-2
tiga_policy_version = 2
```

这表示 MDBX2 仍保持 MDBX-1 的可读结构，但只有理解 MDBX2 写入不变量的实现才能继续写入。

## 3. 自动升级流程

可写打开 MDBX-1 vault 时，MDBX2 MUST：

1. 读取 `format_version` 和 `critical_extensions`。
2. 遇到未知格式或未知关键扩展时拒绝可写打开。
3. 开始 `BEGIN IMMEDIATE` 事务。
4. 以 additive migration 增加新字段和 `schema_migrations`。
5. 将 Tiga1 模式映射为 Tiga2 策略；旧弱覆盖生成确定性的整改例外。
6. 评估旧解锁配置；不满足新策略时标记 `remediation-required`，不得拒绝用户打开。
7. 记录唯一 migration ID。
8. 完成全部结构和数据验证。
9. 最后更新 `format_version = MDBX-2`。
10. 提交事务。

升级器 MUST 幂等。重复打开已经升级的 vault 不得重复迁移或改变用户数据。

未来 MDBX3 打开 MDBX-1 时 MUST 顺序执行 `MDBX-1 -> MDBX-2 -> MDBX-3`，不得跳过中间代际迁移。

早期 schema 2 或 schema 3 的 MDBX2 vault 会原地升级到 schema 4，不改变 `MDBX-2`
格式标记。schema 4 增加 operation-level commit 元数据和设备原子序列状态，同时继续保留旧
`commits` 表与 DAG 作为 MDBX1 兼容投影。schema 4 随后以增量迁移升级到 schema 5，增加可空的
Tiga 审计关联与策略证据字段；旧审计记录继续以空值读取。

schema 5 随后以增量迁移升级到 schema 6，增加可空的 `commit_operations.branch_id` 与查询索引。旧 operation 行继续保留空的分支 ID，因为其 V1 请求哈希与完整性标签只认证 `branch_name`，迁移过程不得推断并回填该字段。

schema 6 到 schema 11 继续采用顺序附加迁移：schema 7 增加通用关系、标签和标签分配；schema 8 增加 tombstone 删除证明与设备确认；schema 9 增加永久清理凭证；schema 10 将 Attachment 纳入 Tiga scope；schema 11 增加一对一 `collection_profiles`。这些迁移均保留 `projects`、`entries` 和旧公开接口。

schema 10 重建 Tiga 策略表时，也会保留当前 reader 不认识但属于附加性质的有界字段；字段必须可空或带安全字面量默认值。无法安全重建的字段会在替换旧表前让事务失败，绝不会静默丢弃非关键字段。

schema 12 增加本地稳定 commit 库存，迁移过程保持 commit 身份不变，并按照 parent-before-child 顺序回填。schema 13 增加状态 delta 批次库存、规范化 commit 关联、有界版本化信封规则，以及固定在迁移 commit 水位的 bootstrap floor。schema 14 为所有参与同步的核心状态族增加事务级逻辑变更采集；每个外层写事务提交前，MDBX 会对逻辑键去重，物化有界状态体，并将 commit 关联批次或 auxiliary 批次与业务行原子保存。创建或升级 vault 时产生的 bootstrap 变更会在同一事务中清除，因为这些状态已经由 floor 覆盖。迁移过程不会虚构历史 delta；早于 floor 的 checkpoint 继续使用有界完整状态完成首次同步。

schema 15 增加 `sync_state_extensions`，用于保存 complete-state 顶层的有界未知字段。apply 只 upsert incoming state 实际携带的键，并与 commit 和业务行处于同一个事务；缺少某个键不表示删除，因此旧 peer 仅仅省略未来字段时不能擦除本地扩展。collect 按键顺序恢复值。迁移和 current-schema 验证共同执行 256 个字段、128-byte 键、64 KiB 聚合预算及既有嵌套深度限制。

schema 16 为 `vault_meta` 增加 `header_integrity_profile` 和
`header_integrity_tag`，并增加受保护字段变更触发器。MDBX1/较早 MDBX2 升级时保持
原密文、unlock wrapper 与身份不变，header 先进入 `pending`；使用原凭据首次成功
解锁后，以 vault integrity subkey 建立 HMAC。此后受保护字段的合法 mutation 必须在
同一事务重新封签，直接篡改会进入 `invalidated` 或产生 tag mismatch。该 additive
字段不把 MDBX1 reader 变成 MDBX2 writer，`min_writer_version = MDBX-2` 边界不变。

schema 17 增加经过认证的 `snapshot_lifecycle` companion table，但不改变 MDBX1
六列 `snapshots` 表，也不重写任何旧 snapshot 行。没有 lifecycle 行的 snapshot 永久
按受保护的 manual recovery point 处理。只有 HMAC 验证通过、类型为 automatic 且
RFC3339 retention 时间已经到达的行，才可进入 storage 签发的有界 prune plan。每次
授权裁剪都在事务内重新核对精确计划，最多删除 200 个 automatic snapshot，并只记录
一个可幂等重试的 operation commit。snapshot lifecycle 属于本地恢复状态，不加入
`SyncStatePayload`。

storage core 将扩展值视为 opaque JSON：只验证、保存和转发，不解释也不解密。opaque 不等于自动加密。非敏感的能力或版本元数据可以使用普通 JSON；密码、邮件正文、token 或其他敏感材料在进入未知扩展前，MUST 由扩展生产者封装为认证密文。这样旧 reader 才能在锁定状态保存未来敏感状态，同时不会自行产生明文。

storage apply 现在识别经过认证的 `mdbx-storage/state-delta-v1` object payload。commit 关联信封必须附着在最后一个关联 commit 上，所有引用 commit 必须已经可用；commit、稀疏状态行、device head、经过授权的删除、接收批次和 capture 清理必须全部成功，否则整体回滚。fast-forward、divergent 和已有 commit 的延迟 payload 修复使用同一边界。bundle v4 及其压缩表示 v6，以及对应的认证信封 v8/v10，会在同一个外层事务中应用 commit 关联批次与 auxiliary 批次；尾部批次失败时整段回滚，也不会创建用户可见 commit。这些新增能力不会改变 `projects`、`entries`、commit DAG、sync-state v1-v2 或 bundle v1-v6 格式。

CLI 首次同步继续使用有界完整状态；取得 commit/delta 双 checkpoint 后改用 bundle v4 语义。未完成的 v4/v6/v8/v10 传输会在 checkpoint 文件中保存 transfer ID、下一段序号和上一段逻辑 payload 摘要；认证和压缩都不会改变该逻辑 SHA-256 身份。没有 resume 字段的旧 checkpoint JSON 仍可读取。transport-neutral 同步客户端只有在双方同时声明 commit paging、delta paging、bundle v4 与 resume 四项能力时才选择增量语义；支持 paging 的 Hello 不再携带旧的完整 commit ID 向量。zstd 通过独立的 `bundle-zstd-v1` 协商；keyed transport authentication 则通过独立的 `authenticated-bundle-v1` 协商，故意不把它加入原有四项增量必选能力。旧 peer 或能力不完整的 peer 继续使用有界完整状态和 v1-v6 回退。

认证 complete/incremental 信封分别使用 v7/v8；对应的 zstd 表示使用 v9/v10。既有逻辑 payload SHA-256 trailer 之后追加 HMAC-SHA-256，密钥取自 vault integrity subkey；tag 绑定版本化 domain、magic、version、20-byte 有界 header 区和逻辑 payload 摘要。密钥绝不会写入或随 bundle 传输。该机制只能证明信封由某个持有共享 vault key 的一方生成并绑定其元数据，不能识别具体设备；它也不提供传输保密性，不替代内部字段、commit 或 delta 的加密认证，因此 bundle 仍不得视为可公开文件。CLI 默认继续输出 legacy v3/v4，显式 `--compression zstd` 才输出 v5/v6，只有显式 `--authenticated` 才选择 v7-v10；apply 会自动使用已打开 vault 的 key，同时继续读取 v1-v6。

已经实现的 `IncrementalIntegrityRoot` profile 是 additive 的，并与 bundle capability
分开。它在 schema 16 时引入但没有占用新的 schema 序号，只有 verified-unlocked 客户端
显式启用后才惰性创建 metadata、leaf 与 sparse-node 表。建立 profile 时登记 critical extension
`authenticated-state-root-v1`，因此不支持该 profile 的较早 MDBX2 writer 会在可写打开前
拒绝 vault。root 与 sync-delta capture 在同一个外层事务中更新；未 opt-in 的现有和旧代
vault 行为完全不变。O(vault-size) content manifest 仍是精确 schema 检查点；外部 Provider
原始字节和未注册物理扩展表不会被增量 root 默默声称覆盖。

protocol-v2 root exchange 保持 additive：只有配置 `authenticated-state-root-v1` 且双方都
提供有界 checkpoint 时，Hello/HelloAck 才携带该字段。legacy JSON 形状不变，该 capability
也不加入四项强制 incremental-sync capability。checkpoint 的 vault-key HMAC 认证和按 peer
单调 generation/inventory anchor 检查由 storage 而不是 transport parser 执行。客户端把上次
验证的远端值保存在 vault 外；由于不同 replica 的 inventory 顺序可能不同，不要求本地与远端
root hash 相等。

### 3.1 真实发布 Golden Vault 与旧 Reader 边界

仓库同时冻结 `crates/mdbx-storage/test-data/mdbx1-release-1.0.mdbx` 与 `mdbx1-draft-golden.mdbx`。release fixture 由历史 `MDBX1.0` tag（commit `1a43fa9e8e87eebf6d0e1b84543c3291d0b25142`）真实生成；DRAFT fixture 由同一个历史 reader 只修改 `vault_meta.format_version` 后 checkpoint 得到。两份 manifest 都记录不可变 SHA-256、测试专用解锁凭据，以及 project、entry、attachment 和 snapshot 的稳定 ID。

共享迁移回归会分别复制两组原始字节，确认 inspection 不修改文件，再执行 schema 1 到当前 schema 的升级；随后使用原 MDBX1 凭据解锁，逐项验证 project metadata、entry payload、project tag、内联附件内容、snapshot 身份，并比较升级前后的 commit 与 object-version 身份，最后验证重复升级幂等。

另外，实测 `MDBX1.0` CLI 可以从已由当前 reader 升级的副本中列出该 project 和 entry。这只证明 MDBX1 物理兼容投影仍可读取，不表示旧 binary 是安全的 MDBX2 writer：旧代码不会执行 `min_writer_version` 门禁，也无法保存未来语义。vault 声明 `min_writer_version = MDBX-2` 后，旧 binary MUST NOT 再执行写入。

### 3.2 有界导航兼容层

Collection、Object 和 Label 摘要 API 都是 additive 的 reader surface，不改变 schema 字节。`CollectionSummaryRepo` 复用 MDBX1 的 `projects` 表，并可选地 left join `collection_profiles`；因此 MDBX1 Collection 仍然可以被发现，只是没有 Profile 类型和版本。摘要查询不会选择旧 Project summary 或 CollectionProfile payload。

新的导航接口使用固定字段和分页限制。旧行如果超出这些限制，只会在有界摘要接口中返回 resource-limit error；完整 Project、Entry、Label repo 和 FFI 方法继续保持历史行为，显式 repair/export 工具仍可读取它们。CLI 和新客户端默认使用摘要接口，不会静默删除或重定义兼容方法。

附件导航遵循同样的 additive 规则。`AttachmentSummaryRepo` 和 UniFFI 的 `MdbxAttachmentSummary` 方法按 Collection 或 Object 分页 active attachment，单独分页 deleted attachment，并按 ID 返回不读取 chunk/blob payload 的元数据。文件名明文最多 4096 个 UTF-8 bytes，media type 明文最多 512 bytes；密文投影预留共享 128 KiB 信封空间，并在认证解密后再次检查精确明文。超出这些限制的旧附件仍可通过 `AttachmentRepo::get_by_id`、`list_by_project`、`list_by_entry`、`list_deleted` 以及已有完整 FFI 方法读取。`attach list` 和 `attach deleted` 使用有界分页；内容导出、repair 和完整性校验继续使用完整路径。

冲突导航遵循同样的 additive 规则。`ConflictSummaryRepo` 与 UniFFI 的
`MdbxConflictSummary` 只分页 unresolved 冲突元数据，可以按核心冲突对象类型过滤，
并把不透明游标绑定到该过滤条件以及 `created_at DESC, conflict_id DESC` keyset。
有界投影把 `conflicting_fields` JSON 限制为 64 KiB，解码后的字段路径最多 256 项，
单个路径最多 4096 个 UTF-8 bytes；SQL 不会物化超限 JSON。客户端通过
`default_conflict_summary_limits` 发现契约。已有完整冲突读取和 typed resolution 方法
继续作为显式解决、repair 和 export 路径；一个类型过滤器生成的游标不能复用于其他类型。

Snapshot 导航遵循同样的 additive 规则。`SnapshotSummaryRepo::get` 与
`SnapshotSummaryRepo::list` 只返回稳定的 snapshot/base commit 身份、摘要 hash、
创建元数据和 `length(snapshot_ct)`，不会选择、解密、反序列化或验证 `snapshot_ct`。
每页 1 到 200 条，按 `created_at DESC, snapshot_id DESC` 使用 keyset，并返回不超过
4096 bytes、绑定查询的游标。每个必需的 snapshot 元数据文本字段最多 4096 个 UTF-8
bytes。UniFFI 通过 `default_snapshot_summary_limits` 发布同一固定契约；`snapshot list`
保持原有命令和输出格式，但改用有界页面迭代。密文长度只表示存储字节数，不表示 payload
有效或完整性验证结果。既有完整 `SnapshotRepo` 读取、创建、校验和授权恢复方法继续保持
MDBX1 客户端与显式 recovery/repair 的行为；损坏或超大的 payload 只会在调用完整路径时
影响该行。

仅增加或读取摘要不会修改 format marker、schema version、commit、object version、同步字段、snapshot 字段、密文或 key epoch。这同时保持 MDBX1 自动升级承诺和历史 reader 看到的物理兼容投影。

## 4. Schema 演进规则

- 新字段 SHOULD 可空或带安全默认值。
- 新表和新索引 SHOULD 使用 additive migration。
- 已发布字段不得改变既有语义。
- 删除旧字段前 MUST 至少经过一个完整兼容代际。
- 未知非关键字段 SHOULD 被保留。
- 未知关键扩展 MUST 阻止写入。
- 格式版本标记 MUST 是迁移事务的最后一个数据变更。

### 4.1 Epoch 标记字段密文

经过正式解锁的新字段密文使用以下外层格式：

```text
MDBXFE2\0 || epoch_id_len_u16_le || epoch_id_utf8 || MDBXAE1 committed AEAD
```

内层 AEAD 使用对应 epoch 的 record、attachment、metadata 或 history 子密钥。AAD 以长度前缀认证 domain、epoch ID、对象类型、对象 ID 和字段名，修改外层 epoch ID、移动密文到其他字段或修改内层密文都会导致认证失败。

reader MUST 继续读取旧的 `MDBXAE1` committed envelope 和更早的 nonce envelope。首次产生 `MDBXFE2` 密文时，storage core MUST 在同一数据库事务中登记关键扩展 `field-key-epochs-v1`。支持该扩展的 reader 可以继续打开；较早的 MDBX2 writer 会把该标识视为未知关键扩展并拒绝可写打开，从而避免使用旧密钥规则覆盖新字段。

## 5. MDBX2 首批一致性修复

MDBX2 同时收紧以下实现边界：

- snapshot 创建和恢复进入原子事务。
- snapshot 恢复重建精确 active set；快照后新增对象保留历史行，但通过 tombstone 离开 active set。
- snapshot 恢复为所有受影响对象写入统一 causal head 和 object version。
- verified-unlocked snapshot 使用 `MDBXSN2` payload profile 和版本化 HMAC descriptor，
  绑定 base commit、创建时间与设备元数据。既有 64 位 SHA snapshot 保持原 AAD 和恢复语义；
  首次写入新 profile 时注册 `snapshot-record-auth-v1`，旧 MDBX2 reader 会因未知 critical
  extension 安全拒绝，而不是静默套用旧解密规则。
- Commit2 增加幂等 operation ID、结构化变更摘要、稳定分支身份、合并后的 vector clock 和
  原子设备序列分配，不重写任何历史 commit。
- 离线 bundle v3 增加显式 payload 长度和有界解码；MDBX2 继续转换读取没有 operation
  元数据的 v1 bundle，并继续读取携带 operation 元数据的 v2 bundle。
- 离线 bundle v4 增加成对增量 inventory、经过认证的 base 校验、有界可恢复 segment，以及 commit 与 auxiliary 的原子应用，同时保留 v1-v3 reader。
- 离线 bundle v5/v6 分别为 complete v3 和 incremental v4 逻辑 payload 增加可选、有界的 zstd 表示；trailer 认证未压缩 bincode payload，压缩与未压缩声明长度分别受限，裁剪构建继续支持 v1-v4 并明确拒绝 v5/v6。
- 离线 bundle v7/v8 分别为 complete 与 incremental payload 增加 keyed HMAC-SHA-256 信封，v9/v10 将同一认证契约与 zstd 组合。认证 trailer 绑定版本化有界 header 和逻辑 payload 摘要，增量 resume 摘要保持稳定；reader 继续支持 v1-v6，而 v7-v10 在没有匹配 vault integrity key 时必须 fail closed。
- 新 snapshot 明确携带 project tags 和 attachment chunks；旧快照缺少这些字段时不清空现有兼容数据。
- Tiga global/project/entry mutation 的 commit、对象更新、head 和 object version 原子提交。
- Tiga2 增加版本化策略、精确例外和类型化安全审计；策略状态、覆盖、例外和审计进入同步状态。
- 产生数据变更的 Tiga 授权在同一事务中记录 Commit2 `operation_id` 与 `commit_id`；拒绝决定和不产生数据库变更的敏感操作没有 commit 关联。
- 新审计记录保存作出决定时的 Tiga 策略版本，以及生效策略序列化内容的 SHA-256 指纹。策略修改前先固定该证据，因此审计记录描述的是授权所采用的策略。
- 审计同步认证新增字段，验证 operation 与 commit 指向同一条 `commit_operations` 记录，并拒绝改写已有事件。MDBX1 与早期 MDBX2 审计记录保留空的关联和证据字段。
- 早期 `MDBX-2/schema 2` 自动执行 `schema 2 -> schema 3`，不改变格式代际。
- 迁移不得修改现有 KDF 参数或 wrapped vault key；凭据相关升级只能在用户成功认证后执行。
- CLI bundle apply 统一使用 `mdbx-storage::SyncApplyRepo`，不再维护独立 SQL 同步实现。
- storage 可以原子接收有界、认证的状态 delta，保存收到的批次以便继续转发，保留稀疏 delta 未涉及的本地 tombstone，并单调合并 device revocation。同一个 commit 不得混用完整状态与 delta，既有完整状态仍保持兼容。
- complete-state 未知扩展可经过 decode、事务 apply、数据库保存、collect 和重新编码而不丢失；incoming 中存在的键原子更新，缺失键保留本地值。
- 可移植备份使用 SQLite online backup，完整包含已提交的 WAL 页面；发布前校验 SQLite 完整性、MDBX metadata 与 `vault_id`，转换为无需旁路文件的单文件，并拒绝替换任何已有目标文件。

## 6. 验收要求

每次新增代际迁移至少必须测试：

- 上一代真实磁盘 vault 自动升级。
- draft/历史兼容格式升级。
- 重复升级幂等。
- 未知格式和关键扩展拒绝写入。
- 迁移失败不改变原格式标记。
- 升级前后对象数量、稳定 ID、commit 和附件内容一致。
- 新建 vault 直接使用当前代际。

## 7. 客户端与核心职责

- 客户端负责升级提示、备份位置、进度、平台能力证据和整改交互。
- `mdbx-storage` 负责格式识别、确定性映射、事务、回滚、幂等、策略例外和结果校验。
- 客户端不得自行复制 MDBX1 到 MDBX2 的字段转换逻辑。
- “兼容上一代”表示新代可以读取并升级上一代；不承诺旧二进制理解 MDBX2 新策略并安全写入。

### 7.1 稳定分支身份

`branch_id` 是分支的不可变内部身份。`branch_name` 是可修改的显示属性，同时作为 schema 6 之前接口的兼容选择条件。多个分支可以使用相同显示名称。

新 operation 元数据同时认证稳定 ID 与提交时的显示名称。基于 ID 的请求只选择一个分支，显示名称修改后仍可按原 operation ID 重试。仅提供名称的请求只在该名称唯一时生效。旧 operation 行的 ID 为空，继续使用 V1 请求哈希与完整性算法；迁移过程不得为这些行补写 ID。

同步双方均提供 ID 时按 ID 比较分支；任一方缺少 ID 时按旧名称比较。相同 ID 与不同名称表示同一分支，相同名称与不同 ID 表示不同分支。旧同步消息缺少 `branch_id` 时仍可反序列化。

### 7.2 客户端可控迁移 API

兼容默认路径仍然支持 `VaultConnection::open` 自动升级，保证旧客户端或简单调用方不会因为代际差异无法打开 vault。需要在 UI 中先提示、备份并取得用户同意的客户端，应先调用：

- `mdbx_storage::migration::inspect_migration_path`
- UniFFI：`inspect_vault_migration`

检查结果是只读的，包含当前 format/schema、最低读写代际、是否需要升级以及未知 critical extension 标志。需要升级时，先调用：

- `mdbx_storage::backup::BackupService::create_portable_copy_path`
- UniFFI：`create_portable_backup`

备份发布且取得用户确认后调用：

- `mdbx_storage::migration::upgrade_path`
- UniFFI：`upgrade_vault`

转换仍由 storage core 的同一事务迁移器执行；客户端只负责备份、提示、进度和整改 UI。open 与显式升级会在建立可写连接前重复执行只读身份预检；路径缺失、未初始化的 SQLite 数据库与未知 critical extension 均会被拒绝，文件内容保持不变。

### 7.3 可移植备份 API

客户端在建立可写连接前，通过 Rust `BackupService::create_portable_copy_path` 或 UniFFI 顶层函数 `create_portable_backup` 创建备份。返回信息包含 vault 身份、保留的格式、保留的 schema 与文件大小。参考 CLI 的 `mdbx backup <output>` 使用同一只读接口，无需解锁凭据。

`MdbxVault.create_backup` 继续作为已经打开 vault 的日常备份接口。文件路径接口承担迁移前归档：它接受受支持的 MDBX1、MDBX1 draft 与 MDBX2 文件，包含已经提交的 WAL 页面，并在结果中保留源格式 metadata。

可移植备份是完整的加密 vault 文件，保留源 vault 的解锁方式，不解密业务记录。vault 内部 snapshot 仍是逻辑恢复点，sync bundle 仍是增量传输文件。源库采用 WAL 时，仅复制 SQLite 主文件会遗漏仍位于 WAL 的已提交页面。

目标主文件、`-wal` 与 `-shm` 名称共同构成发布目标集合，任一文件已经存在时均保留原内容并返回错误。storage 在发布单文件结果前执行完整性、与源一致的 MDBX metadata 和 vault 身份校验。

### 7.4 客户端 operation 写入 API

移动端和桌面端应先通过 UniFFI `MdbxVault::list_branches` 获取稳定 ID，再通过 `execute_write_operation_on_branch` 提交指定分支的多步编辑。原有 `execute_write_operation` 继续作为 main 分支兼容入口。接口只接受有限的类型化命令：创建项目、创建、更新、删除、恢复、移动条目；创建和更新同时接受 MDBX1 类型与 namespaced ObjectTypeId，接口不暴露 SQL。

每个创建命令必须携带客户端生成的稳定 UUID。客户端在首次调用和重试时复用同一 `operation_id` 与完整命令列表。storage 会将命令作为一个事务和一个 commit 执行；已完成 operation 的重试只返回 commit ID 与请求中的对象 ID，不再次执行写入。相同 operation ID 搭配不同命令内容会被拒绝，任一命令失败会回滚整个批次。

原有单项 FFI 方法继续保留，作为 MDBX1 兼容投影和简单调用入口；需要把一个用户动作合并为单一历史节点时，应使用 operation API。

Native Rust Adapter 使用 `mdbx_storage::repo::OperationCoordinator` 以及同一套有界 `WriteCommand` 契约。UniFFI facade 只负责 record 转换、vault 句柄管理和错误映射，不再维护第二套写入协议。`OperationCoordinator::prepare` 可以在客户端取得写锁之前完成；`execute` 与 `execute_prepared` 继续保证通用命令和组合操作共享一个事务。

原有 operation 方法现在施加默认资源契约：256 条命令、单条 JSON payload 1 MiB、全部 JSON payload 8 MiB、序列化 intent 16 MiB。新增 `default_write_operation_limits` 和 `*_with_limits` 接口允许新客户端选择更小或受控的更大限制，但不能超过 4,096 条命令、单条 16 MiB、总 payload 64 MiB 与 intent 128 MiB 的硬上限。限制检查和流式 intent 哈希发生在 vault 写锁及事务之前；超限不会创建对象、commit 或推进 branch head。旧客户端方法签名和默认 main 分支行为不变。

### 7.5 对象摘要与披露读取 API

原有 UniFFI `get_object`、`list_objects` 和 `list_entries` 的签名及完整 payload 行为保持不变，供 MDBX1 和已经生成绑定的客户端继续使用。MDBX2 客户端通过新增 `get_object_summary(object_id)` 获取 metadata-only 详情，通过 `list_object_summaries` 获取有界 collection 页面。

deleted 导航同样只增加接口。新客户端可以调用
`list_deleted_object_summaries(collection_id, object_type_id, page_size,
cursor)` 获取单个 Collection 的 tombstone，或调用
`list_all_deleted_object_summaries` 获取全局 deleted 页面。这些方法返回相同的
无 payload 摘要，绝不选择 `payload_ct`；页大小为 1 到 200，游标绑定查询状态、
Collection 范围和 ObjectTypeId，active 游标不能复用于 deleted 查询。CLI
`entry deleted` 使用全局有界方法；`EntryRepo::list_deleted*` 与
`list_deleted_entries` 仍保留完整 payload 兼容行为，供显式 repair/export 使用。

显式明文动作调用 `reveal_object` 或 `reveal_object_with_device_context`。返回的 `MdbxObjectDisclosureResult` 只有在 `Allow` 或 `AllowWithConstraints` 时才包含 `object`，并始终包含类型化 Tiga 授权决定。会话缺失/过期和策略拒绝因此仍是客户端可处理的状态，但不会返回 payload，也不会让损坏密文先于拒绝决定报错。已删除对象和非授权类 storage 失败继续返回错误。

既有 reveal 方法签名保持不变，并使用 8 MiB 默认明文上限。新增 `default_object_disclosure_limits`、`reveal_object_with_limits` 和 `reveal_object_with_device_context_and_limits`，允许新客户端选择更小或受控的更大资源配置，但不能超过 64 MiB 硬上限。策略允许后，storage 先通过 SQL 长度投影拒绝明显超限的 payload 密文，再认证解密并复核实际明文长度；Tiga 拒绝仍先于这些 payload 检查。MDBX1 大 payload 的数据库字节不会被迁移或改写，原有完整 payload 兼容 API 也保持行为不变。

通用元数据选择采用同样的 additive 兼容规则。新客户端使用 `get_object_relation_summary`、`list_object_relation_summaries_from`、`list_object_relation_summaries_to`、`get_object_label_summary`、`list_object_label_summaries`，以及按 object/label 双向分页的 assignment summary 接口。这些 payload-free 页面每页限制为 1 到 200 项，并使用绑定查询条件的不透明游标。原有完整 relation、label 和 assignment 方法继续供已生成客户端及显式 payload 消费者使用，签名和行为不变。

显式 relation/label payload 访问同样只增加新接口。`reveal_object_relation*` 返回按 source、target 排列的两个 Entry 决定，只有两端都允许时才包含 relation；`reveal_object_label*` 返回 collection Project 决定，只有允许时才包含 label。`default_object_metadata_disclosure_limits` 与显式 limits 变体使用 8 MiB 默认值和 64 MiB 硬上限。relation 的复合审计行共享可空的无 commit operation ID。实现没有增加 Relation/Label Tiga scope、schema row、sync 字段或数据库重写，旧完整 metadata 方法仍保持精确行为。

### 7.6 Commit 历史读取 API

原有 `MdbxCommitHistoryItem`、`list_commit_history` 与 `get_commit_history` 保持字段布局和方法语义，供上一版生成的客户端继续使用。MDBX2 客户端通过 `MdbxCommitHistoryItemV2`、`list_commit_history_v2` 与 `get_commit_history_v2` 读取可空的稳定分支 ID。返回内容包含 operation 信息、分支、parent、类型化变更摘要和兼容标志；没有 operation 元数据的 MDBX1 commit 仍以兼容摘要显示。游标只能由 storage 返回值继续使用，客户端不得按 offset 重建分页。

operation 摘要中的 action 使用 `create`、`update`、`delete`、`restore`、`move` 或兼容用的 `change`；fields 使用稳定的领域字段名。repository 产生的泛化 `change` 只作为占位，不会覆盖客户端已经提供的具体摘要。

### 7.7 Tiga 审计读取 API

原有 UniFFI `MdbxSecurityAuditEvent` 记录与 `list_security_audit_events` 方法保持不变，供上一版生成的客户端继续使用。MDBX2 客户端通过 `MdbxSecurityAuditEventV2` 与 `list_security_audit_events_v2` 读取可空的 operation ID、commit ID、策略版本和策略指纹。

只要 `commit_id` 存在，`operation_id` 就必须存在且两者必须匹配同一条 `commit_operations` 记录。storage 在本地读取和同步导入时执行该验证。两者均为空表示该记录来自 schema 5 之前，或者本次授权没有产生数据库 commit。

### 7.8 密钥 epoch 轮换 API

MDBX2 客户端通过 Rust `KeyEpochService::rotate_authorized` 或 UniFFI `MdbxVault.rotate_key_epoch` 请求轮换。返回的 `previous_epoch_id`、`active_epoch_id`、`commit_id` 与 `rotated_at` 是一次轮换的稳定结果。该调用新增接口，不改变任何 MDBX1 兼容方法的签名。

轮换不属于普通 operation 幂等重试。客户端遇到响应未知时，应先查询 commit history 或 `MdbxSecurityAuditEventV2` 的 commit 关联；再次调用会创建新的 epoch 和 commit。同步 payload 的 key epoch 字段保持可选，旧 payload 继续读取并保留本地 epoch 状态。

### 7.9 同步状态资源限制

完整 `SyncStatePayload` 具有独立的资源契约。默认 Rust API 接受不超过 96 MiB 的编码状态和 250,000 行；桌面调用方可以通过 `SyncStateLimits` 提高限制，但硬上限为 512 MiB 和 2,000,000 行。输出端在读取数据库行后使用有界序列化器，输入端在 JSON 解码前检查字节数，结构解析后再检查逻辑行数。

`mdbx-storage/state-v1` 和旧 `mdbx-cli/state-v1` 必须同时使用 object ID `state` 与匹配的 associated data。错误身份、超限状态或超限 apply 会使完整同步事务回滚；既有 state-v1、state-v2 和旧 CLI 字段保持兼容读取。未知 ObjectPayload 类型继续由普通 opaque payload 处理。

### 7.10 外部 rollback anchor

内部 header HMAC 只能认证当前打开的数据库，不能发现整个文件被替换成较旧但内部自洽的
副本。MDBX2 storage core 因此提供 `RollbackAnchorService::issue/verify`，CLI 提供
`mdbx anchor create/verify`，UniFFI 提供 `MdbxVault.create_rollback_anchor` 与
`verify_rollback_anchor`。token 是有界不透明字节，客户端不得解析、拼接或自行生成。

客户端必须把 token 持久化在 vault 之外，并遵守以下顺序：成功解锁并确认 mutation 或同步
已经持久化后签发 token；下次打开时先验证上一个 token，再信任数据库状态；验证成功后才用
新签发的 token 原子替换客户端保存的 token。相同或更高的 commit/delta append-only
inventory 水位通过，锚定行缺失、被改写、跨 vault、截断、超限或认证失败均拒绝。锚点不
改变 MDBX1/MDBX1-DRAFT 迁移语义，也不提供可信外部时钟、可用性保证或整个 vault 的统一
authentication root。客户端丢失 token 时，storage 无法推断此前状态；未来若要裁剪 delta
inventory，必须先版本化 anchor 语义，不能静默删除锚定行。

### 7.11 精确 vault content manifest

需要确认当前数据库内容而不是仅确认 append-only 水位时，客户端可以调用
`VaultContentManifestService::issue/verify`、CLI 的
`mdbx content-manifest create/verify`，或 UniFFI 的
`MdbxVault.create_content_manifest` / `verify_content_manifest`。清单 token 是有界不透明
字节，覆盖非内部 schema 对象、列定义和所有表行值，未知扩展表与附加列不会被静默遗漏。

新 token 使用 manifest profile v2。V2 通过 SQLite `table_xinfo` 纳入 generated/hidden
columns，并为 nullable primary key 或 collation 相等的行增加带类型的规范排序；header
认证、vault identity 和内容哈希位于同一个 read snapshot。验证端仍按原 v1 算法接受已经
签发的 v1 token，不能把旧 token 静默重解释成 v2。CLI 与 UniFFI 边界继续传递不透明字节，
客户端无需修改方法签名或迁移 token 存储格式。

这是显式的 O(vault-size) 精确检查点：客户端应在备份发布、迁移后、跨设备交接或怀疑文件
被直接改写时使用，而不应把它放进每次小 mutation 的提交路径。验证成功后，任何合法写入
都会使旧清单失效，客户端必须重新签发并替换外部 token。它不包含外部 Blob Provider
本体、操作系统状态或可用性保证，也不改变 MDBX1/MDBX1-DRAFT 的读取和迁移语义。

### 7.12 构建能力发现

裁剪构建通过 Rust `CapabilitySet::build_manifest`、UniFFI
`mdbx_build_capability_manifest` 和不依赖 vault 的 `mdbx capabilities --json` 暴露
`mdbx-build-capabilities-v1`。规范排序的报告分别列出 storage 模块与 sync 协议支持，并同时
给出已启用 ID 和编译时省略的已知可选 ID。

该报告属于进程元数据，不是 vault 元数据。它不会写入 MDBX1/MDBX2，不改变迁移或 wire
格式，也不会打开命令行选择的路径。它不能替代进程内 Collection Adapter 注册、可写打开时
的 critical extension 校验或 peer 间 Hello/HelloAck 协商。不查询该报告的旧客户端行为
完全保持不变。

### 7.13 进程内 Extension Profile Registry

每个已打开的 `VaultConnection` 最多可以注册 256 个规范化 `ExtensionProfile`，用于描述当前
进程实际加载的领域 Adapter。描述符把一个扩展命名空间映射到 CollectionTypeId、自定义
ObjectTypeId、RelationKindId、写入门禁 capability、可选索引、导入/导出 Adapter 和展示
提示。完全相同的重复注册保持幂等；内容变化、所有权冲突或批量替换失败时，旧 registry 原子
保留，不会暴露部分状态。

Registry 属于进程元数据，重新打开 vault 后为空。它不进入 schema、snapshot、同步状态或
critical extension。注册 Profile 不会激活其中的 capability；客户端仍需单独调用
`set_extension_capabilities` 声明当前可执行的 Adapter 能力。两者都不会授予 raw SQL、加密
密钥或 Tiga 权限。

若已注册描述符拥有某个 Collection 类型，后续用户写入会使用该描述符与当前 capability 集合
复核已存 Collection Profile。既有或同步得到的未知数据仍可通过不透明兼容路径读取；Adapter
缺失或被裁剪时不会改写或删除其数据，也不会阻止同步、备份、恢复。因此，从未使用 registry
的 MDBX1 与早期 MDBX2 客户端保持原行为。

### 7.14 二进制 KDBX Adapter

二进制 KDBX 互操作属于可选 Adapter，不改变 MDBX1/MDBX2 schema、迁移、同步或 JSON
桥接。独立的 `kdbx-binary-import` 与 `kdbx-binary-export` feature 分别公布
`mdbx.storage.kdbx-binary-import` 与 `mdbx.storage.kdbx-binary-export`。
原有 `kdbx-import`、`kdbx-export`、`import-kdbx-json` 和 `export-kdbx-json`
标识继续表示 JSON 中间格式。

导入接受 KDBX3 和 KDBX4。密码派生前先限制加密源字节，并检查 KDBX3 AES rounds，或
KDBX4 AES/Argon2 的内存、迭代次数与并行度。解密后检查条目数、字段数、附件数、分组深度、
单项字节和投影总字节；完整投影通过后才允许 `KdbxImporter` 修改 vault。错误凭据、畸形头、
不支持的 KDF 和资源超限均保持 MDBX 原状态。

Repository 落库层明确保留两种兼容契约。原有 `KdbxImporter::import_entries` 在源码和行为上
继续兼容：各源条目独立尝试，有效兄弟条目可以保留，后续 entry 或 attachment 失败会记录为
warning。该接口属于 best-effort 旧桥接，不承诺单条原子性或整批原子性。

新接入使用 `KdbxImporter::import_entries_atomic`。该方法在打开事务前构造完整导入计划，并对
每个源字段和附件字节按固定字段顺序、长度分帧与独立 domain 计算 SHA-256 intent 摘要。随后
所有 project、Login/Note entry、附件元数据与内容、历史、对象版本、head 和同步 delta 都在
同一个 `CommitContext::run_operation` 中完成；任一失败都会回滚整个批次。相同 operation ID
与相同输入的重试直接返回既有 commit，不会重复创建对象；相同 ID 对应不同输入时拒绝执行。
JSON 与二进制 CLI 都只在解析或解密成功后生成新的 operation UUID；无论导入多少对象，一次
成功命令只产生一个 Commit2 commit。

导出固定写入 KDBX4，使用 Argon2id、64 MiB 内存、三次迭代和两个 lane。CLI 密码来自隐藏
交互输入或标准输入中的一行有界 UTF-8，不提供密码参数。输出先写入同目录临时文件并完成同步，
再执行无覆盖发布；已有目标文件保持原内容。

Adapter 在格式可表达时保留标题、用户名、密码、URL、备注、OTP 值、自定义字段、附件、分组
路径、UUID、内置图标和时间戳。KeePass history、autotype、自定义图标、回收站状态、插件字段
和 passkey 插件结构不属于完整保留范围。当前选用的 `keepass` 解析器会在 Adapter 检查投影
明文上限前完成内部 gzip 解压。加密源大小和最终返回投影均有上限，但 gzip 解压期间的瞬时内存
峰值没有独立上限。需要严格限制不可信文件处理进程内存的服务必须增加进程隔离，或改用支持有界
流式解压的解析器。

### 7.15 有界 Steam mafile Adapter

Steam `mafile` 的解释属于可独立裁剪的 Rust Adapter，位于
`crates/mdbx-adapter-steam`。它声明进程内的 `com.monica.steam`
ExtensionProfile、`com.monica.steam.mafile` ObjectTypeId、
`com.monica.steam.store` 写入 capability 以及同一命名空间下的导入/导出
feature。该 crate 不依赖 storage、sync、CLI、FFI、Android 或网络；裁剪它的
构建仍能把已有对象作为不透明密文保存。

客户端 MUST 把 mafile 当作不可信 JSON，并在创建通用写操作前使用 Adapter 的
有界解析器。默认上限为：输入 1 MiB、深度 32、聚合字段 512、每个数组元素
512、聚合节点 8,192、单个字符串/键 64 KiB、字符串/键聚合 1 MiB。硬上限分别为
8 MiB、64、4,096、4,096、65,536、1 MiB 和 8 MiB；客户端可以降低但不能关闭这些
限制。解析前先检查输入字节数；重复对象键直接失败；错误文本不包含源字段值。

Adapter 保留未知字段并输出确定性的规范 JSON，因此旧客户端可以原样保存新 Steam
生产者新增的字段。稳定对象摘要使用命名空间隔离、长度分帧的 SHA-256，输入是规范化
的无符号 64 位 SteamID 与去除首尾空白但保留大小写的 serial number；Generic Object
投影使用摘要前 128 bit，并设置 RFC variant 与自定义 UUID version 8。mafile 可以带
自己的 SteamID；缺失时客户端可提供已认证账号的 SteamID，若两者不一致则拒绝。摘要
与 UUID 都只是 opaque identity，不能替代加密、认证或 Steam 凭据。

Adapter 的 Debug 和错误接口不得输出 payload 值。客户端必须把解析文档和规范字节留在
受保护的进程内存中，并在持久化前交给通用认证加密路径。多份 mafile 导入仍应由一个有界
`CommitOperation` 表示；Adapter 自身不创建表、schema 列、同步字段、commit，也不获得
Tiga 权限。移除 Adapter 不得删除、改写、重分类或阻止不透明读取、同步、备份、恢复和
诊断。

可选的 `crates/mdbx-adapter-steam-storage` bridge 把纯 Adapter 映射到已有通用 storage
API。它默认 feature 为空，只依赖纯 Adapter 与 `mdbx-storage` core。prepare 会先校验整批、
按稳定 UUID 排序、拒绝重复 identity，并且只通过不含 payload 的 Object summary 读取已有
状态；输出仍是已有 create、update 或 restore-then-update 通用命令，不增加 Steam 专用表、
列、snapshot 字段、sync 字段、critical extension 或密钥格式。

bridge 默认每批最多 128 份文档、源字节聚合 8 MiB，硬上限为 2,048 份和 64 MiB。完全
相同的 prepared plan 重试会幂等返回原 commit；根据已变化 vault 状态重新规划属于新动作。
输入中缺失的对象不会自动删除。Profile 注册与 `com.monica.steam.store` 激活继续分离，
capability 缺失会回滚完整 operation。

MDBX1 与 MDBX1-DRAFT 文件升级仍由 storage core 迁移器负责，bridge 不把兼容转换转移给
客户端。裁剪 bridge 或同时裁剪两个 Steam crate，都不会破坏已有加密通用行和旧兼容投影。

### 7.16 Commit Kind 精确往返

commit kind 是经过认证的 commit 表示的一部分，不是单纯的 UI 展示提示。MDBX reader 必须
精确保留以下稳定值：`change`、`merge`、`snapshot`、`key-rotation`、`move`、
`copy`、`restore`、`multi`。遇到无法识别的数据库或 bundle 值时，不得降级为
`change`，因为重写后的值已经不再描述生成该行的原认证 commit。

核心枚举冻结原有 `change`、`merge`、`snapshot`、`key-rotation` 的二进制顺序，
扩展值只追加在其后。因此既有 bincode discriminant 与旧 bundle fixture 保持不变。
本修复不增加 schema 列、format version 字段、bundle version 字段或数据库迁移。

storage history 与 CLI bundle loader 统一调用核心严格解析器。已知值必须原样保留；未知值
在导出、历史认证或 apply 有机会重新解释 commit 之前明确报错。bundle decoder 对不支持的
枚举 discriminant 同样拒绝。新 reader 继续读取旧 bundle。会把扩展数据库值降级的修复前
reader 不得导出或 apply 这段历史，必须升级 reader，因为不存在安全的向下转换。

UniFFI history 继续把 `commit_kind` 作为精确字符串返回。原生客户端可以把已知值映射为
本地化标签，但必须保留原始值用于诊断，绝不能把 UI fallback 写回 MDBX。

### 7.17 Change Scope 精确往返与本地写入验证

change scope 是经过认证的 commit 数据。当前 reader 精确保留 `project`、`entry`、
`attachment`、`object-relation`、`object-label`、`object-label-assignment`、
`vault-meta`、`key-epoch`、`multi`、`snapshot`、`branch`。自动 Snapshot 清理已经
产生 `snapshot`；`branch` 对应墓碑保留路径使用的既有分支对象族。

`Snapshot` 与 `Branch` 只追加在核心原九个枚举变体之后，因此旧 bincode discriminant
0 到 8 保持不变。本修复不增加 schema、format version、bundle version 或迁移字段；
新 reader 继续解码旧 bundle 与数据库行。

ChangeScope 严格解析由核心统一拥有。history 验证、CLI 数据库加载、同步测试和 bundle
序列化都复用该精确契约。未知值必须 fail closed；`multi` 绝不能充当未知值 fallback。
会把 `snapshot`、`branch` 或未来 scope 转为 `multi` 的修复前 CLI 不得导出这段历史，
因为转换后的 commit 无法保持原认证 identity。

CommitContext 还会在直接 CommitOperation 打开写事务前验证 commit kind 与 change scope。
活动 operation 中聚合的 repository commit 也会先验证分类，再修改聚合元数据。公开的字符串
CommitOperation 保持源码兼容，但不能再写入当前核心无法读取或传输的值。

### 7.18 版本化 Operation 请求身份

新的 operation 行复用现有 `request_hash` BLOB 保存版本化
`OperationRequestIdentity`。版本 1 固定为 40 字节：`MDBXORI1` 后接现有 32 字节规范请求
摘要。storage 在解析稳定分支后、执行任何 repository mutation 前生成该身份。活动 operation
在聚合最终 parents、变更对象、change scope、message 和加密摘要时始终保留这份初始身份。

operation 完整性认证完整编码值。重试处理先验证完整性，再比较请求元数据。版本化身份始终
精确匹配，因此调用方即使没有提供 `intent_hash`，相同 operation ID 搭配变化后的内容也会
被拒绝。未知长度以及前缀未知的 40 字节值同样 fail closed。

现有未标记 32 字节值保持原样，无需迁移。直接 operation 重试以及带显式 intent 的 legacy
请求继续精确比较。没有显式 intent 的旧聚合 operation 保留早期重试语义，因为旧 writer
可能已经用最终聚合摘要覆盖初始请求摘要。迁移无法在保持认证历史不变的前提下恢复已经丢失
的初始输入。

operation metadata DTO 与 bundle version 均保持不变。CLI export、同步 bundle、incoming
apply 和数据库重新载入都会精确保留两种字节表示。MDBX1 与 MDBX1-DRAFT 没有 operation
metadata，其读取和升级行为不受影响。当前 reader 接受旧行；可靠重试当前 40 字节身份时
需要使用当前 storage core。

### 7.19 已存在 Commit 的精确重放

同步可能在 commit 行已经存在后收到对应的 state delta 或其他传输 payload。应用迟到 payload
前，storage 会比较 incoming 与本地行的设备、序列、精确 kind 和 scope、加密变更 ID、
vector clock、message、创建时间、完整性标签和规范 parent 成员。相同 commit ID 下任一值
发生变化时，会在状态 mutation 前拒绝。

首次接收 commit 时仍使用活动连接 keyring 重新计算完整性标签。已存在记录的重放则把
incoming 字节和标签与本地已经接受的身份精确比较。该区分可以保留无法从连接当前状态重建
原验证方式的历史 commit，同时阻止同一 ID 出现第二种认证含义。

legacy bundle 中的 operation metadata 继续保持可选。commit 首次接收时缺少该信息，后续
可以补入原始认证 metadata。一旦记录存在，operation ID 与 commit ID 构成一对一映射；
operation kind、分支 ID 与名称、加密摘要、请求身份、创建时间和完整性标签都必须完全一致。
旧重放省略 metadata 时保留本地行。

本次修改不增加数据库或 bundle version。MDBX1、MDBX1-DRAFT 与 bundle v1 继续省略
operation metadata。精确 commit 的迟到 payload 修复继续保持幂等；重新生成 commit 或
operation metadata 的 peer 必须恢复原始认证值后才能继续同步。

### 7.20 Incoming Commit 结构可表示性

合法完整性标签能够证明 incoming commit 字节真实，但首次写入本地时还必须能够由当前存储
模型精确表示。首条 SQL mutation 前，storage 检查 `local_seq` 是否位于 SQLite 有符号
64 位 INTEGER 范围内、vector clock 是否能够解码为值为无符号 64 位整数的 JSON object，
以及 parent 列表中是否存在重复 ID。

commit 完整性会排序 parent，因此 parent 顺序保持兼容。重复成员没有兼容投影：
`commit_parents` 每对 ID 只能保存一行，静默去重会改变认证 parent 数量。畸形 clock 文本无法
保留，因为创建本地 child commit 时会把已存 clock 作为 map 使用。超过 `i64::MAX` 的 sequence
也不能通过环绕转换保存，否则设备顺序会改变。

legacy `{}` vector clock 对 MDBX1 与迁移历史继续有效。预检不要求现代 self-device clock
条目，也不重写历史因果信息。已存在 commit 的精确重放继续采用 7.19 节规则，比较本地已经
接受的字节，不把新的首次插入规则追溯应用到旧记录。

本次修改不增加 schema、format、bundle 或 DTO version。当前 producer 已经生成可表示值。
无效新 commit 会在 commit inventory、parent、sequence floor、payload、tombstone、device head
或 branch head 发生变化前拒绝。

### 7.21 Device Head 设备序列单调性

serialized commit 导入与 state-delta 导入现在共用一条 storage 规则。head 引用的 commit
必须由所声明设备创作。较高 `local_seq` 可以跨 branch 推进 head；迟到的较低序列仍会保存并
继续转发，但不能覆盖较新的 head。同一 commit 重放保持幂等，观察时间保留较晚值，device
revocation 不能被同步清除。

既有 `(device_id, local_seq)` 唯一索引已经规定每个设备序列只能对应一个 commit 身份。新的
同步预检会在 INSERT 前检查该身份；序列已经属于另一 commit ID 时返回 validation error。
重新编号不属于兼容转换，因为序列是经过认证的 commit 身份与因果顺序的一部分。

health verification 会报告引用缺失 commit、引用其他设备 commit 或低于同设备后续已接受
序列的 device head。检查只报告 legacy 状态，不重写历史。

本次修改不增加 schema、migration、format、bundle、DTO 或 capability version。MDBX1 与
MDBX1-DRAFT 的迟到 commit 仍是合法输入，当前 receiver 会保留较新的 head。接收端需要使用
当前 storage core 才能获得与交付顺序无关的合并行为；wire 表示保持不变，因此旧 receiver
继续使用其历史 apply 规则。

### 7.22 Tombstone Acknowledgement 因果单调性

既有 schema 8 acknowledgement 行现在由 storage core 的单一运行时合并规则管理。
`observed_commit_id` 必须引用可用 commit；tombstone 带有 `delete_commit_id` 时，该 commit
必须等于删除 commit 或位于其后代。删除之前的 commit 以及与删除并发的 commit 均不能作为
观察证明。

对于同一 tombstone 与设备，后代证据覆盖祖先证据，祖先证据不能覆盖后代证据。两个有效并发
证明先完成因果比较，再选择较大的 `(acknowledged_at, observed_commit_id)`，因此接收顺序
不会改变最终保存行。无论选择哪一个 commit，确认时间都保留较晚值。

完整状态与 state delta 在本地缺少 tombstone 或 observed commit 时继续跳过对应
acknowledgement。两项引用均存在后，非因果证明会拒绝整个事务。`delete_commit_id` 为
`NULL` 的 tombstone 保留 legacy 表示，只要求 observed commit 可用。health verification
会报告违反这些规则的保存行，同时保持只读，不会重写历史。

本次修改不增加表、列、索引、migration、format generation、同步 DTO、state version、
bundle version 或 capability bit。schema 8 migration backfill 保持原样，MDBX1 与
MDBX1-DRAFT 继续使用同一 storage-core 升级序列。旧 reader 可以解析相同数据库行与 wire
payload，但仍采用早期覆盖规则；因果验证和与接收顺序无关的合并需要当前 storage core。
