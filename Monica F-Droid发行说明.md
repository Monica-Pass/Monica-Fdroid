# Monica for Android (F-Droid) 1.0.312

## 中文

### 简要

- 新增文言文（华夏）、波兰语和猫语，完善多语言体验。
- ZIP、CSV 导入和文件导出支持选择数据库，加入进度显示和后台导出。
- 数据库、WebDAV 备份等页面统一采用 M3E 设计，加入平滑展开与可选卡叠循环。
- KeePass 支持冲突合并，改进 MDBX 令牌读取、数据库筛选、同步状态和回收站体验。

### 详细

- **语言与本地化**：新增文言文（华夏）、波兰语和猫语。文言文采用简洁、统一的古文表达，波兰语调整常用标签与复数形式；猫语缺失内容回退简体中文。清理 Bitwarden、备份恢复、Steam、自动填充等页面的中文硬编码，补齐各语言的新旧功能文案。2FA 和 Steam 卡片统一使用 `Next` 标记。
- **选择目标导入**：ZIP 和 CSV 可导入到本地、KeePass、MDBX 或 Bitwarden，按目标数据库格式保存支持的数据类型。加密 ZIP 使用备份文件自身的解密密码，导入结果展示成功、跳过、失败及待上传状态；重复导入进行去重。
- **按数据库导出**：文件导出使用所选数据库作为来源，ZIP 中的密码、Passkey、附件和回收站均跟随该范围。可移植 Passkey 私钥与附件通过加密 ZIP 导出；附件流式写入并校验完整性，异常时指出具体条目与附件。其他数据库的数据与应用连接配置不混入备份。
- **传输进度与性能**：导入展示读取、解密、写入及附件处理进度；导出可在离开页面后继续，并通过通知显示进度与结果。MDBX 使用 Rust 分批写入，减少重复解锁与落盘，按数据大小控制批次；超限记录明确报错，中断后可保留已提交内容并去重重试。
- **数据库管理**：MDBX 与 KeePass 统一使用自适应、等尺寸数据库磁贴和连接式圆角分组，两侧留白收窄，打开／新建始终可达。同步、冲突、历史、快照、结构等页面统一布局，优先呈现常用操作，技术信息按需展开。数据库筛选菜单按可见范围加载，减少数据库较多时打开与展开的卡顿。
- **WebDAV 全量备份**：备份列表、设置分区和固定操作区采用 M3E 布局。列表展示时间、大小、加密与保留状态；连接、加密及备份内容集中设置，目录选择和恢复确认使用一致的交互。HTTP 连接需在配置中明确启用，默认关闭。
- **语言与多凭据选择**：设置和首次配置共用新的语言选择弹窗。多凭据编辑清晰区分公共信息与当前独立凭据，底栏突出当前编辑对象、新增和保存，切换面板与删除确认明确对应条目。
- **概览与卡包**：类型筛选磁贴统一尺寸，长译文换行时整组增高，并适配窄屏与大字体。卡包可在「页面调整 → 自定义」中开启循环堆叠，保持原有卡片外观，末张接续首张，支持双向浏览。
- **平滑展开动效**：新建、编辑页的折叠区域，以及各类详情中的长密码、私钥和受保护字段使用共用展开动效。显示时自然换行并平滑改变高度，隐藏时立即恢复固定短遮罩；遵循减少动效设置。
- **KeePass 冲突合并**：本地与远端同时修改后，可比较并合并不同条目或字段；重叠修改支持逐项选择保留版本，敏感字段默认遮蔽。上传前再次检查远端版本，失败后保留内容供重试。
- **MDBX API 令牌**：支持自定义服务商、API 地址、多行备注、受保护字段与收藏，并可在原生 MDBX 库之间复制或移动。复用有效读取会话，减少每次查看令牌正文时的重复解锁等待，保留现有 CLI 数据与扩展字段。
- **MDBX 读取与同步**：远端网络传输不再阻塞本地令牌读取。查看令牌产生的安全审计继续同步，但不再误报为条目未同步；真实新增、编辑及同步期间的修改仍保留待上传状态。
- **回收站**：沿用进入前的数据库范围，统一多选、全选、恢复和永久删除操作；返回密码页或密码库的入口改为与其他页面位置一致的悬浮按钮。
- **稳定性修复**：修正 Bitwarden/Vaultwarden 的同步结果与回收站删除处理，保留失败原因和可重试状态；修复密码库概览跳转闪退，以及部分设备打开验证器时的类验证异常。
- **F-Droid 版本说明**：保留 Monica 自有 Passkey 的创建、登录与支持的加密 ZIP 文件迁移。本版不包含依赖 Google Play 服务的系统凭据交换，Google Drive 和 OneDrive 登录继续禁用；扫码使用 CameraX 与 ZXing。

