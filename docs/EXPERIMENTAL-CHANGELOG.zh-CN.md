# Monica for Android (F-Droid) 1.0.315

- 未发布：银行卡、笔记支持完整独立副本，包含卡面、图片、附件及未知字段；复用完整编辑器并隐藏数据库选择，可打开完整详情。附件完成后才发布新内容，失败保留旧内容并支持重试；修复启动附件清理误删新文件。证件和账单地址完整副本编辑器仍待接入。

- **密码强度内嵌标签：** 新版凭据区将强度放入对应密码卡片内部，以简洁的等级标签代替外置进度条和分数；空值收起，输入后平滑撑起卡片，等级和颜色平滑变化。多个密码分别显示自己的强度，保留连续分组圆角与原有保存行为。

- **详情验证器操作：** 详情卡片始终显示自己的倒计时进度，不受列表统一进度条设置影响；按压反馈沿卡片圆角裁切。点击、长按或更多按钮打开操作菜单，支持复制当前验证码、复制 Next、生成迁移二维码和当前验证码二维码；点击操作时重新计算验证码。迁移保留账号和 OTP 参数；HOTP 保留计数器，不提供时间进度和 Next 复制。

- **日期排序补充：** 日期分组与滚动提示统一为 yyyy/MM/dd，提示胶囊按文本及系统字号计算宽度；修复旧密码编辑页异步加载时强度提示访问空列表的崩溃。补充本地编辑时间、概览实时刷新与返回后排序的回归验证；不改写原创建时间。

- **新建页凭据分组：** 账号与连续多个密码统一采用外侧大圆角、相邻小圆角，新增／删除后正确更新组尾，强度提示移至组下方；修复新建单账号多个密码时只保存首项的问题；数据库选择恢复经典强调卡片与独立图标底座，条目图标移出标题输入框，网址继续采用连续分组。

- **生成器拖动与常用建议：** 修复拖动长度时结果清空闪烁和面板跳动；结果区高度固定，长内容自动缩小字号，达到最低可读字号后可滚动；拖动保留结果、松手更新，禁止应用过期结果。用户名与密码建议直接明文显示，预设在前，后附密码库按出现次数排序的最多五个非重复值；排除归档、回收站、锁定／不可访问来源和不可读密码，保留大小写与空格，不修改或同步条目。

- **最近变动与概览：** 密码列表新增“最近变动”，按创建和修改时间中较新的时间倒序排列；概览新增“最近”模块，默认位于常用卡片下方，展示当前可访问数据库最近8项，支持折叠、隐藏、调整位置，点击打开完整详情。修复 KeePass 密码、卡片和验证器读取／同步把导入或刷新时间当作条目时间的问题，保留 KDBX 原始时间。排序不修改数据库内容。

不可用的认证方式从选择列表隐藏；服务端只提供不支持方式时显示说明，不提供验证码提交入口。

- **Bitwarden / Vaultwarden 两步验证：** 兼容旧认证方式列表及 `TwoFactorProviders2`，修复部分服务端无法进入验证步骤的问题；验证码错误后保留弹窗重试，请求中禁止重复提交，取消后清除临时认证状态。多种方式先选择再操作；邮件需显式点击发送验证码，发送中禁止重复请求。TOTP 可从 Monica 搜索已有独立验证器和密码内验证器，不受验证器页面筛选影响；点击时生成当前验证码，填入后由用户确认，也可手动输入。支持现有 TOTP、邮件、新设备邮件验证及 YubiKey OTP；不再把 WebAuthn、Duo 或未知方式误当成验证码，SSO 和 Passkey 登录仍未接入。

- **WebDAV 通行密钥备份处理：** 失败时弹出 M3E 卡片，列出具体项目、原因与解决建议，支持重试或明确跳过问题 Passkey。跳过后另存加密的部分备份并记录遗漏清单，保留远端旧备份，不更新完整备份时间；部分备份仅可合并恢复，禁止覆盖本地数据。兼容可导出的旧密钥别名并校验 PKCS#8；未明确跳过的失败仍阻止上传。


- **生成器统一 Sheet：** 用户名与密码使用同一套底部面板，完整展示彩色等宽结果，操作位于结果下方；保留常用账号/密码建议。用户名支持随机用户名与邮箱加号别名，密码复用随机符号、短语和 PIN 生成；参数连续分组，长度支持滑块和增减，支持 4–128 位符号密码。无效条件禁用使用，取消不修改字段，生成结果不自动写入剪贴板。

- **新建页简化：** 统一新版按需编辑，移除经典入口与失效的旧字段开关；恢复紧凑顶栏，凭据采用填充式连续分组，删除账号密码留空说明。新建不再提供第三方登录，已有第三方登录记录保留提供商和关联账号，可继续查看和编辑。

> 未发布。原实验内容已接入 main，构建版本已切换为 1.0.315，接入后的构建与回归验证已通过，版本尚未发布。接下来的新增功能、优化与修复均记录在本版本。

## 中文

### 简要

