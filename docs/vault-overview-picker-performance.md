# Frequent card and item pickers

Both overview modules use `VaultOverviewPickerSheet`. Wallet entries show their title, bank, masked last four digits and the existing `CardBrandIcon`. Passwords show their title and account through the existing `VaultItemIcon` pipeline. Documents, addresses and other frequent item types remain available in their respective picker.

When the overview includes all databases, the sheet uses `UnifiedDatabaseFilterChipMenu` with its own scope. Filtering keeps selections from other databases and leaves the overview scope unchanged. Rows retain their input order when selected, share grouped corner shapes and use a separate check indicator without a rectangular press overlay. Search and Done stay outside the scrolling list. Keyboard dismissal keeps the query and matches; focus and IME state come from the sheet's dialog owner.

## Background work and lifetime

- `prepareOverviewPicker` resolves source identities, card metadata and normalized search text on `Dispatchers.Default`, once per input revision. Checkbox changes reuse the prepared rows. Locked and unknown sources are excluded before metadata reaches native code.
- At 512 or more candidates, Kotlin transfers one versioned `MOP1` frame to Rust: source indices and normalized display metadata. Passwords, complete card/document numbers, note bodies, secrets and artwork are excluded. Smaller lists use the same cached Kotlin implementation.
- Rust keeps an immutable index for the sheet's lifetime, with source buckets for database filtering. Each search transfers only the normalized query and source index, then returns indices in the original order. Kotlin checks bounds, uniqueness, order and source membership before mapping results to existing row objects. Empty queries reuse cached scoped lists without JNI.
- Kotlin performs `Locale.ROOT` normalization for both stored metadata and queries, giving native and fallback searches identical Unicode semantics. Missing libraries, invalid handles and invalid native output retain the Kotlin path.
- Library loading, index creation and native searching are refused on the main thread. Native index cleanup runs on a worker when the sheet closes or its input changes. Preparation assigns ownership before returning from the worker, so cancellation at the dispatch boundary cannot leak an index. Rust uses an `Arc` during a search, allowing concurrent cleanup without invalidating in-flight reads. No native calls occur in gesture or animation callbacks.

## Measurements

Measured on the dedicated API 32 x86_64 emulator at 1080 × 2400, density 420, with the debug APK and release native library on 2026-09-12. Search values are medians of 40 calls after 12 warm-up calls, using four query patterns and four evenly populated databases. Rust search timings include JNI, validation and mapping the returned indices. These are CPU processing measurements, not UI frame-rate claims or ARM device benchmarks.

| Candidates | Kotlin, all | Rust, all | Kotlin, one database | Rust, one database | Native index preparation |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 64 | 0.030 ms | 0.054 ms | 0.022 ms | 0.074 ms | 0.185 ms |
| 512 | 0.086 ms | 0.051 ms | 0.034 ms | 0.030 ms | 1.632 ms |
| 1,000 | 0.114 ms | 0.085 ms | 0.071 ms | 0.061 ms | 2.347 ms |
| 10,000 | 0.808 ms | 0.306 ms | 0.281 ms | 0.075 ms | 13.777 ms |
| 50,000 | 3.860 ms | 0.577 ms | 0.827 ms | 0.289 ms | 52.243 ms |

Index preparation includes encoding the already prepared metadata, JNI transfer and Rust index construction. It runs once in the background. Item decryption, card parsing, database I/O and rendering are outside these measurements. The small-list comparison is why the production threshold remains 512 candidates rather than loading native code for every sheet.

## Verification

- Rust unit tests cover malformed frames, Unicode metadata, scoped search, result order, concurrent index ownership and idempotent cleanup. `cargo clippy --all-targets -- -D warnings` checks the JNI crate; libraries are built for ARM64, ARMv7 and x86_64.
- `VaultOverviewPickerDataTest` covers bank/account metadata, excluded secrets, damaged card fallback, card/item separation, locked and unknown databases, stable cached rows and invalid returned indices.
- `VaultOverviewPickerNativeTest` compares actual JNI and Kotlin results across scopes and Unicode queries, checks cleanup and main-thread refusal, and writes `vault-picker-performance.json` on the test device.
- `VaultOverviewPickerScreenTest` exercises icons and metadata, cross-database selection, stable row positions, locked/empty states, the selection limit, keyboard Back and IME Search. The existing `VaultOverviewScreenTest` exercises integration with the overview modules and card stack.

Version and release notes are unchanged.

Validation for this change: 28 Rust tests, 14 related JVM tests, 3 real-JNI instrumentation tests and 15 picker/overview UI tests passed. The UI run includes keyboard Back and IME Search after correcting the dialog's focus/inset ownership.
