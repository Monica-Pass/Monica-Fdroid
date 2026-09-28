# Monica MDBX 跨端存储与兼容契约

日期：2026-09-28。对象：Android、CLI、桌面、浏览器、HarmonyOS 的实现者及辅助开发的 AI。

本文件把现有 MDBX 核心约束、Monica 已发布的业务字段和后续客户端的兼容要求放在一起。文中的「必须」是接入要求；测试结果单独记录，不能把要求当作已通过的验收。新增端应优先复用 MDBX 引擎，不再建立独立且互不兼容的密码 JSON 格式。

## 1. 先区分四种版本

| 名称 | 当前代码中的含义 | 客户端应如何处理 |
| --- | --- | --- |
| MDBX3 | 原生 Rust 运行时；Android 打包版本为 `3.0.0-alpha.1` | 读取实际打包的 provenance，不能从产品名推断文件格式 |
| `MDBX-2` | 当前可写文件格式 | 由引擎打开、校验、迁移，不能自行修改标记 |
| `schema_version` | 引擎的物理 SQLite schema；参考核心兼容规范 | 由引擎报告，不在客户端硬编码升级 SQL |
| `payload_schema_version` | 某一种对象的业务数据版本 | 由对应 Adapter 解释，独立于文件和引擎版本 |

这里的 Monica MDBX 使用 SQLite 兼容存储结构和 Rust 加密/历史/同步核心，**不是 libmdbx 的键值库文件格式**。Android 中 `Mdbx2Repository`、`RUST_MDBX2` 和 `mdbx_ffi` 的旧名字也不代表正在使用旧运行时。

当前 Android 运行时依据 `Monica for Android/mdbx-engine/MDBX3_RUNTIME_PROVENANCE.json`；其中列出了源码提交、附加补丁、ABI 和文件哈希。该清单是判断实际运行代码的依据，不能用工作区另一个 MDBX checkout 的 HEAD 代替。

## 2. 唯一持久化模型

```text
Vault
  Collection（兼容物理表 projects）
    ObjectRecord（兼容物理表 entries）
      object_id / collection_id / object_type_id
      payload_schema_version / authenticated encrypted payload
      head commit / version / tombstone
    Attachment / encrypted Blob
  ObjectRelation / ObjectLabel / label assignments
  commit DAG / key epochs / policies / sync state / recovery snapshots
```

引擎负责稳定身份、加密认证、提交历史、冲突、删除、恢复、密钥轮换和同步。业务 Adapter 只解释 payload 和提供界面。Room、浏览器 IndexedDB、搜索索引、列表缓存都是可重建投影，不得成为另一个数据库真相来源。

- 对象移动到别的 Collection 时保留 `object_id`；重命名、编辑、同步和重开也不更换身份。
- Collection 的原生层级由 `group_id` 表达。不要只靠名称、路径文本或 Android 本地 `category_id` 重建层级。
- 删除用引擎 tombstone；不要从表里直接删除行，不要根据列表未加载到某条记录推断用户删除。
- 附件使用引擎的 Attachment/Blob API，保留所属对象、身份和内容。大文件不塞进业务 JSON 或本地文件路径字段。
- 不要以 `INSERT OR REPLACE`、直接 SQL、重建 vault 或整条「转换成密码」来实现编辑。

## 3. 类型必须可扩展

核心的 `ObjectTypeId` 支持历史短类型和自定义类型。类型区分大小写，必须精确保留。

| 原生类型 | 现有用途 | 本次 Android 展示方式 |
| --- | --- | --- |
| `login` | 密码及现有登录子类型 | 现有密码界面 |
| `note`, `totp`, `card` | 笔记、验证器、卡片 | 对应原有界面 |
| `document-ref`, `billing-address`, `payment-account` | 文档引用、地址、支付账户 | 对应原有界面 |
| `passkey` | 通行密钥 | 原有通行密钥路径 |
| `api-token` | CLI/API 令牌 | 已有原生 API Token 界面，保留类型 |
| `steam-mafile` | Steam 文件 | 已有 Steam 路径 |
| `identity`, `ssh-key` 或未注册的新类型 | 核心能保存，但当前 Android 未必有对应原生 Adapter | 密码列表中的只读通用项目及字段详情 |