- 统一“按需添加”密码编辑样式，统一已有字段与详情信息卡的展示。
- 密码详情显示支付卡面，并复用验证器页面的验证码卡片。
- 优化大字体、窄屏和自定义字段操作；“添加附件”移至卡片底部右侧。

### 详细

- **设置页面统一设计：** 重做自动填充、清除数据、功能拓展、数据库与备份页面，统一紧凑分组、外侧大圆角与相邻小圆角，移除重复说明横幅和大图标底座；Monica Plus 卡片保留。数据库入口集中展示，清除范围右侧对齐、确认操作保持可达，未选任何类型时禁止提交，保留主密码校验；策略与选择弹窗支持滚动，避免大字体裁切。


- **Tiga 三形态滑块：** MDBX 本地、WebDAV、OneDrive 新建页直接展示三档滑块，移除数据库选项与引擎说明。Power 红、Multi 紫红渐变、Sky 蓝，三档均有轻柔闪光点，档位切换带渐变色、吸附、阻尼弹簧过渡和轻震动，遵循减少动画与触觉反馈设置；保持默认 Multi 及原有安全参数。


- **紧凑权限管理：** 去除卡片中的大号状态文字、独立操作按钮和“无需操作”提示，改为名称、较小用途说明及统一右对齐的小状态图标，不再显示箭头；点击整张卡片执行对应权限操作。保留读屏状态、不可操作权限保护和返回设置后的刷新。


- **自定义子页面：** 重做密码列表、密码卡片、验证器卡片、密码字段、图标及添加按钮设置，统一连续分组、实时预览与纵向选项。排序行适应大字体，保留拖动并新增上移/下移；预设字段操作置底、敏感默认值遮挡，编辑弹窗支持滚动，清空预设前确认；保留设置联动及搜索定位。


本版已接入的实验内容与后续修复：

- **KeePass 兼容复核：** 补充同一条目同时包含 OTP、通行密钥和恢复备注的 KDBX 3/4 回归；覆盖 6 个 OTP、8 个通行密钥的完整读取，以及编辑或添加 OTP 后备注与私钥保留。本次未新增读取逻辑变更。

- **设置子页面：** 重新设计页面调整自定义与权限管理，改用外侧大圆角、组内小圆角的连续分组。自定义按密码库、卡片和字段图标归类，保留开关、导航与搜索定位；权限用途、状态和操作分层展示，支持大字体完整换行，按需开启权限，从系统设置返回时自动刷新。

- **旧版 Android 自动填充：** 修复 Android 10 等旧版系统在自动填充请求的协程恢复时引用新版内联候选类型、导致服务崩溃的问题。补充候选显示期间的连续输入、组合输入、字段切换及取消验证后的焦点测试。用户反馈的偶发输入阻塞尚不能确定为同一原因。

- **本地数据与验证器恢复：** 覆盖恢复改为解析后再事务写入，写入失败或取消时回滚本地条目及关联字段；不再按同一账号误跳过不同密钥、参数或恢复资料的验证器，重新映射密码绑定时保留未知扩展字段。损坏单条验证器不再中断列表，解密失败的密文不再当作普通密钥；解锁后重新解析。后台敏感字段迁移使用条件更新，避免覆盖并发编辑的新值。

- **多语言补齐：** 补齐 11 种语言共 490 条新增编辑样式、字段、密码库排序、设置搜索及 MDBX 冲突/未知类型提示文案，保留原有译文与彩蛋语言继承规则。

- **详情字段条形码：** 复用独立条形码项目的展示组件，码图保持矩形并留出完整白边，修复条纹被圆角裁切的问题。按实际可用宽度生成 Code 128，内容过长时提示切换二维码；保留格式切换和页面状态恢复。

- **Monica 键盘菜单：** 将按住菜单时的反馈限制在圆形按钮内，去除矩形白色包边，保留完整点击范围。自动填充入口由三个点改为“箭头进入输入框”图标，与密码、验证器等入口区分；清空后的撤销功能保留。

- **时间线稳定性（#144）：** 修复法语界面存在较早历史记录时，打开时间线因日期格式翻译错误而闪退的问题；保留正确的日/月显示，并补充全部语言日期格式及历史日期分组回归测试。

- **按需编辑与已有字段：** 统一按需添加编辑，保留已有数据。密码、卡包、笔记和证件沿用已有模型与存储格式，保留敏感复制、显隐、大字、二维码 / Code 128、分享和 Send。密码编辑沿用 main 已有的 TOTP、HOTP、Steam、Yandex、mOTP 支持，并覆盖按需添加、保存、重新编辑与凭据切换。
- **统一详情卡片：** 内置与自定义信息使用统一平面卡片和字段布局，减少重复边距、阴影和卡片嵌套。大字体与窄屏下操作独立成行，秘密内容变更或切换条目后重新隐藏。
- **密码中的支付卡面：** 将密码已有的单组支付信息显示为银行卡面；展开查看卡号、持卡人、有效期和 CVV，敏感值默认隐藏，大字体下保留卡号尾号。暂不增加多卡绑定和卡包自定义图片关联。
- **密码中的验证器：** 复用验证器页面的验证码卡片、类型格式、隐藏设置与时间偏移；缺少关联验证器时读取密码自身保存的加密 OTP。HOTP 显示已保存的计数器，不再误用即将到期的红色，本轮不增加计数器写入。
- **附件布局：** 经典与按需编辑中的“添加附件”独占卡片底部右侧，标题和文件列表使用完整宽度，修复英文大字体下标题被挤成竖排的问题。
- **验证器已有资料：** 改进发行者、账号和备注的展示，保留 Steam 元数据。验证器新增自定义字段仍暂缓，不列为本版已实现功能。

