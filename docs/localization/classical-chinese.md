# 文言文（华夏） / Classical Chinese (Huaxia)

2026-09-15 更新：文言文现覆盖全部 5,997 个可翻译资源，与默认资源键一致。下文初次交付的数量与验收记录保留作历史记录；最新公共文案调整见 [运行时文案说明](runtime-text-audit.md)。

语言代码采用 ISO 639-3 / BCP 47 的 `lzh`，Android 资源目录为 `values-b+lzh`。
三字母语言代码使用 Android 的 BCP 47 限定符，不借用繁体中文或地区代码。
语言枚举为 `CLASSICAL_CHINESE`；沿用现有 DataStore 与启动语言缓存，以枚举名称持久化。
设置和首次引导使用同一语言名称。该名称是固定的双语自称，不需要改动既有语言包。

自动填充、Passkey 与键盘解锁等独立 Activity 也通过现有 LocaleHelper 读取保存的语言。
键盘使用独立进程；StartupLanguageCache 以仅限本应用接收的语言变更通知更新该进程的显示语言，
使已打开的键盘随选择即时刷新。通知只携带语言枚举，DataStore 格式及其他设置的读取方式保持不变。

资源按默认 `values` 的文件拆分，包含全部可翻译的 `string`、`plurals` 和数组项。
简中缺少的条目以默认资源为准。首次新增文言文时未改其他语言；后续公共文案清理见 [运行时文案说明](runtime-text-audit.md)。
应用资源优先取 `lzh`，LocaleList 配置为 `lzh, zh-CN, en`；未提供译文时沿用 Android 资源回退。
平台资源的具体选择由 Android 决定：实测 API 32 的系统按钮使用英文默认值，不能保证逐条先取简中。
系统权限窗口、用户自写内容、服务器原始错误及第三方返回文本不属于此语言包。

## 文风与术语

用简体字书写文言，按完整语义译写，不作繁简转换或逐词替换。
短按钮从简；提示宜用“请入……”“……不可为空”“果欲……乎”“……乃可……”等明白句式。
保留必要技术术语，以免损失功能含义；不为仿古而造难解之词。

| 含义 | 译法 |
| --- | --- |
| 密码 | 密钥 |
| 主密码 | 总钥 |
| 密码库 / 保管库 | 密府 |
| 数据库 | 库（须区分时用数据库） |
| 用户名 | 名 |
| 账号 | 名籍 |
| 凭据 | 信物 |
| 通行密钥 | 通行之钥 |
| 自动填充 | 自填 |
| 密码生成器 | 造钥之器 |
| 密码强度 | 密钥之坚 |
| 安全检查 | 安危之察 |
| 生物识别 | 生体之辨 |
| 指纹 | 指印 |
| 笔记 | 简牍 |
| 文件夹 | 卷夹 |
| 字段 | 栏 |
| 锁定 / 解锁 | 封 / 启封 |
| 设置 / 搜索 / 保存 / 删除 | 设 / 寻 / 存 / 删 |
| 取消 / 确定 / 编辑 / 添加 | 罢 / 然 / 修 / 增 |
| 返回 / 关闭 / 复制 / 粘贴 | 返 / 闭 / 摹 / 贴 |
| 导入 / 导出 / 重命名 | 纳 / 出 / 更名 |

Monica、KeePass、Bitwarden、Steam 等名称，以及 URL、域名、扩展名、协议、算法、
API 标识（包括技术语境中的 Passkey）、占位符和数值均保持正确，不作仿古改写。
新功能应同时补齐此目录，并运行文言文资源覆盖与 Android locale 回归测试。

## 初次交付时的覆盖范围与边界

共覆盖 **5,383 个可翻译资源、5,384 段文案**：原有 5,124 个资源，加上从 Kotlin 界面及提示中提取的 259 个资源。其间 5,382 个为 `string`，另有 1 组包含 `one` / `other` 的 `plurals`。固定双语语言自称另计，不纳入翻译总数。简体中文缺少的 152 个原有资源，已依默认文本补译。