新扩展推荐使用有命名空间的类型，例如 `com.example.recovery-kit`。具体长度和允许字符必须使用引擎的校验器，不要在各端复制出不同规则。新增类型不要求先让所有旧客户端升级。

注意当前 FFI 的历史 `createEntry/updateEntry` 便捷 API 仍可能只接受旧枚举类型。新类型使用支持 ObjectTypeId 的通用对象接口，或经当前运行时验证的 `executeWriteOperation` 配合 `MdbxWriteCommand.CreateEntry/UpdateEntry`，不能因方法名字类似就假定支持范围相同。原生写入可能规范化 JSON 对象键顺序；无损要求是字段、类型和值完整保留，不承诺排版空格或键顺序不变。

### 未知类型的强制行为

1. 正常发现对象；不能 `when(type) { ... else -> skip }` 而让用户看不到数据。
2. 可以把它投影到「密码/项目列表」以复用导航。**此投影不是把原生 `object_type_id` 改成 `login`。**
3. 显示标题、原始类型、所属数据库/类别；详情通过已授权的原生 disclosure API 读取 payload。
4. JSON 的每个顶层字段可查看；嵌套对象、数组、布尔值、数字、Unicode、空字符串和 `null` 都要保留。不要只挑 `username/password/notes` 三个字段。
5. 字段值默认隐藏。未知字段可能是 token、私钥或恢复码，不能仅凭名字猜测「非敏感」。大数据超限时明确报告，不截断后再覆盖保存。
6. 未知类型的原始 payload 不放进密码字段，不参与自动填充、密码审计、批量改密或自动「孤儿数据救回」写入。
7. 没有能保证无损的 Adapter 时只读；不能显示可保存的普通密码表单。写入层也要保护，不能只禁用一个按钮。
8. 云同步、快照、原生备份继续保留未知对象。界面适配缺失不能成为删除未知数据的理由。
9. 原生对象被删除、移动或改类型后，更新/移除旧投影，不能将缓存中的副本自动写回为 login。

如果原始数据不是 JSON 对象，通用查看器展示完整原始值或原文；不擅自补字段重写它。若安全策略、解锁状态或资源限制阻止读取，应显示明确不可读状态，不能当成空记录。

### 示例：CLI 先增加一个类型

下面是 API 层的说明性对象，不是新增的磁盘封装格式；元数据由引擎保存，只有 `payload` 内容交给该类型的 Adapter。

```json
{
  "object_id": "a8d1d94a-d07f-4b55-bd31-b0193266fafd",
  "collection_id": "6e4986cb-3fbf-43e2-a859-f75ee13101f1",
  "object_type_id": "com.example.recovery-kit",
  "payload_schema_version": 1,
  "title": "恢复资料",
  "payload": {
    "schema": "com.example.recovery-kit.v1",
    "account": "synthetic-user",
    "recovery_codes": ["synthetic-one", "示例二", null],
    "metadata": {"enabled": false, "counter": 9007199254740993, "empty": ""}
  }
}
```

旧 Android 应在密码列表显示「恢复资料」，详情显示 `com.example.recovery-kit` 和全部三个顶层字段。读取、重开和同步后，上述对象身份、类型、版本、字段和值保持不变。JSON 数字不能先经过 JavaScript `Number` 而把大整数舍入。

## 4. 已知类型也必须保留未来字段

未知类型和「已知类型里新增字段」是两个独立的兼容问题。

