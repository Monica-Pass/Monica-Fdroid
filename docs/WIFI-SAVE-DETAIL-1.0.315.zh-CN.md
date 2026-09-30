# Wi-Fi 保存与详情修复验证（1.0.315 未发布）

## 修复

- 新建 Wi-Fi 的安全性和隐藏网络使用连续圆角分组，安全类型显示本地化名称，底层枚举不变。
- 详情显示网络名称，支持名称菜单中的复制、连接与二维码，以及直接连接／二维码按钮。连接会复制已有密码并打开系统 Wi-Fi 设置，仍由用户选择网络连接。
- MDBX 写入和读取补齐完整 `wifi_metadata`，共享导入读取支持旧别名。保留高级网络参数与未知 JSON 字段，不作删减或重建。
- 旧记录的 SSID 缺失或为空时用标题回显。未知／损坏的安全类型或不可读取的密码不会生成看似可用的错误二维码，企业网络延续不支持通用 Wi-Fi 二维码的提示。

## 验证

普通版与 F-Droid 均完成 debug 打包，各通过 30 项单元测试、7 项 Android 设备测试（Android 32，公共 AVD 的隔离 user 10）。

1. 修复前真实 MDBX 测试复现：写入带 SSID、安全类型、隐藏网络和未来字段的记录后，原生 payload 的 Wi-Fi 元数据为空。
2. 修复后关闭原生读取会话再读，元数据完全一致；仅移除合成测试记录的 Room 缓存后，用真实 MdbxViewModel 重新载入，网络名称与完整原始 JSON 一致。
3. 实际填写新建 Wi-Fi、保存并打开详情，保留密码前后空格和含特殊字符的 SSID；成功显示二维码，并确认系统 Wi-Fi 设置 Activity 真正进入前台。
4. 验证旧记录标题回显、未知高级参数、未来安全类型、深色表单、本地化安全类型、不可读取密码及系统设置启动失败处理。
5. 保留已有 API Key／内容块测试。首轮 UI 测试的外层语义节点定位和第二用户无障碍窗口缓存问题已修正；最终完整测试通过。

未执行真实路由器接入；系统设置跳转已实测。对修复前已丢失且无其他副本的高级 Wi-Fi 配置，不能凭空恢复，标题回显仅补足可获得的网络名称。

测试仅使用合成内容，未清空用户应用数据或已有数据库；已恢复前台用户与隔离用户设置，保留原先运行的公共 AVD。此次范围内 12 个源代码／测试文件在普通版、F-Droid 和两份候选中完全一致。

## 设计与截图

[本地 M3E Canvas 可编辑草图](design/wifi-save-detail-315/README.md)

- [新建页](design/wifi-save-detail-315/wifi-editor.png)
- [详情页](design/wifi-save-detail-315/wifi-detail.png)
- [Wi-Fi 二维码](design/wifi-save-detail-315/wifi-detail-qr.png)
- [真实系统 Wi-Fi 设置](design/wifi-save-detail-315/wifi-system-settings.png)

构建、复现与设备测试原始日志在工作区 `.codex-tasks/wifi-save-detail-315/`，`verification.json` 记录源文件 SHA-256、测试 APK SHA-256 和恢复设置情况。
