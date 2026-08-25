# MDBX 安全规范

版本：`MDBX-2 / Tiga policy 2`

本文定义 Tiga 模型、必须采用的密码学能力、密钥层级、内存处理规则。

## 1. 安全目标

MDBX 必须防护以下风险：

- 文件离线失窃
- 不可信云存储
- 加密记录被篡改
- 正常工作流中明文长期驻留
- 默认 KDF 参数过弱

MDBX 不能完全隐藏文件大小、访问时间、同步活动这类元数据。

## 2. 必需算法

推荐基线：

- 密码 KDF：`Argon2id`
- 认证加密：`XChaCha20-Poly1305` 或 `AES-256-GCM`
- 密钥派生：`HKDF-SHA-256`
- 哈希：`SHA-256`
- 文件名或标识符 MAC：`HMAC-SHA-256`

推荐默认组合：

- `Argon2id + HKDF-SHA-256 + XChaCha20-Poly1305`

当前 committed AEAD envelope：

- 新写入的密文值必须使用 `MDBXAE1\0 || commitment || nonce || ciphertext`
- `commitment` 是基于 envelope 上下文、associated data、nonce 和加密载荷计算的 HMAC-SHA-256 commitment
- 解密器必须继续接受 committed envelope 出现前写入的 legacy `nonce || ciphertext`
- 加密器不得再写入新的 legacy envelope

随机 key 或 nonce 生成失败时，操作必须失败。实现不得退回到全零 key、确定性 nonce 或任何占位秘密。

## 3. Tiga 运行时安全策略

Tiga 不是单纯的 KDF 或加密等级选择器。MDBX 必须暴露三档用户可选安全模式，并由每个模式生成版本化的完整运行时策略。策略至少覆盖解锁因素、会话寿命、秘密显示、剪贴板、导出、附件、恢复、设备保证和审计。

存储值与 API 名称如下：

- `power`
  - 对应最高防护模式

- `multi`
  - 对应平衡默认模式

- `sky`
  - 对应灵活便携但仍然安全的模式

兼容性显示名可以使用 `Power Type`、`Multi Type`、`Sky Type`，但存储和 API 值最好使用 `power`、`multi`、`sky`。

### 3.1 Power

目标：

- 尽可能增强离线暴力破解阻力与本地泄露防护

典型影响：

- 最高 Argon2id 成本
- 更短的秘密驻留时间
- 更少的明文缓存
- 导出、复制等行为的警告更严格
- 附件处理默认更保守
- 为完整满足 Power 策略，最好配置密码 + 安全密钥组合解锁
- 独立密码或 PIN 解锁不应满足完整 Power 策略，除非用户明确接受这是降级

### 3.2 Multi

目标：

- 作为推荐默认值，在安全与可用性之间平衡
- 适合网盘同步，同时保留清晰恢复路径

典型影响：

- 强度较高的 Argon2id 参数
- 允许适度缓存
- 在低风险场景启用一定便利性能力
- 应建议用户添加安全密钥
- 除非用户明确另选更严格策略，否则必须保留强密码等便携恢复路径

### 3.3 Sky

目标：

- 灵活、便携、恢复优先，适合网盘同步和多设备日常使用
- Sky 不是不安全模式

典型影响：

- 较低但仍然合格的 KDF 下限
- 更宽松的缓存策略
- 更快的解锁与日常操作
- 可以提供密码、PIN 包装、平台凭据包装或安全密钥解锁
- 所有解锁路径仍必须使用 MDBX 的 KDF、AEAD、keyring 和日志规则

### 3.4 Tiga2 默认运行时策略

| 策略 | Sky | Multi | Power |
|---|---:|---:|---:|
| 空闲锁定 | 30 分钟 | 10 分钟 | 2 分钟 |
| 会话最长时间 | 12 小时 | 2 小时 | 15 分钟 |
| 认证新鲜窗口 | 15 分钟 | 5 分钟 | 60 秒 |
| 剪贴板清除 | 60 秒 | 30 秒 | 10 秒 |
| 进入后台锁定 | 否 | 是 | 是 |
| 导出/打印 | 新鲜认证后允许 | 新鲜认证后允许 | 默认拒绝 |
| 附件明文临时文件 | 允许受保护临时文件 | 默认不允许 | 不允许 |
| 最低因素 | 1 | 1 | 密码 + 安全密钥组合 |
| 审计 | 安全变更 | 敏感操作 | 所有授权决定 |