- 读取器忽略无法解释的附加字段时，写入器仍必须保留它们。`ignoreUnknownKeys = true` 只解决读取异常，**不保证写回无损**。
- 编辑必须基于重新读取的原对象及其版本/提交身份，修改本 Adapter 拥有的字段。避免仅从本地实体重新构造一个精简 JSON 并覆盖原对象。
- 区分字段缺失、`null`、空字符串、`false` 和 `0`。明确清空密码时，`password_plain: ""` 优先于历史 `password` 字段，不能用 `ifBlank` 回退到旧密码。
- 嵌套未知字段必须保留；列表项新增的元数据也必须保留。数组匹配应采用格式规定的稳定 ID，不能假设下标永远稳定。
- 不认识更高 `payload_schema_version` 或不理解关键字段时进入通用只读查看，不能把版本降回本机版本。
- 真正需要迁移 payload 时，用核心的「生成计划 → Adapter 转换 → 验证并原子执行」接口。计划绑定对象、摘要、版本、Collection、分支及能力；过时计划必须失败。

这一节也是旧 Adapter 的审查要求。不能仅因本次未知类型测试通过，就宣称所有历史密码、笔记、卡片 Adapter 的任意扩展字段写回已经验证。

## 5. Monica 现有 payload 兼容字段

接入现有数据时先兼容这些实际字段，不要立即改名。

| 类型 | 关键实际字段 | 兼容说明 |
| --- | --- | --- |
| Android `login` | `kind: "password"`, `username`, `website`, `password_plain`, `notes`, `custom_fields`, `login_type` | `kind` 是 payload 旧标记，不能覆盖原生 type 的权威性 |
| Android 通用字段 | `monica_entry_id`, `room_id`, `mdbx_folder_id`, `category_id`, `sort_order` | `room_id`、`category_id` 是本地实现信息，不是跨设备身份 |
| Android 自定义字段 | `title`, `value`, `is_protected`, `sort_order` | `is_protected` 控制显示，不代替原生静态加密；字段标题和空格不要擅自归一化 |
| Android secure item | `kind`, `item_data`, `notes`, `image_paths`, `bound_password_entry_id` | 有些现有 `item_data` 是序列化 JSON 字符串，不能偷偷变为另一种形状 |
| Android 绑定关系 | `bound_note_entry_id`, `bound_password_entry_id` | 使用稳定 entry ID；Room ID 仅用于本机缓存 |
| CLI `api-token` | `schema: "monica.gateway.credential.v1"`, `provider`, `api_base`, `note`, `token` | token 在加密 payload 中；`note` 是明确的公开上下文，不能用于存放 token |

CLI API Token 必须同时匹配原生 `api-token` 和已知 `schema` 才能解释为 Gateway 连接。其他 `api-token` payload 不能自动获得 Gateway 授权或被改成 login。

Android 的历史逻辑 ID（例如 `password:<room-id>`）通过兼容映射变为原生 UUID。本次未知类型读取直接保留原生 UUID；已知类型旧 Adapter 的 UUID 写回仍需逐项核查，不应据此认为所有类型均已完成兼容。跨端契约要求已有原生 UUID 保持原样。新的端直接使用原生 ID；不要复制别的端的 Room 主键来创建对象。Android 默认 Collection 是依据 vault ID 确定性生成的 root；不能使用随机 root 替换已有 Collection。具体算法以 `Mdbx2VaultSessionExecutor.rootProjectId` 和 CLI 对应兼容实现为准。

## 6. 安全与能力边界

- 通过引擎创建/打开 vault、校验格式与凭据；不得自行实现加密封装、绕过 Tiga 或手动更新 header 完整性字段。
- 默认用分页 Collection/Object summary 浏览列表。选中对象后调用 `revealObjectWithLimits` 或同义受控接口，由引擎先授权再产生明文。
- 解锁不等于允许一切操作。遵守 disclosure 结果里的策略限制、会话生命周期和应用现有截屏/剪贴板规则；锁定/后台时清理详情明文状态。
- `critical_extensions`、`min_reader_version` 和 `min_writer_version` 决定格式门禁。未知关键扩展必须拒绝不安全写入；未知普通业务类型不等于关键扩展。
- ExtensionProfile 注册只声明当前进程载入的 Adapter，不自动授予写能力、不自动接受关键扩展；每次打开后重新注册。能力激活与权限判断是另外的步骤。
- `sync_state_extensions` 的 opaque JSON 本身不自动加密。敏感扩展状态必须先由生产者封装为认证密文；不能把 token 明文放进「未知扩展」。
- 不在日志、测试报告、进程参数、仓库或 AI 工具输出中写入真实凭据与解密 payload。测试用合成数据；明确授权的真实云诊断只操作隔离副本。

