# F-Droid 1.0.310 local synchronization

This tree incorporates the Android features present in the local 1.0.310 source tree while preserving the F-Droid variant's free-software boundaries.

## Preserved F-Droid boundaries

- Package name remains `takagi.ru.monica.fdroid` and build metadata remains static.
- QR scanning uses ZXing and CameraX. Google ML Kit is absent.
- Google Play authentication and the Play Credentials bridge are absent.
- Microsoft MSAL and OneDrive authentication are absent. Google Drive and OneDrive entry points remain disabled; WebDAV remains available.
- The WebDAV cleartext guard remains active and requires an explicit user opt-in for plain HTTP.
- `libmdbx_ffi.so` is compiled from the vendored MDBX3 Rust source at commit `d1d3cc4fdff4e33fcb70099b3e7df36eeae43ba4`.
- `libmonica_rust_jni.so` is compiled from the vendored GPL-3.0-only `rust-core`, `rust-crypto`, and `rust-jni` source directories.
- No native library from the Play build is copied into this repository.

## Dependency audit

The resolved Android runtime dependency report was checked for GMS, ML Kit, MSAL, Firebase, App Center, Crashlytics, and the Play Credentials bridge. None are present. Rust metadata reports license expressions for all 49 Monica JNI packages and all 243 MDBX workspace packages; every expression is a GPL-compatible free-software license.

Existing third-party Android libraries remain sourced from their public upstream projects under permissive or compatible free-software licenses. This synchronization adds no proprietary Android dependency.

## Synchronized features

The synchronization includes the 1.0.308–1.0.310 Android changes: deferred first-frame work, Rust-backed password and list operations with Kotlin fallbacks, note tile layouts, card-wallet custom artwork and manual card-image cropping, encrypted card-face attachments, change-triggered WebDAV backup, UI consistency fixes, the authenticator/card-wallet loading improvements, and the autofill add-password category fix from issue #124.

## Local verification

- Debug and universal release APK builds complete successfully from this source tree.
- The release APK identifies as `takagi.ru.monica.fdroid`, version code `17`, version name `1.0.310`.
- The release DEX contains no GMS, ML Kit, MSAL, Firebase, or App Center package.
- The release manifest contains no MSAL activity, phone-state permission, or location permission.
- ARM64, ARMv7, and x86_64 packages each contain source-built `libmdbx_ffi.so` and `libmonica_rust_jni.so`.
- Focused tests for the F-Droid boundary, ZXing scanner boundary, first-frame caches, authenticator layout, card-image crop geometry and processing, card-face persistence, wallet ordering, autofill add-password categories, and version metadata pass.

The complete inherited JVM suite currently reports 29 source-text guard failures out of 1,379 tests. Representative failures demonstrably conflict with the synchronized upstream source: several still require Room schema version 77 while the current database is version 78, and another requires `proguard-android-optimize.txt` while both current trees use `proguard-android.txt`. The remaining failures are recorded for upstream test maintenance; the functional and F-Droid-specific checks above pass.