默认值属于 `policy_version = 2`，未来改变默认值必须提升策略版本并提供迁移，不得让同一个策略版本在不同客户端产生不同语义。

## 4. Tiga 模式作用范围

Tiga 模式必须支持：

- 全局默认模式
- 可选的 project 级覆盖
- 可选的 entry 级覆盖，用于特别敏感的秘密

Tiga2 的有效策略按以下顺序解析：vault 基线、project 覆盖、entry 覆盖、设备保证、会话保证和本次操作风险。

- project/entry 模式只影响访问该资源所需的保证，不改变 vault 级恢复和全局解锁语义。
- 更窄范围默认只能逐字段加强父策略，不能依靠较低模式整体替换父策略。
- 降低保护必须使用绑定具体 scope 和具体覆盖内容的显式例外；例外必须有原因、审计记录，并可设置过期时间。
- 旧版中已经存在的弱覆盖在迁移时保持原行为，但 vault 标记为 `remediation-required`。
- 并发同步的策略冲突逐字段采用更严格结果；审计事件和例外 ID 不允许被远端改写。

### 4.1 Extension Profile 的非授权边界

已注册的 `ExtensionProfile` 只是对当前进程中某个已加载 Adapter 所提供语义面的声明，不得被
视为认证或授权。注册不能授予 raw SQL、vault 或密钥访问、明文披露、Tiga 例外、critical
extension 接受权或同步 peer 信任。

Profile 注册与 `set_extension_capabilities` 彼此独立。前者描述 Adapter 能理解什么，后者声明
当前进程实际可执行哪些写入门禁能力；两者都不能绕过 repository 校验或逐操作 Tiga 授权。
匹配的 registry 描述符可以收紧用户写入校验，但描述符缺失时不得删除、改写或重新解释未知
密文，也不得阻止不透明读取、同步、备份、恢复。

### 4.2 可选 Steam mafile 的明文边界

可选的 `mdbx-adapter-steam` crate 在通用 Object Module 加密前把 mafile 视为不可信明文。
输入字节 MUST 在 JSON 反序列化前检查。默认解析上限为输入 1 MiB、深度 32、聚合对象字段
512、每个数组 512 项、聚合节点 8,192、单个字符串/键 64 KiB、字符串/键聚合 1 MiB；硬
上限分别为 8 MiB、64、4,096、4,096、65,536、1 MiB 和 8 MiB。客户端 MAY 降低上限，
但 MUST NOT 关闭边界。重复对象键 MUST 失败，不能采用 last-wins 语义。

Adapter 在规范输出中保留未知字段，但不得让这些字段变成可搜索、可执行或有权限的内容。
解析值和规范字节在进入通用认证加密写入路径前 MUST 留在受保护进程内存中；不得进入日志、
审计行、operation 元数据、同步状态或持久缓存。Adapter 的 Debug 和错误接口 MUST 只包含
结构/资源类别与静态字段标签，不能输出 mafile 值或 secret。稳定对象 ID 是对规范化身份组成
部分的命名空间隔离哈希，不是 secret，也不能替代认证。

可选的 `mdbx-adapter-steam-storage` bridge MUST 保持同一明文边界。source、request、
prepared plan 和 error 的 Debug 输出都不得泄漏 mafile 字节、SteamID、serial number、账号名、
token 或 secret 字段。整批输入必须在逐份解析前有界：默认最多 128 份文档、源字节聚合
8 MiB，硬上限为 2,048 份和 64 MiB；单文档限制与通用 write limits 继续作为独立防线。

规划阶段 MUST 只通过不含 payload 的 summary 读取已有对象，并在 mutation 前检查 Collection
归属、精确 ObjectTypeId 与 payload schema version。capability 拒绝或后续任一命令失败都
MUST 回滚整批。执行结果不确定时只能复用同一份内存 prepared plan；客户端不得把 plan
序列化到磁盘或日志，也不得把基于已变化 vault 状态的重新规划当作同一次重试。输入缺失对象
MUST NOT 触发隐式删除。

## 5. 必需用户警告

当用户切换到更弱模式时，UI 必须：

- 清楚说明新风险画像
- 要求显式确认
- 明确展示哪些保护会变弱
- 完成新鲜认证，并在需要时完成额外因素认证
- 创建可审计的精确策略例外，而不是仅保存一个确认框状态

## 5.1 逐操作授权

敏感操作不得直接依据模式枚举分支。策略引擎必须返回以下稳定结果之一：

- `allow`
- `allow-with-constraints`
- `require-fresh-authentication`
- `require-additional-factor`
- `deny`