## English

### Summary

- Use the unified on-demand password editor and unify existing detail fields.
- Show payment card artwork and authenticator cards in password details.
- Improve large-text and narrow-screen layouts, with Add attachment at the bottom-right of its card.

### Details

- **Unified settings pages:** Redesigned Autofill, Clear data, Extensions, and Database & backup with compact grouped rows. Kept the Monica Plus card, navigation, and password verification; clear actions remain reachable and require a selected data type.


- **Tiga slider:** Local, WebDAV and OneDrive MDBX creation now show a three-position slider instead of database engine options. Red Power, purple-to-red Multi and blue Sky have subtle sparkles, animated colors, damped snapping and optional haptics. Existing security profiles and default Multi remain unchanged.


- **Compact permissions:** Remove large status labels and separate action buttons. Show smaller descriptions and right-aligned status icons without arrows; tap the whole row to manage a permission. Preserve accessibility states, static permissions and refresh on return.


- **Customization subpages:** Redesign password lists/cards, authenticator cards, fields, icons and the Add button with grouped settings, live previews and vertical choices. Keep drag reordering and add move buttons; support large text, mask sensitive preset defaults, scroll field dialogs and confirm clearing presets. Preserve setting dependencies and search navigation.


- **KeePass compatibility regression:** Cover KDBX 3/4 entries combining OTP, passkeys and recovery notes, including all six OTPs and eight passkeys plus preservation after editing or adding OTP. No additional runtime parsing change was needed.

- **Settings subpages:** Redesign page customization and permission management with grouped rows. Preserve toggles, navigation and search focus; separate permission descriptions, statuses and actions, support large text, and refresh statuses after returning from system settings.

- **Autofill on older Android:** Fix a service crash caused by restoring an Android 11-only inline request type across coroutine suspension on Android 10 and earlier. Add real input-connection coverage for typing, composition, field changes and focus after cancelling authentication. The reported intermittent input blockage has not been confirmed as the same issue.

These changes are integrated into main with build version 1.0.315. Post-integration builds and regression checks passed. Version 1.0.315 remains unpublished.

- **Local data and authenticator recovery:** Apply local replacement in a transaction after parsing, rolling back records and related fields on write failure or cancellation. Preserve authenticators with different secrets, parameters or recovery metadata, including unknown fields when remapping password bindings. Isolate unreadable records, retry parsing after unlock, and prevent background encryption migration from overwriting concurrent edits.

- **Translations:** Complete 490 missing strings across 11 locales for editor styles, fields, vault sorting, settings search and MDBX conflict/unknown-type guidance, preserving existing translations and easter-egg locale fallback.

- **Detail-field barcodes:** Reuse the saved barcode preview with rectangular artwork and a complete white border, fixing rounded clipping of the bars. Generate Code 128 for the available width and suggest QR for values that cannot fit; preserve format switching and state restoration.

- **Monica keyboard toolbar:** Keep press feedback inside circular buttons while preserving the full touch area. Replace the three-dot autofill shortcut with an arrow-entering-field icon, distinct from the password and authenticator icons; retain the temporary undo action.

- **Timeline stability (#144):** Fix a crash when opening older history in French caused by translated date-format tokens. Preserve day/month display and add date-pattern and timeline-grouping regression checks across languages.

- **Editing and existing fields:** Keep the classic editor as the default and offer an on-demand alternative in page settings. Preserve existing storage models and sensitive-field actions. Carry main's existing TOTP, HOTP, Steam, Yandex and mOTP support through on-demand editing, saving, reopening and credential switching.
- **Detail cards:** Give built-in and custom fields consistent surfaces and spacing. Place actions on separate rows for large text or narrow screens, and hide revealed secrets again when their values or entries change.
- **Payment information:** Render the password's existing single payment group as a card face, with expandable details and masked sensitive values. Preserve the last digits with large text. Multiple-card binding and linked custom card images are outside this change.
- **Authenticator display:** Reuse authenticator-page cards and their formatting, masking and time offset. Fall back to the password's encrypted OTP when no linked authenticator exists. Show the saved HOTP counter without expiry-red styling; no new counter-write path is introduced.
- **Attachments:** Place Add attachment on its own bottom-right row in both editor styles, leaving full width for the heading and file list.
- **Existing authenticator metadata:** Improve issuer, account and notes display while preserving Steam metadata. New authenticator custom fields remain deferred.

- Docker 真实登录验证：普通版与 F-Droid 均通过 Vaultwarden 1.37.3 的主密码、邮件验证码、TOTP、多方式挑战及错误验证码重试；三条成功路径均校验解密后的密码库密钥。
