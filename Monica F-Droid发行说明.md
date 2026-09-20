# Monica for Android (F-Droid) 1.0.314

## 中文

### 简要

- 新增 GPG 密钥与生成器操作菜单。
- 新增意大利语及 Liberapay 支持入口。
- 优化新建页面、验证器布局和密码库预览性能。
- 完善二维码编辑、SSH 数据保存及数据库、卡包的显示与返回体验。

### 详细

- **GPG 密钥**：在新建页和生成器中生成或导入密钥，加密保存到密码库；支持导出公私钥、复制指纹和设置私钥口令。
- **生成器菜单**：点击随机密码、单词、短语或 PIN 的结果卡片，可复制或用于新建用户名、密码；SSH 结果可分别复制公钥、指纹和私钥，GPG 结果提供复制、导出与创建条目操作。
- **语言与支持**：新增完整意大利语，中文界面中显示为“超级马里奥语”；Monica Plus 支付页和支持作者页新增 Liberapay 欧元（EUR）支持入口。
- **验证器与卡片间距**：列表卡片边缘间距统一为 8dp。磁贴保留 313 的紧凑外观和等高外框，无账号不留空行；当前验证码完整显示，空间紧张时缩小或隐藏下一组码，正常列表保留 Next。收紧密码分组及组内留白，保留收藏、封面按钮原有尺寸（#139）。
- **新建体验**：密码、银行卡、证件和笔记页面复用安全组件并精简重复过渡，减少打开时的停顿。生成器打开的编辑页使用独立窗口，保存按钮始终可达。
- **二维码编辑**：二维码表单不再显示“密码登录／第三方登录”，避免误切换为密码条目；扫描、内容输入、保存和顶部类型菜单保持原流程。
- **返回与显示**：修复 MDBX 详情返回列表时短暂显示空数据库的情况，以及卡包堆叠详情返回收起后位置偏移的问题。
- **SSH 数据完整性**：保留 MDBX / Rust MDBX2 导入、编辑和完整导出中的密钥材料、扩展字段及私钥换行，完善 SSH / GPG 跨端格式约定。
- **KeePass 文件夹**：修复通行密钥引起的编码文件夹名重复显示，同一路径统一名称与计数。
- **密码库预览**：密码条目就绪后先显示概览，其他类型在后台解析并补全统计与推荐；大规模概览使用 Rust 批量聚合，小规模沿用 Kotlin，减少 JNI 开销。
- **长文案排版**：KeePass WebDAV 浏览器和云备份按钮的文字换行后保持居中（#140）。

本版继续采用 F-Droid 的无 Google Play 服务构建；Monica 自有 Passkey 和文件导入导出保持可用。

## English

### Summary

- Add GPG keys and generator action menus.
- Add Italian and a Liberapay support option.
- Improve entry creation, authenticator layouts and vault overview performance.
- Fix QR editing, SSH data preservation, and database and card-wallet navigation.

### Details

- **GPG keys:** Generate or import keys from the entry editor and generator, store them encrypted in the vault, export public/private keys, copy fingerprints, and optionally protect private keys with a passphrase.
- **Generator menus:** Tap password, word, passphrase or PIN results to copy them or create username/password entries. SSH results offer separate public-key, fingerprint and private-key actions; GPG results support copying, exporting and creating entries.
- **Language and support:** Add complete Italian localization, playfully named “Super Mario language” in the Chinese interface, and a Liberapay option in euros (EUR) on the Monica Plus payment and Support Author pages.
- **Authenticator and card spacing:** Use an actual 8dp gap between list cards. Tiles retain the compact 313 appearance and equal outer heights without empty account rows. Current codes remain complete; crowded tiles shrink or hide the next-code preview, while regular lists retain Next. Reduce grouped-password padding while preserving favorite and cover button sizes (#139).
- **Entry creation:** Reuse security components and remove duplicate transitions when opening password, bank-card, document and note editors. Editors opened from the generator use separate windows so Save remains accessible.
- **QR editing:** Remove password/third-party login controls from the QR form to prevent accidental conversion into a password entry. Scanning, content editing, saving and the top type menu retain their existing flow.
- **Navigation and display:** Prevent a false empty-database state when leaving MDBX details, and keep card stacks in their original position after returning from details and collapsing them.
- **SSH data preservation:** Retain key material, extension fields and private-key line endings across MDBX / Rust MDBX2 imports, edits and full exports, with documented SSH / GPG interchange formats.
- **KeePass folders:** Correct duplicate encoded folder names introduced by passkeys, using consistent names and counts for the same path.
- **Vault overview:** Display password entries as soon as they are ready, then fill in other types, counts and recommendations in the background. Use Rust batch aggregation for large overviews and Kotlin for smaller ones to avoid JNI overhead.
- **Long labels:** Keep wrapped button labels centered in the KeePass WebDAV browser and cloud backup pages (#140).

The F-Droid edition remains free of Google Play Services dependencies. Monica passkeys and file import/export remain available.
