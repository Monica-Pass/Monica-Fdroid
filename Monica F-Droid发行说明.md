# Monica for Android (F-Droid) 1.0.315

## 中文

### 简要

- 全新 M3E 按需编辑与密码详情，支持内容拖动排序、银行卡、证件和笔记完整副本、附件轮播。
- 密码内可添加独立 API Key、API 令牌、SSH、GPG 和二维码内容块，支持动态字段二维码模板。
- 验证器密钥直接输入并预览，支持详细参数、倒计时和验证码操作菜单；表单直填后可按设置提示／复制验证码，支持 Android 16 实况通知。
- 统一用户名／密码生成器，新增库内常用建议；密码库新增最近变动排序与“最近”概览。
- 统一设置、权限与新建模板设计；新增 TiGA 三档滑块和图标／卡面订阅。
- 完善多密码、MDBX 跨实例同步和 KeePass 时间兼容，改进 Bitwarden／Vaultwarden 两步验证。
- 加强启动保护、备份下载和恢复回滚，提供问题通行密钥处理；修复旧版 Android 自动填充及法语时间线崩溃。

### 详细

- **后台数据任务：** 导入、导出和手动 WebDAV 全量备份可在离开页面后继续，静默通知显示当前阶段和实际进度；点击通知经正常解锁后返回对应页面，再次进入可查看结果，阻止重复提交。系统中断后提示核对数据，不自动重放导入；保留现有写入保护和失败清理边界，不删除导入源文件。WebDAV 备份改为文件流上传，避免整包读入内存；大文件采用独立传输超时，目录查询仍保持短超时，修复超时误显示为 Canceled，继续遵守服务器限流等待。

- **按需新建与编辑：** 采用紧凑顶栏和 M3E 连续分组，账号、多密码、网址等字段统一圆角；数据库与项目图标独立展示。内容按添加顺序排列，长按整卡跟手拖动，邻项平滑让位，仅被拖卡片和邻卡相向边变圆、落位后恢复分组，菜单确认删除，“添加内容”始终位于列表底部，详情沿用相同排序。移除经典编辑入口和失效开关；新建不再提供第三方登录，旧记录的提供商和关联账号仍可查看、编辑。

- **密码内容块：** “添加内容”新增独立的 API Key、API 令牌、SSH 密钥、二维码入口，GPG 密钥位于更多。支持同类重复添加、完整字段编辑、长按排序、确认删除和按保存顺序查看详情；私密内容默认隐藏，公私钥支持文件导入与导出。内容随原有受保护字段保存，保留未知字段；不完整或无法识别的内容只读保留，超限拒绝保存而不截断。密码内的 API 令牌与 API Key 分开，且不会自动获得原生 Gateway 调用授权。二维码支持保存字段模板，插入账号、密码或自定义字段，生成时读取最新值；提供 Wi-Fi 预设和特殊字符转义，字段缺失或不可读时提示错误。 API Key 的独立新建与嵌入编辑允许保存普通文本、无协议及本地接口地址；空密钥明确提示必填，不再误报文件大小错误。 二维码内容点击后直接以底部弹窗预览，可切换 Code 128 条形码并保存图片；无法编码时显示原因，原文与动态模板保持不变。

- **银行卡、笔记与可选资料：** 密码内的银行卡、证件和笔记可保存为完整独立副本，包含卡面、正反面照片、图片、附件及扩展字段；复用完整编辑器和详情入口，无需重复选择数据库。附件准备完成后再保存新内容，失败保留旧内容并可重试。支付、个人信息和地址支持更多可选字段；银行卡低频字段按需添加，保留旧品牌、昵称等已有数据。

- **原生 API 令牌附件：** 附件归属真实原生条目，正文、副本元数据与文件一起原子保存；读取失败、内容损坏或并发修改时保留原数据。支持完整卡片／笔记副本、移动、原生快照／同步，以及数据库加密 ZIP 导出恢复；核对附件数量、大小、摘要及副本引用，拒绝缺失的令牌清单。每次保存新增／移动文件合计最多 64 MiB、每个令牌最多 128 个附件；ZIP 内原生令牌附件合计最多 64 MiB。带附件令牌不支持 KDBX 导出，明确提示而不遗漏文件。

