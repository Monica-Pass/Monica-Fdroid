# Monica for Android (F-Droid) 1.0.316

> 未发布 / Unreleased

## 中文

### 简要

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

- **System picker compatibility:** Transfer target IDs, field hints and authentication context using framework-readable values, avoiding dropped arguments on Android 12. Preserve the previous caller contract and authentication requirements.

- **Wallet content filling:** Offer embedded copies with their parent entry names using shared card mappings, including legacy payment/address fields, without creating duplicate records. The keyboard uses one horizontally scrolling action row that stays expanded after field or quick fills until the header is tapped, with explicit field selection for addresses and embedded copies. Load wallet data only when opening its panel; recheck current values, ownership and unlock state on selection. Reject stale fills after deletion, moves, locking or input changes, never fill unreadable ciphertext, and retain existing storage, backup and sync formats.

- **Keyboard custom fields:** Select fields by name, including entries with no username or password. Expand one entry at a time with separate left-aligned, horizontally scrolling rows for common actions and custom fields. Keep values out of buttons and mark protected fields with a lock. Load names only when expanded and read the latest value on selection, preserving Base64 keys, whitespace, line breaks and Unicode. Reject stale fills after deletion, entry moves, vault locking or input changes, without changing data or backup formats.

- **Generator suggestions:** Use equal-height cards and a stable horizontal row for username and password suggestions. Reserve one title line and two plaintext preview lines, scale the shared height with the font setting, and apply the full original value when a truncated preview is selected.

- **Content filters:** Keep API Keys and API Tokens distinct; match native MDBX tokens and password content blocks. Both vault layouts use a read-only type index decoded in the background and refreshed on field changes, without changing database or backup formats.
- **Form choice menus:** Share rounded menus for bank-card and document types, with a selected background and right-aligned checkmark, consistent spacing, clipped press feedback and large-font wrapping.

- **Unified filter menus:** Vault, authenticators, passkeys, card wallet, notes and Steam share the same top-right menu anchor, native enter/exit animation, 460dp height cap, internal scrolling and 12dp horizontal insets. Management actions stay in a fixed filled-tonal footer. Database rows scroll horizontally and can expand; database-only menus remain expanded. Notes retain tags and include the MDBX source, wallet keeps card-type filters, and passkeys omit empty quick filters. Rounded press feedback and low-DPI sizing are shared; password filters retain drag reordering.

- **Unreadable authenticator protection:** Retain the list entry and original data; block code copying, QR generation and empty editing while the key is unreadable. Retry after unlock or source recovery, and exclude unreadable entries from code pickers. Keep database and backup formats and existing keys unchanged.
- **mOTP display deduplication:** Include the PIN in authenticator identity so distinct PINs remain visible. Continue collapsing identical bound mirrors without deleting stored records.
