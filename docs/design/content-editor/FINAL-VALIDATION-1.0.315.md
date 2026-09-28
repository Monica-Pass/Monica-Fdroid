> 2026-09-29 收尾状态见 [提交前核对](../../FINALIZATION-1.0.315.zh-CN.md)；下文的提交状态与测试结果保留为当时的验证记录。

# 1.0.315 最终可用性验收

2026-09-29。确认范围已接入普通版和 F-Droid 的本地 main。本轮检查在这两个主目录进行，新增测试和修复同步到各自独立候选；没有合并旧实验分支，也没有提交、推送、打标签或发布。

## 已完成范围

- 默认经典编辑，按需编辑可选；切换样式保留已有内容。
- 密码、笔记、卡包和证件沿用 main 的字段及存储模型。密码中已有的联系、地址、支付、自定义字段和五种 OTP 均保留。
- 密码详情展示支付卡面、验证码卡片及统一字段操作；附件按钮位于底部右侧。
- 设置搜索定位具体设置项，返回可收起；键盘菜单反馈、自动填充图标、法语时间线及详情条形码修复保留。
- MDBX 继续使用当前原生实现，包含未知类型只读详情、完整参数展开、冲突确认和同步依赖处理。
- 验证器新增自定义字段按用户决定暂缓。旧版 1.0.314 编辑会丢失这些新增扩展，本轮未引入。

本轮补齐 11 种语言共 490 条编辑、字段、排序、搜索及 MDBX 提示文案；格式参数与换行校验通过。保留已有译文，彩蛋语言继续继承中文。

## 验证结果

| 检查 | 普通版 | F-Droid |
| --- | ---: | ---: |
| 全量 JVM | 1975 通过，1 跳过 | 1976 通过，1 跳过 |
| 编辑、详情和字段操作 | 39 通过 | 39 通过 |
| 存储、导入导出、冲突与同步 | 33 通过 | 33 通过 |
| 设置搜索与返回 | 11 通过 | 12 通过 |
| MDBX/CLI 契约 | 1 通过 | 1 通过 |
| 实际 Rust JNI、KDF 与投影 | 5 通过 | 5 通过 |
| MDBX 历史、快照、冲突与未知类型 UI | 4 通过 | 4 通过 |
| 设备合计 | 93 通过 | 94 通过 |
| Debug 应用与测试包 | 构建通过 | 构建通过 |
| Release/R8 | 构建通过 | 构建通过 |
| Release 启动 | 测试签名副本启动通过 | 测试签名副本启动通过 |

JVM 跳过项为 `SteamInventoryLiveTest.realMaFileEligibilityCheckReturnsInventoryOverview`，需要 `STEAM_LIVE_MAFILE` 指定真实 Steam 账号文件，未提供。它不是实验编辑功能测试。

新增实际操作覆盖：按需编辑 TOTP、HOTP、Steam、Yandex 的保存和重编辑（mOTP 原已覆盖），联系/地址保存和清空，以及隐藏自定义字段保存、再次编辑和明确删除。测试使用真实页面、ViewModel 和内存 Room；存储套件另外使用真实 MDBX/KDBX 文件，并验证关闭后重开和重建投影。

存储回归包括原生文件导出与重复导入、无账号内容、字段和附件保留、大批量导入取消后重试、未知类型不丢失、冲突持久化及同步依赖。Bitwarden 使用真实加密格式和模拟服务，不代表真实线上账号联调。

测试复用公共 `Monica_Issue136_API_32` / API 32 / x86_64。全流程临时使用 1080×2400，系统字体 1.5 倍；另有 280dp 窄组件及深色大字检查。结束恢复原来的 840×2100 覆盖尺寸，保留原本运行的 AVD。每个设备套件开始和结束均核对相同应用/测试 APK 的 SHA-256。

Release 使用最终源码构建；为保留模拟器数据，仅将本地测试副本改用 Android debug 签名安装，确认启动到首次密码设置页、前台页面树存在且进程无崩溃后恢复已验证的 Debug 包。没有设置主密码或改动已有数据库。原 Release 输出和正式签名材料未改动。完整编辑/存储流程验收运行于 Debug，Release 本轮仅检查打包及启动；ARM 设备与远端 CI 未在本轮实测。

