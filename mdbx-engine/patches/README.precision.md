# JSON precision runtime overlay (2026-09-28)

The native contract fixture found that `serde_json` without `arbitrary_precision` rounds large integers and long decimal fractions during FFI disclosure. `90005c8-json-precision.patch` enables lossless numbers across the workspace. It changes no storage schema or UniFFI API.

Rebuild from an isolated source directory; do not apply the old overlays twice. Export upstream commit `90005c8c608c952093a4522ffa507a562e2e39a4`, then apply, in this order:

1. `90005c8-read-session-lifetime.patch`
2. `90005c8-sync-delta-limits.patch`
3. `90005c8-json-precision.patch`

Use Rust 1.86.0, cargo-ndk 4.1.2, NDK 28.2.13676358, `RUSTFLAGS=-C link-arg=-Wl,--build-id=sha1`:

```text
cargo ndk -t x86_64 -t arm64-v8a -t armeabi-v7a -P 21 -o <output> build -p mdbx-ffi --profile mdbx3-release --locked
llvm-strip --strip-all --keep-section=.note.gnu.build-id <each libmdbx_ffi.so>
```

Generate the ABI baseline from the packaged `src/main/java/uniffi/mdbx_ffi/mdbx_ffi.kt`, then use the upstream `scripts/verify-mdbx3-ffi-abi.py verify-exports` on all three libraries. Current bindings require 539 symbols and validate 239 API checksums when loaded. Static export verification does not prove runtime behavior on ARM.

F-Droid builds from its vendored `Mdbx-ffi` sources through `buildMdbxFfiFromSource`; all three overlays are already applied there. Do not add prebuilt libraries to that project.

The reconstructed source matches F-Droid vendored content after LF normalization. Current provenance hashes use that normalization and retain prior main-runtime hashes as historical evidence. A new build ID is expected; this is not a claim of bit-identical reproduction of older binaries.

Validation uses `MdbxCliContractInstrumentedTest` with only synthetic fixtures created by CLI `contract_tests::synthetic_cross_client_fixture_and_portable_copy`. Verify the two returned Android portable copies independently with CLI `contract_tests::android_returned_contract -- --ignored`. Keep full application JVM/UI results separate from isolated native-engine results.
