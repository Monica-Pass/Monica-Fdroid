# Keyboard custom fields — 1.0.316

Implements [issue #149](https://github.com/Monica-Pass/Monica/issues/149).

## Editable design

Start the local M3E Canvas at `http://127.0.0.1:5186/`, then open the full link in
[`canvas-url.txt`](canvas-url.txt), or import [`canvas.json`](canvas.json).
[`canvas-preview.png`](canvas-preview.png) is the rendered Canvas draft.

## Interaction

- Expand one password entry at a time.
- Common actions stay on one horizontally scrolling row. Custom fields use a
  second lazy horizontal row aligned with the common actions, without a decorative row marker; additional fields do not add rows or grow the keyboard.
- Field buttons display names only. Protected fields have a lock indicator.
- Custom-field-only entries are included. Custom fields are selected explicitly;
  the existing quick-fill sequence is unchanged.
- Only the expanded entry observes field descriptors. A click reads the latest
  individual value and commits it to the current input connection, without copying it.
- Retain ordinary labels including Email and PIN; omit internal content metadata,
  KeePass plugin data, OTP configuration and Passkey material.

## Data and lifecycle

No schema, serialization, synchronization or backup-format changes. The implementation
does not write credential data. Arbitrary plaintext, Base64 keys, Unicode and whitespace
are preserved; explicit Monica ciphertext uses the existing decryptor.

A transactional read checks that the field still belongs to the same active entry and
database. Input generation, connection, selected source, panel and vault unlock state
are checked again before filling. Locking, closing the keyboard and changing input
cancel pending work. Missing or unreadable fields produce a localized error.

## Verification

- `ImeVaultLoadInstrumentedTest`: metadata-only queries with large values, latest
  plaintext/encrypted fill, unchanged stored fields, wrong ownership, missing fields,
  deleted/moved entries, corrupt ciphertext, input changes and locking during reads.
- `MonicaImeUiTest`: 80 fields, single expanded entry, retry, protected labels,
  narrow 320dp layout, 150% text size and stable geometry while scrolling.
- `MonicaImePerformanceTest`: production service loader with 3,000 entries,
  12,000 custom fields and 24 MiB of notes.

Verified on 2026-10-02 using the public API 32 x86_64 emulator, isolated user 10:

- Main: debug app/test packages built; **29/29 tests passed**.
- F-Droid: debug app/test packages built; **29/29 tests passed**.
- Both editions' light/dark native screenshots were inspected. The 320dp, 150%
  font case retains two rows while scrolling through 80 custom fields.
- Initial device sleep prevented test access to the vault/UI. Final runs kept the
  device awake without bypassing production lock checks. A test-only lazy-list
  locator was corrected before the final passing runs.
- Original test-user settings and active user were restored. The emulator started
  by this task was stopped, preserving its data disk.

See [`validation.json`](validation.json) for APK/source hashes,
[`main-device-tests.log`](main-device-tests.log) and
[`fdroid-device-tests.log`](fdroid-device-tests.log) for final test results.
[`performance.log`](performance.log) records the synthetic benchmark, including
earlier runs under build contention; these emulator timings are not physical-device
performance guarantees. No additional Rust dependency was introduced.

Native previews: [large text](main-custom-fields-dark-large.png),
[last fields after horizontal scroll](main-custom-fields-scrolled.png),
[custom-only entry](main-custom-only-light.png),
[narrow common-action row](main-narrow-website.png).

## Row marker removal

Removed the decorative list marker and its spacing at the user’s request. Both editions rebuilt and passed the two existing custom-field UI cases on API 32, covering the 80-field horizontal row and custom-only/retry behavior. Inspected updated native rendering; protected-field locks remain. See [verification](icon-removal-validation.json), [large-text preview](main-no-label-custom-fields-dark-large.png) and [custom-only preview](main-no-label-custom-only-light.png). The original 29-case results above precede this visual-only cleanup.