- **验证器编辑与操作：** 验证器密钥成为默认字段，采用与凭据一致的填充圆角输入框，可直接输入并预览，默认 TOTP；输入框内三点进入详细设置，验证码右侧显示图形倒计时，HOTP 显示计数器。添加验证器的高级与关联选项采用 2dp 间隙连续分组，应用选择与绑定操作统一填充底色，保留输入错误提示。保留 TOTP、HOTP、Steam、Yandex、mOTP 及已有参数、发行者、账号、备注和 Steam 元数据。详情卡片提供复制当前验证码、复制 Next、迁移二维码和当前验证码二维码，操作时计算当前值；HOTP 不显示时间倒计时或 Next，按压反馈贴合圆角。

- **密码详情与附件：** 账号、密码、网站、自定义字段、通行密钥、备注和历史统一 M3E 分组，兼顾大字体与窄屏。保留验证器和银行卡的专用外观，银行卡与账单地址按排序组成卡叠，点击进入卡叠页和单卡完整详情。附件采用轮播，支持缩略图、完整文件名、下载、重试、预览和保存；编辑列表采用连续分组，提供添加入口、空态、进度与错误提示。保留敏感信息显隐、复制、大字、分享及 Send 等操作。

- **生成器与密码强度：** 用户名和密码统一为底部面板，支持随机用户名、邮箱加号别名、4–128 位符号密码、短语和 PIN。结果区保持稳定高度，长内容缩字并可滚动，拖动保留结果、松手更新。建议明文展示预设及库内最多五个常用非重复值，排除归档、回收站、锁定或不可读项目。密码字段内显示平滑展开的强度等级，各密码独立计算；取消不修改字段，结果不自动复制。

- **多密码与 MDBX：** 独立项目不再因标题、账号、网站相同而自动合并，只有明确设置多个密码的项目合并展示；多选和批量移动按整个项目处理，保留各密码及分组关系。修复本机多层密文进入 MDBX 后其他安装实例无法读取的问题；原实例打开或同步时尝试修复可解密旧记录，保留未知字段与无法解密的数据。 MDBX 待同步状态使用原生提交检查点核对，避免只读访问、无变化维护或连接失败产生“未同步”误报；连接失败、远端变化和冲突分别提示，保留审计记录同步及并发编辑的待上传状态。

- **最近变动与日期：** 新增“最近变动”排序，按创建与修改时间中较新的时间排列；概览“最近”默认位于常用卡片下方，显示可访问数据库的最近八项，可折叠、隐藏和调整位置。日期分组及滚动提示统一 yyyy/MM/dd，适应系统字号。拖动滚动条时减少重复重组和文字测量，优化大量日期分组的定位。保留 KeePass 原始创建／修改时间，避免读取或同步刷新时间影响排序。

- **新建模板与数据库选择：** Wi-Fi、SSH、API Key 和 GPG 复用密码页的图标／标题、按需内容和保存流程，支持备注、完整卡片／笔记副本、字段、附件和内容排序，详情同步显示；Wi-Fi 扫码位于网络名称下面。嵌入 API Key、API 令牌、SSH 和 GPG 使用完整页面及相同核心表单，保留密钥生成／导入。个人信息与地址合为账单地址，复用卡包完整编辑器；银行卡与账单地址直接复用卡包已有卡叠，点击单卡查看完整详情，不再重复展示支付信息折叠栏。保留旧联系信息、证件扩展、卡面和附件。原生 API 令牌继续使用独立 MDBX payload／元数据，支持备注、自定义字段、内容块和 Emoji 图标，支持原生附件及包含卡面、照片和附件的完整卡片／笔记副本。 切换保留数据库与文件夹；Wi-Fi 高级参数和未知字段沿用原格式。 补齐内容字段翻译并适配大字体；复制内容的最终保存位置不受列表数据库筛选影响。 验证器、银行卡、证件和账单地址统一采用密码页的图标／标题、连续填充分组与底部“添加内容”。卡包按需添加普通／敏感字段及各类型已有扩展内容；验证器按需显示备注、关联与 OTP 参数，保留已有格式。旧项目有值的栏目自动显示，编辑证件时分别保留完整姓名与分段姓名。 复制卡片与选择常用卡片共用底部弹窗，显示银行／持卡人、卡组织和掩码尾号，支持按标题、银行、尾号和数据库筛选；点选保留完整卡片、卡面与附件。 Wi-Fi 安全性与隐藏网络采用连续圆角分组；详情支持点击网络名称操作、连接系统 Wi-Fi 设置及生成二维码。MDBX 完整保存并读取 Wi-Fi 元数据，兼容旧记录用标题回显网络名称，保留高级参数与未知字段。