感谢 @aiguozhi123456 对猫语语言包和验证器兼容性修复的贡献。

## English

### Summary

- Add Classical Chinese (Huaxia), Polish, and Nya, and improve localization across the app.
- Add database selection for ZIP/CSV imports and file exports, progress indicators, and background exports.
- Refresh database and WebDAV backup pages with M3E layouts, smooth expansion, and optional looping card stacks.
- Add KeePass conflict merging and improve MDBX token loading, database filters, sync status, and the recycle bin.

### Details

- **Languages and localization:** Add Classical Chinese (Huaxia), Polish, and Nya. Classical Chinese uses concise, consistent terminology; Polish includes compact labels and proper plural forms. Missing Nya text falls back to Simplified Chinese. Replace hardcoded Chinese in Bitwarden, backup, Steam, autofill, and other screens, and complete translations for existing and new features. Both 2FA and Steam cards use the label `Next`.
- **Import destinations:** Import ZIP and CSV files into a local, KeePass, MDBX, or Bitwarden database, using the destination's format for supported items. Encrypted ZIP files use their own decryption password. Results show imported, skipped, failed, and pending-upload items, with deduplication for repeated imports.
- **Database-scoped exports:** File exports use the selected database. Passwords, passkeys, attachments, and trash in a ZIP follow that scope. Portable passkey private keys and attachments require an encrypted ZIP. Attachments are streamed and checked for integrity; failures identify the item and attachment. Unrelated databases and app connection settings are excluded.
- **Transfer progress and performance:** Import shows reading, decryption, writing, and attachment stages. Exports continue after leaving the page, with progress and results in notifications. MDBX batches writes through Rust to reduce repeated unlocking and disk writes, limits batches by data size, and reports oversized records. Interrupted imports retain committed items for deduplicated retries.
- **Database management:** MDBX and KeePass share adaptive, equally sized database tiles, grouped rounded controls, and smaller side margins. Open/Create remain accessible above long lists. Sync, conflicts, history, snapshots, structure, and related pages prioritize common actions and reveal technical details on demand. Database filter menus compose visible chips on demand to reduce opening and expansion delays with many databases.
- **Full WebDAV backups:** Backup lists, settings sections, and fixed action areas use M3E layouts. Lists show dates, sizes, encryption, and retention status. Connection, encryption, and backup contents are configured together, with consistent folder selection and restore confirmation. HTTP connections require explicit opt-in and remain disabled by default.
- **Language and credential selection:** Settings and initial setup share the new language dialog. Multiple-credential editing clearly distinguishes shared information from the current individual credential. The bottom bar highlights the current editor, Add, and Save; selection and deletion identify the affected entry.
- **Overview and card wallet:** Type filters use uniform tile sizes, growing together when translated labels wrap and adapting to narrow screens and large text. Optional looping stacks under Page adjustment → Customization preserve the existing card appearance, connect the last card to the first, and support browsing in both directions.
- **Smooth expansion:** Collapsible editor sections and long passwords, private keys, and protected detail fields share smooth expansion. Revealed values wrap naturally as cards resize; hiding immediately restores a short fixed mask. Reduced-motion settings are respected.
- **KeePass conflict merging:** Compare and merge concurrent local and remote changes to entries or fields. Choose versions for overlapping changes, with sensitive values masked by default. The remote version is checked again before upload, and failed uploads retain content for retry.
- **MDBX API tokens:** Add custom providers, API addresses, multiline notes, protected fields, favorites, and copying or moving between native MDBX databases. Reuse valid read sessions to reduce repeated unlock waits for token content while preserving existing CLI data and extension fields.
- **MDBX reading and sync:** Remote network transfers no longer block local token reads. Viewing a token still produces synchronized security audit records without incorrectly marking entries as unsynced. Actual additions, edits, and changes made during sync retain their pending-upload status.
- **Recycle bin:** Preserve the current database scope and use consistent selection, Select all, Restore, and permanent deletion controls. A floating button returns to passwords or the vault, matching the action placement on other pages.
- **Stability fixes:** Correct Bitwarden/Vaultwarden sync results and trash deletion while retaining failure details and retryable state. Fix vault overview navigation crashes and authenticator class verification errors on affected devices.
- **F-Droid edition:** Existing Monica passkey creation, sign-in, and supported encrypted ZIP migration remain available. System Credential Exchange, which depends on Google Play Services, is omitted. Google Drive and OneDrive sign-in remain disabled. Scanning uses CameraX and ZXing.

Thanks to @aiguozhi123456 for contributions to Nya and authenticator compatibility.
