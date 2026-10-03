# F-Droid 1.0.316 release validation

2026-10-03, application ID `takagi.ru.monica.fdroid`, versionCode 23.

- Release metadata validation and its 6 tests passed.
- Final debug builds of both editions succeeded; F-Droid R8-minified Release packaging succeeded.
- Both editions passed 8 developer-log UI tests and 3 credential editor/detail tests on the shared API32 emulator.
- F-Droid additionally passed 41 instrumented data tests: local recovery, connection recovery, project credential storage/exchange and database manager transfers. Synthetic fixtures ran in test user 10; user and system settings were restored.
- The complete JVM suite is **not green**. Failures include source-string checks tied to earlier layouts/security flows, old 1.0.315 version constants, the intentionally removed automatic password merging expectation, and incomplete translation coverage. These are retained as visible test debt; tests were not disabled or rewritten to make this release pass. Missing localized resources fall back to default Android resources.
- This report does not claim live third-party cloud provider coverage or universal device compatibility.

# F-Droid 1.0.316 unit test audit

2151 tests, 23 failures, 1 skipped. This is not an all-green test suite.

- takagi.ru.monica.ime.MonicaImeRegressionGuardTest.imePasswordRowsResolvePasswordsOnlyWhenTheUserFills
- takagi.ru.monica.localization.ClassicalChineseResourceCoverageTest.technicalIdentifiersUrlsAndNumbersRemainIntact
- takagi.ru.monica.localization.ClassicalChineseResourceCoverageTest.everyTranslatableResourceHasTheSameStructureAndArguments
- takagi.ru.monica.localization.FrenchResourceCoverageTest.everyTranslatableResourceIsPresentAcrossTheFrenchModules
- takagi.ru.monica.localization.LocaleResourceCoverageTest.everyLocaleIncludesEveryTranslatableResourceAcrossAllModules
- takagi.ru.monica.localization.PolishResourceCoverageTest.everyTranslatableResourceHasACompletePolishTranslation
- takagi.ru.monica.passkey.PasskeyRemarkAndNavigationGuardTest.authenticatorAndPasskeyShareOneDockDestinationWithBidirectionalControls
- takagi.ru.monica.security.BiometricUnlockRegressionGuardTest.mdkWrapperRebuildHandlesInvalidatedAndUnrecoverableKeystoreKeys
- takagi.ru.monica.security.BiometricUnlockRegressionGuardTest.emptyRuntimeMdkCacheCannotMaskReadableKeystoreWrapper
- takagi.ru.monica.security.BiometricUnlockRegressionGuardTest.pageSwitchHotPathsDoNotRunAuthOrBitwardenSyncWorkOnMainThread
- takagi.ru.monica.security.BiometricUnlockRegressionGuardTest.autofillPasswordSelectionReturnPathDoesNotBlockMainThread
- takagi.ru.monica.ui.AddPasswordGeneratorPreferencesTest.addPasswordDialogReadsDefaultsWithoutWritingTemporaryChangesBack
- takagi.ru.monica.ui.password.PasswordStackPerformanceGuardTest.stack summary preserves merged favorite and cover semantics without allocations
- takagi.ru.monica.ui.screens.PasswordAccountLabelRegressionGuardTest.accountLabelStaysConsistentWhenUsernameSeparationIsDisabled
- takagi.ru.monica.ui.screens.PasswordEditorIdentityRegressionGuardTest.newModeNeverPassesRememberedIdsToTheSavePipeline
- takagi.ru.monica.ui.SelectableTextRegressionGuardTest.passwordDetailNotesAreSelectableInViewMode
- takagi.ru.monica.versioning.FrozenVersionCodeGuardTest.fdroid version code matches synchronized release
- takagi.ru.monica.VersionMetadataRegressionGuardTest.fdroid version metadata remains static and reproducible
- takagi.ru.monica.viewmodel.MultiPasswordSaveRegressionGuardTest.webDavBackupScreenManualCreateSharesCoordinatorQueue
- takagi.ru.monica.viewmodel.MultiPasswordSaveRegressionGuardTest.mdbxManagerLivesUnderDatabaseBackupAndUsesStandalonePages
- takagi.ru.monica.viewmodel.MultiPasswordSaveRegressionGuardTest.addPasswordAuthenticatorKeyFieldHasInlineScanAction
- takagi.ru.monica.viewmodel.MultiPasswordSaveRegressionGuardTest.inlineTotpPreviewMatchesSimplePasswordPreviewAndKeepsCountdownInSync
- takagi.ru.monica.viewmodel.MultiPasswordSaveRegressionGuardTest.webDavBackupsUseMonicaLocalContentScope
