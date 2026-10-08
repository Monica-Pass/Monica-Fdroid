# 姓名与地址输入建议验证（1.0.318）

2026-10-08；普通版与 F-Droid。

## 范围

- 持卡人、证件、账单及旧联系人完整姓名共享候选；姓、名、中间名分别匹配，不音译、不拆分完整姓名。
- 街道、门牌、第三行地址、省市、邮编、国家按字段匹配；签发机关继续支持同类建议。
- 读取独立卡包、密码内嵌内容、旧的密码字段与已保存常用姓名／账单地址；不修改存储格式，不记录未保存输入。
- 仅在当前字段有输入且获得焦点时展示最多三项，点选只更新当前字段；锁定、删除和不可访问来源不参与建议。

## 构建与测试

- 两版 `assembleDebug`、`assembleDebugAndroidTest` 构建成功，测试产物哈希见 results.json。
- 每版 13 项 CommonFieldSuggestionsTest 单元测试通过。
- 每版 7 项 CommonFieldSuggestionRepositoryTest + 7 项 CommonInfoSuggestionUiTest 设备测试通过；逐类启动独立 instrumentation。
- 数据测试：加密资料、多种存储形态、内嵌地址、旧字段、常用模板、损坏数据、锁定与删除来源。
- 页面测试：实际银行卡、证件、账单编辑页点选；姓名选择不改姓／名／中间名和证件号码；账单保存重开保持新姓名／街道及其他原值。
- 17 种字段均覆盖 320dp 控件宽度、1.7 倍字体、键盘可见时点击与继续输入；包含失焦隐藏、锁定隐藏及页面重建。
- 使用合成数据及内存数据库；没有操作用户密码库。公共 AVD 的配置与数据盘保留，测试恢复原用户与设置。

## 额外检查限制

额外执行页面性能源码检查 `BiometricUnlockRegressionGuardTest.pageSwitchHotPathsDoNotRunAuthOrBitwardenSyncWorkOnMainThread`，在第 804 行“Password editor load must move entry lookup…”断言失败。将普通版 HEAD 97a9ce9e606d10c6d0aa112179639dfba6378c9f 的引用源文件导出到独立目录，以同一测试复核，得到同一失败。这是未修改的密码编辑器源码断言；没有为此改动业务代码或放宽该断言。此次仅更新该测试后面的姓名建议惰性加载断言，使其识别新的空索引类型。不能将本次验证描述为完整回归套件全绿。

## 界面记录

[可编辑本地 M3E Canvas 与草图](../../design/common-identity-318/README.md)。真实页面截图见同目录 main/ 与 fdroid/；虚拟机结果不代表所有厂商设备与输入法均已覆盖。