- **设置与权限：** 重新设计页面调整自定义、权限、自动填充、清除数据、功能拓展、数据库与备份页面，使用外侧大圆角、组内小圆角和紧凑布局。权限整行可点击，小状态图标统一对齐，返回系统设置后刷新；保留读屏与大字体支持。清除数据仍需选择范围和主密码校验，确认操作保持可达；Monica Plus 卡片保留原样。

- **自定义子页面与预设字段：** 密码列表、密码卡片、验证器卡片、字段、图标及添加按钮设置采用统一分组和预览，保留搜索定位。长按整行直接排序，去掉拖柄和上下按钮，保留开关与读屏移动操作；仅被拖卡片及邻卡相向边变圆，落位后恢复分组。预设字段使用紧凑底部面板，敏感／必填开关同行显示，默认值与提示收纳至更多选项；保留字段类型、排序和旧值，敏感默认值遮挡，清空前确认。

- **TiGA 三形态：** MDBX 本地和 WebDAV 新建页直接展示三档滑块：从左到右为 Sky 紫、Multi 紫红渐变、Power 红。背景随档位轻染色，支持闪光点、吸附、阻尼过渡与轻震动，兼顾深浅主题及减少动画设置。默认 Multi，沿用原有安全参数。

- **图标与卡面订阅：** 图标设置支持公开 HTTPS JSON 图包和图片直链，按图标／卡面管理，可搜索、刷新和退订。密码及验证器可选用订阅图标，卡面下载原图后裁剪；裁剪支持左右旋转 90°、上下／左右翻转和一键重置，预览与保存结果一致；选用图片保存为独立副本。刷新失败保留旧目录，退订不删除已用图片；订阅配置目前仅保存在本机。

- **Bitwarden／Vaultwarden 两步验证：** 兼容旧认证方式列表和新版挑战信息，支持 TOTP、邮件、新设备邮件验证及 YubiKey OTP。多方式先选择再操作，邮件显式发送，TOTP 可搜索并选用 Monica 中已有验证器。错误验证码可重试，请求中防止重复提交，取消清除临时认证状态；隐藏不支持方式，全部不可用时给出说明。

- **通行密钥备份处理：** WebDAV 备份失败时列出具体通行密钥、原因和处理建议，可重试或明确跳过。跳过后生成带遗漏清单的加密部分备份，保留旧备份且不更新完整备份时间；部分备份仅支持合并恢复，不能覆盖本地数据。兼容可导出的旧私钥格式，未明确跳过的失败仍阻止上传。

- **云端备份安全：** WebDAV 备份列表每次只查询一次目录，合并页面进入与备份完成的重复刷新；刷新失败保留已显示列表。WebDAV 并行请求成功不再提前解除其他请求触发的限流等待，保留服务器 Retry-After 和主机退避。下载完整写入后才替换本地文件，网络或写入失败保留原文件。自动清理仅处理 Monica 标准命名的临时备份，保留其他 ZIP、未知文件名及部分备份。

- **恢复与数据保留：** 覆盖恢复先解析再事务写入，写入失败或取消时回滚条目及关联字段。不同密钥、参数或恢复资料的验证器不再因账号相同而遗漏，重建密码关联时保留未知字段。单条损坏记录不再中断验证器列表，不将无法解密的密文视为普通密钥，解锁后重新解析；后台加密迁移避免覆盖并发编辑。修复附件启动清理可能误删新文件的问题。

- **安全存储启动保护：** Keystore 或加密配置校验失败时显示保护页，提供重试与脱敏诊断，并暂停本次启动的相关同步、上传恢复及附件维护。不自动清除配置或重建丢失密钥；该保护避免直接启动崩溃，但无法恢复永久丢失的系统密钥。

- **自动填充与键盘：** 表单下拉列表及键盘内联候选填入密码后，也按现有开关显示／复制该项目的验证码，兼容密码内置密钥及绑定验证器；验证码不可用、通知被禁用或附加处理失败时不影响密码填充。关联判断使用后台缓存，冷查询超时保留普通直填，避免拖慢候选列表。修复 Android 10 等旧系统自动填充服务引用新版内联候选类型导致的崩溃。自动填充设置移除分组内多余横线，统一连续圆角与间距。Monica 键盘菜单按压反馈限制在圆形按钮内，自动填充入口改用更明确的“进入输入框”图标，保留清空撤销。 Android 16 的验证码倒计时和磁贴顺序复制支持请求实况通知，权限入口位于权限管理；未授权或系统不支持时沿用普通通知。状态条不显示验证码或密码，锁屏公开内容仅保留通用提示，复制需解锁；关闭、到期或替换后旧动作失效。

