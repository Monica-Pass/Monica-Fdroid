# 卡包卡叠详情返回偏移修复

日期：2026-09-20。范围：卡包卡叠位置恢复；不改布局或导航动效设计。

## 问题与修改

录像反馈路径：卡包列表 → 打开卡叠 → 打开卡片详情 → 返回卡叠 → 收起回列表，收起目标向右下偏移。

`WalletStackCard` 原先每次全局定位均写出 `Rect(positionInWindow, size)`。前者包含祖先导航图层的缩放/位移，而 size 是未变换的布局尺寸。详情进出动画期间重新布局，会把这个混合坐标矩形覆盖进卡包的 `stackCoverBounds`，并传入 `WalletStackPreview`，用于浏览器的收起目标。概览入口已有动画状态过滤，卡包入口遗漏了它。

修复在共用卡叠组件的测量回调执行时直接检查当前导航状态：只接受导航静止且 currentState/targetState 都为 Visible 的窗口坐标；无导航作用域时仍接受正常测量。卡包入口同时排除分栏布局中详情仍可见时的测量。保持正常滚动、列表布局更新的重新定位能力。

代码：

- `app/src/main/java/takagi/ru/monica/ui/cardwallet/WalletStackCard.kt`
- `app/src/main/java/takagi/ru/monica/ui/screens/CardWalletScreen.kt`
- `app/src/androidTest/java/takagi/ru/monica/ui/cardwallet/WalletStackOriginNavigationTest.kt`

## 验证计划与结果

测试直接组合生产 WalletStackCard、WalletStackPreview、WalletStackBrowser 和 NavHost。

1. 动画中触发真实重新布局，检查未过滤的实际窗口坐标已经移动，但缓存的原位矩形不变。
2. 连续三次卡叠 → 详情 → 返回 → 收起，比较收起动画最后一帧和列表卡面的左上位置、宽高。
3. 旧 APK 先运行相同测试，再安装修复 APK 对照。

结果：

- 修复前 APK `1.0.313-26092012-02`：相同的两项测试中，坐标污染测试失败。返回动画重新测量后，left 从期望的 42px 被覆盖为 79.66336px；普通稳定往返测试通过。说明需要覆盖动画中重新布局，单纯检查“能够返回”不足以发现问题。
- 修复 APK `1.0.313-26092012-04`：两项新测试加三项已有浏览器回归，全部通过（5 项，18.2 秒）。包括恢复同一焦点卡片、普通收起交接、循环卡叠收起交接的坐标和像素断言。
- 测试设备：公共 AVD `Monica_Issue136_API_32`，Android 32 / x86_64，动画开启。
- 往返三次后的完整测试画面与初始画面均为 840×1995，RGB 像素比较完全一致；也已人工查看截图。
- `assembleDebug` 与 `assembleDebugAndroidTest` 构建成功。修复后只调整过测试的同步等待，测试包单独重建成功；生产源码未在首次成功构建后修改。
- 生产源码与测试已同步到 F-Droid 对应目录；F-Droid 本轮未单独构建。发行说明已更新，版本保持 313。

证据（相对工作区根目录）：

- `.codex-tmp/card-stack-origin-baseline.log`：旧版真正的坐标失败，不含此前测试同步问题的首次试跑。
- `.codex-tmp/card-stack-origin-fixed.log`：修复版 5 项设备测试。
- `.codex-tmp/card-stack-navigation-build.log`、`.codex-tmp/card-stack-test-build.log`：构建记录。
- `docs/card-wallet-return-device/before.png`、`after-three-returns.png`（相对 Android 工程）：修复版真实渲染截图。

范围限制：使用生产卡叠组件、预览缓存和真实 NavHost 的独立设备测试场景；没有导入用户录像中的真实卡片数据，也未模拟所有分栏、旋转、进程重建情形。旧 APK 与修复 APK 的导航时长包含上次独立调整，因此这里只比较应保持不变的原位坐标，不据此声明动画性能提升。
