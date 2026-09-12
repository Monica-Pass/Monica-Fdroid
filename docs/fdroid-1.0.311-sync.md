# F-Droid 1.0.311 synchronization

This tree synchronizes the Android application at upstream commit `830612b3`, including the ZXing scanner updates merged in PR #133. F-Droid uses application ID `takagi.ru.monica.fdroid`, version name `1.0.311`, and version code `18`.

## Included changes

- Vault overview, frequent and pinned items, selection and swipe actions, and native-assisted picker search.
- Card stacks, card artwork cropping improvements, and list loading and scrolling updates.
- Keyboard and autofill improvements, optional accessibility availability monitoring, and optional Shizuku recovery.
- Bitwarden attachment encryption and transfer fixes, login keyboard handling, and page-scoped synchronization.
- Expanded French and other translations, database merge messages, and scrollable language selection.
- Shared CameraX/ZXing scanning for camera and gallery, with inverted and multiple codes, small-code full-resolution sweeps, rotation/crop/stride handling, foreground recovery, and bounded decode work per frame.

## F-Droid adaptations

Google Play Services, ML Kit, Firebase, Microsoft MSAL, and Play-backed credential authentication are excluded. Google Drive and OneDrive login remain disabled. ZXing core `3.5.3` and JourneyApps `4.3.0` remain the scanner dependencies; no barcode model download is needed. Shizuku API, provider, shared, and AIDL `13.1.5` declare the MIT license in their published POMs; the integration is optional and requires user authorization.

Accessibility recovery resolves the current application's package ID while retaining the service class's source namespace. It therefore leaves the main Monica edition's accessibility service untouched when both editions are installed. A co-installation regression test covers this distinction.

Build metadata remains static. F-Droid's universal APK setting, ABI filters, dependency-signing-block exclusions, source-built Rust libraries, and opt-in cleartext WebDAV policy are preserved. No upstream native binaries or signing material were copied. The additional JNI entry points are compiled from source, and their facade names are retained under R8.

Optional proprietary network integrations retain the existing F-Droid `NonFreeNet` declaration. This local synchronization does not update the external fdroiddata build commit or publish a release; those must refer to an actual published revision.

## Local validation (2026-09-12/13)

- Universal debug and release APKs built successfully, including source-built ARM64, ARMv7, and x86_64 native libraries. Release passed R8 and resource shrinking.
- APK manifest/DEX inspection confirmed the F-Droid application ID, required native libraries, retained JNI facade names, and absence of the excluded Google/Microsoft SDK packages. Debug and release runtime dependency graphs were also checked.
- Rust JNI: 28 tests passed. Focused Android JVM coverage: 157 tests passed, including scanner decoding and recovery, F-Droid boundaries, package-isolated accessibility recovery, attachment handling, overview, card stacks, and French coverage.
- Full inherited JVM suite: 1,560 tests, 25 failures, 1 skipped. Twenty-two failures match the previous F-Droid baseline. The remaining three are source-text guards that expect superseded upstream code or the deliberately disabled OneDrive entry. The suite is **not fully green**; no tests were disabled to conceal these failures.
- Android 15 emulator: all 14 scanner tests passed, including camera/gallery handling, 13 barcode formats, lifecycle recovery, and 30-second idle sessions for Steam and authenticator scanning. Idle tests use real emulator camera frames followed by a supplied test-code frame; no real Steam login was approved.
- Android 12L emulator: all 10 functional JNI tests for overview, picker search, and card stacks passed with the same debug APK.
- The Android 15 16 KiB preview emulator encountered ART ProfileSaver/JIT crashes during additional native projection tests. Those attempts are not counted as passing; logs are preserved. Performance benchmarks were not completed, and no physical-device result is claimed.
- Installed release on Android 12L: startup, test-vault initialization, authenticator creation screen, and live CameraX scanner opening passed; the crash buffer was empty and CameraService reported the F-Droid application as an active client.

Detailed logs, XML results, dependency/license reports, and APK hashes are stored locally under `.codex-tasks/20260912-upstream-sync/`. These checks do not replace the official F-Droid build-server review or reproducibility verification.
