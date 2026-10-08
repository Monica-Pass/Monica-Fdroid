# Monica for Android (F-Droid) 1.0.318

> 发布日期 / Released: 2026-10-08

## 中文

- 扩展常用信息输入建议：银行卡持卡人、证件及账单姓名共享已保存姓名，姓、名和中间名分别匹配；账单和证件地址支持街道、门牌、省市、邮编及国家建议，兼容独立卡包、密码内嵌内容及旧字段。保留签发机关建议，点击仅填写当前字段，不自动拆分姓名或修改其他资料。

- 修复 KDBX 密码批量删除缓慢及条目重新出现：密码页与密码库页统一按数据库一次保存，成功后整体更新列表；原生索引在后台以事务提交，拒绝过期快照。保存失败保留列表数据，不再逐条重试；外部文件完整写入后若校验失败，保留当前文件与恢复副本，避免覆盖另一会话的写入。

- 改善安全存储启动容错：未分类的读取异常在后台最多尝试三次，减少短暂失败直接进入保护页的情况；持续失败、密钥缺失及认证错误仍保留原数据并阻止正常启动。诊断补充失败阶段、尝试次数及异常类型，不包含密码、密钥或异常原文。

- 卡包的银行卡和证件编辑统一使用 M3E 内容卡片：新增内容以紧凑卡片展示，支持长按拖动及菜单排序，顺序随条目保存；扩展字段、自定义字段和账单地址改用相连的填充式编辑，缩小间距并适配键盘与大字体。隐藏字段使用密码键盘。

- 自动填充新增“保持解锁两分钟”设置，默认关闭；开启后同一应用及网站可复用验证，支持账号与密码分步填写及手动选择，连续填充不延长有效期。关闭此项、锁屏、手动锁定、移除 Monica 最近任务或进程重启均撤销临时授权。修复主应用清理后台后仍可恢复旧解锁会话的问题，自动填充操作不再延长主应用解锁时间；普通切换应用仍遵循自动锁定设置。

- 新增常用信息输入建议：银行名称、分行代码、客服电话、凭据分组标签、网站网址及证件签发机关可匹配已保存的同类值，点击即可填入；支持项目内嵌银行卡和证件，最多显示三项，锁定或不可访问的数据源不参与建议。

- 修复导入条目中应用关联元数据被显示为普通自定义字段的问题；保留签名约束及再次导出能力，编辑或移除普通字段分组不会删除这些关联信息。

- 优化去重合并到 Monica 本地：无附件的密码与无附件、无照片的安全条目按小批次事务写入，减少完整列表反复查询；进度在提交后更新，失败批次回滚并逐条定位，取消时停止后续处理。保留附件、MDBX 与 Passkey 的原有写入和回滚流程，减少重复分析读取及无附件条目的空查询。

- 优化设置中的清空数据：按批次删除，减少逐条写库与列表刷新；显示当前阶段和实际已清空数量，完成或失败时保留结果提示，防止重复确认，并在页面重建后继续显示进度。保留原有类型选择及删除范围。

- Monica CLI 同样暂不接入 Glitter：不创建、打开、编辑、导出或同步该档位，仅保留只读格式识别；即使提供正确密码和密钥也不会放行，原有数据库不变。

验证：通用 Release 构建与 R8 启动检查通过；姓名和地址建议的 13 项单元测试、14 项设备测试，以及安全启动的 16 项设备测试通过。部分既有源码断言仍失败且已在原提交复现，范围与限制见[发布验证记录](https://github.com/Monica-Pass/Monica-Fdroid/blob/v1.0.318/docs/verification/release-318/README.md)。

F-Droid 将从正式标签自行构建、签名和分发，GitHub 发布后仍需等待其构建与索引队列。

## English

- Extend saved-value suggestions to cardholder, identity and billing names, with separate first/middle/last-name matching. Suggest street, unit, city, region, postal code and country across wallet items, embedded content and legacy fields. Keep issuing-authority suggestions; selection fills only the active field without splitting names or changing other details.

- Fix slow KDBX password bulk deletion and reappearing entries. Both password and vault pages save each database once and update the list after success. Apply native indexes in background transactions and reject stale snapshots. Preserve list data on save failure without per-entry retries; if verification fails after a complete external write, retain the current file and recovery copy instead of overwriting another session's changes.

- Improve secure-storage startup handling: retry unclassified read failures up to three times in the background before showing the recovery screen. Persistent failures, missing keys and authentication errors still preserve stored data and block normal startup. Diagnostics now include the failure phase, attempt count and exception types without passwords, keys or exception messages.

- Unify bank-card and identity-document editing with compact M3E content cards. Reorder added sections by long press or menu and save their order with the entry. Use connected filled editors for supplemental fields, custom fields and billing addresses, with tighter spacing and keyboard/large-text support. Hidden fields use password keyboards.

- Add an optional “Keep unlocked for two minutes” autofill setting, off by default. When enabled, verification is reused within the same app and website across separate username/password steps and the manual picker, without extending the window. Turning it off, screen-off, explicit locking, removing Monica from Recents or restarting the process revokes access. Fix old main-app sessions being restored after task removal; autofill interaction no longer extends the main-app unlock timer. Normal background switching still follows the auto-lock setting.

- Suggest saved values while typing bank names, branch codes, customer service phone numbers, credential group labels, website URLs and issuing authorities. Tap to fill from up to three matches, including embedded cards and identity documents; locked or inaccessible sources are excluded.

- Keep imported application-scope metadata out of ordinary custom-field displays while preserving signature constraints and re-export support. Editing or removing a user-field section retains this metadata.

- Speed up deduplication into Monica Local with small transactions for passwords without attachments and secure items without attachments or photos, reducing repeated full-list queries. Update progress after commit, roll back failed batches and retry entries individually, and stop subsequent work on cancellation. Preserve existing attachment, MDBX, and Passkey write and rollback paths while reducing redundant analysis reads and empty attachment queries.

- Speed up Clear data in Settings with batched deletion and fewer database writes and list refreshes. Show the current stage and committed entry count, retain completion or failure feedback, prevent duplicate confirmation, and preserve progress across activity recreation. Existing type selections and deletion boundaries remain unchanged.

- Monica CLI also defers Glitter integration: no creation, opening, editing, export or sync, even with the correct password and key. Read-only format detection remains available, and existing databases stay unchanged.

Validation: the universal Release build and R8 startup smoke check passed, along with 13 suggestion unit tests, 14 suggestion device tests and 16 secure-startup device tests. Some existing source-text assertions still fail and were reproduced on the baseline; see the [release verification record](https://github.com/Monica-Pass/Monica-Fdroid/blob/v1.0.318/docs/verification/release-318/README.md) for scope and limitations.

F-Droid builds, signs and distributes from the release tag; its build and index queues are separate from this GitHub source release.
