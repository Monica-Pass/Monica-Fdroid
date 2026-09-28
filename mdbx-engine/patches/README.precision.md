# JSON precision runtime overlay (2026-09-28)

The native contract fixture found that `serde_json` without `arbitrary_precision` rounds large integers and long decimal fractions during FFI disclosure. `90005c8-json-precision.patch` enables lossless numbers across the workspace. It changes no storage schema or UniFFI API. The feature alone does not preserve literal serde marker keys; the fourth overlay below is required for that guarantee.

Rebuild from an isolated source directory; do not apply the old overlays twice. Export upstream commit `90005c8c608c952093a4522ffa507a562e2e39a4`, then apply, in this order:

1. `90005c8-read-session-lifetime.patch`
2. `90005c8-sync-delta-limits.patch`
3. `90005c8-json-precision.patch`
4. `90005c8-json-literal-keys.patch` (shared parser, JSON boundaries and lossless sync extensions)

Use Rust 1.86.0, cargo-ndk 4.1.2, NDK 28.2.13676358, `RUSTFLAGS=-C link-arg=-Wl,--build-id=sha1`:

```text
cargo ndk -t x86_64 -t arm64-v8a -t armeabi-v7a -P 21 -o <output> build -p mdbx-ffi --profile mdbx3-release --locked
llvm-strip --strip-all --keep-section=.note.gnu.build-id <each libmdbx_ffi.so>
```

Generate the ABI baseline from the packaged `src/main/java/uniffi/mdbx_ffi/mdbx_ffi.kt`, then use the upstream `scripts/verify-mdbx3-ffi-abi.py verify-exports` on all three libraries. Current bindings require 539 symbols and validate 239 API checksums when loaded. Static export verification does not prove runtime behavior on ARM.

F-Droid builds from its vendored `Mdbx-ffi` sources through `buildMdbxFfiFromSource`; all four overlays are already applied there. Do not add prebuilt libraries to that project.

The reconstructed source matches F-Droid vendored content after LF normalization. Current provenance hashes use that normalization and retain prior main-runtime hashes as historical evidence. A new build ID is expected; this is not a claim of bit-identical reproduction of older binaries.

Validation uses the bundled synthetic fixture in `MdbxCliContractInstrumentedTest`; ordinary instrumentation requires no preparation and cleans its temporary directory. For fresh cross-client outputs, generate a fixture using CLI `synthetic_cross_client_fixture_and_portable_copy`, then run `scripts/check_android_contract.py` with the installed app package, serial, fixture and a new host output directory. The CLI verifier derives expected identities, payloads and collections from the original input, then corrupts disposable copies to check rejection of lost fields, wrong versions and wrong collections. Keep full application results separate from the isolated native harness.

Hashes under each overlay describe that stage. `final_overlay_files` records final content for files touched again by later overlays. Validation history retains prior runs; reused Cargo caches are not evidence of independent clean-build reproducibility.
