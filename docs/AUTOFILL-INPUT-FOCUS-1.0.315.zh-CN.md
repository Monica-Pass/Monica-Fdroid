> 2026-09-29 收尾状态见 [提交前核对](FINALIZATION-1.0.315.zh-CN.md)；下文的提交状态与测试结果保留为当时的验证记录。

# 自动填充候选与输入焦点排查（1.0.315）

## 反馈与结论边界

用户转述：自动填充候选小框出现时，偶发不能输入，需要取消再点输入框。反馈者的 Android 版本、输入法和目标应用尚未确认；截图中的设备署名属于帖子作者，不能据此认定反馈者的设备。

本轮在 Android 10（API 29）真实系统中复现了 Monica 自动填充服务崩溃，已修复。**这项兼容缺陷不能直接认定为原反馈中偶发输入阻塞的根因。** Android 厂商系统、具体目标 App 与输入法仍需对应环境验证。

## 已复现并修复的兼容缺陷

`MonicaAutofillServiceNg.processFillRequest` 先取得 `InlineSuggestionsRequest?`，随后通过 `Flow.first()` 读取其他设置。协程发生挂起时，编译器把该局部变量保存为 `Object`，恢复时转换回 `InlineSuggestionsRequest`。Android 10 没有这个 Android 11 才加入的类，即使保存的是 `null`，恢复时仍会抛出 `NoClassDefFoundError`。

原有 `getInlineRequest` 的系统版本检查只保证旧版返回空值，无法保护协程生成的类型转换。Robolectric 在宿主 JVM 上运行，不足以替代旧版 ART 的这条检查。

修复将所有挂起的设置读取移到取得内联请求之前，后续响应构建同步完成，避免新版对象跨越协程挂起点。没有修改认证要求、数据库格式、凭据内容或自动填充范围。

检查编译字节码，修复前可见三个 `checkcast InlineSuggestionsRequest`，修复后该服务不再有这种转换。今后新增设置读取时，也要保持这一约束。

## 调用链检查

- 普通候选通过 Android Autofill 的 `FillResponse` / `RemoteViews` 展示。Monica 不控制系统候选窗口的焦点标志。
- 候选展示阶段创建认证 `PendingIntent`，没有自动启动认证 Activity；启动密码选择或解锁需要用户选择候选。
- 无障碍服务在字段聚焦时进行识别并可提供通知，没有在这条路径创建悬浮窗口；实际填写动作需要用户发起。
- 请求取消时检查取消状态并取消协程，避免已取消请求继续回报。

## 设备回归方法

使用独立 Android 测试用户和合成凭据，测试走真实系统 AutofillManager、独立表单进程和测试输入法的 InputConnection，不使用无障碍 `ACTION_SET_TEXT` 代替输入。

新增场景：

1. 普通候选出现后，直接完成组合输入及账号输入，重复 10 次。
2. 锁定状态的解锁候选出现后，直接输入账号，重复 10 次，不打开解锁界面。
3. WebView 候选出现后，直接组合输入账号，重复 5 次。
4. 仅密码字段候选出现后，直接输入密码，重复 5 次。
5. 主动打开并取消验证，检查原字段保留焦点，一次点回唤起键盘后即可输入。

前四项均不重新点击原字段或主动关闭候选。第五项与“候选刚出现”不同：Android 可以在取消认证后收起软键盘，不能把键盘隐藏直接判为输入阻塞。探索中使用硬件 Tab／键盘事件时，系统候选还可能消费按键；最终触屏输入测试不依赖这类事件绕过输入连接。字段切换及实际选择填充由既有完整填写流程测试覆盖。

测试调试阶段还遇到公共 AVD 用户切换未完成、系统锁屏未退出、调试连接中断及测试夹具的键盘动作问题；对应失败日志保留，不作为产品通过证据。

## 验证记录

最终设备与构建结果见本任务 `.codex-tasks/autofill-input-focus-315` 下的验证记录。设备运行前后均核对本地及已安装 APK 的 SHA-256，避免将旧包的结果计入当前版本。全量 JVM 回归：普通版 1980 项（1979 通过、1 跳过），F-Droid 1981 项（1980 通过、1 跳过）；跳过项需要真实 Steam 测试文件。

| 最终检查 | 结果 | 证据 |
| --- | --- | --- |
| Android 10 / F-Droid 真实输入 | 5 项全部通过，含 31 轮输入操作 | `Fdroid-emulator-5556-final-input.log` 及同名 `.json` |
| Android 12 / 普通版完整 Autofill 流程 | 22 项全部通过，包含上述 5 项和既有 17 项 | `Main-emulator-5554-final-complete.log` 及同名 `.json` |
| 两版 Debug 与 AndroidTest 包 | 构建通过 | `autofill-focus-*-fixed-build.log`、`autofill-focus-*-final-package.log` |
| 两版 Release / R8 | 构建通过 | `autofill-focus-main-release.log`、`autofill-focus-fdroid-release.log` |
| 四个工作目录源码一致性 | 通过，均仅新增本轮一个生产文件和两个测试文件的变更 | `source-audit.json` |

构建日志位于 `.codex-tasks/experimental-final-315`；其他证据位于本轮任务目录。Release 未安装做设备交互测试，设备测试使用 Debug。普通版 R8 仍有既有 Kotlin metadata 兼容警告，但构建完成；本轮没有重新运行全量 lint。

默认公共 AVD `Monica_Issue136_API_32` 保持运行，结束后切回原用户 0，并恢复测试用户的自动填充／输入法设置。为覆盖 API 29 实际运行时新增并使用 `Monica_Autofill_API_29`，数据目录 `D:/AndroidSDK/codex-avd/autofill-api29-20260929`；已停止本次启动的实例，保留配置供后续低版本测试复用。

本轮只改动一个生产文件及两个测试文件，普通版、F-Droid 和两份候选保持一致。原有未提交修改保留，两个 main 的 HEAD 未改变。修复计入 1.0.315 未发布说明，未提交、推送、打标签或发布。

## 若用户仍能复现

补充 Android／系统版本、输入法、目标 App 和失败发生阶段（候选刚出现／选中候选／取消验证之后），以及同一时段的 Monica 自动填充诊断。诊断应避免包含真实密码或验证码。先对应具体阶段复现，再判断是否需要处理应用侧焦点、系统下拉候选或输入法兼容，不能仅凭设备版本推断责任。