## 7. 同步和备份

Android 当前网盘增量布局是：

```text
vault.mdbx                         # 引擎生成的 bootstrap
vault.mdbx.sync/
  streams/<device>/<generation>/segments/<sequence>-<digest>.mdbxsync
  blobs/<encrypted-content-id>...
```

精确文件名、Blob 分层和规范化规则以 `MdbxRemoteSyncPaths` 为准。不要根据这个示意自己拼另一个协议。

1. 使用引擎导出 bootstrap、增量和便携备份。不要复制正在运行的 SQLite 主文件，忽略 WAL 或把 JSON 导出当成完整 MDBX 备份。
2. 一个 checkpoint 是 commit inventory 与 delta inventory 的配对水位；续传还绑定 transfer ID、序号和上一段摘要。只保存一个时间戳无法代替它。
3. 远端增量不可变，上传使用 create-only；重复上传校验原有内容。覆盖快照方案必须使用 ETag/条件写，冲突时不能强制覆盖。
4. 目录排序不是因果顺序。依赖未齐的增量保持待处理；只有其他增量成功推进后才有理由重试它。
5. apply 必须原子回滚。认证、错误 vault ID、损坏、外键错误或缺段都不能被当成成功；失败不能推进接收游标。
6. 只有增量及引用 Blob 全部确认后，才承认对应同步状态。中断后重开应幂等恢复，不重复创建对象。
7. HTTP 429/503 要遵守服务器和共享本地 backoff；重试必须有次数和等待上限，取消要立即传播。401/403、冲突、认证失败不靠盲目重试修复。
8. 不能因为已下载过某代历史目录就永久跳过它，除非协议明确证明完整结束；否则会漏掉延迟到达的分段。
9. CLI 当前 Gateway 的整库条件快照同步，与 Android 增量目录同步不是自动等价的两个实现。新增客户端必须明确选择并实现同一同步协议，不能只宣称「都支持 WebDAV」就认为双向互通。

## 8. 必须执行的跨端验收

| 场景 | 必须验证的结果 |
| --- | --- |
| CLI 创建未来类型 → Android 列表/详情 | 可发现，完整读取，原生类型/ID/Collection 不变 |
| 嵌套 JSON、数组、空值、Unicode、大整数 | 值不丢失、不归一化、不舍入；默认不泄露字段值 |
| 未知类型误入普通编辑/移动/批量入口 | 在写入前拒绝，不触发补偿删除，不产生 login 副本 |
| 已知类型追加未知字段后在旧端编辑 | 原始扩展字段和嵌套元数据仍在；否则旧端必须只读 |
| 已知类型的更高 payload version | 通用查看或明确受限，不能降低版本覆盖 |
| 原生删除、改类型、移动后再导入缓存 | 投影与原生状态一致，不错误复活 |
| A 创建 → B 读取 → B 编辑 → A 重开 | 稳定 ID、值、类型、分类及附件一致 |
| 多设备跨代历史、乱序/重复/缺失分段 | 有序依赖最终收敛；缺失时明确待处理；无游标越过失败 |
| 503/429、断网、上传成功但确认丢失 | 有界恢复，重复发布安全，未完成内容不标成已同步 |
| 原始库/快照/便携备份独立回读 | 原始库未受测试修改；输出由独立客户端验证 |
| 错密钥、篡改、错误 vault ID、Tiga 拒绝 | 失败且无数据改写；没有明文日志 |

Android 必须运行 JVM 回归、真实原生库的设备测试和界面检查；只 Mock Repository 不足以证明 MDBX 互通。报告分别记录执行数、失败、跳过、设备/API/ABI、运行时 provenance、真实云验证状态。F-Droid 与主版共享改动须分别编译验证。

