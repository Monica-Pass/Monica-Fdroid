# 密码内容块格式（1.0.315，未发布）

本格式扩展密码内的内容，不改变密码项目的原生类型，不替换已有独立 API Token、API Key、SSH、GPG 或二维码模板。API Key 与 API 令牌是两个独立入口与 `kind`，不合并为一种“API 凭据”。实现依据：`data/model/PasswordContentBlocks.kt`。

## 身份与内容

每块使用 UUID，`kind` 为 `API_KEY|API_TOKEN|SSH_KEY|GPG_KEY|QR_CODE`，允许同类重复。JSON envelope：

```json
{"version":1,"id":"<uuid>","kind":"API_TOKEN","title":"示例","data":{"provider":"example","api_base":"https://example.invalid","token":"synthetic-token","notes":""}}
```

`data` 字段按 kind 分开。Key 使用 key/url/notes；Token 使用 provider/api_base/token/notes；SSH 保存公私钥、算法、指纹、注释；GPG 保存公私钥、指纹、用户身份；二维码保存 content/notes。具体字段以 codec `editableKeys` 为准。密码里的 Token 是随项目保存的令牌内容，不能因其存在就自动获得原生 Gateway 调用授权。

二维码另有可选 `mode` 与 `templateVersion`。`mode="template"`、`templateVersion="1"` 表示 content 是项目字段模板，生成时读取当前字段值；默认模式保留普通文本语义。占位符、Wi-Fi 转义和兼容要求见 [二维码字段模板](QR-FIELD-TEMPLATES-1.0.315.zh-CN.md)。`editableKeys` 仅列出通用内容表单的键，模板编辑器同时管理这两个模式字段。

## 传输

采用现有受保护自定义字段，无新增 Room 表或 MDBX 原生类型。

- manifest 字段名：`monica.content.block.<uuid>`。
- manifest 值：`{"version":1,"encoding":"base64","parts":N,"sha256":"<UTF-8原始JSON字节的SHA256>"}`。
- 片段字段：`monica.content.block.<uuid>.0000`，后续四位十进制顺序递增。
- 完整 JSON UTF-8 字节经 Base64 编码后，每段最多 1600 字符；所有片段和 manifest 都标记 `is_protected=true`。Base64 不是加密，仍依赖原有加密字段、数据库及归档链路。
- 单块最大 256 KiB，超限拒绝保存，不截断；外部后端若还有总字段数限制，必须明确失败，不静默丢片段。
- `monica.content.order` 使用 `BLOCK:<uuid>` 与原有 section token 交错表示顺序；它依然是逗号分隔字符串。

旧端不认识此格式时必须保留全部字段；编辑普通密码不应丢弃这些字段。现有旧软件是否满足这一要求需实际往返验证，本文件不宣称所有历史版本都已支持。

## 失败与编辑

缺片段、重复字段、非法 UUID、摘要不符、未知版本/类型或已知字段形状改变时，展示不可编辑状态并保留原始字段。不能用空表单替换坏数据。编辑基于原 envelope patch，保留不认识的顶层/内部键和大数字原值；只在明确确认删除某个可读取内容块时删除该块的 manifest、全部片段和顺序引用。

编辑底部面板中取消/返回不提交草稿。详情中的秘密默认隐藏，复制显式触发；公私钥可以导出，二维码需主动查看。来源项目的 OTP 等字段临时生成二维码仍属于字段操作，不另存重复内容。

## 验证状态

二维码字段模板的新增验证记录见 [模板验证说明](QR-FIELD-TEMPLATES-1.0.315.zh-CN.md)。下列记录对应基础内容块接入时的构建。

普通版与 F-Droid 均已完成 Debug／AndroidTest 打包，各通过 6 项格式单元测试、10 项 Android 32 设备测试。复用公共模拟器，不清除已有数据。运行记录、APK 校验值及源文件摘要见 [验证报告](credential-blocks-315-verification.json)。

- 实际新建页连续添加全部五类及第二个 API Key，完整保存和重开；验证取消编辑不改原值、详情秘密默认隐藏，混合笔记仍可编辑。
- 实际长按拖动、删除确认／取消、取消新增；过大二维码明确报错且原内容仍可完整查看。
- 长中文、换行和分片数据经过 MDBX 原生写入／重开／投影重建、KeePass 受保护字段、加密备份恢复和重复导入后完整保留。
- 缺片段、重复片段、摘要损坏、未来版本／类型／字段形状、超限编辑均有验证，不以空数据覆盖原内容。

本轮未新增真实 WebDAV／OneDrive／Bitwarden 服务端传输测试，不代表已验证所有历史客户端。没有发布版本或交付安装包。设计见 [Canvas 草图与实际截图](design/credential-blocks-315/README.md)；在线站点受保存的浏览器权限限制，本地草图可编辑，在线预览未验证。
