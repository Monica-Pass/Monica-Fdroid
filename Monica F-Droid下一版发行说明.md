# Monica for Android (F-Droid) 1.0.319

> 未发布 / Unreleased

## 中文

- 移除喵喵语、芝士雪豹语和雪豹彩蛋图标，原始语言与图标资源归档至“彩蛋语言备份”；旧语言设置自动恢复简体中文，保留升级后的正常桌面入口。

- 附近同步使用常驻状态区显示准备、传输、保存、失败与完成，避免进度条和提示出现时推挤设备卡片；诊断补充实时阶段、所选数据库、默认网络、连接与发现统计、重试及最近成功时间，并提供对应处理建议和可复制信息。

- 修复附近同步引用解析错误：普通扩展字段不再被当作对象 ID，保留原始内容；诊断异常类型不再显示混淆名称。
- 自动同步与手动同步独立：关闭自动同步后仍可点击“立即同步”，支持取消本次操作；两端均手动时需分别点击。同步数据库默认“本地”，可选择受支持的 MDBX 库，发送范围和接收目标均限所选库，切换不会删除其他库内容。

- 修复附近同步准备内容时的文件夹标识缺失异常：本地 Steam 等条目使用关联 Bitwarden 的共享分类时保留文件夹归属，不复制外部账户绑定；本地 Passkey 遗留 MDBX 文件夹引用不再阻断同步。准备失败诊断增加具体操作位置，不记录凭据内容。

- 新增附近设备加密同步：同一可互通 Wi-Fi／局域网中扫码或粘贴链接配对，自动或手动同步本地密码、验证器、Steam 账户、通行密钥、笔记、卡片、文件夹与附件；支持双向、仅发送及仅接收，离线冲突保留副本。Bitwarden／Vaultwarden、KeePass 继续使用各自同步。
- 发送前复用通行密钥迁移弹窗，说明仅本地与跨平台限制，可取消同步或了解后继续；不可导出密钥只同步凭据信息，可导出密钥在接收端重新加密。锁定或编辑时暂停保存，校验和保存完成后才记录成功。
- 附近同步使用 M3E 分组设置与底部弹层，生成二维码时可复制链接；统一附近同步和扫码转场，扫码返回停留在附近同步页，修复快速失败或取消配对导致的前台服务闪退。
- Steam 本地账户同步保留令牌、会话、未知 maFile 字段及文件夹，接收端重新加密；同 Steam ID 的独立副本不合并，当前选中账户保持本机设置。
- 附近同步使用开源组件与 Android 局域网接口，不依赖 Google Nearby、Play 服务或云中转，保留 F-Droid 的源码构建与功能差异。

- Steam 筛选复用密码与通行密钥页面的数据库／文件夹菜单，支持本地分类、MDBX 文件夹、KeePass 分组及 Bitwarden／Vaultwarden 文件夹；记住所选文件夹，并可与搜索组合。切换同库文件夹不重复加载或同步账户，共用菜单支持从 MDBX 子文件夹逐级返回。

- Passkey 和 Steam 支持选择数据库内的文件夹进行移动或复制，也可在当前数据库中整理归属；修复 Passkey 目标选择器未加载 MDBX 文件夹的问题。
- Passkey 复制保留源记录；操作前检查绑定、引用、私钥及目标限制，遇到无法处理的条目可取消或跳过后继续，并显示逐项结果。要求凭据 ID 唯一的 KeePass／MDBX 不支持同库复制，可改选其他数据库。
- Steam 复制生成独立条目，同库移动保留原记录；新增本地分类归属并迁移旧数据，修复 MDBX 后续编辑或刷新会话时回到根目录的问题。跨库移动在目标写入成功后才清理源记录。

- 修复通行密钥本地数据库页面反复出现同步状态：按当前数据库和文件夹范围触发刷新，跳过无目标、无法读取或已更新的 KeePass 索引；本地页面不再显示其他 Bitwarden／Vaultwarden 账户的同步进度。
- 缓存通行密钥的“密钥不可用”“跨端受限”等标记，返回列表或打开详情时立即复用检查结果；密钥删除、恢复、兼容性参数或解锁会话变化后重新检查，减少重复加载与标记闪烁。

- 修复通行密钥列表进入详情后返回会重置为“全部”的问题：保留所选数据库及文件夹，重新打开后恢复上次选择，避免页面重建时用默认值覆盖筛选设置。

- 修复旧 Passkey 在刷新或合并时可能改变已使用的备份资格标志：保留既有凭据的注册属性，冲突时保留原数据；新导入仍遵循来源标志。
- 修复修改 Bitwarden／Vaultwarden Passkey 时可能覆盖同一登录项中的其他密钥、密码、网址和自定义字段；更新前读取完整条目，使用版本校验并回读确认，失败或冲突时保留本地数据；保留用户主动移动文件夹和移回根目录的操作。
- 通行密钥列表在格式旁标记“仅本地”“跨端受限”或“密钥不可用”，详情解释限制原因；支持小屏及大字体换行。

