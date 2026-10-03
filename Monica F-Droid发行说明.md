# Monica for Android (F-Droid) 1.0.316

> 发布日期 / Released: 2026-10-03

## 中文

### 简要

- 新增统一数据库原始信息管理器，根目录按 Monica 本地、MDBX、KeePass 分组；支持独立双栏、滑动多选和跨栏批量复制／移动，快照采用统一文件夹浏览布局。

- 开发者设置改为 M3E 连续分组布局；日志改为独立页面，支持搜索、来源与级别筛选、连续日志阅读和完整报告分享。日志正文改为连续等宽文本，保留筛选和长按复制；清空日志与查看日志并列，提示先清空、复现再分享，并移除旧自动填充 V2 测试入口。

- 密码项目支持通过“添加内容”增加多组账号密码，每组独立管理多密码与验证器，详情和自动填充按账号区分；批量添加仍创建独立项目。#151 安全分析统一显示在全部凭据和添加内容之后，不再插入账号分组之间。

- 修复密码附加内容编辑弹窗随底层卡片回收而意外消失、重建的问题，保留未保存草稿。

- 密码库概览、验证器和通行密钥页的右上角菜单新增“扫描通行密钥二维码”，复用密码页扫码流程。

- 通行密钥移动遇到跳过或失败时，弹窗逐项说明原因和处理建议，修正成功数量文案。

- 银行卡号码字段统一显示为“卡号”，避免借记卡误标为信用卡。

- 修复使用 32 字节 KeePass 密钥文件时原始密钥被修改，影响再次打开或密钥副本的问题。

- Monica 本地新增主密码恢复保护；设备安全密钥失效时保留原数据，使用已准备的加密恢复材料尝试恢复。
- 修复全量备份的归档密码遗漏、账单地址与支付账户类型误判、密码历史和回收站恢复问题。

- 修复 Android 12 等系统打开自动填充选择页时丢失目标表单信息的问题。

- 自动填充与 Monica 键盘支持密码内的银行卡、账单地址和证件副本；键盘补齐独立账单地址。

- Monica 键盘支持填写密码项目的自定义字段，横向滚动操作按钮，避免列表拥挤。

- 统一生成器常用建议高度，修复横向滚动时页面上下跳动。

- 快捷筛选新增 API Key、API 令牌和 GPG 密钥，同时匹配独立项目与密码内嵌内容。
- 银行卡、证件类型菜单统一为紧凑圆角样式，清晰显示当前选项。

- 统一各页筛选菜单，保留标签和类型差异，优化布局、拖拽及应用内缩放。

- 修复密码内的验证器密钥暂时不可读时从验证器列表消失的问题。
- 修复相同绑定账号下，密钥相同但 PIN 不同的 mOTP 验证器被误合并显示的问题。

### 详细

- **数据库原始信息与快照：** 选中 MDBX／KeePass 后，可从各列表右上角直接进入管理器。两栏可分别选择数据库、文件夹和搜索条件，横纵滚动与选择互不影响；支持长按滑动多选、新建与重命名文件夹、条目重命名及向另一栏复制／移动。双栏收紧文字与间距，保留大字体可读性。MDBX 同格式复制保留未知结构与附件，KeePass 同格式传送保留原生字段与文件夹属性；跨格式传送先验证字段、附件与源版本，再移除源项目，无法完整转换时逐项提示并保留源数据，目标可能保留未完成副本。修复笔记正文与备注转入 KeePass 时的分别保存，并按文件名识别附件类型。原始字段按需读取、默认隐藏，MDBX 原始字段保持只读。快照保留独立横屏对照及原有恢复／删除确认，不更改数据库格式。

- **开发者诊断：** 复用设置页的紧凑分组组件，保留验证、兼容性与同步诊断选项。日志页同时展示系统及各持久化来源，修复系统日志存在时其他来源无法查看的问题；搜索保留整条异常上下文，连续等宽正文支持长按复制、刷新及完整文件分享。后台加载与筛选、长文本分段按需渲染减少界面阻塞，旋转保留筛选与日志快照；清除日志需确认，仅处理诊断日志，不清除密码库或配置。

