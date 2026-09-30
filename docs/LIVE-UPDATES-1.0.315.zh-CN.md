# OTP 与顺序复制实况通知（1.0.315）

## 范围

- 自动填充后的验证码通知：保留既有开关和持续时间；支持实况倒计时、复制当前验证码和关闭。
- 自动填充磁贴的顺序复制：先复制用户选择的第一项，通知继续复制另一项；60 秒后结束。
- 系统授权入口仅放在「设置 → 权限管理 → 实况通知」，普通通知授权独立保留。
- 本次不接入后台同步，不变更数据库结构、备份格式或用户数据迁移。

## 平台与兼容

应用保持 minSdk 26、targetSdk 34；只将 compileSdk 升为 36。声明 `POST_PROMOTED_NOTIFICATIONS`，通过 `NotificationManager.canPostPromotedNotifications()` 查询资格。设置入口使用正式的 `Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS` 和 `EXTRA_APP_PACKAGE`，缺少入口或启动失败时回退普通通知设置／应用详情，返回后刷新状态。

增强逻辑只在 API 36 及以上尝试，并隔离新版方法调用。保留现有原生／Compat 通知和系统标准 BigTextStyle；通过原生 `recoverBuilder` 设置 ongoing、shortCriticalText，以及 AndroidX 的公开 `android.requestPromotedOngoing` extras 协议，不引入全局 AndroidX 升级。

系统或厂商决定是否提升。早期 Android 16 镜像的实况功能仍采用旧版机制，可能始终返回不可用；这时继续普通通知，不使用旧的 colorized 通知伪装实况。Android 16.1 系统镜像已验证两类通知的实际提升。缺少普通通知权限时不请求授权，不阻止已完成的填充或第一步复制。

参考：[Live Updates](https://developer.android.com/develop/ui/views/notifications/live-update)、[设置入口](https://developer.android.com/reference/android/provider/Settings#ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS)、[AndroidX 请求提升](https://developer.android.com/reference/androidx/core/app/NotificationCompat.Builder#setRequestPromotedOngoing(boolean))。

## 生命周期与隐私

- 状态条短文本只显示倒计时或「复制」，不放密码、账号或验证码。通知使用 PRIVATE 和不含秘密／操作的 publicVersion；锁屏公开版本只提示解锁。系统锁屏内容偏好仍由用户管理。
- 系统支持时复制 action 要求身份验证；服务／接收器再次检查设备锁定状态，锁定时不复制。
- OTP 复制按点击当时的时间重新计算；新会话使旧动作失效，关闭或到期停止服务，清理会话与 PendingIntent，避免下一次刷新重新弹出。
- 顺序复制只持久化非秘密会话 ID；凭据保留在不可变、显式指向非导出接收器的 PendingIntent 中。关闭／复制后取消动作，过期拒绝复制；旧会话动作不能关闭或复制新会话。
- 使用现有敏感剪贴板及自动清理逻辑；异常不在日志中输出凭据。
- 新增判断只发生在通知发布或操作阶段，不进入自动填充候选列表构建路径，也不新增常驻后台服务。

## 设计与测试

[本地 M3E Canvas 草图](http://127.0.0.1:5186/live-updates-315.html)；可编辑源与完整分享链接保存在 [design/live-updates-315](design/live-updates-315)。通知最终由系统绘制，草图用于检查信息层级、权限行和操作状态。

验证记录见 [live-updates-315-verification.json](live-updates-315-verification.json)，包含实际安装 APK 的 SHA-256、测试结果、源码摘要和系统提升标记。

| 验证 | 普通版 | F-Droid |
| --- | --- | --- |
| 应用与设备测试 APK 构建 | 通过 | 通过 |
| 自动填充、通知和权限相关单测 | 173 项通过 | 173 项通过 |
| 公共 API 32 设备回归 | 7 项通过 | 7 项通过 |
| API 36.1 实况通知与权限测试 | 4 项通过 | 4 项通过 |
| OTP／顺序复制的 requested、eligible、promoted | 均为 true | 均为 true |

API 32 覆盖通知复制、替换、关闭、到期、权限入口按版本隐藏，以及真实表单下拉填充、输入法内联填充、关闭 OTP 通知后的正常填充。API 36.1 覆盖真实系统提升、权限页面跳转和通知生命周期；设备指纹为 `google/sdk_gphone64_x86_64/emu64xa:16/BE4B.251210.005/14574095:userdebug/dev-keys`。普通版另通过早期 API 36 的四项普通通知回退测试，该结果不算作实况提升验证。

代表性系统截图：[OTP](design/live-updates-315/main-qpr2-live-updates-otp-api36.png)、[顺序复制](design/live-updates-315/fdroid-qpr2-live-updates-smart-copy-api36.png)、[权限管理](design/live-updates-315/main-qpr2-live-updates-permissions-api36.png)、[Android 12 回退](design/live-updates-315/main-live-updates-smart-copy-api32.png)。系统决定是否显示状态条胶囊及其外观，不能据此保证所有厂商设备均显示相同的“灵动岛”。

测试环境中发现并解决了三类问题，保留原始失败记录：API 36.1 默认图形路径使系统 SurfaceFlinger 在 Monica 启动前崩溃，复用该 AVD 并启用 `-gpu swiftshader -feature Minigbm,Vulkan` 后恢复；Espresso 3.6.1 调用已移除的系统方法，只升级测试依赖到 Espresso 3.7.0／AndroidX test ext-junit 1.3.0；初次 API 32 截图后读取剪贴板丢失焦点，补充恢复窗口焦点后完整回归通过。截图还会等待系统通知内容出现，避免将展开动画前的画面当成通知截图。

测试仅使用隔离 Android 用户的合成数据，保留公共 AVD 和用户数据盘；恢复测试前的通知授权、输入法、自动填充、无障碍和前台用户。没有提交、推送或发布 APK。

另有一次 API 36.1 测试进程在测试类加载阶段发生 `BIND APPLICATION ANR`，当时尚未进入任何用例。保留了失败日志、系统进程退出原因和 logcat；正常应用启动检查、同一 APK 的重新验证均通过。该次失败未作为通过结果统计，也未通过修改应用代码或清除数据规避。
