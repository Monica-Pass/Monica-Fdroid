# Android ABI split distribution

## Goal

Reduce per-device MDBX3 Android download and installation size without removing an ABI or changing the MDBX2-compatible library contract.

## Contract

- A native ELF remains architecture-specific.
- The release set contains `arm64-v8a`, `armeabi-v7a`, and `x86_64` thin packages.
- Each thin package contains exactly one `android-jniLibs/<abi>/libmdbx_ffi.so`.
- A compatibility universal package retains all three standard JNI directories.
- Every packaged library is bound by SHA-256 to the full MDBX3 artifact report and frozen MDBX2 UniFFI ABI.
- F-Droid and direct APK clients select one ABI per APK variant; this repository does not mutate client Gradle files.

## Exclusions

- No fake cross-architecture ELF or runtime instruction translation.
- No removal of ARM32 or x86_64 from the build matrix.
- No change to storage format, schema, FFI namespace, basename, bindings, Tiga, sync, or runtime capabilities.
