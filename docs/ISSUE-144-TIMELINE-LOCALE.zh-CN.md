# Issue #144：法语时间线闪退

2026-09-29，修复归入 1.0.315，已同步普通版、F-Droid 及对应候选；本轮未提交、推送或发布。

## 反馈与原因

- [用户反馈](https://github.com/Monica-Pass/Monica/issues/144#issuecomment-5874142490)
- [用户日志](https://github.com/Monica-Pass/Monica/issues/144#issuecomment-5874357049)

日志中的 1.0.314 在 Android 15 上两次因 `IllegalArgumentException: Illegal pattern character 'j'` 崩溃，调用位置为 `SimpleDateFormat` 构造函数。

法语资源 `timeline_date_month_day` 将 Java 日期模式的 `dd` 翻译为了 `jj`，得到非法模式 `MM-jj`。时间线仅在历史记录早于“今天／昨天／本周”分组时使用该资源。因此，中文环境、空历史或只有近期记录时不会走到这个错误分支。此问题发生于操作历史的日期分组；列表排序数据和数据库格式未变更。

## 修复与防回归

法语模式改为 `dd/MM`，例如 9 月 17 日显示为 `17/09`。默认资源和法语资源增加注释，明确日期模式字母不得翻译。

新增测试读取所有资源目录中的真实模式，执行日期格式化和反向解析，验证月份与日数；Android 测试另外使用编译后的资源覆盖全部内置语言及系统语言，执行真实 `groupAndAggregateEvents` 分组函数，检查法语新旧历史混合和应用语言切换。该函数只由 `private` 改为 `internal` 以便直接测试，其分组和排序逻辑保持原样。

此前语言覆盖测试检查的是文本和占位符，没有执行日期格式解析。本次增加这层语义验证，以捕获类似翻译错误。

## 验证结果

- 修复前：新增两项 JVM 测试均失败，重现日志中的 `Illegal pattern character 'j'`。
- 修复后：普通版与 F-Droid 各 6 项定向 JVM 测试通过，各 3 项 Android 日期与分组测试通过。
- 两版 Debug 应用和测试包构建成功；测试前后核对应用与测试 APK 的 SHA-256。
- 复用公共 `Monica_Issue136_API_32`（API 32 / x86_64）；未复测用户的实体 Android 15 设备，未执行本轮 Release/R8 构建。
- 两版源码与各自候选一致；相较此前接入记录，本轮只变更日期资源、测试入口可见性和新增测试。F-Droid 1.0.315 / versionCode 22 元数据检查通过。

本地证据位于工作区 `.codex-tasks/issue-144-timeline-315/`：`red-test-results.xml`、两版构建和设备日志、`validation-final.json`。下载的用户原始日志仅保存在该工作区任务目录中，不复制到仓库文档。