## 9. 可直接交给其他 AI 的接入指令

> 先读本契约、当前 MDBX 核心兼容/安全规范和目标端现有 Adapter。使用现有 Rust/FFI 引擎，不直接写 SQLite 或另建 JSON 数据库。建立基于 Collection/ObjectRecord 稳定身份的存储层；列表采用摘要，详情采用授权 disclosure。已知类型按明确的 type + payload version/schema 解析，未知类型作为通用项目显示且只读，完整保留原生类型和 payload。编辑仅修改已知字段并保留未知数据；不能证明无损时禁止写入。同步使用引擎协议、配对 checkpoint、不可变分段/条件写和原子回滚。用双端合成 fixture 验证新类型、未来字段、删除、乱序增量和重开；报告实际运行结果，不能用文档承诺代替测试。修改前列出当前实现与本契约的差异，并完成本次范围内的适配。

## 10. 规范与源码依据

- [MDBX2 兼容规范](https://github.com/Monica-Pass/Mdbx/blob/master/docs/09-mdbx2-compatibility.zh-CN.md)
- [通用对象兼容层 ADR-0001](https://github.com/Monica-Pass/Mdbx/blob/master/docs/adr/0001-generic-object-compatibility-layer.md)
- [授权后才产生明文 ADR-0024](https://github.com/Monica-Pass/Mdbx/blob/master/docs/adr/0024-policy-before-plaintext-object-disclosure.md)
- [业务 payload 迁移 ADR-0016](https://github.com/Monica-Pass/Mdbx/blob/master/docs/adr/0016-bounded-adapter-payload-migrations.md)
- [进程内扩展注册 ADR-0035](https://github.com/Monica-Pass/Mdbx/blob/master/docs/adr/0035-process-local-extension-profile-registry.md)
- 本仓库 `Monica for Android/.../repository/Mdbx2Repository.kt`、`Mdbx2SyncEngine.kt`、`Mdbx2RemoteSyncCoordinator.kt`、`viewmodel/MdbxViewModel.kt`。
- CLI 仓库 `docs/token-format.md`、`src/vault.rs` 及 `AGENTS.md`。若源码与旧说明冲突，以经测试的当前契约为准并修正文档，不能默默创造第三种格式。

## 11. Android 本次实现边界与管理界面

- 当前缓存导入仍复用现有全量原生读取路径；只读投影不会把未知 payload 持久化到 Room。这不等同于所有列表加载均已改成无 payload 的 summary。详情采用受控的按需 disclosure，payload 上限为 4 MiB；超限显示不可读取，不截断保存。数值文本完整保留，原生 payload 不写入 Room 密码缓存。
- 已知类型更高 payload 版本自动只读、任意未知字段无损编辑仍是接入要求，尚未由本次未知类型功能覆盖，需单独实现和验收。
- 当前打包的 FFI 返回冲突身份、字段名、提交引用和时间，没有双方历史 payload 的受控 disclosure API；Android 明确提示历史值不可用，不伪造空值比较，不直接解密底层表绕过授权。
- 默认提供「保留本机版本」和「采用传入版本」，再次确认后调用原生 resolve。传入版本不保证是来源设备此刻的最新版。旧 MARK_RESOLVED 是 LOCAL_WINS 的兼容别名，不能描述成无副作用的标记操作。
- 冲突解决后以原生状态重建缓存，包括删除状态；重复点击、已消失冲突和其他数据库的过时回调不得再次提交。
- 历史与快照以时间、操作和影响项目作为默认摘要。技术详情默认收起，完整 ID、设备、原始时间、字节数、类型及校验值可选择复制。恢复、删除快照保留确认与完整性门禁。
- 精确的原生外键依赖错误，只有确认 checkpoint 未改变才标为待依赖；失败段不确认。其他流推进后再尝试，无进展则保留 blocked，不能忽略错误或标成成功。

可编辑设计见 Android docs/design/mdbx-unknown-type/ 与 mdbx-management-details/。实际测试结果另见本目录的验证报告。
