# F-Droid 1.0.321 release verification

Verified on 2026-10-21 for version 1.0.321 / versionCode 28.

- Ordinary Android and F-Droid: five selected JVM suites each (counts in results.json).
- Native sync replica: 18 tests passed, including deletion review, keeping local content, repeated deletion versions and tamper rejection.
- Universal F-Droid Release APK built with R8, including arm64-v8a, armeabi-v7a and x86_64. Packaged Rust JNI libraries match their source-built outputs.
- Release launch smoke passed on the shared API 32 emulator, isolated user 10. Only a temporary test copy was debug-signed; the original release APK was unchanged. Debug installation, user, screen timeout and keyguard settings were restored.
- Six Python release-validator tests and version/tag/changelog metadata checks passed.
- Earlier device verification is retained in the adjacent editor-menu-scale, nearby-device-management and nearby-status-cancellation reports.
- Unified template-menu sources match across editions. Three existing device tests cover credential template navigation, dark menus, and wallet template switching/draft preservation at large font size. Old credential fixtures referenced a nonexistent native database and stopped at the unavailable-database screen; they now create and clean up a synthetic vault and wait for its metadata. Optional protected-window screenshots are not required for UI assertions. User and system settings were restored.

This is selected regression coverage, not a claim that the entire legacy test suite passed. The local R8 launch smoke does not replace official F-Droid builds or end-to-end testing on every device. GitHub publishes source only; F-Droid independently builds, signs and indexes the release. No APK assets, private screenshots or design drafts are published.
