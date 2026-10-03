# 通行密钥扫码菜单验证（1.0.316）

验证日期：2026-10-02。普通版与 F-Droid 均已接入。

- 密码库概览、验证器、通行密钥右上角菜单复用密码页的扫码菜单项；先关闭菜单，再调用现有 FIDO 扫码导航。
- 验证器保留原 OTP 扫码，通行密钥扫码使用独立回调。
- 概览仍可打开布局调整；Passkey 页在显示底部导航时也能打开更多菜单。前往验证器的快捷操作收进菜单，常规顶栏维持分类、搜索、更多三个按钮。
- 紧凑、宽屏和可拖动导航分支均已接入同一回调；生产代码继续进入 MainActivity 的 Screen.FidoQrScan。

## 构建与测试

两个发行版均通过 `:app:assembleDebug :app:assembleDebugAndroidTest -PincludeX86TestAbi --max-workers=2`。
复用公共 AVD Monica_Issue136_API_32（API 32，x86_64）；使用覆盖安装，没有清除现有应用数据。

每版通过 4 项仪器测试，共 8 项：

1. Passkey 菜单入口可达、搜索与更多按钮不重叠、点击扫码后菜单关闭、前往验证器仍能使用、搜索仍可打开。
2. 验证器 STANDARD 布局：两个扫码回调互不混用，并保留多选、二维码、删除确认行为。
3. 验证器 TILE 布局：同样覆盖扫码和原有列表交互。
4. 密码库概览：触发扫码回调、返回后菜单关闭、布局调整仍可打开。

测试使用内存数据库中的合成项目。结果见 [instrumentation-results.txt](instrumentation-results.txt)。
实际渲染截图保存在本目录 android-*.png；已检查菜单文字、图标和圆角，以及 Passkey 页按钮布局。
公共模拟器窄屏下英文长标题仍使用现有顶栏的裁切行为；本次移入菜单的快捷入口避免额外按钮覆盖标题。

本轮验证覆盖菜单与导航回调，不代表进行了真实跨设备 FIDO 认证。扫码解析、相机权限与系统认证分发复用原有实现，本次未修改。

## 设计

草图使用本地 M3E Canvas 的 topAppBar 与连续分组 listItem；可编辑源文件为 [canvas.json](canvas.json)。
本地浏览器连接器本轮不可用，视觉验收使用 Android 原生截图；分享链接已验证能往返解析为同一草图。

[打开本地 Canvas 草图](http://127.0.0.1:5186/#docz=1VZbTxNBFP4rZJ5rUhok2DeNMSGG-AMMIUM7tRu2u-vsbAUJSQtyMy0lCFEuRoogCAlI5FIukcS_QmcvT_wFz3SWbQtammCIvuzOnjlnzved851pBxFTmEpQFHXpmhLDLT8PW7zMglvM8Z0xb2bNntxylrN8dI0XPqMQ6qUKSYAzX807sxvgAptecZ8XvpdLb931Gb40Bk-3sMTzc-XSlIz2ikcXp4vSmR-_s78Mu-sT5bOPztw8L43w3Yz7bcSe27s4zXmbOXcny-c3_N2pT4FFHiV8atDJRLxU8r6eQApn-IiPn_DRCXv2wN0_9HfH8u7ZomAwnSuXMuXSZsuTzsfPWuSB7uGOM7t7nhmuY-0VD-zlgg9iYszZPTnPZO3pJWdvRSwW9_jqQrk0CWfZuUlvZlvQm8zzH5kArrPwhp9t2flDWTpnHWD5cAWH7BkfzXvZbTtXtJfHvZUPgADKm6A4JXphJHWNwLehYpbQaQpMWItTXYkLI1YJY-QpGRCeFjVU4cqSRIQOojimfSiawKpJoF86S3bpcWKiKKMWGMwkNkQGqltanMTRkJ8UHJ4PIjg_itLYUhmcqEksQd_A1I-i4RCCvOGhkO_NdGZUnQP-0rmt7aq7gU2zD5BXI2rKLoM6Oi6DukPoBQA1roK7lyQ4TmgdoBDC_Qo4gimEFEZS14Kk0EOoT9EkcOOhYTzC4hgV9xL1ClclpmsoqlmqKtcR2E_plPSkCRXlSWOqYI2BNaGoqihld8BSJkwRzZIYWyMS5YP2AObAH2CGqwhVxWSd4FILsGZeAoyIWRW1iK8ngAUsmtSPqbyGGkfaw3IJFMTyGvIQiulUI9QU-mEQL-AyKt-9aqW6vVR0pJ5ha0OsMGB2oVDb3_JxzjnZgwpXob-kPTHQZ48Zw5pWaenfY-GT8DlEfBIRYNFdJ986OQWSbSSoSlQTeqodh9voqZKvKqf77c3ISQSFm-jQHXflZm0J4P-VtH5PIdJ4kifeu8UNOc9VzGmFvOoR3nc-CP7FXDcLwU3caBYuA5sZh_q7_jYTcZm15o4Nh5uZCj8w_G_r6-YhuSwAtuCXX2Pw143ptCGp65cRMknMogobuDO1dQ_9Ag)
