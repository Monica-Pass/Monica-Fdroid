# Monica Pass 通行密钥品牌资料

2026-09-30 已向公开 Passkey Provider AAGUIDs 资料库提交 [PR #121](https://github.com/passkeydeveloper/passkey-authenticator-aaguids/pull/121)，等待上游审核。

## 对外标识

| 项目 | 内容 |
| --- | --- |
| 显示名称 | Monica Pass |
| AAGUID | `6d6f6e69-6361-4d33-a001-706173736b79` |
| 矢量图标 | [monica-pass.svg](monica-pass.svg)，128 × 128，纯 SVG |
| 资料条目 | [aaguid.json](aaguid.json) |
| 提交 | `3960429efe5d3dde539f146fbb0103638ab92da6`，仅新增一条资料 |

这是 Android 现有注册响应一直使用的标识，不是新生成或替换的 AAGUID。普通版、F-Droid 和两份候选代码已核对一致。不要为了让某个网站显示品牌而更换标识或借用其他管理器的 AAGUID。

图标沿用当前应用的笑脸锁、黄色钥匙和四色点缀，转换为无位图嵌入、无外部链接、无脚本的矢量版本。图标自身带深色背景，`icon_light` 和 `icon_dark` 使用相同资源。PNG 只供人工预览，不写入资料库条目。

![矢量图标预览](monica-pass-preview.png)

## Telegram 显示为什么还需要等待

截图中的页面是 Telegram 自己的已注册通行密钥列表。其客户端直接使用服务端 `Passkey.name` 和 `software_emoji_id`：名称为空则显示通用“通行密钥”，图标为空则显示通用钥匙。它不会从手机里读取 Monica 的应用名称和桌面图标来填充这一行。

Monica 已在注册响应中发送自身 AAGUID。本次补充的是公开品牌映射，Google Play 上架不是该资料库的收录前提。上游合入后，使用该资料库的服务还需更新目录；目前没有证据证明 Telegram 自动使用此资料库。Telegram 自己的品牌映射及专用图标可能仍需它单独收录，不能承诺合入即生效或旧记录自动补齐。

资料仅用于显示名称和图标，不是 FIDO 认证声明，也不是凭据真实性或信任等级的证明。没有修改注册／签名流程、已有凭据、数据库、备份格式或 Android 系统选择器，因此本轮无需更换 APK 才能保留现有标识，也无需重建用户的通行密钥。

## 已完成校验

- 候选条目和完整上游 JSON 均通过上游 Draft 7 Schema。
- 除新增 Monica 条目外，全部既有条目内容保持一致；未手工修改生成文件 `combined_aaguid.json`。
- 两个 SVG 字段可正确解码，尺寸、方形 viewBox、纯矢量元素和无外部引用均已校验；图标经过实际渲染检查。
- 四份 Android 注册实现中的 16 字节 AAGUID 均与条目 UUID 一致；原有 Android 运行时代码未修改。
- 仅资料库专用副本创建了提交与 PR，未把 Monica 主仓库的其他未提交工作上传。

本地证据：工作区 `.codex-tasks/passkey-provider-registry/validation.json`、`pr-body.md` 和专用 `upstream/` 副本。

## 参考

- [现有 Monica 注册实现](https://github.com/Monica-Pass/Monica/blob/49c6faba459e2d6c0e2f1b3ec0ade6b507615017/Monica%20for%20Android/app/src/main/java/takagi/ru/monica/passkey/PasskeyCreateActivity.kt)
- [Telegram Passkey 数据字段](https://core.telegram.org/constructor/passkey)
- [Telegram 列表渲染实现](https://github.com/DrKLO/Telegram/blob/dc780e81ed1261c369c27870e8e0999a1eb0b600/TMessagesProj/src/main/java/org/telegram/ui/PasskeysActivity.java)
- [上游资料格式与贡献说明](https://github.com/passkeydeveloper/passkey-authenticator-aaguids)
- [本地可编辑品牌草图](../design/passkey-registry-brand/README.md)