| 模块 | 资源数 | 文案数 |
| --- | ---: | ---: |
| `attachment_errors.xml` | 6 | 6 |
| `autofill_protection_strings.xml` | 56 | 56 |
| `bitwarden_errors.xml` | 11 | 11 |
| `dedup_merge_strings.xml` | 101 | 101 |
| `legacy_ui_strings.xml` | 259 | 259 |
| `mdbx_manager_strings.xml` | 514 | 514 |
| `page_adjustment_strings.xml` | 5 | 5 |
| `strings.xml` | 4,338 | 4,339 |
| `strings_api_token.xml` | 35 | 35 |
| `vault_overview_actions_strings.xml` | 1 | 1 |
| `vault_overview_picker_strings.xml` | 7 | 7 |
| `vault_overview_strings.xml` | 46 | 46 |
| `wallet_selection_strings.xml` | 4 | 4 |

`legacy_ui_strings.xml` 收录原先直接写在 Kotlin 中的可见文案。2026-09-15 的清理将默认值统一为英文，中文原文保留在 `values-zh`；详见 [运行时文案说明](runtime-text-audit.md)。适用处复用已有通用资源；其余补入文言文，包括 Bitwarden 登录与两步验证、同步队列、备份报告、全屏编辑、卡片操作、批量传送、文件夹状态、证书、指纹、Passkey 服务与通知验码提示。提取仅涉及显示文案、参数格式化和资源读取，不改变认证、同步、存储、操作分支或布局。

原始协议名称、API 字段名、算法、URL、域名、扩展名、数字、格式参数及品牌均保持正确。用户内容、原始技术诊断、历史记录中已保存的字段名和第三方返回的错误详情保留原文。它们不是未翻译的 UI 文案，也不以仿古措辞改写技术数据。复合提示的外围说明仍使用文言文。系统界面或依赖资源无 `lzh` 版本时，由 LocaleList 回退。提示中引用的系统设置路径保留系统原名，以便查找。

现有其他语言文件未因本次工作改动。法文覆盖测试另记录固定的 259 项旧硬编码文案基线，因为这些文案此前在法文界面中同样没有译文；原有模块继续严格检查，任何基线以外的新缺项仍会失败。该基线只用于记录既有翻译欠项，不以文件名或前缀豁免未来新增资源。

## 变更文件

以下按本任务开始时的工作区基线列出，排除先前已存在的循环卡叠等改动。共 97 个 `app/src` 文件，及本文档。

### 语言包与默认名称

- `app/src/main/res/values-b+lzh/attachment_errors.xml`
- `app/src/main/res/values-b+lzh/autofill_protection_strings.xml`
- `app/src/main/res/values-b+lzh/bitwarden_errors.xml`
- `app/src/main/res/values-b+lzh/dedup_merge_strings.xml`
- `app/src/main/res/values-b+lzh/legacy_ui_strings.xml`
- `app/src/main/res/values-b+lzh/mdbx_manager_strings.xml`
- `app/src/main/res/values-b+lzh/page_adjustment_strings.xml`
- `app/src/main/res/values-b+lzh/strings.xml`
- `app/src/main/res/values-b+lzh/strings_api_token.xml`
- `app/src/main/res/values-b+lzh/vault_overview_actions_strings.xml`
- `app/src/main/res/values-b+lzh/vault_overview_picker_strings.xml`
- `app/src/main/res/values-b+lzh/vault_overview_strings.xml`
- `app/src/main/res/values-b+lzh/wallet_selection_strings.xml`
- `app/src/main/res/values/classical_chinese_language.xml`
- `app/src/main/res/values/legacy_ui_strings.xml`

### 语言注册、界面与提示接线

