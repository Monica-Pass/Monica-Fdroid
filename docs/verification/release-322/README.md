# F-Droid 1.0.322 release verification

Verified on 2026-10-27 for version 1.0.322 / versionCode 29.

- Verified source: a clean detached checkout of `abff3efe`, without local changes. The release commit adds only release documentation on top of it.
- Selected JVM suites: 81 tests in 11 suites passed with none skipped, covering the note ciphertext repair and plain note storage in nearby sync (including the native replica exchange), manual nearby sync, HOTP ordering, Root authorization, and wallet stack collapse and reordering (counts in results.json).
- Device tests on the shared API 32 emulator: 7/7 passed, covering wallet card and collapsed-stack reordering, selection scope, large-font layout, and note repair against a real master-password vault in an isolated context.
- Universal F-Droid Release APK built with R8, including arm64-v8a, armeabi-v7a and x86_64. Packaged Rust JNI libraries match their source-built outputs.
- Release launch smoke passed on the shared API 32 emulator: the R8 build started, stayed in the foreground and rendered its first screen without fatal errors. The window blocks screenshots, so the screen was verified from the UI hierarchy. The debug installation was restored.
- Release metadata check and six Python release-validator tests passed.
- Earlier wallet device verification is retained in `docs/verification/wallet-selection-collapse-20261025.md`.

This is selected regression coverage, not a claim that the entire legacy test suite passed. The local R8 launch smoke does not replace official F-Droid builds or end-to-end testing on every device. GitHub publishes source only; F-Droid independently builds, signs and indexes the release. No APK assets, private screenshots or design drafts are published.