- 修复仅绑定 Bitwarden／Vaultwarden 时密码库首页和搜索可能看不到已同步条目：未保存数据库范围时默认显示“全部”，旧版省略范围的设置同步采用该默认值；明确保存的本地库或具体数据库选择继续保留，锁定库仍不参与展示与搜索。

## English

- Removed the Nya and Snow Leopard Easter egg languages and Snow Leopard icon. Original resources are archived in “彩蛋语言备份”; existing selections migrate to Simplified Chinese while preserving a working launcher entry.

- Nearby sync keeps preparation, transfer, saving, failure and completion in a stable status panel so progress updates do not shift device cards. Diagnostics now show the live stage, selected database, default network, connection/discovery counts, retry timing and last success, with actionable guidance and copyable details.

- Fixed Nearby reference resolution: opaque extension fields are no longer mistaken for object IDs and are preserved unchanged. Diagnostic exception types remain readable in release builds.
- Manual sync works with automatic sync off and can be cancelled; when both devices use manual mode, tap Sync now on each. The sync database defaults to Local and can be changed to a supported MDBX vault. Sending and receiving use only the selected store; switching stores does not delete other contents.

- Fixed missing folder identities aborting Nearby sync preparation. Local Steam and other entries retain shared categories linked to Bitwarden without copying external account bindings; stale MDBX folder references on local Passkeys no longer block sync. Preparation diagnostics identify the failing operation without recording credential content.

- Added encrypted Nearby sync: pair by QR code or link on the same reachable Wi-Fi/LAN, then sync local passwords, authenticators, Steam accounts, Passkeys, notes, cards, folders and attachments automatically or manually. Supports two-way, send-only and receive-only modes, preserving offline conflict copies. Bitwarden/Vaultwarden and KeePass keep their own sync mechanisms.
- Reused the Passkey transfer dialog before sending to explain local-only and cross-platform restrictions, with options to cancel or continue. Non-exportable keys sync metadata only; exportable keys are encrypted again on receipt. Incoming saves pause while locked or editing; success is recorded after validation and saving.
- Nearby sync uses M3E settings groups and bottom sheets, with copyable links beside generated QR codes. Nearby and scanner transitions are consistent, scanner Back returns to Nearby, and immediate failure or cancellation no longer races foreground-service startup.
- Local Steam account sync preserves tokens, sessions, unknown maFile fields and folders, with destination encryption. Independent copies sharing a Steam ID remain separate; the selected account remains a local preference.
- Nearby sync uses open-source components and Android LAN APIs, without Google Nearby, Play Services or a cloud relay; F-Droid source-build and feature differences are preserved.

- Steam now shares the database/folder filter menu used by passwords and Passkeys, supporting local categories, MDBX folders, KeePass groups and Bitwarden/Vaultwarden folders. Folder selections persist and work with search. Switching folders within a database does not reload or sync accounts; the shared menu also supports navigating back through MDBX parent folders.

- Passkey and Steam entries can now be moved or copied into folders, including organization within the current database. Fixed MDBX folders not loading in the Passkey destination picker.
- Passkey copies preserve the source. A preflight check identifies bound entries, references, unavailable keys and destination restrictions, with options to cancel or skip blocked entries and continue, followed by per-entry results. KeePass/MDBX databases requiring unique credential IDs cannot hold a second copy in the same database; choose another database instead.
- Steam copies create independent entries, while moves within a database retain the original record. Added local category assignments with migration of existing data, and fixed MDBX edits or session refreshes moving entries back to root. Cross-database moves clean up sources only after destination writes succeed.

- Fixed repeated sync activity on local Passkey pages. Refreshes follow the selected database and folder, skipping KeePass indexing with no target, inaccessible databases or an unchanged index. Local pages no longer display another Bitwarden/Vaultwarden account's sync progress.
- Cached Passkey compatibility labels, including Key unavailable and Transfer limited, so returning to the list or opening details reuses known results immediately. Key deletion or restoration, compatibility changes and lock-session changes trigger a new check, reducing repeated loading and badge flicker.

- Fixed the Passkey list resetting to All after returning from details. The selected database and folder now remain active and are restored on reopening, without page recreation overwriting saved filters with defaults.

- Fixed refresh and merge of legacy Passkeys changing previously used backup eligibility: preserve existing credential properties and retain local data on conflicts; new imports retain source flags.
- Fixed Bitwarden/Vaultwarden Passkey updates overwriting other credentials, passwords, URLs and custom fields in the same login. Updates now read the full item, check its revision and verify the saved content; failures and conflicts retain local data, while explicit folder moves and moves back to root remain supported.
- Added Local only, Transfer limited and Key unavailable labels beside Passkey formats, with explanations in details and wrapping for narrow screens and large text.

- Fixed synced entries being absent from the vault overview and search for Bitwarden/Vaultwarden users: use All when no database scope is saved, including older settings that omitted the scope. Explicitly saved local or individual database scopes are preserved, and locked vaults remain excluded from display and search.
