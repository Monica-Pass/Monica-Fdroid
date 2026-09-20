# 生成结果操作菜单 · 2026-09-20

## 行为

- 随机符号、单词、短语、PIN：点击整张结果卡片弹出菜单，提供复制、使用此用户名新建条目、使用此密码新建条目。现有快捷复制按钮继续可用。菜单预览最多两行，复制与草稿传递使用完整字符串，PIN 保留前导零。
- SSH：独立结果卡片，点击卡片或右上角三点打开同一个菜单。分别复制 OpenSSH 公钥、SHA256 指纹及 OpenSSH 私钥。私钥使用敏感剪贴板标记，菜单不显示私钥预览。
- GPG：保留已有点击卡片／三点打开复制、导出、创建条目的菜单。
- 从生成器打开的普通密码编辑页和 GPG 编辑页使用独立全屏 Dialog，隔离宿主刷新 FAB，草稿保持内存状态。使用现有编辑器和保存路径。

[可编辑 M3E Canvas](design/ssh-generator-actions-m3e.md)

## 验证

Debug 应用和测试包构建通过。公共 Android 32 x86_64 虚拟机、320dp 宽度下共 14 项唯一用例通过：首轮 13 项通过，用户名草稿测试因页面第二次延迟初始化生成覆盖测试值失败；显式推进 Compose 时钟完成初始化后，该用例单独重跑通过（7.831 秒），生产实现无需为测试修改。已检查普通结果和 SSH 原生菜单截图。新增普通结果完整复制／两种草稿回调测试、SSH 三种内容与敏感标记测试、真实生成器打开用户名草稿测试、GPG 编辑器保存按钮可见性测试。

SSH 本次菜单实现复制公钥、指纹、私钥；文件导出和从 SSH 结果创建条目尚不在该菜单中。GPG 原有导出操作保留。

- [实际密码页面菜单](generator-menus-device/password-menu-page.png)
- [SSH 菜单](generator-menus-device/generator-menus/ssh-menu.png)
- [PIN 菜单](generator-menus-device/generator-menus/pin-menu.png)
- [首轮设备日志](generator-menus-device/device-initial.log)
- [用户名草稿最终测试](generator-menus-device/draft-final.log)

本轮验证 Debug 包；未重建 Release 或 F-Droid 包。之前 GPG 的 R8 保留规则保持不变。测试没有新增或更改生产数据库格式。