- **条形码与其他稳定性：** 详情字段复用独立条形码预览，保留矩形码图与完整白边，Code 128 按可用宽度生成，过长时提示改用二维码。修复法语时间线处理较早历史日期时闪退，以及旧密码编辑页加载期间的空列表崩溃。

- **多语言：** 补齐 11 种语言中的编辑、字段、排序、设置搜索及 MDBX 提示等文案，保留已有翻译与彩蛋语言的继承规则。

## English

### Summary

- A redesigned M3E on-demand editor and password details, with draggable content, complete card, identity-document and note copies, and an attachment carousel.
- Embed distinct API Keys, API tokens, SSH/GPG keys and QR content with dynamic field templates in password entries.
- Enter authenticator secrets directly with live previews, advanced settings, countdowns and code actions; optionally show/copy OTP after direct autofill, with Android 16 live updates.
- Unified username/password generators with frequently used vault values, plus recent-change sorting and a Recent overview section.
- Consistent settings, permissions and creation templates, plus a three-mode TiGA slider and icon/card-art subscriptions.
- Improved multiple-password handling, cross-install MDBX sync, KeePass timestamps and Bitwarden/Vaultwarden two-step login.
- Safer startup, backup downloads and restore rollback, actionable passkey-backup errors, and fixes for older-Android autofill and French timeline crashes.

### Details

- **Background data tasks:** Imports, exports and manual full WebDAV backups continue after leaving the page, with silent stage/progress notifications and results available on return. Notification taps use normal vault authentication; duplicate submissions are blocked. Interrupted tasks ask users to check existing results instead of automatically replaying imports. Existing write safeguards and cleanup boundaries remain in place; import source files are retained. WebDAV streams archives instead of loading the entire ZIP into memory, uses a separate archive-transfer timeout while keeping metadata requests bounded, reports timeouts correctly rather than as Canceled, and retains server backoff.

- **On-demand editing:** Use a compact toolbar and M3E grouped fields for accounts, multiple passwords and websites, with separate database and item icons. Content follows insertion order, supports direct long-press dragging and confirmed deletion, and keeps Add content at the bottom; details use the same order. Remove the classic editor entry and obsolete switches. New entries no longer offer third-party login, while existing providers and linked accounts remain viewable and editable.

- **Password content blocks:** Add separate API Key, API token, SSH key and QR code entries under Add content, with GPG keys under More. Support repeated types, full-field editing, long-press ordering, confirmed deletion and details in saved order. Sensitive content is hidden by default; public/private keys support file import and export. Store content through existing protected fields, preserve unknown fields and retain incomplete or unrecognized blocks read-only. Reject oversized content without truncation. Embedded API tokens remain distinct from API Keys and do not grant native Gateway access. QR content supports saved field templates, inserting account, password or custom fields and resolving current values when generated. Include a Wi-Fi preset with escaping and explicit missing/unreadable-field errors. Standalone and embedded API Key editors accept recorded addresses, including plain text and local endpoints; missing keys show a required-field error instead of a file-size error. QR content opens directly in a bottom sheet with a Code 128 barcode option and image export; unsupported content shows an error without changing the original text or dynamic template.

- **Cards, notes and optional information:** Store complete independent card, identity-document and note copies inside passwords, including artwork, front/back photos, images, attachments and extension fields. Reuse full editors and detail views without another database selector. Save new content after attachments are ready, preserving previous content on failure and allowing retries. Expand optional payment, identity and address fields; add less-used bank-card fields on demand while retaining existing brand, nickname and other legacy values.

- **Native API-token attachments:** Attach files to the actual native entry and save token data, copy metadata and files atomically. Read failures, damaged content and concurrent changes preserve originals. Support complete wallet/note copies, moves, native snapshots/sync and encrypted database ZIP export/restore; verify attachment counts, sizes, hashes, copy references and the token manifest. New or moved files are limited to 64 MiB per save and 128 attachments per token; native token files in a ZIP total at most 64 MiB. KDBX export explicitly rejects tokens with attachments instead of omitting files.

