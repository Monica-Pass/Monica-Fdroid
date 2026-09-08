# Authenticator and card-wallet first-frame work

## Changes

- Query active password rows with nonempty `authenticatorKey` instead of materializing every active password for OTP merging. Delete confirmation reads only IDs and titles.
- Carry readiness with the parsed authenticator snapshot. The UI shows loading until the first real result, including when that result is empty.
- Reuse parsed stored and password-bound OTPs within the current merged snapshot, avoiding repeated decryption and JSON parsing. Invalid results remain retryable.
- Cache card, document and billing-address payload parsing by ID and encoded content. Reordering and favorite changes reuse successful values; changed payloads invalidate them, and removed entries are dropped.
- Prepare wallet projections, sorting and filtering on `Dispatchers.Default`. Category/query changes cannot display the preceding filter's result as current or clear selection before readiness.
- Sort batches of at least 256 rows through Rust/JNI. Smaller batches and unavailable native code use Kotlin. The batch contains only a version and numeric favorite/order/ID/timestamp fields. The original two tie-breaking policies and stable ties are preserved. Kotlin validates a complete index permutation before accepting a native result.

## Measurements, 2026-09-05

Environment: dedicated Android 15 API 35 emulator, x86_64 system image with 16 KB pages, ARM64 APK via the emulator's ARM translation, 4 CPU cores and 4 GB guest memory. Debug Kotlin and release Rust. These are synthetic component measurements, **not device first-frame benchmarks**.

Sorting includes Kotlin batch allocation/packing, JNI transfer, Rust sorting, permutation validation and mapping back to the original objects. Five warm-up pairs and eleven measured pairs were run per size; the table reports medians. Kotlin means the retained Kotlin fallback, not a separately built historical APK. Initial library loading is outside the warmed measurement.

| Rows | Kotlin fallback (ms) | Rust round trip (ms) |
| ---: | ---: | ---: |
| 256 | 2.4624 | 0.4030 |
| 1,000 | 2.6872 | 0.7066 |
| 10,000 | 40.3890 | 5.4494 |
| 50,000 | 189.9520 | 17.7057 |

An in-memory Room dataset with 2,001 active passwords and one OTP candidate returned all rows in 46.6501 ms and the candidate query in 1.8477 ms. This is one sequential sample; it demonstrates the narrower materialization path, not a statistically established query speedup. Archived and deleted OTP passwords were excluded. Real databases, encryption, backend initialization, device characteristics and cache state may dominate total page-entry time.

## Validation

- 14 Rust unit tests and strict Clippy passed; ARM64 and ARMv7 JNI builds passed.
- Android main Kotlin compilation and the Debug APK succeeded. Subsequent test-only runs reused those unchanged main class jars/APK because `BUILD_TIME` changes on every Gradle invocation and otherwise triggers costly recompilation. No test compilation or execution was skipped.
- 28 targeted JVM tests passed: payload cache reuse/invalidation/recovery, loading guards, OTP resolution/binding, wallet ordering and category/search semantics.
- Three Android tests passed: real Room candidate queries and first-ready state, key update/removal, both native/Kotlin sort policies with benchmarks, and asynchronous wallet loading/filter/empty-state transitions.
- An initial Android test had an inferred non-void return type; it was corrected to `runBlocking<Unit>`. The first dedicated emulator run also suffered an Android `system_server` crash. The successful measurements above were collected after restarting that emulator with 4 GB memory and software graphics.

Tests: `ListFirstFrameInstrumentedTest`, `WalletListPreparationTest`, and `ItemDataSnapshotCacheTest`. `cargo ndk` output is generated and is not committed. No account-backed Bitwarden/KeePass/MDBX end-to-end timing or physical-device first-frame timing is claimed.
