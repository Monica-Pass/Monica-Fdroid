# Android ELF release hardening

## Goal

Turn the remaining mandatory ELF properties in the MDBX3 acceptance matrix into executable release checks.

## Contract

- Android release links with `--build-id=sha1`; strip must retain one unique build ID per ABI.
- `NEEDED` remains exactly the MDBX2 baseline set: `libc.so`, `libdl.so`, and `libm.so`.
- SONAME remains absent, matching the MDBX2 baseline.
- `arm64-v8a` and `x86_64` LOAD segments support 16 KiB pages.
- Legacy `armeabi-v7a` LOAD segments use the NDK 4 KiB ARM32 profile.
- The ABI split verifier consumes and enforces the full release gate evidence.
- The Android loader smoke variant applies the same ABI filter to the JNI shim and MDBX library.

## Non-goals

- No file-format, schema, FFI, Tiga, sync, or capability change.
- No claim that an ARM32 process runs on a 16 KiB-only Android device.
- No SONAME addition that would change the MDBX2 loader contract.
