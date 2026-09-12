# Vault overview: computation and animation

The overview is enabled by default and can be disabled independently of the saved classic/hierarchical vault layout. Its eight modules have separate visibility, order and expansion preferences. Wallet cards and other frequent items have independent pins and recommendations. Database scope follows navigation and new-item creation; locked databases are excluded from item projections.

## Work distribution

- Kotlin prepares source/folder identities and numeric metadata on `Dispatchers.Default`. The prepared batch is reused when the database scope changes.
- Rust computes scoped membership, source/type/folder counts and bounded rankings for cards and frequent items in one call. It receives numeric indices and counters, never credentials, titles or artwork. Native results are checked before they index application data.
- Batches below 1,024 rows use the Kotlin implementation to avoid JNI overhead. Missing or invalid native results also fall back to Kotlin. The JNI wrapper refuses calls and library initialization on the main thread.
- The overview skips the hidden classic-list display pipeline, hierarchy construction and unused password lookup. Snapshot publication uses reference equality so the UI thread does not compare the complete projected lists.
- Frequent cards use `WalletStackCard` and `WalletStackBrowser`, including their vertical browsing, detail-return and collapse behavior. The open deck keeps its order when usage rankings change. Only the cover artwork is rendered while collapsed; the browser composes the visible neighbourhood. Compose owns gestures and animation frames, with no JNI work in that path.
- Browser actions observe the current navigation transition instead of capturing a returning frame's inactive state. This keeps interactions blocked during the transition and restores browsing and collapse when the page is visible again.
- The open deck's small parsed cache lives with the vault navigation state and is cleared by the existing security cleanup. Its scope and input must match before it can be displayed.

## Measurements

Measured on the dedicated API 32 x86_64 emulator at 1080 × 2400, density 420, using the debug APK and actual JNI library on 2026-09-11. These are median CPU processing times for synthetic metadata, not device-independent UI frame-rate claims.

| Rows | Kotlin aggregation | Rust aggregation, JNI and validation | Kotlin cached scope projection | Rust cached scope projection |
| ---: | ---: | ---: | ---: | ---: |
| 64 | 0.010 ms | 0.081 ms | 0.037 ms | 0.085 ms |
| 512 | 0.062 ms | 0.075 ms | 0.072 ms | 0.067 ms |
| 1,000 | 0.133 ms | 0.070 ms | 0.113 ms | 0.073 ms |
| 10,000 | 1.436 ms | 0.456 ms | 1.226 ms | 0.629 ms |
| 50,000 | 7.183 ms | 3.415 ms | 5.938 ms | 4.193 ms |

Aggregation timings start from an already packed numeric batch. Cached scope timings additionally include copying the scope header and mapping results back to items and modules. A complete snapshot also prepares identities and folders: at 10,000 rows this measured 7.776 ms with Kotlin and 6.579 ms with Rust; at 50,000 rows, 51.558 ms and 49.403 ms respectively. Reusing preparation removes that larger cost from scope changes. First-load item decoding, database I/O, rendering and bitmap loading are outside these measurements.

## Verification

- Rust: 25 unit tests, Clippy with warnings denied, and Android libraries for ARM64, ARMv7 and x86_64.
- `VaultOverviewNativeTest`: real-JNI randomized parity, main-thread refusal and end-to-end timing.
- Overview storage/screen/pane tests: backup round-trip, scoped trash counts, separate pickers, the shared creation FAB, module customization, real-list navigation, disabling the overview, locked-source presentation and card-stack/detail return.
- Final emulator verification: 52 instrumented tests passed. This includes 51 overview storage/screen/pane/native, wallet-browser/navigation/performance and pull-search/keyboard-dismissal cases, plus the real `MainActivity` regression below.
- `VaultOverviewMainActivityTest` logs in with the keyboard action, opens password creation through the existing FAB, and opens a card detail before returning and collapsing the shared stack. Run it separately on a fresh disposable emulator with `-e overviewFreshInstall true`; it skips configured vaults and requires an emulator. The real navigation flow reproduced the stale callback failure that isolated screen tests missed.
- Full JVM run: 1,474 tests, 26 failures, one skipped. Comparing failures with the saved HEAD-source guard audit found no new failing cases. That audit ran the compiled test set against the previous tracked sources; it was not a clean baseline application build. Existing failures are not presented as a passing full suite.

Version remains 1.0.311. This implementation does not change the release notes.