- **MDBX 密码正文边界：** 修复导入密码以密文标记开头时被重复解密而导入失败的问题；明确新写入的密码正文，避免后续旧数据修复误改密码，同时保留旧格式嵌套加密迁移。
- **项目内多账号：** 新增内联账号组，支持独立用户名、多密码、验证器密钥及验证码预览；密码强度显示在字段内部，组间留白与组内连续圆角分开处理。账号、所有密码与验证器在编辑和详情中连续分组；添加密码位于验证码预览下方，新增密码插在验证器之前，详情账号密码使用紧凑横向布局，保留复制和显隐，点击内容可在触点附近打开操作菜单；详情验证码省略重复图标与名称，密码行根据实际读取结果显示，避免轻量列表数据导致附加账号密码被隐藏。系统自动填充和 Monica 键盘使用选中的真实密码记录，键盘填写前重新读取以避免过期数据。分组标识随本地、MDBX、KeePass、Bitwarden 与全量备份保存，保留未知扩展字段；删除首个密码时转移公共附件。独立批量创建的行为保持不变，其他客户端仍可能将其显示为多条记录。 自动填充的同账号多密码显示序号，旧版加密用户名使用独立显示值，不改写填充值。

- **附加内容编辑稳定性：** 笔记／备注等内容编辑期间保留所属列表卡片，避免未聚焦输入框时因底层滚动、布局变化而销毁弹窗；关闭编辑器后恢复列表回收。处理同一卡片内的更多菜单与删除确认，保留原有保存行为。

- **KeePass 密钥文件：** 新建数据库、更换凭据和兼容凭据重试均向底层库传入独立的密钥字节副本，避免原始 32 字节密钥在内存中被修改；不修改用户的原始密钥文件或现有数据库格式。

- **本地数据恢复：** 成功输入主密码后准备独立加密恢复材料，后台逐批验证并转换仍依赖设备密钥的旧字段，保留项目时间、排序及同步归属。设备安全存储损坏时可使用主密码恢复到新的安全配置，保留原文件；密码变更失败、恢复材料损坏或缺少原密钥时不自动清空数据、不生成替代数据库密钥。覆盖密码内的更多内容、完整卡片与笔记副本、附件和可导出的通行密钥。配置恢复覆盖 WebDAV、数据库远端凭据、MDBX 设备密钥包装及 Bitwarden 离线缓存；Bitwarden 设置恢复后关闭自动同步，保留账号、离线项目与待处理队列。KeePass 凭据变更暂存遇到读取失败或长时间未使用时不再自动丢弃。恢复仍依赖保留的数据库和附件；尚未转换的旧数据及硬件专用密钥不能保证恢复。正常升级前后保留旧版格式；真正执行安全配置恢复后需继续使用本版或更新版本。
- **全量备份完整性：** 本地密码查询包含归档项目，恢复时优先保留账单地址和支付账户的明确类型；密码历史不再依赖调用者可选的历史仓库。兼容全量备份根目录和旧嵌套目录的回收站记录，与其他项目一起进入事务恢复，避免覆盖恢复时遗漏。保留现有备份格式。

- **系统选择页兼容：** 使用系统可解析的参数传递目标输入框、字段类型及认证上下文，避免 Android 12 转交请求时丢失信息；兼容旧调用，不放宽原有解锁要求。

- **卡包内容填充：** 密码内副本作为标明所属项目的候选，沿用卡片字段映射并兼容旧版支付与地址字段，不另存重复卡片。键盘提供单行横向字段按钮，填写字段或快速填充后保持展开，点击卡片标题手动收起；账单地址和内嵌副本逐字段填写；仅在打开卡包面板时读取，点击时重新检查最新数据、来源与解锁状态，阻止删除、移动、锁库或输入框切换后的过期填写。损坏字段不输出密文，不更改存储、备份或同步格式。

- **键盘自定义字段：** 展开项目后按名称选择字段，支持只有自定义字段的密码项目；常用操作与自定义字段各占一行、从左侧对齐并可横向滚动，同一时间只展开一个项目。按钮不显示字段值，受保护字段标注锁图标；仅展开时加载字段名称，点击时读取最新值直接填写，保留 Base64 密钥、空格、换行和 Unicode。字段删除、项目移动、锁库或输入框切换后阻止过期填写，不更改数据和备份格式。

- **生成器常用建议：** 用户名和密码共用等高建议卡片及横向列表；预留一行标题、两行明文预览，随字体缩放统一增高，超长内容省略显示，点击仍使用完整原值，避免可见建议切换时推动下方控件。

- **内容类型筛选：** API Key 与 API 令牌分别筛选，兼容 MDBX 原生令牌与密码中的内容块；两种密码库布局共用只读类型索引，在后台解析并随字段修改刷新，不更改数据库和备份格式。
- **表单选择菜单：** 银行卡的卡类型与证件类型复用同一圆角菜单，当前选项提供底色及右侧对号，统一选项间距、按压裁剪和大字体换行。

