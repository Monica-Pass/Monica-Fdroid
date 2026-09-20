# 二维码编辑模式修复 · 1.0.314

二维码复用了密码编辑器，但 LoginTypeSelector 未排除 BARCODE 模式。点击“密码登录／第三方登录”会把 loginType 改成 PASSWORD 或 SSO，导致同一编辑器切换成密码表单。

现在该选择器仅出现在普通密码模式；二维码保留扫描、内容输入、应用绑定、备注、保存和顶部明确的类型切换菜单。

## 验证

- 最终 1.0.314 Debug 应用及测试包构建成功（17 分 33 秒），版本元数据为 1.0.314-26092012-01，普通版 versionCode 保持 12。
- 使用同一个二维码回归用例在旧 313 包复现：二维码表单仍找到 Password 单选控件，断言按预期失败。
- 安装 314 后，NewEntrySecurityReuseTest 的两项设备用例通过（8.658 秒）：普通密码仍可加密保存；二维码登录方式不可见，保存记录仍为 BARCODE，解密内容与输入完全相同。
- 测试使用真实编辑器组件、内存测试库及已有 SecurityManager。已检查原生页面截图；本次未重新测试相机扫描。
- F-Droid 同步源码、测试、Canvas 和记录，版本为 1.0.314 / 21，发行准备检查通过；未单独构建 F-Droid 二进制。

[可编辑 M3E Canvas](design/qr-editor-m3e.md) · [原生编辑器截图](qr-editor-device/qr-editor-314.png)

构建和设备日志留在工作区 `.codex-tmp/android-314-{final-build,qr-test-build,qr-before,qr-device}.log`。二维码回归测试包的后续编译复用已经生成的 314 构建元数据，未跳过修改过的测试源码；生产应用没有再次改动逻辑。
