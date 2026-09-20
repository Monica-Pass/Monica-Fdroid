> 已撤回（2026-09-20）：按用户要求恢复原有大屏布局。本文与 Canvas 仅保留为历史设计记录，不代表当前实现。

# Monica 大屏适配

[打开可编辑 M3E Canvas 草图](https://lnkiai.github.io/m3e-canvas/#docz=zZptT9NaHMC_StPXmNtzuif3zvtkbm5Mbu59eeOLwjppLOvSFcVrSEAzGGFjRkRRhoJRICpDLsTBAE3uR8GddnvlV7inO23XTtfTsKaRF2IP_be_8z8P_f-63WU1SZNFNs1eU3LSmMD812DQq220X0WN162zGio32RF2VJXELD5HX1jUa83Wp3W9PIuWXugbjS-na-j4QF__1Pq4rW9U0d5pu_Tm88yssfjOeLuob8x3Xq62jpbwxVCxgU_Af0KlJ-3Nnfbeln6_iEpzVuN-sXW0i9tRZeXzzD18z6wqTJhcGbFwU1PyuCUvC1pWUSdwo5DLqIqUMRsFWdQ08XfxDm7OT6p53JkRVhsXzeC7bEZQb7LprCAXRNwNRRu_puALsmlNncQNY0pOU4WChkMLGr6moJqXLIwLefPOqjKZy4hmSxafZ55zp6CJE_h4QtEkJYdbxKm8KhYK0i2RnbaQ8cX_vstitjSb5fC5OdINtDdnbMyi5nI3w90U4LyQLOCzptg0N8Le6f47esO816SaFcbMvuQUrXuB-hlqbrWOKu0PB0wqxmXyDD5Ap01U-oCqZZxGPBb6XrV19IZkVS8voPrzztMiidSXHqKj-19Oy9Yg4tjqKkn1bTYNYArfeZxNpzhuesTmBz1-49ELvfQAPd0x-TvFinFWx_zGybL-vEb4AZ_4DrsAe11o1z8Sav3xe3TS7HZktdjeOyFXJ72ASfj99OL6CHsDz8K8a05xl0ZVPFMtWILK41_ClITPwkcjrIQn6Tcjbko5s0ETpzR8JAujouwsfHPeS__g_oHUCHtLUCWhO-Wzkix3l8CoImfsZSONmZM_NynL09edTHOXcsItzpoKiS4X4ChgVoTFNTqpafjCPTJnzThwZm4GwlmrfDAd8NIlE1Q64EPXeVNu783iFRESHfTQwTik0kG_3FU2UbkYEhrvQeNhiorG-6AZ7x616-9DQot50GJcjIoW8xvTmWftzTKed52Hr0MCjHsBU_QlEffLnb0Nh0SX8NDF4_QlkfChIztsGGikKiFssWC7nB3y7V3u670EXnij0xRFLnjhUpQlYYdYcLJU0H4jtYQzsrtrnZkF5vzxR8b8MZ8SzSpzPrdCDtcO0atnzPliL7v8EOktiII6Nu7tAohTEuwEDe6E_qBmHL7U1zeNtfr5zFYorKpym_OSQtpUsEIGc5Litn24haqNsCBB33QFlK3IChkMiQuIVrMZLiTsg7xMmbdWiE8mi_ut5lsy4qFAZkRNkGSCGU_GyNQElAF3ggYs_m8N98XXP7kb18d4ORCj_7w0Hu3opQZ6UHEo40OnEng5YYqyx_eifEa9u5syPzB9j8rhaaGXlk9RyqBelA_tq3n9YCcUyJx4mwB2pzgGTNCGnUQMemJ-OV1kiI70CvDYRfGA2w2ATei_WYJo7AC47YBPBvIDEKEfALcfOHz-hgAiNATgNgSbj-IIICpHAG5HsOEolgCisgTgtgQbjuIJIFpPAG5PcBD9TQFEaArAbQo2H8UVQFSuADyuEOeCbXr-svB17i5aLACPLNh0_rYAAtgCWppD1X_xAkFnK8z5k0b4kgC8kmCjUywBBLEEkl5j_wQ9XzTfwl3946pVSJj6Ewq7Sxpscoo1gADWsL2MS7PW0VLnXt2o74cFCvrnrr85ALo5GPUn-sGKmdo__7rC8FwShsUK-1n9BQLQBUKvLegrJb32wsQF6PgwFFS3RYDLfCCNADSNsOat_c572J3BoxE9SP-CEgTyCL08bzSPzYxe-RFA5qef-Rjzy6_xRAg1MPBqhcNN8QoQzCuK7_AuYH6wsD2L_6MvV7C3eTa04cFhHzhFMUAgxWh9Wke7q6j0_uuadBhiRzdAMhbINwDFN8gUDsE1oOdziGSgd3QwGteALteAST6Qa8AIXQO6XKPH5-8aMELXgC7XcPgorgGjcg3ocg0HjuIaMCrXgC7XcOAorgGjdQ3oco0eor9rwAhdA7pcw-GjuAaMyjWg2zXwAy_YpufvGn2fSg9dV8BL46Jg7cs8x3NBKh8nxqei6NKR97yM7w95FDLntd5WmRgi4-Y3Pri-7kBI6Y4d5FNp4Kf1vZ3WmflFBrNO6nv2DEsM-oj5IMT-tRH5Mk1ohLCPMBaEkPKedX5Xr9RDI-T7CONBCHl_wuKH1snjzsxCZ_N4CM7r0_8D)

保持手机布局；大屏使用独立标题、工具区、搜索行。双栏至少需要 840dp；表单限制阅读宽度。

## 实现范围

- 共享 ExpressiveTopBar：密码库/概览、验证器、卡包、笔记、通行密钥、Send、时间线。窗口至少 600dp 时使用标题和独立操作区；分栏不足 640dp 或字体放大时纵向排列，搜索不覆盖标题。手机保留原实现。
- 主工作区：至少 840dp 才显示列表/详情双栏；列表宽度随窗口变化，限制在 320–480dp；保留现有导航侧栏和条目打开行为。
- 生成器：可用宽度至少 760dp（按字体缩放折算）时，类型和参数在左、真实结果卡片在右。两栏独立滚动，复制和 GPG 操作菜单继续使用原组件；小屏保持类型、结果、参数顺序。
- 设置：移除空白的详情占位栏；表单和设置内容上限 840dp，MDBX 管理页上限 960dp。数据库原有内部 12dp 留白与操作组保持不变。
- 详情分栏使用带间隔的圆角容器；新建、编辑、详情与设置子页通过 AdaptivePageScaffold 共用阅读宽度，窄分栏不强制最小宽度。
- 不调整版本号，不发布标签；不改动 CLI。

表单/设置/详情接入文件：

- AddEditApiTokenScreen.kt
- AddEditBankCardScreen.kt
- AddEditBillingAddressScreen.kt
- AddEditDocumentScreen.kt
- AddEditNoteScreen.kt
- AddEditPasswordScreen.kt
- AddEditSshKeyScreen.kt
- AddEditTotpScreen.kt
- AddEditWifiScreen.kt
- ApiTokenDetailScreen.kt
- AutofillBlockedFieldsScreen.kt
- AutofillProtectionScreen.kt
- AutofillSaveBlockedTargetsScreen.kt
- AutofillSettingsScreen.kt
- AutofillSettingsV2Screen.kt
- BankCardDetailScreen.kt
- BarcodeDetailScreen.kt
- BillingAddressDetailScreen.kt
- ChangePasswordScreen.kt
- ColorSchemeSelectionScreen.kt
- CommonAccountTemplatesScreen.kt
- CustomColorSettingsScreen.kt
- DedupEngineScreen.kt
- DeveloperSettingsScreen.kt
- DocumentDetailScreen.kt
- ExportDataScreen.kt
- ExtensionsScreen.kt
- ForgotPasswordScreen.kt
- GpgKeyScreen.kt
- ImportDataScreen.kt
- KeePassNativeDatabaseSettingsScreen.kt
- KeePassNativeDatabaseToolsScreen.kt
- KeePassNativeEntryDetailScreen.kt
- KeePassNativeEntryEditorScreen.kt
- LocalKeePassScreen.kt
- MasterPasswordLockingSettingsScreen.kt
- MdbxLocalCreateScreen.kt
- MdbxLocalOpenScreen.kt
- MdbxManagerScreen.kt
- MdbxOneDriveCreateScreen.kt
- MdbxOneDriveOpenScreen.kt
- MdbxWebDavCreateScreen.kt
- MdbxWebDavOpenScreen.kt
- MonicaPlusScreen.kt
- NativeApiTokensScreen.kt
- NoteDetailScreen.kt
- PageAdjustmentCustomizationScreen.kt
- PasskeyDetailScreen.kt
- PasskeySettingsScreen.kt
- PasswordDetailScreen.kt
- PasswordFieldCustomizationScreen.kt
- PaymentScreen.kt
- PermissionManagementScreen.kt
- ResetPasswordScreen.kt
- SecurityAnalysisScreen.kt
- SecurityQuestionsSetupScreen.kt
- SecurityQuestionsVerificationScreen.kt
- SettingsScreen.kt
- SshKeyDetailScreen.kt
- SupportAuthorScreen.kt
- SyncBackupScreen.kt
- WifiDetailScreen.kt

## 设备验证（2026-09-20）

使用公共 Android 32 x86_64 AVD `Monica_Issue136_API_32`，未新建 AVD。

- 完整 Debug 与 AndroidTest APK 构建成功，版本 `1.0.313-26092012-05`。
- 手机：840×2100px / 420dpi，真实生成器切换 GPG 测试通过。与旧版 `-04` 截图比较，仅状态栏时钟区域 `(51,19)-(93,47)` 有像素差异；页面内容完全一致。
- 平板：1920×1200px / 240dpi（1280×800dp），8 项设备测试通过：6 项共享布局/设置回归，真实 GPG 表单切换，以及真实 GPG 生成、结果菜单、复制指纹。
- 共享布局检查包括：400dp 列表栏的标题/操作不重叠，输入与关闭搜索，宽顶栏水平排布，840dp 表单居中，窄化后保留输入，生成器左右分栏及点击回调。手机表单还与原始 Material Scaffold 做了逐像素比较，结果一致。
- 已检查真实生成器、GPG 菜单、设置页及共享顶栏截图。
- 设备测试覆盖上述代表路径，不等于 62 个接入文件的所有业务流程都逐页回归；F-Droid 同步了源码、测试和发行说明，未单独构建。

### 证据

- [手机旧版](large-screen-device/phone-generator-before.png) / [手机新版](large-screen-device/phone-generator-after.png)
- [平板生成器](large-screen-device/tablet-generator.png)
- [平板 GPG 结果菜单](large-screen-device/tablet-generator-menu.png)
- [平板设置页](large-screen-device/adaptive-tests/settings-page.png)
- [窄分栏搜索](large-screen-device/adaptive-tests/tablet-narrow-search.png)

构建与设备日志保留在工作区 `.codex-tmp/adaptive-build.log`、`adaptive-phone-fixed.log`、`adaptive-tablet.log`。

深色顶栏在补齐测试 Surface 后单独复验通过（1 项），已检查正确深色背景截图： [深色顶栏](large-screen-device/adaptive-tests/tablet-wide-header-dark.png)。设备屏幕恢复为原 840×2100px / 420dpi，字体缩放恢复为 1.5。