- **统一筛选菜单：** 密码库、验证器、通行密钥、卡包、笔记和 Steam 共用右上角菜单与弹出锚点，保留原生开合动画，460dp 高度上限、内部滚动和12dp横向留白；有管理操作的页面将带背景的新建分类、编辑按钮固定在底部。数据库默认单行横向滚动并可展开，只有数据库选项的页面始终展开；笔记保留标签筛选并补齐 MDBX 入口，卡包保留卡片类型，通行密钥不再显示空的快捷筛选区。统一圆角按压及低 DPI 宽度，密码页保留长按拖动和编辑状态布局。

- **不可读验证器保护：** 保留列表项目和原始数据，暂时不可读时不提供验证码复制、二维码生成和空白编辑；解锁或源数据恢复后重新读取。验证码选择器排除不可读项目，不更改数据库、备份格式或原有密钥。
- **mOTP 显示去重：** 将 PIN 纳入验证码身份判断，保留不同 PIN 的验证器；完全相同的绑定映射继续合并显示，不删除底层记录。

## English

### Summary

- Added a unified original-information manager grouped by Monica local, MDBX and KeePass, with independent panes, swipe selection and batch copy/move to the opposite pane. Snapshots share the folder-browser layout.

- Redesigned developer settings with grouped M3E controls and a dedicated log page with search, source/severity filters, expandable stacks and full-report sharing.

- Add multiple account groups to one password project, each with its own passwords and OTP; keep details and autofill account-specific while bulk creation stays separate. #151

- Keep additional-content editors open when their cards leave the viewport, preventing unexpected dismissal and recreation while preserving drafts.

- Add Scan passkey QR to the vault overview, authenticator and passkey menus using the existing password-page scanner.

- Explain skipped or failed passkey moves in a per-item result dialog, with guidance and accurate success wording.

- Use “Card Number” for bank-card fields so debit cards are not labeled as credit cards.

- Preserve 32-byte KeePass key files during credential creation and retries, preventing invalid key copies and reopen failures.

- Add password-based recovery protection for the Monica local vault using prepared encrypted recovery material while preserving original data.
- Fix omitted archived passwords, wallet-type misclassification, password history and trash restoration in full backups.

- Fix lost target-form information when opening the autofill picker on Android 12 and similar systems.

- Fill embedded bank cards, billing addresses and documents through autofill and the Monica keyboard; add standalone billing addresses to the keyboard.

- Fill password-entry custom fields with the Monica keyboard; scroll actions horizontally to keep the list compact.

- Keep generator suggestion cards at a consistent height to prevent vertical jumps while scrolling.

- Add API Key, API Token and GPG key filters for standalone entries and embedded password content.
- Unify bank-card and document type menus with compact rounded choices and clear selection.

- Unify page filter menus while retaining tags and type filters, consistent drag editing and in-app scaling.

- Keep password-linked authenticators visible when their keys are temporarily unreadable.
- Fix mOTP authenticators with the same binding and secret but different PINs being collapsed in the list.

### Details

- **Database information and snapshots:** Open the manager directly from list menus for the selected MDBX/KeePass database. Each pane independently chooses its database, folder, search and horizontal/vertical scroll position. Long-press swipe selection, folder creation/renaming, entry renaming and copying/moving to the opposite pane use a compact layout with scalable text. Native MDBX copies retain unknown structures and attachments; native KeePass transfers retain fields and folder properties. Cross-format moves verify fields, attachment bytes and source revisions before removing originals. Unsupported conversions report per-item failures and retain sources; incomplete destination copies may remain. Preserve note bodies and separate notes when writing KeePass, and infer attachment media types from names. Disclose raw fields on demand, hidden by default; MDBX raw fields remain read-only. Snapshots retain independent landscape comparison and existing restore/delete confirmations. Database formats are unchanged.

- **Developer diagnostics:** Reuses compact settings groups while preserving verification, compatibility and sync diagnostic options. The standalone viewer includes both system and persisted sources instead of hiding other sources when logcat is available. Search retains complete event context; logs are displayed as continuous monospace text, with long-press copying, refresh, and full-report sharing. The settings page offers log clearing alongside viewing, explains the reproduce-and-share workflow, and removes the obsolete Autofill V2 test entry. Background loading/filtering and bounded lazy text blocks keep the interface responsive; filters and snapshots survive rotation. Clearing diagnostic logs requires confirmation and leaves vault data and settings intact.

