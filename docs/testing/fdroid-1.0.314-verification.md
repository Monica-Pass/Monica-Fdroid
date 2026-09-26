# F-Droid 1.0.314 验证记录

2026-09-26。此前按用户要求暂缓的 F-Droid 验证现已完成。验证对象为 F-Droid 独立仓库的实际工作区，版本 `1.0.314`、versionCode `21`、包名 `takagi.ru.monica.fdroid`。共享修复同步到主版 Android，未移植暂停中的密码内容编辑器实验。

## 验证发现并修复的问题

最初的两项原生内容设备回归均失败：KDBX 空密码被自定义受保护字段 `Recovery hint` 替代；MDBX 原生记录缺少银行卡等既有密码字段。

- KDBX 中存在的标准 `Password` 优先且原样读取，包括空字符串、空格及字面值 `password`；不再从 PIN 或任意受保护字段猜测登录密码。兼容标准字段的大小写，保持自定义字段名的原始区分。
- MDBX 保存、同步、缓存重建及原生导出保留联系人、地址和银行卡字段。旧写入方缺少字段时保留已有投影，显式空值仍可清空。卡号与 CVV 在进入原生加密记录前转换为可跨设备读取的内容，不携带设备本地密文。
- 备份与恢复保留相同字段及回收站条目的已有属性。重复导入比较完整笔记、地址和卡片内容，避免把内容不同的记录合并。新增设备回归覆盖真实文件保存、关闭重开、删除 Room 投影后重建、导出及重复导入。
- 补齐十种语言共 426 项新文案；F-Droid 的 MDBX 管理说明不再声称支持 OneDrive。

同时修正过期的源码守卫断言、Windows CRLF/XML 属性顺序处理及 F-Droid 专属测试预期。既有测试方法未删除；测试 Provider 的 authority 随包名区分，允许主版与 F-Droid 测试包共存。

## 自动化结果

| 检查 | 总数 | 跳过 | 失败 / 错误 |
| --- | ---: | ---: | ---: |
| F-Droid 完整 JVM，418 个测试类 | 1,942 | 1 | 0 |
| 原生 KDBX / MDBX 设备回归 | 85 | 0 | 0 |
| 键盘加载、搜索、设置、真实系统输入及布局 | 27 | 0 | 0 |
| API Key、图标、GPG 等设备回归 | 47 | 1 | 0 |
| 新增原生内容保存回归 | 4 | 0 | 0 |
| 导入导出、备份与恢复设备回归 | 33 | 0 | 0 |
| 管理页面、窄屏、大字号等 UI 回归 | 41 | 0 | 0 |
| F-Droid 设备合计 | 237 | 1 | 0 |
| 同步主版后的相关 JVM 回归 | 28 | 0 | 0 |
| 同步主版后的相关设备回归 | 40 | 0 | 0 |

跳过项明确保留：`SteamInventoryLiveTest.realMaFileEligibilityCheckReturnsInventoryOverview` 需要真实 Steam 资料；`NativeApiTokenUiTest.detailNavigationAfterFolderBrowsingPerformance` 为已有禁用的性能用例。数量包含跳过项，不将它们计为通过。为保留即时性能日志另执行一次已有键盘基准，未重复计入上述 237 项。

公共 `Monica_Issue136_API_32` 完成设备测试。覆盖 MDBX1 停用及升级、CLI 缺省根集合写入、增量同步/失败恢复、KDBX 3/4 字段与附件/历史/图标保留、OTP/HOTP、回收站、密钥变更、冲突及 WebDAV 条件写入。网络故障与冲突使用本地测试服务器；没有使用用户的真实密码库或线上账户。

## Release 与独立读取

Debug、AndroidTest 和通用 Release 打包成功。Release 启用 R8 与资源裁剪，包含 `arm64-v8a`、`armeabi-v7a`、`x86_64`，MDBX/Rust JNI/JNA 原生库齐全；ELF 段及 APK ZIP 的 16KB 对齐检查通过，baseline profile 已打包，APK 不可调试。已解析的 Release 运行时依赖及 APK 定义的 DEX 类中没有检查范围内的 GMS、ML Kit、Firebase、MSAL 或 Credential Provider Events 依赖。

最终 APK 为 57,383,873 字节，SHA-256：

```text
20496f9c0ab6ef94aa12864ff650fc43a39cebc2d9e9e0020a926e327fd3f083
```