客户端必须执行返回的约束，例如定时清除剪贴板、排除剪贴板历史、防截屏或禁止明文落盘。防截屏属于平台能力范围内的约束；平台不支持时不得宣称已经实现绝对防截屏。

### 5.2 授权先于对象明文

MDBX2 必须把“选择对象”和“披露对象秘密”视为两个不同操作：

- vault 重开后的顶层导航必须使用 `CollectionSummary`；摘要只包含 collection 身份、标题、可选 Profile 类型/版本、group/icon 引用、展示状态、附件计数、head、删除状态和更新时间，不得查询 Project summary 或 CollectionProfile payload。
- 列表、选择和默认详情只读取 `ObjectSummary` 所需的对象 ID、collection、类型、标题、schema 版本、head commit、删除状态和更新时间，不得查询或解密 `payload_ct`。
- relation、label 与 label assignment 的导航也必须使用有界摘要页；relation/label 摘要不得查询 payload 密文，损坏 payload 不能阻断关系图或分类页面。
- Collection/Object 标题明文最多 64 KiB，ObjectLabel 名称最多 512 bytes，Collection group/icon 引用最多 4096 UTF-8 bytes。加密展示字段必须先由 SQL 查询长度，并只在“明文上限 + 128 KiB 兼容信封预留”内通过 `CASE` 返回 BLOB；认证解密后再次按实际明文字节数复核。明文引用也必须按 UTF-8 字节计数，并在转换为宿主语言字符串前执行条件投影。
- Tiga 策略解析只读取 project/entry 的策略上下文列，不得为了得到 scope、覆盖模式、删除状态或 object clock 而先解密标题、摘要或 payload。
- 持久化的 Tiga 覆盖值无法解析时必须失败关闭，不得把未知值静默当作“没有覆盖”。
- 对象秘密只能通过统一披露边界执行 `RevealSecret` 授权；只有 `allow` 或 `allow-with-constraints` 可以进入 payload 解密。
- relation payload 必须按 source、target 顺序同时求值两个 Entry scope，任一端不允许都不得披露；两个决定及其原因、约束必须分别保留，不能压平为一个结果。
- label payload 必须继承所属 collection 的 Project scope；label assignment 没有加密 payload，不需要披露接口。不能仅为 relation/label 披露就扩展持久化 `TigaScope` 枚举。
- 已删除对象不得披露 payload。
- 允许结果必须连同 `AuthorizationDecision` 返回，客户端必须执行其中的约束。对象 storage 拒绝保持授权错误，绑定层可转换为类型化空 payload；relation/label 拒绝直接返回全部 scoped decision 和空 payload。任何路径都不能让损坏密文先于拒绝决定报错。
- 统一披露边界的默认明文 payload 上限为 8 MiB；资源配置只能在 1 byte 到 64 MiB 硬上限之间选择，不能通过自定义配置关闭边界。
- 策略允许并确认对象未删除后，storage 必须先用 SQL `length(payload_ct)` 检查密文长度，不得先把 BLOB 载入 Rust。密文门禁允许在明文上限之外预留 128 KiB，以覆盖现有 AEAD、key epoch 信封和兼容演进。
- 通过密文门禁后仍必须完成认证解密，并按实际明文字节数再次执行上限；超限属于 `ResourceLimit`，不得把它伪装成密码学损坏。

scope 路由、全部策略求值、允许后的读取和成功/拒绝审计必须位于同一个 immediate transaction，避免策略、endpoint 与对象在判定和解密之间发生切换。relation 的两个审计决定必须共享一个无 commit 的 operation ID。拒绝决定必须在不检查删除状态、不读取 payload 长度和密文字段的情况下完成。活动会话只能在明文成功返回后续期。

`EntryRepo::get_by_id` 等完整记录 API 为 MDBX1 和既有 MDBX2 调用方继续保留，不改变其返回完整明文记录的兼容语义；新客户端的列表、默认详情和策略路径不得把这些兼容 API 当作披露边界。

Adapter payload 迁移同时属于明文披露与管理类修改。创建计划前必须针对所属 Collection 的 Project scope 执行 `TigaOperation::MigratePayload`，并且授权必须先于源 payload 长度查询、BLOB 载入和解密。成功计划的审计使用短生命周期 `plan_id` 关联，不引用 commit。执行时必须再次授权同一 scope；策略求值、计划绑定复核、全部对象更新、一条幂等 `CommitOperation`、关联该 commit 的安全审计和 sync-delta 物化必须位于同一个 immediate transaction。拒绝、过期计划或畸形输出不得留下对象或 commit 修改。源/目标明文不得进入审计行、operation 元数据、同步状态、日志或持久缓存。

