# Database filter menu rendering

The password-page folder menu and the shared database filter menu now use `DatabaseFilterChipContent`. Provider order, database identities, selection, labels, status dots and chip appearance are preserved.

Previously, both the horizontally scrolling row and the expanded `FlowRow` composed every database. Switching between them briefly kept both layouts alive, and every chip created five animation controllers. This cost existed even when the menu only showed a few databases; the chip path itself did not open, decrypt or synchronize those databases.

The collapsed menu now uses `LazyRow`. Expanded lists of up to 32 options retain `FlowRow`; larger lists use lazily composed rows. A worker measures display labels and prepares row boundaries, while the first viewport remains available. The worker owns its text-measurement cache, checks cancellation, and restarts when labels, font resolution, text size, density or direction change. Passwords, database contents and native database handles are not involved. Selecting a database dismisses the popup, so the extra per-chip selection animations are omitted; bounded press feedback remains. A single animated container handles expansion without retaining two complete chip trees.

The dropdown's intrinsic sizing pass asks for both width and height. The viewport supplies these estimates without querying lazy children, while normal measurement still determines actual dimensions. Its height reserves space for the header and popup padding so the final row remains reachable inside the popup. Row widths include Material's empty trailing slot and both internal gaps; changing Material chip geometry should be checked against the layout regression test.

This change is in Compose. Moving the row-boundary arithmetic to Rust would leave the expensive component creation and measurement on the UI thread.

## Focused measurements

Measured on 2026-09-16 using Windows, OpenJDK 17 and Robolectric 4.16.1 with Android 34 native graphics, cached application resources and the actual menu components. Each case opened the menu five times; the first was discarded and the median of the other four is shown. Timings cover publishing visibility, processing the initial frames and measuring the menu, including host-test overhead. They do not measure an ARM phone's input-to-display latency or the completion of all background label preparation. Smaller cases ran first, so JVM warm-up also affects comparisons between dataset sizes.

| Databases | Collapsed before | Collapsed after | Expanded before | Expanded after |
| ---: | ---: | ---: | ---: | ---: |
| 8 | 45.9 ms | 31.3 ms | 43.9 ms | 30.2 ms |
| 32 | 84.5 ms | 18.5 ms | 67.6 ms | 43.0 ms |
| 128 | 101.1 ms | 15.2 ms | 95.9 ms | 31.6 ms |
| 512 | 204.9 ms | 14.6 ms | 148.8 ms | 22.7 ms |

In the real popup harness, the 512-database expanded menu composed 11 database chips initially. Both collapsed and expanded menus scrolled to database 512, displayed it inside the popup, selected the correct ID and dismissed successfully. Rendering comparisons matched the original `FlowRow` positions and widths for mixed Chinese, Polish, Japanese, Arabic, English and emoji labels at font scales 1.0 and 1.6 in LTR, and 1.3 in RTL.

## Verification scope

- Two JVM row-packing tests passed, covering boundaries, integer overflow and 100 randomized sets of 1,000 widths without dropping or duplicating entries.
- A standalone Robolectric run passed both popup scroll/selection scenarios and the three rendered layout comparisons described above.
- Four production Kotlin files and both regression-test files compiled with the Compose compiler against cached Android dependencies. `DatabaseFilterChipMenuTest` retains the popup and multilingual-layout checks for future device runs.
- The seven shared source, test and documentation files are also synchronized to F-Droid, whose Compose BOM matches the standard edition.

No Gradle build, APK packaging, emulator or physical-device run was performed for this change. The Android instrumentation tests were compiled, not executed on a device.