| 实际检查 | 结果 |
| --- | --- |
| PyKeePass 4.1.1.post1 读取 Android 写出的 KDBX 3 和 4 | 字段名/值空白、大小写、保护状态、附件、历史、图标、标签和有效期保留 |
| 实际 R8 Release 经系统文件选择器编辑 KDBX、创建空密码条目并写回 | 独立 PyKeePass 读取通过；旧 UUID 和原有内容保留 |
| 新安装的 API 36 Release 重开上述 KDBX | 两条原生记录、大小写不同的自定义字段、标签和有效期可见 |
| 实际 Release 编辑 CLI 创建的 MDBX，并新增空密码条目 | 原 CLI API 令牌和两条 Android 登录记录同时保留 |
| 连续十轮 MDBX 编辑、保存、返回列表 | 130.91 秒内十轮完成，进程未退出 |
| 关闭 Release 进程后重新解锁 | MDBX 新条目及修改后的账号保留；GPG 指纹不变 |
| Monica CLI 0.5.0 读取 Release 实际写回的 MDBX | 独立副本读取通过；原 CLI 条目的 ID/分类/类型/标题保持不变 |
| 实际 Release GPG | 带私钥口令的 RSA 3072 生成、保存、进程重开成功 |
| 系统通行密钥 | 指纹确认创建，主密码确认登录；独立验证 ES256 签名、credential ID、RP 哈希、挑战值、Android 调用方 origin 和 UV 标志 |

通行密钥采用既有的可同步密钥零计数器策略，符合 WebAuthn 6.1.1，未错误要求计数器递增。未注册指纹的创建尝试明确返回失败；登记测试指纹后完成真实系统创建流程。系统保存位置、浅色登录及深色登录选择器中的 Monica 彩色图标已目视检查。测试调用程序通过公开 Credential Manager API 发起请求，没有对 Release 注入 instrumentation。

API 36 写回 MDBX 的独立读取样本 SHA-256：`ff9be001f0ed69218ca2de434bb48cd6b3e0712d5bab438d9f945adfcf8b2c78`。KDBX Release 写回样本：`bb723ddc7135ad4b7c3fdea5a40c4e63e0bf23dfb1d39c487610a6316f22345a`。

## 旧 API 35 镜像的异常与范围

已有 `Pixel_Fold_API_35` 使用早期 16KB 实验镜像 `AE3A.240806.043/12960925`。本次出现三次原生崩溃，解混淆后分别在 Compose 文本样式、密码列表和布局绘制路径；同机 Gboard 有相同的 null-PC/JIT 崩溃，系统服务也有独立错误。将相同 APK 改为系统 AOT `speed` 编译仍会崩溃，不能仅归因于 JIT，更不能据此认定 MDBX 导致崩溃。没有向生产代码加入未经证实的规避。

该环境无法可靠完成后续验证，因此使用已安装的 Android 16 16KB 镜像建立可复用 `Monica_API_36_16K`（`BE2A.250530.026.F3/13894323`）。相同 APK、相同输入 MDBX 在此完成新增/编辑、十轮保存/返回、进程重开、GPG 和通行密钥流程，退出记录无崩溃或 ANR。旧 API 35 镜像异常仍保留为未确定根因的限制，不能宣称所有 Android 15 设备均已验收。

本次为本地签名的真实 Release 验证，不是官方 `fdroidserver` 可复现构建认证；三个 ABI 均完成打包/对齐检查，设备运行覆盖 x86_64。没有实测所有厂商设备、第三方 KeePass 插件或真实远端服务，亦不声称与 KeePassDX 的所有功能等同。

## 性能与证据

闲置公共模拟器、3,000 条合成中文记录、24,576,000 字节笔记：加载并建索引三次中位数 **1,098 ms**（1,082–1,465 ms），首个查询中位数 **5 ms**，60 次缓存查询中位数 **962 μs**。排序已包含在加载中，不应再相加；此数值不是实机完整冷启动保证，也不是与旧记录不同负载下的严格性能对照。

- [机器可读结果与 APK 原生库检查](../fdroid-1.0.314-verification-device/results.json)
- [MDBX 连续保存后的列表](../fdroid-1.0.314-verification-device/release-mdbx-navigation.png)、[进程重开后的条目](../fdroid-1.0.314-verification-device/release-reopened.png)
- [KDBX 原生列表](../fdroid-1.0.314-verification-device/release-keepass-reopened.png)、[字段、标签和有效期](../fdroid-1.0.314-verification-device/release-keepass-detail.png)
- [GPG 重开](../fdroid-1.0.314-verification-device/release-gpg-reopened.png)
- [系统保存位置](../fdroid-1.0.314-verification-device/passkey-save-destinations.png)、[浅色登录](../fdroid-1.0.314-verification-device/passkey-get-light.png)、[深色登录](../fdroid-1.0.314-verification-device/passkey-get-dark.png)
- 已有可编辑 M3E 草图：[键盘](../design/monica-keyboard-m3e.md)、[KeePass 管理](../design/keepass-native-management-m3e.md)、[API Key](../design/ai-api-key-m3e.md)、[通行密钥](../design/passkey-provider-branding-m3e.md)。本次验证未改变界面布局。

原始构建/JUnit/设备日志、独立读写文件、验证脚本及旧镜像崩溃证据保存在主仓库 `.codex-tasks/20260926-fdroid-verification/`。测试后恢复公共设备设置，移除任务临时用户/探针与测试机上的测试锁，停止本任务启动的模拟器；保留 AVD 配置和数据盘供复用。未额外交付安装包。
