# Monica for Android (F-Droid) 1.0.313

## 中文

### 简要

- 修复密码库列表混入普通卡片或堆叠卡片的问题：所有条目始终平铺，不受密钥页堆叠设置影响。
- 优化开启预览页面时的密码库首屏加载：密码条目就绪后立即显示概览，其他类型在后台完成解析并自动补全。
- 恢复 Sui 初始化，修复已授权却显示未运行的问题。

- 重做去重引擎，支持单库或多库整理、冲突预览、可用 Passkey 去重和完整字段保存。
- 优化 Dock 连续切换，大列表分组与排序接入 Rust。
- 快速初始化新增六套布局预设，Dock 与页面微调分步设置。
- 新增香港繁体中文、文言文（华夏）、波兰语和猫语，中文选项集中在可展开的语言卡片中。
- ZIP、CSV 导入和文件导出支持选择数据库，加入进度显示和后台导出。
- 数据库、WebDAV 备份等页面统一采用 M3E 设计，加入平滑展开与可选卡叠循环。
- KeePass 支持冲突合并，改进 MDBX 令牌读取、数据库筛选、同步状态和回收站体验。
- 修复标记“非自动填充”后仍出现解锁提示的问题。
- 改进工号、学号、分步登录及网页表单的填充，减少漏填和误填。

### 详细