- `app/src/main/java/takagi/ru/monica/MainActivity.kt`
- `app/src/main/java/takagi/ru/monica/attachments/ui/AttachmentsEditSection.kt`
- `app/src/main/java/takagi/ru/monica/autofill_ng/AutofillAuthenticationActivity.kt`
- `app/src/main/java/takagi/ru/monica/autofill_ng/AutofillCipherCallbackActivity.kt`
- `app/src/main/java/takagi/ru/monica/autofill_ng/AutofillPickerActivity.kt`
- `app/src/main/java/takagi/ru/monica/autofill_ng/AutofillSaveActivity.kt`
- `app/src/main/java/takagi/ru/monica/autofill_ng/AutofillSaveTransparentActivity.kt`
- `app/src/main/java/takagi/ru/monica/autofill_ng/AutofillUnlockActivity.kt`
- `app/src/main/java/takagi/ru/monica/autofill_ng/BiometricAuthActivity.kt`
- `app/src/main/java/takagi/ru/monica/autofill_ng/MonicaAutofillServiceNg.kt`
- `app/src/main/java/takagi/ru/monica/autofill_ng/PasswordSuggestionActivity.kt`
- `app/src/main/java/takagi/ru/monica/autofill_ng/builder/AutofillDatasetBuilder.kt`
- `app/src/main/java/takagi/ru/monica/autofill_ng/builder/FillResponseBuilderNg.kt`
- `app/src/main/java/takagi/ru/monica/autofill_ng/service/AutofillOtpNotificationService.kt`
- `app/src/main/java/takagi/ru/monica/bitwarden/ui/BitwardenLoginScreen.kt`
- `app/src/main/java/takagi/ru/monica/bitwarden/ui/BitwardenSettingsScreen.kt`
- `app/src/main/java/takagi/ru/monica/bitwarden/ui/SyncQueueScreen.kt`
- `app/src/main/java/takagi/ru/monica/bitwarden/viewmodel/BitwardenViewModel.kt`
- `app/src/main/java/takagi/ru/monica/data/AppSettings.kt`
- `app/src/main/java/takagi/ru/monica/data/BackupReport.kt`
- `app/src/main/java/takagi/ru/monica/ime/ImeBiometricAuthActivity.kt`
- `app/src/main/java/takagi/ru/monica/ime/ImeUnlockActivity.kt`
- `app/src/main/java/takagi/ru/monica/ime/MonicaInputMethodService.kt`
- `app/src/main/java/takagi/ru/monica/passkey/MonicaCredentialProviderService.kt`
- `app/src/main/java/takagi/ru/monica/passkey/PasskeyAuthActivity.kt`
- `app/src/main/java/takagi/ru/monica/passkey/PasskeyCreateActivity.kt`
- `app/src/main/java/takagi/ru/monica/passkey/PasskeySettingsActivity.kt`
- `app/src/main/java/takagi/ru/monica/service/NotificationValidatorService.kt`
- `app/src/main/java/takagi/ru/monica/ui/SimpleMainScreen.kt`
- `app/src/main/java/takagi/ru/monica/ui/category/CategoryManagementState.kt`
- `app/src/main/java/takagi/ru/monica/ui/components/MarkdownPreviewText.kt`
- `app/src/main/java/takagi/ru/monica/ui/components/QrCodeDialog.kt`
- `app/src/main/java/takagi/ru/monica/ui/components/QuickStatusDeleteBar.kt`
- `app/src/main/java/takagi/ru/monica/ui/components/QuickStatusTransferBar.kt`
- `app/src/main/java/takagi/ru/monica/ui/components/TotpCodeCard.kt`
- `app/src/main/java/takagi/ru/monica/ui/components/UnifiedCategoryFilterChipMenu.kt`
- `app/src/main/java/takagi/ru/monica/ui/components/UnifiedMoveToCategoryBottomSheet.kt`
- `app/src/main/java/takagi/ru/monica/ui/passkey/PasskeyDetailPanes.kt`
- `app/src/main/java/takagi/ru/monica/ui/password/BitwardenSyncSnapshotSection.kt`
- `app/src/main/java/takagi/ru/monica/ui/password/MultiPasswordEntryCard.kt`
- `app/src/main/java/takagi/ru/monica/ui/password/PasswordBatchCategoryTransferSupport.kt`
- `app/src/main/java/takagi/ru/monica/ui/password/PasswordEntryCard.kt`
- `app/src/main/java/takagi/ru/monica/ui/password/PasswordListQuickStatusDialogs.kt`
- `app/src/main/java/takagi/ru/monica/ui/password/PasswordListTopSection.kt`
- `app/src/main/java/takagi/ru/monica/ui/password/PasswordQuickFolderSections.kt`
- `app/src/main/java/takagi/ru/monica/ui/password/PasswordQuickFolderSupport.kt`
- `app/src/main/java/takagi/ru/monica/ui/password/PasswordTopActionsMenu.kt`
- `app/src/main/java/takagi/ru/monica/ui/screens/AddEditNoteScreen.kt`
- `app/src/main/java/takagi/ru/monica/ui/screens/AddEditPasswordScreen.kt`
- `app/src/main/java/takagi/ru/monica/ui/screens/CardWalletScreen.kt`
- `app/src/main/java/takagi/ru/monica/ui/screens/GeneratorScreen.kt`
- `app/src/main/java/takagi/ru/monica/ui/screens/LocalKeePassGoogleDriveBrowser.kt`
- `app/src/main/java/takagi/ru/monica/ui/screens/MdbxManagerScreen.kt`
- `app/src/main/java/takagi/ru/monica/ui/screens/NoteListScreen.kt`
- `app/src/main/java/takagi/ru/monica/ui/screens/OneDriveBackupScreen.kt`
- `app/src/main/java/takagi/ru/monica/ui/screens/PasskeyDetailScreen.kt`
- `app/src/main/java/takagi/ru/monica/ui/screens/PasskeyListScreen.kt`
- `app/src/main/java/takagi/ru/monica/ui/screens/QuickSetupScreen.kt`
- `app/src/main/java/takagi/ru/monica/ui/screens/SendScreen.kt`
- `app/src/main/java/takagi/ru/monica/ui/screens/SettingsScreen.kt`
- `app/src/main/java/takagi/ru/monica/ui/screens/WebDavBackupScreen.kt`
- `app/src/main/java/takagi/ru/monica/ui/screens/WebDavCertificateDialog.kt`
- `app/src/main/java/takagi/ru/monica/ui/vaultv2/VaultV2Pane.kt`
- `app/src/main/java/takagi/ru/monica/utils/AppLocaleStringResolver.kt`
- `app/src/main/java/takagi/ru/monica/utils/BackupRestoreApplier.kt`
- `app/src/main/java/takagi/ru/monica/utils/BiometricAuthHelper.kt`
- `app/src/main/java/takagi/ru/monica/utils/BiometricHelper.kt`
- `app/src/main/java/takagi/ru/monica/utils/LocaleHelper.kt`
- `app/src/main/java/takagi/ru/monica/utils/StartupLanguageCache.kt`
- `app/src/main/java/takagi/ru/monica/utils/VivoFingerprintHelper.kt`
- `app/src/main/java/takagi/ru/monica/viewmodel/DataExportImportViewModel.kt`
- `app/src/main/java/takagi/ru/monica/workers/AutoBackupWorker.kt`