对象 payload 用于有界结构化数据。超大邮件正文、原始 MIME/EML、网页归档和文件内容必须转入 attachment 或 encrypted blob provider 的分块/流式明文边界，并由对象保存稳定引用。当前整块 AEAD payload 不得被描述成流式解密。

## 6. 密钥层级

合规实现最好使用分层密钥结构：

- 用户输入的秘密因子
- 主解锁密钥
- vault 密钥
- 用途子密钥
- 记录或对象密钥

推荐链路：

- 由 `Argon2id` 导出解锁密钥
- 由 `HKDF` 派生 vault key
- 再为元数据、记录、附件、历史分别派生子密钥

## 7. 记录认证

MDBX 必须认证以下内容：

- 会影响解密的 vault 头部元数据
- project 记录
- entry 记录
- attachment 元数据
- attachment 内容或 chunk 内容
- 历史记录
- snapshot 记录

密文被移动到错误上下文后必须认证失败。

MDBX2 schema 16 使用 vault 完整性子密钥和
`mdbx-vault-header-hmac-sha256-v1` 认证 vault header。长度分隔的 HMAC
覆盖 vault 身份、格式/schema 版本、最低 reader/writer 版本、创建/更新时间、
默认 Tiga 模式、active key epoch、兼容与关键扩展标志、Tiga 策略版本和合规状态。
数据库触发器会在任一受保护列变化时使既有标签进入 `invalidated`；合法 storage
core mutation 必须在同一事务内刷新标签。已经建立认证的 header 不得降级回仅供
迁移使用的 `pending`。

MDBX1 和较早 MDBX2 在 additive migration 时还没有已验证 vault key，因此先进入
`pending`；首次成功解锁时建立标签。后续解锁必须在附加 Keyring 前验证标签，health
check 必须把 invalidated 或 tag mismatch 报告为错误。锁定状态的 health check 可以
检查标签形状，但只能提示需要解锁后做 keyed verification。storage core 另外通过 CLI
和 UniFFI 提供有界、不透明的 HMAC 外部 rollback anchor。客户端在成功解锁并完成持久化
mutation 或同步后，必须把 token 保存在 vault 之外；重新打开时必须先验证上一个 token，
验证成功并签发新 token 后才能替换客户端保存的 token。相等或前进的 append-only
commit/sync-delta inventory head 可以通过；锚定行缺失或被改写必须按 rollback 拒绝。
token 的保留、备份和替换策略由客户端负责。客户端丢失 token 时数据库无法检测；anchor
也不是可信时钟、可用性保证或整个 vault 的 authentication root。

verified-unlocked 状态创建 snapshot 时启用 critical extension
`snapshot-record-auth-v1`。`MDBXSN2` ciphertext profile 把字段 AAD 从旧 `payload` 改为
`payload-v2`，因此不能只把 descriptor 换成重新计算的普通 hash 就完成降级。版本化
descriptor 保留公开 SHA-256 密文摘要，并用 integrity subkey 对 vault、snapshot identity、
base commit、摘要、时间和创建设备做 HMAC。restore、health 与 snapshot Blob 引用扫描必须
共用同一个 verifier。既有 64 位十六进制 snapshot 保留历史 SHA + AEAD 边界；需要元数据
认证时应新建替代 snapshot，不能原地伪装升级旧行。

对于需要精确检查点的场景，MDBX2 还通过 storage、CLI 和 UniFFI 提供有界、不透明的
vault content manifest。它在同一个 SQLite read snapshot 中哈希非内部主 schema、列定义
和带类型的行值，未知扩展表与附加列也会自动纳入，再用 vault integrity subkey 认证摘要。
客户端必须把清单保存在 vault 之外，并在信任精确重开状态前验证；任何合法 mutation 都会
使旧清单失效，客户端必须重新签发。该操作是显式的 O(vault-size) 检查点，不挂在日常
commit hook 上。外部 Blob Provider 内容、操作系统状态和可用性不在清单边界内。

