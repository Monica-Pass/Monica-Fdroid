# New-entry encrypted-storage initialization — 2026-09-20

The password, bank-card, document and note editors now reuse the Activity security manager through the existing composition local. CommonAccountPreferences accepts that instance, preserving the default constructor for other callers and its existing encrypted format.

## Evidence

Android 32 shared AVD, debug app 1.0.313. ART method sampling at 1 ms during an actual password-list FAB tap:

- Before (build 06): SecurityManager constructors account for approximately 74.2 ms inclusive on the main thread. EncryptedSharedPreferences.create accounts for 67.2 ms within that time; CommonAccountPreferences initialization accounts for 34.9 ms and includes the second security manager. These values overlap and must not be added.
- After (build 07): neither SecurityManager constructor nor EncryptedSharedPreferences.create appears in the sampled main-thread click path. CommonAccountPreferences no longer initializes an independent security manager.
- Raw traces: workspace `.codex-tmp/monica-add.trace` and `.codex-tmp/new-entry-security-reuse.trace`; parser `.codex-tmp/parse-add-trace.py` (main TIDs 4002 and 7242 respectively).

The later trace ran while another build was active. It verifies the removed work, not a comparable wall-clock speedup. Frame results under build load are not suitable for a before/after percentage. Navigation composition/layout still require independent measurement; the nested entry animation was deliberately unchanged in this experiment.

## Functional regression

Build 07 device tests passed:
- CommonAccountSecurityReuseTest: standalone and shared-manager preferences can read each other's encrypted writes, including clearing/restoring values.
- NewEntrySecurityReuseTest: actual password editor creates and saves an entry with the Activity-provided manager; stored password is encrypted and decrypts to the entered value.

## Keyguard comparison

Reference checkout `.codex-tmp/keyguard-new`, commit `7e124b5`: AddStateProducer obtains dependencies from currentKoinScope. No Keyguard runtime timings are claimed. The official 3.2.3 setup requires a license confirmation; the user selected source comparison instead.

## Final navigation pass (build 10)

Removed AddEditRouteContent's nested AnimatedVisibility and its LaunchedEffect visibility flip. NavHost retains its existing entrance/exit transitions and the existing LocalAnimatedVisibilityScope. Editors can compose on the destination's first composition without a second transition.

Actual FAB taps on the shared API 32 emulator, same resolution/font and 10 repeated opens per build, no build from this task running during measurement. First 5 repetitions used as warm-up; median of per-open maxima for the remaining 5:

| Build | Maximum frame duration | HandleInputStart → DrawStart | Frames >50 ms per open |
|---|---:|---:|---:|
| 06 (before) | 457.3 ms | 145.3 ms | 5 |
| 09 (security reuse only) | 370.4 ms | 34.5 ms | 4 |
| 10 (reuse + single transition) | 261.1 ms | 27.4 ms | 2 |

These are frame metrics, not click-to-interactive latency. The emulator/host still has variability and remaining long frames; no claim of Keyguard-equivalent speed or universal percentage improvement. The final method trace again contains no sampled SecurityManager construction or EncryptedSharedPreferences.create on the main-thread click path. Remaining sampled work is Compose composition/layout and rendering. This completes verification of these two changes; it does not prove every device is free of new-entry jank.

Raw evidence: workspace `.codex-tmp/new-entry-{before-idle,after-idle,final-idle}.json`, per-run framestats, and `new-entry-final-navigation.trace`. Main build: `new-entry-final-build.log` (PASS). Functional results: `new-entry-final-regression.log`.
