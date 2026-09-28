# Monica for Android (F-Droid) 1.0.315

> 未发布。原实验内容已接入 main，构建版本已切换为 1.0.315，接入后的构建与回归验证已通过，版本尚未发布。接下来的新增功能、优化与修复均记录在本版本。

## 中文

### 简要

- 新增可选“按需添加”密码编辑样式，统一已有字段与详情信息卡的展示。
- 密码详情显示支付卡面，并复用验证器页面的验证码卡片。
- 优化大字体、窄屏和自定义字段操作；“添加附件”移至卡片底部右侧。

### 详细

本版已接入的实验内容与后续修复：

- **设置子页面：** 重新设计页面调整自定义与权限管理，改用外侧大圆角、组内小圆角的连续分组。自定义按密码库、卡片和字段图标归类，保留开关、导航与搜索定位；权限用途、状态和操作分层展示，支持大字体完整换行，按需开启权限，从系统设置返回时自动刷新。

- **旧版 Android 自动填充：** 修复 Android 10 等旧版系统在自动填充请求的协程恢复时引用新版内联候选类型、导致服务崩溃的问题。补充候选显示期间的连续输入、组合输入、字段切换及取消验证后的焦点测试。用户反馈的偶发输入阻塞尚不能确定为同一原因。

- **本地数据与验证器恢复：** 覆盖恢复改为解析后再事务写入，写入失败或取消时回滚本地条目及关联字段；不再按同一账号误跳过不同密钥、参数或恢复资料的验证器，重新映射密码绑定时保留未知扩展字段。损坏单条验证器不再中断列表，解密失败的密文不再当作普通密钥；解锁后重新解析。后台敏感字段迁移使用条件更新，避免覆盖并发编辑的新值。

- **多语言补齐：** 补齐 11 种语言共 490 条新增编辑样式、字段、密码库排序、设置搜索及 MDBX 冲突/未知类型提示文案，保留原有译文与彩蛋语言继承规则。

- **详情字段条形码：** 复用独立条形码项目的展示组件，码图保持矩形并留出完整白边，修复条纹被圆角裁切的问题。按实际可用宽度生成 Code 128，内容过长时提示切换二维码；保留格式切换和页面状态恢复。

- **Monica 键盘菜单：** 将按住菜单时的反馈限制在圆形按钮内，去除矩形白色包边，保留完整点击范围。自动填充入口由三个点改为“箭头进入输入框”图标，与密码、验证器等入口区分；清空后的撤销功能保留。

- **时间线稳定性（#144）：** 修复法语界面存在较早历史记录时，打开时间线因日期格式翻译错误而闪退的问题；保留正确的日/月显示，并补充全部语言日期格式及历史日期分组回归测试。

- **按需编辑与已有字段：** 默认保留经典编辑，可在页面调整中切换按需添加。密码、卡包、笔记和证件沿用已有模型与存储格式，保留敏感复制、显隐、大字、二维码 / Code 128、分享和 Send。密码编辑沿用 main 已有的 TOTP、HOTP、Steam、Yandex、mOTP 支持，并覆盖按需添加、保存、重新编辑与凭据切换。
- **统一详情卡片：** 内置与自定义信息使用统一平面卡片和字段布局，减少重复边距、阴影和卡片嵌套。大字体与窄屏下操作独立成行，秘密内容变更或切换条目后重新隐藏。
- **密码中的支付卡面：** 将密码已有的单组支付信息显示为银行卡面；展开查看卡号、持卡人、有效期和 CVV，敏感值默认隐藏，大字体下保留卡号尾号。暂不增加多卡绑定和卡包自定义图片关联。
- **密码中的验证器：** 复用验证器页面的验证码卡片、类型格式、隐藏设置与时间偏移；缺少关联验证器时读取密码自身保存的加密 OTP。HOTP 显示已保存的计数器，不再误用即将到期的红色，本轮不增加计数器写入。
- **附件布局：** 经典与按需编辑中的“添加附件”独占卡片底部右侧，标题和文件列表使用完整宽度，修复英文大字体下标题被挤成竖排的问题。
- **验证器已有资料：** 改进发行者、账号和备注的展示，保留 Steam 元数据。验证器新增自定义字段仍暂缓，不列为本版已实现功能。

## English

### Summary

- Add an optional on-demand password editor and unify existing detail fields.
- Show payment card artwork and authenticator cards in password details.
- Improve large-text and narrow-screen layouts, with Add attachment at the bottom-right of its card.

### Details

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