## 收尾发现与处理

- 首轮两项 UI 测试失败来自测试操作：保存完成后未模拟导航重入编辑器，以及复制动作落到浮动按钮区域。补齐导航生命周期、滚动到可点击区域后，定向复测及完整 UI 套件通过。
- 全量 JVM 检查发现旧版本号、旧组件名称、资源属性顺序与 Windows 换行导致的过期断言。按当前实现更新定位，保留安全/存储行为断言；普通版保留 OneDrive 检查。没有修改运行时加密或放宽翻译覆盖。
- 历史候选兼容文档已增加状态提示，避免误认为改动仍未接入 main。
- 普通版版本 1.0.315 / versionCode 12；F-Droid 1.0.315 / versionCode 22。发行说明与 changelog 22 已同步，F-Droid 元数据检查和 6 项发布脚本测试通过。

## Android lint

未取得完整 lint 通过结果。普通版全量 `:app:lintDebug` 在迁移依赖的检测器 JAR 时抛出 `NegativeArraySizeException`（`LintJarApiMigration` / ASM），后续 Kotlin UAST 扫描超过 12 分钟，保存线程诊断后中止。缩小 lint 范围的尝试也遇到同一检测器异常；F-Droid 的单独运行同样复现该异常后中止。

项目既有 `app/build.gradle` 已记载该检测器兼容问题。本轮没有更改 lint 基线、禁用项或 Release 检查设置，也不将缩小范围的结果等同于全量通过。新增 490 条文案另经 AAPT 构建、全量语言 JVM 检查和独立 XML/重名/覆盖率/格式参数/换行检查验证通过。日志可用于后续单独升级 AGP、Kotlin 和依赖检测器时复核。

普通版 Release/R8 成功，但仍有 Kotlin 元数据版本兼容警告；已保留原始输出。它不影响本轮 Debug 功能回归和 Release 启动检查的实际通过结果，不能据此推断所有 Release 功能都已逐项验证。


## 设计与实际页面

本轮未重新设计 UI；沿用已保存的可编辑草图，并核对实际渲染：

- [详情与附件 M3E Canvas、设计说明](../detail-cards/README.md)
- [详情条形码 M3E Canvas、扫码和白边验证](../field-barcode/README.md)
- [按需编辑 HOTP](device/final-audit-315/content-hotp.png)
- [按需编辑 Yandex](device/final-audit-315/content-yandex.png)
- [联系/地址重编辑](device/final-audit-315/content-contact-address-reedited.png)
- [密码支付卡面与验证码](device/final-audit-315/payment-and-otp-detail.png)
- [深色大字体下的验证码复制操作](device/final-audit-315/detail-otp-before-copy-dark-large.png)
- [窄屏自定义字段操作](device/final-audit-315/detail-narrow-dark.png)
- [MDBX 快照完整参数](device/final-audit-315/mdbx-snapshot-parameters.png)
- [MDBX 冲突选择与参数](device/final-audit-315/mdbx-conflict-parameters.png)

## 可复核证据

共享工作区 `.codex-tasks/experimental-final-315/` 保存：

- `before.json`：开始时的源文件摘要和 HEAD。
- `validation-final.json`、`final-source-audit.json`：最终结果、每轮 APK 身份、改动范围及候选一致性。
- `main-jvm-final.log`、`fdroid-full-final.log`：全量 JVM 结果。
- `*-{ui,storage,search,mdbx,native,managerui}-device.log` 及同名 JSON：实际设备输出和包身份。
- `*-release-final.log`、`*-release-smoke*`：Release 构建、启动日志与页面树。
- `*-lint-final.log`、`main-lint-threads.txt`、`main-resource-lint.log`：静态检查的工具链异常及诊断。
- `translation-validation.json`：本轮全部新增文案的 XML、重名、覆盖率、参数及换行专项检查。
- `apk-audit.json`：两版 Debug/Release 的三种 ABI 包均包含有效 ELF 格式的 MDBX 和 Rust JNI 库。
- `build.ps1`、`run-device.ps1`、`device.ps1`：明确指向主目录的复现命令。

源码比较按开始时相同的 LF/CRLF 规则计算；所有 `app/src` 与各自候选一致。HEAD 保持不变，没有删除原有源码。本轮未交付或发布 APK。
