# GPG usability repair — 2026-09-20

Status: final build 1.0.313-26092012-09 passed all 18 device tests (88.234 seconds).

## Reproduction and cause

- The reported `ji` / `dgbbd` identity reaches the generator with an invalid email. The shared catch-all error incorrectly suggested key-file or storage-access problems.
- The same failure appeared both behind and inside the generation sheet.
- Only English and Simplified Chinese GPG resources existed; Classical Chinese and other app languages fell back to English.
- GPG used a separate editor and omitted the favorite action and notes offered by the other entry editors.

## Changes

- Shared identity validation in the generation API and view model, inline name/email errors, optional empty email, trimming outer whitespace in UI requests, retry without losing entered values.
- Specific generation/import/load/save/export error messages, with no duplicate error behind an open sheet.
- Existing entry type selector and favorite button in the app bar; editable notes; both fields loaded and saved with the entry. Private-key passphrase visibility toggle.
- Full GPG resource sets for all 14 app resource locales (35 keys each), checked for missing keys and format arguments.
- Existing generator layout, result-card action menu and storage selector retained.

Design: [editable M3E Canvas](gpg-m3e.md), [native component layout sketch](gpg-canvas.png).

## Baseline checks

Shared AVD `Monica_Issue136_API_32`, Android 32 x86_64, app 1.0.313 / build 07:
- GpgCryptoDeviceTest: 3 passed (protected signing/encryption, malformed input rejection, RSA 4096 and binary/public-only import).
- GpgStorageDeviceTest: 1 passed (real KDBX write, close/reopen and encrypted private storage).
- CommonAccountSecurityReuseTest and NewEntrySecurityReuseTest: 2 passed (previous navigation optimization compatibility and actual password save).

These tests verify valid input; they did not previously cover the misleading invalid-email flow or missing editor actions.

## Verification-driven adjustments

Build 08 completed successfully with a single 6 GB heap / 2 processor / 1 worker process. A 4 GB attempt exhausted the Compose compiler heap; no separate Kotlin daemon was started.

The first device suite passed 17 of 18 tests. The localized UI test needed an Activity-backed context wrapper and explicit Compose LocalResources override. Android resource resolution itself passed for 16 locale tags, including zh-HK, zh-TW, lzh and zh-NY.

Visual inspection found that the original single-row header truncated the new-entry title under 1.5x font scaling. The final revision uses existing native top-bar actions followed by a separate wrapping title. Added a TextLayoutResult assertion that the title has no visual overflow, along with screenshot checks in four locales.

## Final result

- Main + instrumentation build: PASS (single-use compiler process exited).
- GpgKeyFlowTest: 10 PASS, including blank-name/invalid-email recovery, passphrase-protected creation, favorite/notes persistence, menu actions, clipboard, generator navigation and actual generation, public-only actions, large text and four localized editors.
- GpgLocalizationDeviceTest: 1 PASS (16 Android locale tags).
- GpgCryptoDeviceTest: 3 PASS.
- GpgStorageDeviceTest: 1 PASS.
- MdbxKeyInteropTest: 1 PASS (native MDBX import/edit/reopen/export).
- Previous new-entry security reuse regressions: 2 PASS.
- Total: OK (18 tests), 88.234 seconds. Log: workspace `.codex-tmp/gpg-final-device.log`.

Visual review: `gpg-repair-device/entry-lzh.png`, `entry-zh-Hant.png`, `entry-zh-CN.png`, `entry-de.png` and localized validation captures. The title and favorite are visible at 1.5x text; the title is not ellipsized. Long German card text wraps heavily but controls remain reachable. Error screenshots show the keyboard still open; the form is scrollable and generate actions are reached through scrolling in the test.

Scope: native Android debug x86_64 on the shared API 32 emulator. F-Droid source/resources/tests mirrored; no separate F-Droid binary build in this run. System document picker export write and physical vendor-device behavior were not tested here. Existing export callback and crypto/storage round-trip checks passed.