### 回归检查

- `app/src/androidTest/java/takagi/ru/monica/localization/ClassicalChineseEntryPointsInstrumentedTest.kt`
- `app/src/androidTest/java/takagi/ru/monica/localization/ClassicalChineseImeInstrumentedTest.kt`
- `app/src/androidTest/java/takagi/ru/monica/localization/ClassicalChineseLocaleInstrumentedTest.kt`
- `app/src/androidTest/java/takagi/ru/monica/localization/ClassicalChineseUiInstrumentedTest.kt`
- `app/src/test/java/takagi/ru/monica/localization/ClassicalChineseResourceCoverageTest.kt`
- `app/src/test/java/takagi/ru/monica/localization/FrenchResourceCoverageTest.kt`
- `app/src/test/java/takagi/ru/monica/localization/XmlTestStrings.kt`
- `app/src/test/java/takagi/ru/monica/utils/StartupLanguageCacheTest.kt`
- `app/src/test/java/takagi/ru/monica/viewmodel/MultiPasswordSaveRegressionGuardTest.kt`
- `app/src/test/resources/localization/french_legacy_ui_gaps.txt`

## 验证记录

验证日期：2026-09-14。最终生产源码的 Debug 应用、Android 测试 APK 及所选 JVM 检查在同一次 Gradle 构建中通过，结果为 `BUILD SUCCESSFUL`。最终构建正常执行 BuildConfig、Kotlin 编译及打包任务；没有跳过生产编译。版本为 `1.0.311-26091412-02`，设备运行使用 API 32 / x86_64 模拟器。

