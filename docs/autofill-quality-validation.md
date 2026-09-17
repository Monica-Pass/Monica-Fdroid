# 自动填充兼容性验证（2026-09-17）

本轮检查系统 Autofill、无障碍填充和键盘顺序填充。测试表单模拟学校、工厂内部软件常见的非标准字段，没有取得具体用户失败 App。免验证自动填充及 Android Q 的兼容行为保持不变。

## 已复现的问题与修复

| 场景 | 原行为 | 调整 |
| --- | --- | --- |
| 仅账号或仅密码的一步登录 | 无障碍可能将密码填入账号框，或密码填入后仍报告失败 | 明确区分字段角色，按当前页面存在的目标判断完成情况 |
| 工号、学号、普通文本类型的密码框 | 缺少标准提示时漏识别或颠倒角色 | 共用字段语义识别，补充中文和企业账号标识，优先识别密码复合标签 |
| WebView 连续粘贴账号、密码 | 粘贴与焦点切换异步执行，可能账号内容异常、密码为空，却返回成功 | 优先对指定字段执行 SET_TEXT；粘贴后备必须确认真实焦点，并保留剪贴板还原 |
| 网页填写账号后重建密码框 | 对旧密码控件的写入被页面更新丢弃 | 等待网页输入事件处理后重新查找密码框，并复查当前 App、窗口和可读取的浏览器 URL |
| 验证码、搜索与账号并列 | 兜底选择可能把这些控件作为密码目标 | 排除搜索和验证码；兼容模式不得把 OTP 提升成普通密码 |
| 原生登录旁有无关 WebView、多个网页、前台弹窗 | 原生字段被过滤，或不同表单的字段与域名混合 | 选择当前焦点窗口及 WebView 范围，缓存和兜底也遵守相同范围 |
| 无标签文本框的显式系统填充请求 | 找不到填充目标 | 仅在明确的手动请求中接受当前焦点的普通文本框 |
| 快速切换输入框并取消旧请求 | 旧请求仍可能回报失败 | 取消协程并在回调前检查请求完成状态 |
| 键盘“下一项”被消费但焦点未移动 | 密码接在账号后面 | 检查目标包名与字段身份；重新建立输入连接本身不算切换字段 |

无障碍识别仅使用控件的提示、描述、关联标签和字段 ID。已经输入的文本不作为角色证据。不可见、禁用控件与其他前台 App 不接收凭据。直接写入被拒绝时才尝试粘贴，原有临时剪贴板还原和有界重试保留。

## 自动化覆盖

JVM 测试通过 Robolectric 执行真实生产方法，分别覆盖 API 29 和 API 34：

- `AutofillStructureIntegrationTest`：窗口、原生与 WebView、跨来源子树、复合标签、OTP、隐藏偏好和手动请求。
- `AccessibilityCredentialFillTest`：单步与双字段、中文非标准字段、关联标签、填写失败、剪贴板还原和 App 切换。
- `AutofillResponseCompatibilityTest`：免验证响应包含可用值；验证开启且会话锁定时仅返回解锁入口。
- `AutofillCancellationTest`：取消请求后的回调行为。
- `MonicaImeSequentialFillTest`：有效焦点切换、无变化的下一项、输入连接重启、未知字段 ID、切换 App 和拒绝写入。

`AutofillFlowInstrumentedTest` 通过 Android 的真实 AutofillManager、AccessibilityService 和独立表单 App 验证填入结果。原生控件与 WebView 页面只上报 `OK`、`EMPTY`、`BAD` 等状态；网页通过 `input` 和 `change` 事件报告结果。凭据均为合成测试数据，不连接真实站点。

### 设备测试隔离

复用 `Monica_Issue136_API_32`，使用独立 Android 测试用户。测试只在非主用户且显式传入 `autofillIsolatedUser=true` 时执行。若该用户已有其他主密码则停止，不重置已有数据。测试记录通过实际 SecurityManager 加密，结束后删除本轮插入的记录并恢复验证设置。

启动模拟器或切换用户后先唤醒屏幕并关闭无密码的系统锁屏；否则 Activity 不能进入前台，测试只会读到壁纸窗口。这与 Monica 的主密码验证无关，不能通过放宽生产验证逻辑解决。

在已配置好独立用户的设备上运行：

```text
adb -s <serial> shell am instrument --user <test-user> -w -r \
  -e autofillIsolatedUser true \
  -e class takagi.ru.monica.autofill_ng.AutofillFlowInstrumentedTest \
  <test-package>/androidx.test.runner.AndroidJUnitRunner
```

普通版的 `<test-package>` 为 `takagi.ru.monica.test`，F-Droid 版为 `takagi.ru.monica.fdroid.test`。

运行前记录测试用户的自动填充、无障碍和输入法设置，安装主 APK 与 AndroidTest APK，并将 `autofill_service` 指向 Monica。无障碍服务由测试进程启动后按需绑定，每项结束后解绑；不要预先启用，以免 instrumentation 强停进程后遗留失效的系统绑定。

普通下拉建议使用 AndroidTest 中不提供 inline 建议的简易输入法；inline 场景使用设备原有的兼容输入法。这样不会因隐藏 Gboard 后建议仍送往键盘而误报失败。完成后还原设置并切回原用户；仅关闭本轮启动的虚拟机，保留公共 AVD 数据盘。

## 验证记录

| 检查 | 最终结果 |
| --- | --- |
| Android Debug APK 与 AndroidTest APK | 构建通过 |
| Android 相关 JVM 回归（新增用例覆盖 API 29、34） | 236 项通过，0 失败、0 跳过 |
| F-Droid Debug APK 与 AndroidTest APK | 构建通过 |
| F-Droid 相关 JVM 回归（新增用例覆盖 API 29、34） | 238 项通过，0 失败、0 跳过 |
| Android 32 完整填写流程 | 17 项通过 |
| Android 32 “非自动填充”与缓存提示撤销 | 4 项通过 |

F-Droid 首次完整编译发现导入页缺少 `kotlinx.coroutines.CancellationException` 导入；该文件原有的取消异常处理已引用此类型。本轮仅补齐对应 import，保留 F-Droid 的功能与依赖差异。

设备测试使用普通 Android 版；F-Droid 共用相同的填充实现与测试源码。设备场景包括：标准原生登录、工号／学号、非标准文本密码框、账号／密码分步登录、原生表单旁的 WebView、键盘 inline 建议、显式手动请求、验证后返回、取消验证后重新填写、关闭验证后的锁定会话、无障碍选择器返回原 App、网页输入事件、动态密码框、验证码／搜索排除及跨 App 拒绝填写。

测试调试中也修正了夹具遗漏的菜单点击、覆盖输入框的建议弹层和隐藏 API 断言方式，并在重启后解除模拟器的无密码系统锁屏。上表仅统计最终实际执行通过的结果；环境未就绪时的失败不作为产品通过证据。

测试结束后已恢复测试用户的自动填充、无障碍与输入法设置，切回用户 0 并关闭本轮启动的公共 AVD，保留配置和数据盘。

## 验证边界

这些结果不能代表所有学校、工厂内部 App，也不能替代 Android Q 真机、厂商系统与商业密码管理器的对比验收。完全不公开可编辑控件的自绘表单、禁止粘贴且拒绝无障碍写入的控件，以及没有任何可区分目标信息的键盘表单，仍受目标 App 与系统能力限制。
