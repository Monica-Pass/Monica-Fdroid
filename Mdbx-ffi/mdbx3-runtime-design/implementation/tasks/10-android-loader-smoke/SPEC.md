# Android loader smoke application

## Goal

Exercise the real Android package loader with one ABI-specific MDBX3 shared object, without requiring product UI or client source changes.

## Contract

- The APK receives exactly one ABI-specific `libmdbx_ffi.so` under `lib/<abi>/`.
- A tiny JNI shim loads the canonical basename and resolves `ffi_mdbx_ffi_uniffi_contract_version`.
- The activity fails on any linker error, missing symbol, or contract version other than 30.
- The project is a test fixture only; it does not replace the frozen UniFFI bindings or product client tests.
- Gradle can run online or with `-Offline` when dependencies are already cached.

## Validation

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/run-mdbx3-android-loader-smoke.ps1 -Abi x86_64 -InstallAndRun
```

The tested APK must contain only `lib/x86_64/libmdbx_ffi.so` and the matching shim. Other ABI variants use the same project and selected library source.

For a release-specific or physical-device check, pass the artifact root and ADB serial:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/run-mdbx3-android-loader-smoke.ps1 `
  -Abi arm64-v8a -LibraryRoot target/mdbx3-android-final-88058b9 `
  -DeviceSerial <adb-serial> -InstallAndRun
```