| 检查 | 结果 |
| --- | --- |
| `:app:assembleDebug` | 通过，包含 arm64-v8a、armeabi-v7a、x86_64 APK |
| `:app:assembleDebugAndroidTest` | 通过 |
| 文言文资源覆盖、格式与术语 JVM 检查 | 3 / 3 通过 |
| 法文既有覆盖与旧文案缺项基线 | 1 / 1 通过 |
| 启动语言解析 / 缓存 JVM 检查 | 2 / 2 通过 |
| 本次文案资源化涉及的 MDBX / 批量操作守卫 | 3 / 3 通过 |
| 密码建议界面守卫 | 3 / 3 通过 |
| Android 资源、保存语言、fallback、缓存与自动填充展示 | 6 / 6 通过 |
| 设置选择、首次引导及界面随语言刷新 | 3 / 3 通过 |
| 实际安装的键盘独立启动及打开时切换语言 | 1 / 1 通过 |
| 自动填充密码建议及系统 Passkey 设置独立入口 | 2 / 2 通过 |
| 既有 MDBX 本地化设备回归 | 2 / 2 通过 |

最终所选检查合计 **12 项 JVM 测试、14 项 Android 测试通过**。设备测试恢复原有语言与输入法设置。语言选择、首次引导和键盘截图已拉取检查；语言名称可完整显示。键盘工具栏使用图标，其文言文无障碍标签以及文言文 / 英文即时切换由实际输入法窗口测试验证。

资源审计确认 5,383 个资源项全部覆盖，资源类型、复数项、格式参数、数值、显式换行及空白与默认资源一致；技术标识、URL 和扩展名检查通过。相对任务开始时的工作区基线，既有其他语言资源没有改动，先前的循环卡叠和发行说明改动保留。`git diff --check` 通过。

首次扩展 JVM 回归共运行 71 项。其中 3 项失败来自匹配旧的硬编码文案，已改为检查资源引用并在最终定向回归通过。另外 6 项既有失败涉及下列守卫；它们失败断言读取的生产源码与任务开始基线的 SHA-256 完全一致，本次未改动相应业务以迎合断言：

- `editingPasswordWithAuthenticatorReusesBoundTotpAndDoesNotClearPasswordWhenDeletingDuplicates`
- `editingPasswordReplicasPreservesExistingTargets`
- `mdbxManagerLivesUnderDatabaseBackupAndUsesStandalonePages`
- `keepassRemoteWritesStayVisibleToOtherClients`
- `mdbxHistoryAndSnapshotViewsUseStaleWhileRevalidateCache`
- `webDavMonicaConfigBackupIncludesSecurityAutofillAndBlacklistSettings`

因此上述“通过”仅指本次列出的构建与定向检查，不代表全仓测试全部通过。系统资源的语言仍受 Android 自身支持范围约束，API 32 的部分系统按钮会使用英文；应用自有文案已完整提供 `lzh` 资源。