- **MDBX password boundaries:** Preserve imported passwords that resemble encryption envelopes; mark newly written plaintext to prevent legacy repair from reinterpreting user content, while retaining unmarked legacy nested-encryption migration.
- **Multiple accounts per project:** Add inline account groups with independent usernames, multiple passwords and OTP previews. Keep strength feedback inside each field and separate account groups visually. Connect the account, all passwords and OTP in editor and detail groups. Place Add password below the preview and insert new passwords before OTP; use compact credential rows with copy/visibility actions and touch-anchored content menus, and omit repeated OTP identity in details. Determine password-row visibility from resolved secrets so sanitized list metadata cannot hide additional accounts’ passwords. Detail and autofill use the selected real password record; the keyboard rereads it before filling. Preserve grouping metadata across local, MDBX, KeePass, Bitwarden and full backups, retain unknown fields, and transfer common attachments before removing their owning password. Independent bulk creation remains unchanged; other clients may display the underlying records separately. Show password ordinals for matching account rows and resolve legacy encrypted usernames for display without changing action values. Security analysis follows all credential groups and additional content instead of splitting accounts.

- **Additional-content editor stability:** Retain the owning lazy-list card while its editor is open, including before an input gains focus, so parent scrolling or layout changes cannot dispose the dialog. Release it on close and apply the same lifetime protection to the card menu and removal confirmation. Preserve existing save behavior.

- **KeePass key files:** Pass independent key-byte copies to the codec when creating databases, changing credentials and building fallback candidates. Protect caller-owned 32-byte keys from in-place masking without changing original key files or database formats.

- **Local vault recovery:** Prepare independent encrypted recovery material after a successful master-password unlock. Convert legacy device-bound fields in verified background batches without changing timestamps, order or sync ownership. If secure storage fails, recover into a separate secure-preference generation while retaining originals; never reset data or generate a replacement vault key when key material is missing. Cover embedded content, full card/note copies, attachments and exportable Passkeys. Recovery also covers WebDAV settings, remote database credentials, MDBX device-key wrappers and Bitwarden offline caches. Restore Bitwarden settings with automatic sync disabled, retaining accounts, offline items and pending operations. Keep KeePass credential-transition fallbacks after read failures or long absences. Recovery still requires database/attachment files; unconverted legacy data and hardware-only keys may remain unavailable. Normal upgrades retain legacy formats; after actual secure-store recovery, continue using this or a newer version.
- **Full-backup integrity:** Include archived local passwords, preserve explicit billing-address/payment-account types and restore history independently of optional repository wiring. Stage root-level and legacy nested trash entries with the other records for transactional replacement. Retain the existing backup format.

- **System picker compatibility:** Transfer target IDs, field hints and authentication context using framework-readable values, avoiding dropped arguments on Android 12. Preserve the previous caller contract and authentication requirements.

- **Wallet content filling:** Offer embedded copies with their parent entry names using shared card mappings, including legacy payment/address fields, without creating duplicate records. The keyboard uses one horizontally scrolling action row that stays expanded after field or quick fills until the header is tapped, with explicit field selection for addresses and embedded copies. Load wallet data only when opening its panel; recheck current values, ownership and unlock state on selection. Reject stale fills after deletion, moves, locking or input changes, never fill unreadable ciphertext, and retain existing storage, backup and sync formats.

- **Keyboard custom fields:** Select fields by name, including entries with no username or password. Expand one entry at a time with separate left-aligned, horizontally scrolling rows for common actions and custom fields. Keep values out of buttons and mark protected fields with a lock. Load names only when expanded and read the latest value on selection, preserving Base64 keys, whitespace, line breaks and Unicode. Reject stale fills after deletion, entry moves, vault locking or input changes, without changing data or backup formats.

- **Generator suggestions:** Use equal-height cards and a stable horizontal row for username and password suggestions. Reserve one title line and two plaintext preview lines, scale the shared height with the font setting, and apply the full original value when a truncated preview is selected.

- **Content filters:** Keep API Keys and API Tokens distinct; match native MDBX tokens and password content blocks. Both vault layouts use a read-only type index decoded in the background and refreshed on field changes, without changing database or backup formats.
- **Form choice menus:** Share rounded menus for bank-card and document types, with a selected background and right-aligned checkmark, consistent spacing, clipped press feedback and large-font wrapping.

- **Unified filter menus:** Vault, authenticators, passkeys, card wallet, notes and Steam share the same top-right menu anchor, native enter/exit animation, 460dp height cap, internal scrolling and 12dp horizontal insets. Management actions stay in a fixed filled-tonal footer. Database rows scroll horizontally and can expand; database-only menus remain expanded. Notes retain tags and include the MDBX source, wallet keeps card-type filters, and passkeys omit empty quick filters. Rounded press feedback and low-DPI sizing are shared; password filters retain drag reordering.

- **Unreadable authenticator protection:** Retain the list entry and original data; block code copying, QR generation and empty editing while the key is unreadable. Retry after unlock or source recovery, and exclude unreadable entries from code pickers. Keep database and backup formats and existing keys unchanged.
- **mOTP display deduplication:** Include the PIN in authenticator identity so distinct PINs remain visible. Continue collapsing identical bound mirrors without deleting stored records.
