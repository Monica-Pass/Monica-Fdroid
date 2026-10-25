# F-Droid 密码列表性能同步验证（2026-10-25）

## 范围与来源

同步普通版提交 `fa969be136e24fe6c171e845073015019904d60e` 中的三个生产代码文件改动和两个设备测试文件，F-Droid 基线为 `afa3660c41263870f04dcca21aa9b87e8e7c1ab1`。补丁无冲突，测试源码与普通版一致（仅工作树换行符不同）。

- 滚动像素在 `snapshotFlow` 中观察，回顶可见性仅在变化时通知页面，保持原有阈值。
- 恢复位置使用派生的列表数量，避免每次布局变化触发页面重组。
- 密码页仅在请求快速定位时读取进度，不订阅普通滚动产生的进度反馈。
- 分类就绪标记和分类内容共享订阅，活动及归档的元数据流跳过完全相同的快照复制。
- 原始搜索通知、排序、去重、数据写入、加密和同步规则保持原样。

保留 F-Droid 的应用身份、依赖与构建策略，版本仍为 `1.0.321` / versionCode `28`。中文及英文更新记录追加到 `Monica F-Droid下一版发行说明.md`；已发布说明和 fastlane `28.txt` 保持原样。

## 验证方法

复用已运行的公共模拟器 `Monica_Issue136_API_32`（API 32 / x86_64）。安装 F-Droid 自身的应用与测试包，运行：

- `PasswordListScrollWorkTest`：真实滚动逻辑、300 行合成列表，检查 24 次小幅滚动的页面重组数、最终像素位置、回顶可见性、快速定位，以及离开/返回后的滚动位置恢复。
- `PasswordListLoadingWorkTest`：真实 ViewModel/Repository、独立内存数据库及委托 DAO，检查分类订阅数，并验证 1,000 条记录的重复快照、编辑、重排、删除和空列表在活动/归档元数据中的结果及原始记录不变。

测试不清空或写入模拟器已有保险库。模拟器由此前任务启动，本次结束后保留运行状态。

## 结果

- F-Droid universal debug APK（arm64-v8a / armeabi-v7a / x86_64）和测试 APK 构建成功。
- 48 项针对性单测全部通过：0 失败、0 错误、0 跳过。
- F-Droid 包名下的四项设备测试全部通过。

| 检查项 | F-Droid 实测结果 |
| --- | --- |
| 24 次小幅滚动的页面重组 | 0 次 |
| 分类数据订阅 | 1 条 |
| 返回第 80 行、17 像素位置 | 通过 |
| 快速定位、回顶及按钮可见性 | 通过 |
| 1,000 条记录的活动/归档元数据更新 | 通过 |

日志中的两个性能计数均来自本次 F-Droid 测试进程，已与 ActivityManager 的包名和进程号对应。测试结束后 runner 正常停止进程；采集脚本兼容进程已经退出的情况。

实际应用启动冒烟返回 `Status: ok`，当前进程未记录 AndroidRuntime 错误。单次冷启动耗时为 6,959 ms；这只是公共模拟器上的启动冒烟，不作为优化前后对比结果。

普通版此前完成了优化前后对比。本次是 F-Droid 同步后的独立回归，不将普通版基线数值当作 F-Droid 的新测量，也不把重组次数直接换算为帧率或加载耗时提升。

## 复现与证据

构建执行 `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest`，选择列表、共享流、去重、分组、分类过滤、聚合、首帧及浮动按钮相关测试，传入 `-PfdroidUniversal=true -PincludeX86TestAbi=true`。测试 APK 的 runner 为 `takagi.ru.monica.fdroid.test/androidx.test.runner.AndroidJUnitRunner`。

本地完整命令和原始结果保存在工作区 `.codex-tasks/fdroid-password-performance-20261025/`，包括 `build.log`、`unit-results/`、`run-device.ps1`、`device-tests.txt`、`device-metrics.txt` 和 `startup-smoke.txt`。安装包只用于本地构建验证，不创建发行或交付副本。