ADR-0022 定义了已经实现的 opt-in `IncrementalIntegrityRoot`。它以认证逻辑叶子和固定
16 层 sparse Merkle tree 覆盖同步逻辑状态，并通过事务级 sync-delta seam 维护。显式启用
时才惰性创建 root 表，并在不改变 schema 16 的前提下登记 critical extension
`authenticated-state-root-v1`。日常修改只更新命中的 bucket 与路径；rebuild 和完整 verify
仍是显式有界操作。解锁、health 与写事务 finalization 对过期或篡改的 established root
均 fail closed。它不是外部 Provider 原始字节或任意未注册 SQLite 表的证明；显式
manifest 与 rollback anchor 继续分别承担其既定范围内的完整性职责。

当双方协商 `authenticated-state-root-v1` 时，protocol-v2 Hello/HelloAck 可以携带有界
peer checkpoint。storage 使用独立 HMAC domain 签名，并把本地 vault ID 与 schema version
作为不随 wire 发送的隐式上下文；wire 值只含 profile、generation、leaf count、root hash、
commit/delta anchor 与 authentication tag。客户端 MUST 使用已打开 vault 验证，并按 peer
identity 把上次可信值持久化到 vault 外。generation 下降、anchor 下降、同 generation 字段变化、
foreign-vault checkpoint 或 tag 无效都必须 fail closed。由于 inventory 顺序可能是本地的，
root 相等不能作为两个 replica 收敛的强制条件。

新签发清单使用 profile v2。V2 通过 `table_xinfo` 同时纳入普通列、generated column 与
hidden column；即使 nullable primary key 或声明 collation 不能形成全序，也会按 SQLite
类型与规范值稳定排序。header 验证、vault identity 和内容哈希共享同一个 read snapshot。
reader 必须继续按 v1 算法验证已认证的 v1 token；token version 选择对应 profile，禁止把
旧 token 静默按当前算法重新解释。CLI 与 UniFFI 仍把 token 视为不透明字节，因此公开方法
签名不变。

## 8. 附件安全规则

附件属于一等敏感数据。

因此 MDBX 必须：

- 认证附件元数据
- 认证附件内容
- 保证附件重命名只改元数据，不影响无关内容
- 在重建附件时先验证内容 hash

如果支持外部引用附件，那么外部内容也必须和数据库元数据建立完整性绑定。

只读附件元数据导航是独立的有界安全面。文件名最多 4096 个 UTF-8 bytes，media type 最多 512 bytes。摘要查询必须在物化 BLOB 前检查展示字段密文长度并条件投影，使用共享的 128 KiB 信封预留；绝不能读取 attachment chunk 内容或 external URI 密文。认证解密后，storage 还必须复核精确明文字节长度和 UTF-8 有效性。因此损坏 chunk 或不可用的外部 provider 不能把元数据列表变成内容披露或拒绝服务路径。完整附件读取和完整性校验 API 仍是独立、显式授权的操作。

## 9. 内存安全规则

MDBX2 必须让长期持有的 Keyring 字段使用离开作用域后自动清零的 buffer。
Argon2id/HKDF 输出、解包得到的 vault key，以及解包得到的数据 epoch key，
必须在产生时立即进入该 buffer。复制 Keyring 密钥字段时，复制结果也必须
保留自动清零所有权。这保证 MDBX storage core 自己拥有的正常 Rust 生命周期；
不宣称能够擦除客户端主动复制、操作系统 crash dump、硬件或密码库内部，或
storage core 不拥有的 allocator 副本。

实现最好做到：

- 尽量缩短明文在内存中的停留时间
- 不为列表、策略解析、权限检查或默认详情预先解密对象 payload
- 在可行时擦除敏感 buffer
- 不记录秘密日志
- 尽量避免 crash dump 中残留原始秘密
- 对大附件采用流式处理，避免整份明文长期驻留内存

## 10. 解锁因子

MDBX 应明确区分“用户可见的解锁方式”和“底层实际参与加密的密钥模型”。

用户看到的解锁方式可以是：

- `PIN`
- `密码`
- `安全密钥`
- 平台支持下的生物识别封装

其中，底层真正保护 vault 的秘密材料仍应由主解锁密钥、vault 密钥及其派生链负责。

MDBX 最好支持以下组合：

- 主密码
- 密钥文件
- 安全密钥或硬件保护密钥材料
- 平台支持下的生物识别封装
- 密码 + 安全密钥组合解锁，表示为 `password_security_key`

### 10.1 PIN 解锁

`PIN` 可以作为用户可见的快速解锁方式。

但 `PIN` 不应直接等同于真正的 vault 主秘密。
更合适的做法是：

- `PIN` 用于解锁本地受保护的包装密钥
- 包装密钥再去解锁真正的 vault 密钥材料

