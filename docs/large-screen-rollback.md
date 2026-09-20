# Large-screen redesign rollback — 2026-09-20

Completed after the new-entry performance changes were measured and their 12 device regression tests passed. This restores the previous large-screen behavior, not a phone-only mode.

## Scope

- Restore original ExpressiveTopBar capsule, PanePrimitives, GeneratorPane and SettingsTabContent.
- Restore original compact-width decision and 400 dp list pane in SimpleMainScreen.
- Remove AdaptivePageScaffold wrappers, AdaptiveGeneratorLayout, and their redesign-only device test.
- Restore the generator LazyColumn and remove the added MDBX maximum-width override.
- Retain historical M3E Canvas artifacts, clearly marked withdrawn; remove the redesign claim from release notes.

Before applying the staged rollback, all 142 manifest entries were checked against their saved SHA-256 baselines. Existing unrelated edits were not reset. Main and F-Droid sources received the same scoped rollback; their independent differences remain.

## Preserved changes

- Reused SecurityManager/CommonAccountPreferences and the single editor navigation animation.
- GPG generation, import/export, persistence, result actions, localization, favorite/notes and validation repairs.
- MDBX detail content retained throughout the return transition.
- Wallet stack resting-origin preservation during navigation.
- SSH/GPG interoperability, Rust overview aggregation and unrelated autofill/storage work.

## Verification

Build 11 (`assembleDebug` and `assembleDebugAndroidTest`) passed in 22m39s, with one worker, two active processors, Kotlin in-process and a 6 GiB heap. Installed on the shared Android 32 x86_64 AVD.

- Initial phone regression at 840×2100 / 420 dpi / 1.5 font: 22 tests, 21 passed. One existing MDBX test assumes normal width supports two columns; 320 dp correctly selected one column.
- Re-ran all 7 MDBX layout tests at physical 1080×2400 / 420 dpi (411 dp): all passed, without changing production code or weakening assertions.
- The other 15 phone tests passed: common-account security reuse (1), actual new-entry save/decrypt (1), GPG flow (10), localization (1), wallet navigation origin (2).
- Additional tablet test at 1920×1200 / 240 dpi / font 1.0: actual GeneratorScreen GPG generation and result-menu interaction passed (1 test).
- Inspected actual app screenshots of the phone vault, tablet vault and tablet settings. The generator screenshot comes from its production screen hosted directly by the device test; it does not verify the full GeneratorPane host. The original GeneratorPane was restored and source-compared to HEAD.
- The tablet vault screenshot shows the existing system taskbar overlapping the lower edge of the add button. This rollback does not claim to repair that pre-existing layout behavior.
- Main/F-Droid core rollback files match; both repositories pass `git diff --check`. No separate F-Droid binary is claimed tested.

Screenshot protection was temporarily disabled through settings for inspection, then restored to Enabled. Resolution, density and font scale were restored to 840×2100 / 420 dpi / 1.5. The task-started shared emulator is stopped after verification, with data retained.

Evidence: `large-screen-rollback-device/` screenshots and test logs. Raw build log and reviewed rollback diff remain in workspace `.codex-tmp/`. Performance evidence is in [new-entry-security-reuse.md](new-entry-security-reuse.md).
