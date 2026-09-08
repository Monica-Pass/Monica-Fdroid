# Release packaging and replacement gate

## Drop-in contract

The Android replacement package contains exactly one native basename,
`libmdbx_ffi.so`, under each supported ABI directory. Existing MDBX2 Kotlin
bindings remain the consumer contract.

The gate requires:

- `arm64-v8a`, `armeabi-v7a`, and `x86_64` ELF artifacts with matching machine
  identifiers;
- MDBX3 runtime manifest with MDBX-2 storage, schema 17, `mdbx_ffi` namespace,
  and the legacy library basename;
- 535 frozen native symbols, 237 checksums, and UniFFI contract version 30;
- artifact SHA-256 values matching the staged files;
- `llvm-strip --strip-all` postprocessing;
- raw and deflate artifact totals lower than the MDBX2 baseline.

The gate does not alter vault files, bindings, or application code. A peer or
client lacking incremental capabilities continues to use complete-state MDBX2
fallback.
