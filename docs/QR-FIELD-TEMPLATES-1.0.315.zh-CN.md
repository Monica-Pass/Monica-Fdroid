# 项目字段二维码模板（1.0.315，未发布）

对应 [issue #141 评论](https://github.com/Monica-Pass/Monica/issues/141#issuecomment-5890089033)。本功能是通用字段模板，Wi-Fi 是内置预设。

## 使用

- 密码详情的字段菜单 → 显示二维码／条形码 → 自定义二维码模板。
- 新建／编辑密码 → 添加内容 → 二维码 → 字段模板。
- 插入字段只显示名称；编辑时可生成预览，明确保存后成为项目中的二维码内容块。取消不保存。
- 保存的是模板文本，生成时再读取当前项目字段。修改密码后再次生成会使用新值。普通文本模式不会解释占位符。

## 跨端格式

复用 [密码内容块](PASSWORD-CONTENT-BLOCKS-1.0.315.zh-CN.md) 的 `QR_CODE`，manifest、分片及加密传输保持一致。示例 `data`：

```json
{"content":"WIFI:T:WPA;S:%ACCOUNT%;P:%PASSWORD%;H:false;;","notes":"","mode":"template","templateVersion":"1"}
```

`mode` 缺省、空或 `literal` 视为原有普通文本；只有 `template` 且 `templateVersion` 为字符串 `"1"` 才展开。新端将未知模式／模板版本保留为只读。旧端可能仅显示占位符，但必须保留未知键；不承诺旧端具备动态生成能力。

| 占位符 | 数据来源 |
| --- | --- |
| `%ACCOUNT%` | 当前凭据 username |
| `%PASSWORD%` | 当前凭据解密后的 password；不可读不是空密码 |
| `%TITLE%` | 项目 title |
| `%URL%` | website 原值（包含原有多网址编码） |
| `%EMAIL%` / `%PHONE%` | email / phone |
| `%NOTES%` | notes |
| `%FIELD:<name>%` | `<name>` 为字段名称 UTF-8 的无 padding Base64URL；按原始名称精确匹配，Room ID 不参与 |
| `%%` | 一个字面量 `%` |

只执行一次替换，插入值中的占位符不会再次展开，不执行脚本、网络请求或任意表达式。字段名称重复、未知字段或无法解密时阻止生成并提示字段名；已存在的空字符串允许使用。保留其他普通文本和 Unicode。

模板以 `WIFI:` 开头（大小写不敏感）时，替换值中的反斜杠、分号、冒号、逗号、双引号加反斜杠转义，模板中的固定分隔符不变。通用模板不添加 Wi-Fi 转义。预设为 WPA、非隐藏网络，用户可修改固定参数。

模板与展开结果均限制为 256 KiB；实际二维码容量更低，编码失败明确提示而不无限加载，不截断内容。展开后的秘密仅用于当前预览，不写回模板、配置或日志；保存二维码图片属于用户显式导出。

## 验证

2026-09-30 已补完两版最终设备验收。二维码模板与内容块的 14 项单元测试包含在各版 55 项通过结果中；页面操作、拖动排序、模板保存、MDBX／KeePass 重开、修改字段后的动态生成及原生回归包含在各版 25 项设备测试中，全部通过。

此前公共 AVD 空间不足导致的中断已解决：按用户授权扩容原数据盘，复用同一台 Android32 x86_64 AVD 并保留数据，无需新建 AVD。历史失败与中断仍保留在验证 JSON 的历史记录中，不计为通过。

已有真实渲染截图见 [编辑面板](design/qr-template-315/template-editor.png)、[生成二维码](design/qr-template-315/template-generated-qr.png) 和 [深色大字体错误状态](design/qr-template-315/template-error-dark-large-text.png)。本轮另外通过两版 MDBX 本机真实 WebDAV 附件／移动／冲突同步；未执行真实 OneDrive 服务测试，不能以此证明所有云端模板流程通过。

证据与兼容性边界见 [模板验证记录](qr-template-315-verification.json) 和 [最终整体验证](pending-content-315-verification.json)。保留[可编辑 Canvas 草图](design/qr-template-315/canvas.json)，以后通过本地 M3E Canvas 使用。
