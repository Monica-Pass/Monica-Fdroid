# Liberapay 欧元支持入口

Plus 支付页新增 `Liberapay · EUR` 按钮，使用欧元图标，打开 <https://liberapay.com/JoyinJoester>。复用现有支付链接流程，浏览器不可用时保留复制链接处理；长标签可换行，外部打开图标保留独立空间。品牌名和国际货币代码各语言共用。

旧 SupportAuthorScreen 同步添加按钮；代码检索显示当前只有路由声明，没有导航调用，因此该旧页仅完成编译检查，没有声称通过实际点击测试。

主仓库与 F-Droid 的 README、GitHub FUNDING.yml、发行说明已补充入口。GitHub 配置为 `liberapay: JoyinJoester`。

[可编辑 M3E Canvas 草图](design/liberapay-m3e.md) · [Canvas 预览](design/liberapay-canvas.png) · [实际支付页面](liberapay-device/plus-payment.png)

## 验证（2026-09-20）

- Liberapay 公开页面 HTTP 返回 200；README、应用入口和赞助配置指向同一账号。
- Android assembleDebug 成功；PlusLocalActivationGuardTest（2 项）、LocaleResourceCoverageTest（3 项）全部通过。
- 最后仅将按钮文字从 `Liberapay · EUR (€)` 精简为 `Liberapay · EUR`，避免大字体下货币符号独占一行。资源重打包成功；跳过已完成的 Kotlin/KSP 和 BuildConfig 生成，前后 1992 项源码、BuildConfig 和 R 类内容哈希一致。
- 公共 AVD Monica_Issue136_API_32，Android 32 / x86_64，320dp 宽、字体 1.5 倍：实际按钮文字和两侧图标完整显示。
- 实际点击 Plus 页 Liberapay 后，ACTION_VIEW 精确指向上述 URL。浏览器停留在首次使用页面，未进行付款；未改动 Plus 激活状态。
- 截图检查后已重新启用 Screenshot Protection，并确认 Enabled 状态；已关闭本任务启动的公共虚拟机，保留其配置和数据。
- F-Droid 相关源码和资源与主 Android 一致；本次未单独构建 F-Droid APK。

构建与跳转证据见 [验证日志](liberapay-device/verification.txt)。