- **Authenticators:** Make the secret a default inline field with the same filled, rounded style as credentials, a live preview and TOTP default. An in-field menu opens advanced settings; a graphical countdown appears beside the code, or a counter for HOTP. Standalone advanced and association options use connected groups with 2dp spacing, matching filled app/binding rows and retained input-error guidance. Preserve TOTP, HOTP, Steam, Yandex and mOTP settings, issuer, account, notes and Steam metadata. Detail cards offer current-code copying, Copy Next, migration QR and current-code QR, calculating codes when actions run. HOTP has no time countdown or Next action; press feedback follows rounded corners.

- **Password details and attachments:** Unify accounts, passwords, websites, custom fields, passkeys, notes and history with M3E groups that adapt to large text and narrow screens. Keep dedicated authenticator and bank-card visuals, and group payment and billing-address cards in saved order, opening a dedicated stack page and individual full details. An attachment carousel provides thumbnails, full filenames, downloads, retries, previews and saving. Grouped attachment editing includes an add action, empty state, progress and errors. Retain sensitive-value visibility, copying, large-text viewing, sharing and Send actions.

- **Generators and password strength:** Use matching bottom sheets for random usernames, email plus aliases, 4–128-character random passwords, passphrases and PINs. Keep the result area stable, scale long text with scrolling when needed, and update after releasing the slider. Show readable presets followed by up to five distinct frequently used vault values, excluding archived, trashed, locked or unreadable entries. Each password has an animated inline strength label. Cancelling leaves fields unchanged, and results are not copied automatically.

- **Multiple passwords and MDBX:** Do not automatically merge independent entries sharing a title, account and website. Only explicitly grouped passwords appear together; selection and bulk moves operate on complete entries and retain every password and its grouping. Fix device-encrypted values in MDBX becoming unreadable in another installation. The original installation attempts repair of decryptable legacy records when opening or syncing, preserving unknown fields and unreadable data. Compare MDBX pending state against native commit checkpoints so read-only access, unchanged maintenance and connection failures do not invent unsynced edits. Show failed sync, remote changes and conflicts explicitly while retaining audit uploads and pending concurrent edits.

- **Recent changes and dates:** Add recent-change sorting using the later of creation and modification time. Recent appears below favorite cards by default, shows eight entries from accessible databases, and supports collapsing, hiding and repositioning. Date groups and scroll hints use yyyy/MM/dd and adapt to text size. Reduce redundant scrollbar recomposition and text measurement, and speed up navigation across many date groups. Preserve original KeePass creation/modification times instead of substituting import or sync times.

- **Creation templates and database selection:** Wi-Fi, SSH, API Key and GPG share the password editor’s icon/title, optional content and save pipeline, including notes, complete wallet/note copies, custom fields, attachments and ordering, with matching detail views. Wi-Fi scanning sits below the network name. Embedded API keys/tokens and SSH/GPG keys use full-page editors with shared core fields and key generation/import. Contact and address information now share the complete billing-address editor. Payment and billing-address cards open a separate stack page and individual full details, without duplicate expandable payment rows. Preserve legacy contact/document fields, card artwork and attachments. Native API tokens retain their separate MDBX payload/metadata, with notes, fields, content blocks and Emoji icons, with native attachments and complete wallet/note copies including artwork, photos and files. Preserve selected databases/folders, advanced Wi-Fi settings and unknown fields. Reuses the existing wallet stack, adds translated content labels and large-text support, and preserves the selected storage destination when publishing copied content. Authenticator, bank-card, document and billing-address editors share the password editor’s icon/title, connected filled groups and bottom Add content sheet. Wallet items can add regular/protected fields and existing optional content; authenticators expose notes, associations and OTP parameters using their existing format. Populated legacy sections remain visible, and editing documents preserves full names independently of structured name parts. Card copying shares the frequent-card sheet, showing bank/holder, card brand and masked last digits, with title/bank/tail search and database filters; selecting a card preserves its complete content, artwork and attachments. Wi-Fi security and hidden-network controls use connected rounded groups; details offer network-name actions, system Wi-Fi settings and QR codes. MDBX preserves complete Wi-Fi metadata and unknown advanced fields, with title fallback for legacy entries missing a network name.

- **Settings and permissions:** Redesign customization, permissions, autofill, clear data, extensions, and database/backup pages with compact groups and rounded outer corners. Permission rows are tappable, use aligned compact status icons and refresh on return from system settings, with accessibility and large-text support. Clearing data still requires a selected scope and master-password verification, with confirmation actions kept reachable. Keep the existing Monica Plus card.