这样可以避免把短 PIN 直接暴露为唯一安全边界。

### 10.2 密码解锁

`密码` 是 MDBX 必须重点支持的核心解锁方式。

密码输入必须支持 Unicode。
这意味着：

- 必须支持中文密码
- 不得假设密码只包含 ASCII
- 规范与实现都应明确字符编码与规范化策略

推荐要求：

- 密码在进入 KDF 前使用稳定的 Unicode 字符串处理流程
- 实现必须避免因为平台差异导致同一中文密码在不同设备上无法解锁

### 10.3 安全密钥解锁

`安全密钥` 应作为受支持的解锁方式之一。

安全密钥可以用于：

- 提供硬件保护的解锁因子
- 包装或释放本地保存的密钥材料
- 与密码或 PIN 组合形成更强的解锁方案

安全密钥不应被描述成“必须联网才能工作的云端依赖”。
MDBX 仍然必须保持本地优先。

支持硬件密钥本身并不会让网盘存储变得不安全或不可用。是否便携取决于 vault 配置了哪些解锁方式：

- `password` 和设计正确的便携恢复方式可以在新设备上打开网盘同步过来的 vault。
- 仅配置 `security_key` 的 vault 需要同一把硬件密钥或等价平台凭据。
- `password_security_key` 能增强离线爆破阻力，但会有意降低独立便携性。

客户端在禁用所有便携解锁路径前，必须向用户说明这些恢复后果。

生物识别最好只是包裹更强的底层秘密，而不是取代真正的 vault 加密模型。

## 10.4 最低解锁能力要求

一个面向最终用户的 MDBX 实现最好至少支持以下三种解锁方式中的两种，完整实现应支持全部三种：

- `PIN`
- `密码`
- `安全密钥`
- `password_security_key`

如果实现声明支持 `密码`，则其密码输入必须支持中文。

## 11. 最低参数哲学

MDBX 必须定义最低安全底线。
即使是 `sky`，也必须仍然是有意义的安全配置，而不是玩具参数。

具体参数表最好单独发布并带版本号。

## 12. 审计与日志规则

Tiga2 安全审计必须记录类型化操作、结果、scope、会话 ID、设备 ID、原因码、执行约束和例外 ID。审计记录不得包含秘密载荷，并必须通过 vault 完整性子密钥认证；读取或同步应用带标签的策略、例外和审计记录时必须重新校验标签。同步收到相同 event ID 的不同内容时必须拒绝。

`MigratePayload` 的计划披露审计必须通过 `plan_id` 关联；成功执行审计必须关联唯一迁移 commit。对完全相同且已完成的计划重试不得再生成第二条成功审计。

日志绝不能包含：

- 明文密码
- TOTP seed
- Passkey 私钥材料
- 解密后的附件名，除非用户显式导出诊断信息

## 13. 恢复与轮换

MDBX 最好支持：

- 密钥轮换
- 备份校验
- snapshot 校验
- 附件完整性扫描

轮换过程中必须保持旧记录可读，直到迁移完成。

数据密钥 epoch 轮换必须通过 `RotateKeyEpoch` Tiga 管理操作授权。成功轮换必须在同一事务中生成独立随机 epoch key、在 vault root key 下使用绑定 vault 与 epoch 身份的 AAD 包装、退休旧 active epoch、激活新 epoch、更新 `vault_meta`、创建 `key-rotation` / `key-epoch` commit，并把安全审计记录关联到该 commit。拒绝和失败必须保持原 active epoch、wrapper 集合与 commit 状态。

同步状态必须携带 active epoch、全部 active 和 retired wrapper 以及由 vault 完整性子密钥认证的状态标签。改变 epoch 状态前必须验证标签和 wrapper；并发轮换必须保留双方密钥材料并确定性选择一个 active epoch。发送端必须先传播 rotation commit 与 key epoch state，再传播使用新 epoch 产生的字段密文。

## 14. 拒收规则

以下安全设计不符合规范：

- 没有认证加密
- 附件完整性规则未定义
- 新写入密文不使用 committed AEAD envelope
- 没有关键安全迁移说明却故意移除读取 legacy 有效密文或旧 vault 的能力
- RNG 失败后退回到全零 key、确定性 nonce 或占位秘密
- 切换到更弱模式时没有显式用户确认
- 默认长期存储明文秘密却没有充分理由
- 把生物识别当作唯一真实秘密
- 把 Extension Profile 注册或 capability 激活当成访问密钥、绕过 Tiga 或直接写 raw SQL 的权限
