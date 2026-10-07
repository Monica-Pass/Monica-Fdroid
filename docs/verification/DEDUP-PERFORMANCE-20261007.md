# 去重合并性能诊断（2026-10-07）

本轮仅增加诊断测试并检查调用链，未修改去重业务实现。

## 结论

合并阶段在 `Dispatchers.IO` 中后台执行，但密码、安全项、Passkey 均逐条串行等待写入。它不是在界面主线程运行，也不是并行处理多条记录；协程可能在不同 IO 工作线程之间恢复。

合成测试确认：逐条写入期间，一个完整密码列表订阅就能引发大量重复查询，显著放大耗时。不能仅凭截图判断用户设备的具体瓶颈占比。

## 测试与结果

- 普通版当前工作区，基于提交 `086c1298c94a4fb2a81bf198bc5b922b3ef525a5`；已有清空数据改动不涉及去重调用链。
- 公共 AVD `Monica_Issue136_API_32`，Android 32、x86_64。
- 独立磁盘 Room 数据库，使用合成数据；不操作用户密码库。
- 854 条来源密码，其中 6 条重复，目标为 Monica Local 空库，预计新增 848 条。
- 密码经真实 `SecurityManager` 加密，调用真实 `DedupMergeService.buildPlan` 和 `executePlan`。
- 无自定义字段、无附件；未注入附件支持，也未模拟完整 Compose 页面、密码列表派生计算、自动同步等订阅者。
- 单次对照诊断，不作为跨设备性能承诺。

| 场景 | 初次分析 | 执行至首次进度 | 完整执行 | 执行期间完整密码查询 |
| --- | ---: | ---: | ---: | ---: |
| 无持续列表订阅 | 1,415 ms | 1,572 ms | 2,305 ms | 3 |
| 一个完整列表订阅 | 1,233 ms | 1,278 ms | 12,060 ms | 841 |

完整执行包含执行前重新分析，首次进度时间包含处理第一条。查询计数只匹配 `SELECT * FROM password_entries`，不包含其他表查询。两个场景均成功新增 848 条、失败 0 条；来源 854 条全部保留。

测试：`app/src/androidTest/java/takagi/ru/monica/data/dedup/DedupLocalPerformanceInstrumentedTest.kt`。

运行 `:app:assembleDebugAndroidTest -PincludeX86TestAbi --max-workers=2` 成功；设备测试 `diskMergeWithAndWithoutAnActiveListObserver` 通过。测试耗时约 20 秒，包含数据准备及两个场景。测试在 finally 中关闭并删除独立数据库。

日志保存在工作区根目录 `.codex-tasks/dedup-performance-20261007/`：`build.log`、`device.log`、`benchmark.log`。

## 调用链与额外开销

- `DedupEngineViewModel` 将执行放到 IO 调度器，但 `DedupMergeExecutor` 用三个顺序 `forEach` 调用单条 writer，没有批量写入。
- `RepositoryDedupMergeWriter` 逐条插入密码、自定义字段，再复制附件。MDBX 目标带自定义字段时还会重新读取并再次写入完整记录；本次目标为本地，因此未测这部分。
- `PasswordRepository` 已有 `insertPasswordEntries` 批量 API，当前去重流程未使用。
- `buildPlan` 获取来源选项后再次加载列表；`executePlan` 校验目标并重新分析，以防预览之后发生变化。这会增加开始等待，但不足以解释中途逐条推进慢。
- 附件分析可能读取或下载内容并计算哈希，执行时逐个复制、校验。此项未纳入本次计时。
- `PasswordViewModel` 使用共享密码 Flow，仍可能在订阅活跃时随每次写入重新查询；本轮仅测量一个裸列表订阅，没有复现用户当时全部订阅状态。
- 进度中的完成数是已尝试处理数，单条失败也会推进；不能把进度数字直接当作成功新增数。

## 后续优化方向

优先使用有界批次和事务，减少逐条提交引发的列表查询与重新计算；保留停止操作、单条失败反馈、自定义字段及附件回滚语义。附件复制等独立工作可另行评估有限并发，SQLite 写入不宜直接改成无限并行。

重新分析承担防止过期计划的职责，优化重复读取时仍需保留该一致性检查。此报告记录诊断结果，不表示这些优化已经实现。
