# Monica for Android (F-Droid) 1.0.318

> 未发布 / Unreleased

## 中文

- 自动填充新增“保持解锁两分钟”设置，默认关闭；开启后同一应用及网站可复用验证，支持账号与密码分步填写及手动选择，连续填充不延长有效期。关闭此项、锁屏、手动锁定、移除 Monica 最近任务或进程重启均撤销临时授权。修复主应用清理后台后仍可恢复旧解锁会话的问题，自动填充操作不再延长主应用解锁时间；普通切换应用仍遵循自动锁定设置。

- 新增常用信息输入建议：银行名称、分行代码、客服电话、凭据分组标签、网站网址及证件签发机关可匹配已保存的同类值，点击即可填入；支持项目内嵌银行卡和证件，最多显示三项，锁定或不可访问的数据源不参与建议。

- 修复导入条目中应用关联元数据被显示为普通自定义字段的问题；保留签名约束及再次导出能力，编辑或移除普通字段分组不会删除这些关联信息。

- 优化去重合并到 Monica 本地：无附件的密码与无附件、无照片的安全条目按小批次事务写入，减少完整列表反复查询；进度在提交后更新，失败批次回滚并逐条定位，取消时停止后续处理。保留附件、MDBX 与 Passkey 的原有写入和回滚流程，减少重复分析读取及无附件条目的空查询。

- 优化设置中的清空数据：按批次删除，减少逐条写库与列表刷新；显示当前阶段和实际已清空数量，完成或失败时保留结果提示，防止重复确认，并在页面重建后继续显示进度。保留原有类型选择及删除范围。

- Monica CLI 同样暂不接入 Glitter：不创建、打开、编辑、导出或同步该档位，仅保留只读格式识别；即使提供正确密码和密钥也不会放行，原有数据库不变。

## English

- Add an optional “Keep unlocked for two minutes” autofill setting, off by default. When enabled, verification is reused within the same app and website across separate username/password steps and the manual picker, without extending the window. Turning it off, screen-off, explicit locking, removing Monica from Recents or restarting the process revokes access. Fix old main-app sessions being restored after task removal; autofill interaction no longer extends the main-app unlock timer. Normal background switching still follows the auto-lock setting.

- Suggest saved values while typing bank names, branch codes, customer service phone numbers, credential group labels, website URLs and issuing authorities. Tap to fill from up to three matches, including embedded cards and identity documents; locked or inaccessible sources are excluded.

- Keep imported application-scope metadata out of ordinary custom-field displays while preserving signature constraints and re-export support. Editing or removing a user-field section retains this metadata.

- Speed up deduplication into Monica Local with small transactions for passwords without attachments and secure items without attachments or photos, reducing repeated full-list queries. Update progress after commit, roll back failed batches and retry entries individually, and stop subsequent work on cancellation. Preserve existing attachment, MDBX, and Passkey write and rollback paths while reducing redundant analysis reads and empty attachment queries.

- Speed up Clear data in Settings with batched deletion and fewer database writes and list refreshes. Show the current stage and committed entry count, retain completion or failure feedback, prevent duplicate confirmation, and preserve progress across activity recreation. Existing type selections and deletion boundaries remain unchanged.

- Monica CLI also defers Glitter integration: no creation, opening, editing, export or sync, even with the correct password and key. Read-only format detection remains available, and existing databases stay unchanged.
