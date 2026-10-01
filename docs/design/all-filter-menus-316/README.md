# 全页面统一筛选菜单

本地可编辑草图见 [canvas-url.txt](canvas-url.txt)，源文件 [canvas.json](canvas.json)。

共用 MonicaFilterMenu 和 FilterMenuDatabaseSection：右上角动画菜单、460dp限高、12dp左右留白、缩放一致、圆角按压。管理按钮固定在内容滚动区外，数据库大量条目继续使用按需绘制。

- 密码库／密码：保留内容筛选与拖拽排序；概览的纯数据库菜单始终展开。
- 验证器：保留标星、未分类及分类文件夹。
- 通行密钥：数据库及分类，不显示空快捷筛选区。
- 卡包：保留银行、证件、账单地址类型。
- 笔记：独立标签标题，保留标签选中／取消，补齐已支持的 MDBX 数据库入口。
- Steam：保留原支持的数据源，始终展开，不显示折叠控件；文件夹按钮触发，弹窗与其他列表页一样锚定在最右侧操作按钮，避免菜单向左偏移。

Steam 完整顶栏定位草图见 [steam-canvas-url.txt](steam-canvas-url.txt)（本地可编辑），以及 [steam-canvas.json](steam-canvas.json)。

## 验证

普通版和 F-Droid 各13项设备测试通过（API32）；覆盖上述菜单组件的合成数据场景、低DPI、圆角按压、开合、拖拽，以及512个数据库的按需绘制和选择。截图是菜单测试场景，使用测试分类、标签及数据库。

2026-10-02 Steam 锚点修复：普通版、F-Droid 均完成编译，本轮各3项 UnifiedFilterMenusTest 设备测试通过。新增回归直接渲染真实 SteamRootTopBar，使用屏幕坐标比较弹窗和页面边缘，覆盖65%、85%、100%、130%界面缩放，以及数据库选择、关闭、重开保留选中状态和返回键关闭。未更改数据层。

截图 `steam-topbar-*.png`（普通版）、`fdroid-steam-topbar-*.png`（F-Droid）为真实顶栏组件加合成数据库的设备测试画面，不是用户账号数据。