- **去重整理**：来源、目标与冲突策略集中在连接式圆角操作组；数据库和合并明细支持搜索，新增、冲突、跳过分别筛选，写入进度与主操作保持可达。支持将单个或多个来源整理到独立的本地或 MDBX 目标，保留来源与目标已有内容；同账号但内容不同的目标条目另存一份。判重区分 URL 路径、非邮箱账号大小写、密码空格及 OTP 参数，保留自定义字段、附件、照片和卡面，同名不同内容的附件分别保留；再次整理不会重复新增，MDBX 重新打开后内容完整。取消或重新扫描不会执行旧结果，Passkey 按凭据身份和密钥判重，同账号的不同钥匙分别保留；非零计数、不可用或冲突记录会列明跳过原因。批量移动先保存目标再清理来源，删除副本不影响仍在使用的共享私钥。
- **Dock 与列表性能**：切换页面复用已初始化的加密组件，返回密码页复用完全匹配的分组结果；数据或堆叠设置变化时仍及时重算。大列表的归组、评分和稳定排序使用 Rust 批量处理，保留原有卡片顺序与样式，并设定批量上限、结果校验和兼容回退。
- **快速初始化**：新增 Bitwarden 预览／列表、验证器专用、分页管理、日常常用和极简密码六套预设，可先预览 Dock 再应用；支持保留当前布局。流程调整为欢迎、预设、Dock、页面微调、安全与自动填充、接入数据、完成；Dock 复用底栏设置卡片，完整显示已开启及隐藏页面，支持长按拖拽排序；列表、卡片和配色在后续步骤按需展开。返回前一步保留微调，设置入口始终可达，并补齐各语言文案。
- **语言与本地化**：新增香港繁体中文、文言文（华夏）、波兰语和猫语。繁体中文采用香港常用书面用语，明确区分简繁语言，缺失文案优先回退简体中文；文言文采用简洁、统一的古文表达，波兰语调整常用标签与复数形式，猫语缺失内容回退简体中文。清理 Bitwarden、备份恢复、Steam、自动填充等页面的中文硬编码，补齐各语言的新旧功能文案。2FA 和 Steam 卡片统一使用 `Next` 标记。
- **选择目标导入**：ZIP 和 CSV 可导入到本地、KeePass、MDBX 或 Bitwarden，按目标数据库格式保存支持的数据类型。加密 ZIP 使用备份文件自身的解密密码，导入结果展示成功、跳过、失败及待上传状态；重复导入进行去重。
- **按数据库导出**：文件导出使用所选数据库作为来源，ZIP 中的密码、Passkey、附件和回收站均跟随该范围。可移植 Passkey 私钥与附件通过加密 ZIP 导出；附件流式写入并校验完整性，异常时指出具体条目与附件。其他数据库的数据与应用连接配置不混入备份。
- **传输进度与性能**：导入展示读取、解密、写入及附件处理进度；导出可在离开页面后继续，并通过通知显示进度与结果。MDBX 使用 Rust 分批写入，减少重复解锁与落盘，按数据大小控制批次；超限记录明确报错，中断后可保留已提交内容并去重重试。
- **数据库管理**：MDBX 与 KeePass 统一使用自适应、等尺寸数据库磁贴和连接式圆角分组，两侧留白收窄，打开／新建始终可达。同步、冲突、历史、快照、结构等页面统一布局，优先呈现常用操作，技术信息按需展开。数据库筛选菜单按可见范围加载，减少数据库较多时打开与展开的卡顿。
- **WebDAV 全量备份**：备份列表、设置分区和固定操作区采用 M3E 布局。列表展示时间、大小、加密与保留状态；连接、加密及备份内容集中设置，目录选择和恢复确认使用一致的交互。HTTP 连接需在配置中明确启用，默认关闭。
- **语言与多凭据选择**：设置和首次配置共用新的语言选择弹窗。中文采用与其他语言一致的长卡片，右侧箭头独立展开简体、繁体、猫语和文言文，选择后同步更新卡片名称和设置页摘要；保留独立的「跟随系统」选项，适配深色模式与大字体。多凭据编辑清晰区分公共信息与当前独立凭据，底栏突出当前编辑对象、新增和保存，切换面板与删除确认明确对应条目。
- **概览与卡包**：类型筛选磁贴统一尺寸，长译文换行时整组增高，并适配窄屏与大字体。卡包可在「页面调整 → 自定义」中开启循环堆叠，保持原有卡片外观，末张接续首张，支持双向浏览。
- **平滑展开动效**：新建、编辑页的折叠区域，以及各类详情中的长密码、私钥和受保护字段使用共用展开动效。显示时自然换行并平滑改变高度，隐藏时立即恢复固定短遮罩；遵循减少动效设置。
- **KeePass 冲突合并**：本地与远端同时修改后，可比较并合并不同条目或字段；重叠修改支持逐项选择保留版本，敏感字段默认遮蔽。上传前再次检查远端版本，失败后保留内容供重试。
- **MDBX API 令牌**：支持自定义服务商、API 地址、多行备注、受保护字段与收藏，并可在原生 MDBX 库之间复制或移动。复用有效读取会话，减少每次查看令牌正文时的重复解锁等待，保留现有 CLI 数据与扩展字段。
- **MDBX 读取与同步**：远端网络传输不再阻塞本地令牌读取。查看令牌产生的安全审计继续同步，但不再误报为条目未同步；真实新增、编辑及同步期间的修改仍保留待上传状态。
- **回收站**：沿用进入前的数据库范围，统一多选、全选、恢复和永久删除操作；返回密码页或密码库的入口改为与其他页面位置一致的悬浮按钮。
- **自动填充标记**：标记“非自动填充”后立即撤下当前输入框的系统提示；旧解锁入口与密码建议在启动时重新检查标记，避免开启自动填充验证时反复显示解锁卡片。
- **填充兼容性**：系统与无障碍填充补充工号、学号及非标准中文字段识别，支持账号、密码分步登录；排除搜索框和验证码，避免混入其他窗口或网页的字段。网页优先按指定字段写入，减少异步粘贴错位；取消旧请求后停止回调，保留 Android Q 的免验证填充。
- **键盘填充**：连续填写前确认焦点已切换；App 消费“下一项”但未移动焦点、重建同一输入框连接或切换到其他 App 时停止，避免密码追加到账号中。
- **稳定性修复**：修正 Bitwarden/Vaultwarden 的同步结果与回收站删除处理，保留失败原因和可重试状态；修复密码库概览跳转闪退，以及部分设备打开验证器时的类验证异常。
- **F-Droid 版本说明**：保留 Monica 自有 Passkey 的创建、登录与支持的加密 ZIP 文件迁移。本版不包含依赖 Google Play 服务的系统凭据交换，Google Drive 和 OneDrive 登录继续禁用；扫码使用 CameraX 与 ZXing。

