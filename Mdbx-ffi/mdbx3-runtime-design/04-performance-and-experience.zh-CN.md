# MDBX3 性能与体验设计

## 1. 测量原则

MDBX3 的性能数据必须与同一设备、同一 vault、同一构建工具生成的 MDBX2 release 比较。每项报告同时给出 p50、p95、峰值内存、数据库字节增长和操作结果校验。

KDF 解锁耗时与运行时开销分开记录。Tiga 的 Argon2id 参数保持不变，禁止把降低 KDF 成本计入性能提升。

## 2. 场景与目标

| 场景 | 发布门禁 | 第一阶段目标 |
|---|---:|---:|
| SO 装载至 runtime manifest 可用 | 不慢于 MDBX2 | p95 至少降低 15% |
| 已解锁 vault 打开，不含 KDF | 不慢于 MDBX2 | p95 至少降低 15% |
| 100 项 metadata 首屏 | 不慢于 MDBX2 | p95 至少降低 20% |
| 100 项单次批量写入 | 不慢于 MDBX2 | p95 至少降低 15% |
| 10,000 commit 增量同步 | 不慢于 MDBX2 | 时间降低 20%，峰值内存降低 30% |
| 首次同步大型 vault | 保持有界并可恢复 | 峰值内存降低 40% |
| 1 GiB 外部 Blob 传输 | 常量级 chunk 内存 | 中断后从 checkpoint 继续 |
| snapshot 列表与预览 | 不读取 snapshot body | p95 至少降低 15% |

发布门禁使用统计区间判断，避免一次测量波动影响判定。达到速度目标但返回数据、审计或 commit 数量不同的结果视为失败。

## 3. 启动与打开

启动阶段只初始化以下内容：

1. UniFFI scaffolding。
2. runtime manifest 常量。
3. 基础错误映射。

首次打开 vault 后再初始化 SQLite、keyring、Tiga session 和连接状态。搜索、KDBX、Blob Provider、同步压缩和领域 Adapter 采用首次使用初始化。

延迟初始化只改变成本发生时间。客户端可以通过附加的 `prepare_runtime` 或 `prepare_vault_capability` API 主动预热；旧 bindings 无需调用。

## 4. 读取模型

默认导航继续使用 Collection、Object、Attachment、Conflict 和 Snapshot summary，SQL 先检查长度，再决定是否物化密文或文本。

MDBX3 可以引入 read snapshot 与 statement cache，但必须满足：

1. cursor 仍然绑定查询范围和限制。
2. Tiga 决定在披露时重新求值。
3. key epoch、session、revocation 和 policy 变化会使缓存失效。
4. 缓存中不保存 payload 明文、附件明文或密钥材料。
5. 完整兼容 API 保持原返回语义。

## 5. 写入模型

single writer 负责用户写入、snapshot、restore、同步应用、迁移和密钥轮换。批量操作先构造有界计划，再进入一次事务和一个 CommitOperation。

写入优化优先处理：

1. 减少重复 SQL prepare。
2. 聚合同一操作的 state delta。
3. 避免重复读取刚写入的对象。
4. 只更新受影响的 derived index。
5. 将 Blob body 与数据库状态传输分离。

禁止通过跳过 object version、commit、审计、Tiga 或同步 delta 获得写入速度。

## 6. 历史与同步

MDBX3 需要解决长期运行后的 commit 和首次同步成本：

1. 引入版本化因果 checkpoint。
2. bootstrap 使用有界分段和 resume token。
3. commit inventory、delta inventory 和 Blob manifest 分别分页。
4. 旧 MDBX2 peer 继续使用已有完整状态或兼容增量协议。
5. checkpoint 只证明一致性水位，不授予权限。
6. 历史保留和清理只有在因果确认、审计与恢复要求满足后执行。

checkpoint、retention 和 compaction 属于独立能力。首个版本可以先提供分段 bootstrap，不应把未经证明的历史删除加入同一提交。

## 7. 附件与 Blob

附件和外部 Blob 使用固定上限 chunk 进行读取、加密、校验和传输。峰值明文内存应与单个 chunk 大小相关，不随完整对象大小增长。

客户端仍只需要 MDBX3 SO；具体网盘网络调用可以留在客户端，SO 提供 transport-neutral manifest、lease、chunk 和 checkpoint Interface。

## 8. 长操作体验

MDBX3 新增长操作句柄，适用于首次同步、KDBX 导入、snapshot、完整性检查、rekey、compaction 和大型 Blob 传输。句柄至少包含：

1. 稳定 operation ID。
2. 当前阶段、已完成数量和总量估计。
3. 已处理字节和总字节估计。
4. 可取消状态。
5. 可恢复 token。
6. 最终结果或结构化错误。

现有同步函数和导入函数保持同步调用语义。新 bindings 可以调用长操作 API，旧 bindings 继续使用兼容封装。

## 9. 错误与诊断

现有 `MdbxFfiError` 变体保持冻结。MDBX3 通过附加诊断函数提供稳定 code、category、retryable、operation ID、resource limit 和授权原因。

诊断内容不得包含密码、payload、密钥、token、附件明文或完整外部 URI。release 构建保留 build ID 和 runtime manifest，详细符号放入单独归档。

## 10. 体验完整性

MDBX3 候选版本需要证明以下行为保持可用：

1. 创建、打开、解锁和关闭 vault。
2. Collection、Object、Relation、Label 和 Attachment 操作。
3. 删除、恢复、移动、复制和批量写入。
4. snapshot 创建、列表、恢复和清理。
5. conflict 导航和解决。
6. Tiga 三档策略、解锁方式、审计和 key epoch。
7. 完整状态、增量同步、resume 和 Blob transfer。
8. MDBX1 自动升级和 MDBX2 原位打开。

体积和速度只能在该清单全部通过后评价。