- **Customization and preset fields:** Use consistent groups and previews for password lists/cards, authenticator cards, fields, icons and Add-button settings, retaining search navigation. Drag whole rows directly without handles or up/down buttons, keeping switches and accessible move actions. Password content uses the same reorder library with finger tracking and animated placement; only the lifted card and facing neighbour edges round, while other rows remain joined. Compact preset-field sheets put sensitive/required toggles on one row and default values/hints under more options. Preserve types, order and existing values, mask sensitive defaults and confirm clearing.

- **TiGA modes:** Local and WebDAV MDBX creation directly show three slider positions: purple Sky, purple/red Multi and red Power, from left to right. Mode-tinted backgrounds, subtle sparkles, snapping, damped transitions and optional haptics respect light/dark themes and reduced-motion settings. Keep Multi as the default and preserve existing security profiles.

- **Icon and card-art subscriptions:** Subscribe to public HTTPS JSON image packs or direct image URLs, organized as icons or card artwork, with search, refresh and unsubscribe actions. Use subscribed icons for passwords/authenticators and crop original images for card artwork. Cropping supports 90° rotation in either direction, horizontal/vertical flips and reset, with matching preview and saved output. Selected images become independent copies. Failed refreshes retain the previous catalog, unsubscribing keeps selected images, and subscription settings currently remain local to the device.

- **Bitwarden/Vaultwarden two-step login:** Handle both legacy provider lists and newer challenge information, supporting TOTP, email, new-device email verification and YubiKey OTP. Choose a method before proceeding, explicitly send email codes, and select existing Monica authenticators for TOTP. Allow retries after incorrect codes, prevent duplicate requests and clear temporary authentication state on cancellation. Hide unsupported methods and explain when none is available.

- **Passkey backup handling:** On WebDAV backup failure, list affected passkeys, reasons and suggested actions, with retry or explicit skip. Skipping creates an encrypted partial backup with an omission list, retains existing backups and leaves the last complete-backup time unchanged. Partial backups support merge restore only, never replacement. Support exportable legacy private-key formats; failures still block upload unless explicitly skipped.

- **Cloud backup safety:** WebDAV backup listings use a single directory request, coalesce entry/completion refreshes, and retain the displayed list on refresh failure. Successful concurrent WebDAV requests no longer cancel another request’s active cooldown; preserve server Retry-After and host backoff. Downloads replace local files only after a complete write, retaining previous files on network or write failure. Automatic cleanup only handles standard-named Monica temporary backups, preserving unrelated ZIPs, unknown filenames and partial backups.

- **Restore and data preservation:** Parse replacement backups before transactional writes and roll back entries and related fields on failure or cancellation. Preserve authenticators with different secrets, parameters or recovery data even when accounts match, retaining unknown fields while rebuilding password links. Isolate damaged authenticator records, do not interpret undecryptable ciphertext as a secret, and retry after unlock. Prevent background encryption migration from overwriting concurrent edits and fix startup attachment cleanup potentially removing new files.

- **Secure-storage startup protection:** Show a protection page with retry and redacted diagnostics when Keystore or encrypted preferences fail validation, suspending related sync, backup upload/restore and attachment maintenance for that startup. Do not automatically clear settings or recreate missing keys. This guards against a startup crash but cannot recover permanently lost system keys.

- **Autofill and keyboard:** After selecting a password from a form dropdown or keyboard inline suggestion, honor the existing OTP notification/copy settings for its inline key or linked authenticator. Unavailable OTP data, disabled notifications and optional-action failures do not block password filling. Cache binding IDs in the background and retain direct filling when a cold lookup exceeds its short budget. Fix an autofill-service crash on Android 10 and earlier caused by references to newer inline-suggestion types. Autofill settings remove redundant separators from connected rounded groups. Keep Monica keyboard press feedback inside circular buttons and use a clearer enter-input-field icon for autofill, retaining undo after clearing. On Android 16, OTP countdowns and tile-based sequential credential copying can request live updates, with permission access in Permission Management and standard-notification fallback. Keep codes and passwords out of status chips and public lock-screen content; require unlock to copy and invalidate actions after closing, expiry or replacement.

- **Barcodes and other stability fixes:** Reuse the standalone barcode preview for detail fields, retaining rectangular artwork and a full quiet zone. Generate Code 128 for the available width and suggest QR when content is too long. Fix French timeline crashes with older dates and an empty-list crash while the legacy password editor loads.

- **Translations:** Complete editor, field, sorting, settings-search and MDBX guidance strings across 11 languages, preserving existing translations and easter-egg language fallback.