感谢 @aiguozhi123456 对猫语语言包和验证器兼容性修复的贡献。

## English

### Summary

- Keep all vault entries in individual list rows, independent of password-page stacking settings.
- Speed up the vault overview first frame by showing password entries as soon as they are ready and completing other item types in the background.
- Restore Sui initialization to detect authorized connections.

- Redesign deduplication with single- or multi-vault consolidation, conflict previews, eligible passkey deduplication, and complete field preservation.
- Improve rapid Dock switching and use Rust for large-list grouping and sorting.
- Add six quick-setup layouts, with separate Dock and page-customization steps.
- Add Hong Kong Traditional Chinese, Classical Chinese (Huaxia), Polish, and Nya, with Chinese variants grouped in an expandable language card.
- Add database selection for ZIP/CSV imports and file exports, progress indicators, and background exports.
- Refresh database and WebDAV backup pages with M3E layouts, smooth expansion, and optional looping card stacks.
- Add KeePass conflict merging and improve MDBX token loading, database filters, sync status, and the recycle bin.
- Fix unlock prompts remaining after a field is marked as unsuitable for autofill.
- Improve filling for employee/student IDs, two-step sign-in, and web forms, reducing missed or incorrect fields.

### Details

- **Deduplication:** Configure sources, destination, and conflict policy in connected rounded rows. Search databases and preview details, filter additions, conflicts, and skipped items, and keep progress and primary actions accessible. Consolidate one or more sources into a separate local or MDBX destination while preserving sources and existing destination entries. Keep a separate copy when an existing account has different content. Matching preserves URL path and non-email username case, password whitespace, OTP parameters, custom fields, attachments, photos, and card faces, including after reopening MDBX. Same-name attachments with different content are retained, and repeat runs do not add duplicates. Cancelled or replaced scans cannot execute stale results. Passkeys are matched by credential identity and key data, preserving different keys for the same account. Nonzero counters, unavailable keys, and conflicts show explicit skip reasons. Batch moves save the destination before source cleanup, and deleting a copy preserves keys still referenced by other records.
- **Dock and list performance:** Reuse initialized security components between pages and reuse password groups when the complete input snapshot matches. Data or stack-setting changes still trigger recalculation. Rust batches grouping, scoring, and stable sorting for large lists, preserving card order and appearance with input limits, result validation, and a compatible fallback.
- **Quick setup:** Preview and apply six layouts: Bitwarden overview or list, Authenticator, Separate pages, Everyday, and Minimal. Keep your current layout if preferred. The flow now covers welcome, presets, Dock, page customization, security and autofill, data connections, and completion. Dock uses the existing settings cards, lists both visible and hidden pages, and supports drag-and-drop ordering in its own step; list, card, and color options expand separately in the next step. Going back preserves adjustments, Settings remains accessible, and all supported languages include the new text.
- **Languages and localization:** Add Hong Kong Traditional Chinese, Classical Chinese (Huaxia), Polish, and Nya. Traditional Chinese uses Hong Kong terminology and is distinguished from Simplified Chinese, with Simplified Chinese as its first fallback. Classical Chinese uses concise, consistent terminology; Polish includes compact labels and proper plural forms. Missing Nya text falls back to Simplified Chinese. Replace hardcoded Chinese in Bitwarden, backup, Steam, autofill, and other screens, and complete translations for existing and new features. Both 2FA and Steam cards use the label `Next`.
- **Import destinations:** Import ZIP and CSV files into a local, KeePass, MDBX, or Bitwarden database, using the destination's format for supported items. Encrypted ZIP files use their own decryption password. Results show imported, skipped, failed, and pending-upload items, with deduplication for repeated imports.
- **Database-scoped exports:** File exports use the selected database. Passwords, passkeys, attachments, and trash in a ZIP follow that scope. Portable passkey private keys and attachments require an encrypted ZIP. Attachments are streamed and checked for integrity; failures identify the item and attachment. Unrelated databases and app connection settings are excluded.
- **Transfer progress and performance:** Import shows reading, decryption, writing, and attachment stages. Exports continue after leaving the page, with progress and results in notifications. MDBX batches writes through Rust to reduce repeated unlocking and disk writes, limits batches by data size, and reports oversized records. Interrupted imports retain committed items for deduplicated retries.
- **Database management:** MDBX and KeePass share adaptive, equally sized database tiles, grouped rounded controls, and smaller side margins. Open/Create remain accessible above long lists. Sync, conflicts, history, snapshots, structure, and related pages prioritize common actions and reveal technical details on demand. Database filter menus compose visible chips on demand to reduce opening and expansion delays with many databases.
- **Full WebDAV backups:** Backup lists, settings sections, and fixed action areas use M3E layouts. Lists show dates, sizes, encryption, and retention status. Connection, encryption, and backup contents are configured together, with consistent folder selection and restore confirmation. HTTP connections require explicit opt-in and remain disabled by default.
- **Language and credential selection:** Settings and initial setup share the new language dialog. Chinese uses the same full-width card style as other languages, with a separate arrow for Simplified Chinese, Traditional Chinese, Nya, and Classical Chinese. Selection updates the card label and Settings summary. Follow system remains separate, and the dialog supports dark mode and large text. Multiple-credential editing clearly distinguishes shared information from the current individual credential. The bottom bar highlights the current editor, Add, and Save; selection and deletion identify the affected entry.
- **Overview and card wallet:** Type filters use uniform tile sizes, growing together when translated labels wrap and adapting to narrow screens and large text. Optional looping stacks under Page adjustment → Customization preserve the existing card appearance, connect the last card to the first, and support browsing in both directions.
- **Smooth expansion:** Collapsible editor sections and long passwords, private keys, and protected detail fields share smooth expansion. Revealed values wrap naturally as cards resize; hiding immediately restores a short fixed mask. Reduced-motion settings are respected.
- **KeePass conflict merging:** Compare and merge concurrent local and remote changes to entries or fields. Choose versions for overlapping changes, with sensitive values masked by default. The remote version is checked again before upload, and failed uploads retain content for retry.
- **MDBX API tokens:** Add custom providers, API addresses, multiline notes, protected fields, favorites, and copying or moving between native MDBX databases. Reuse valid read sessions to reduce repeated unlock waits for token content while preserving existing CLI data and extension fields.
- **MDBX reading and sync:** Remote network transfers no longer block local token reads. Viewing a token still produces synchronized security audit records without incorrectly marking entries as unsynced. Actual additions, edits, and changes made during sync retain their pending-upload status.
- **Recycle bin:** Preserve the current database scope and use consistent selection, Select all, Restore, and permanent deletion controls. A floating button returns to passwords or the vault, matching the action placement on other pages.
- **Autofill exclusions:** Marking a field as unsuitable for autofill dismisses its current system suggestions. Cached unlock entries and password suggestions recheck the exclusion before opening, preventing repeated unlock cards when autofill verification is enabled.
- **Filling compatibility:** System and accessibility filling recognize employee/student IDs and nonstandard Chinese fields, support separate username/password steps, exclude search and verification-code fields, and keep fields scoped to the active window or web origin. Web fields are addressed directly to avoid asynchronous paste targeting errors; cancelled requests stop delivering callbacks. Android Q filling without verification remains available.
- **Keyboard filling:** Verify that focus has moved before filling the next value. Stop when an app consumes Next without moving focus, restarts the same editor, or switches to another app, preventing passwords from being appended to usernames.
- **Stability fixes:** Correct Bitwarden/Vaultwarden sync results and trash deletion while retaining failure details and retryable state. Fix vault overview navigation crashes and authenticator class verification errors on affected devices.
- **F-Droid edition:** Existing Monica passkey creation, sign-in, and supported encrypted ZIP migration remain available. System Credential Exchange, which depends on Google Play Services, is omitted. Google Drive and OneDrive sign-in remain disabled. Scanning uses CameraX and ZXing.

Thanks to @aiguozhi123456 for contributions to Nya and authenticator compatibility.
