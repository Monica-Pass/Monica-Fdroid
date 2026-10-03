# Passkey move result dialog — 1.0.316

The previous batch callback combined bound entries, reference-only entries and all operation failures into a skipped count, and labeled successful moves as selected items. Both single and batch moves now use a report with display-only account/site metadata and classified reasons. A result dialog is shown when any item needs attention; successful batches use moved-item wording.

## Behavior

- Bound password entries and references retain their original restrictions and never reach the write callback.
- Bitwarden mode/private-key restrictions and duplicate KeePass credentials have distinct guidance.
- Unknown errors are explicitly described as undetermined. No raw exception, key alias, private key, or credential ID is included in the report.
- A successfully saved destination followed by failed KeePass source cleanup or Bitwarden deletion queueing is labeled as a partial operation, without offering automatic retry or deletion.
- Cancellation stops the batch; repeated submissions are guarded while a move is in progress.
- The dialog uses the existing backup-diagnostics layout: a 28dp outer shape, connected item groups, a scrollable list and a fixed Close button. Large text and both themes are covered by the UI test.
- No schema, backup format, key encoding or move/delete persistence order changes.

## Design

Editable local M3E Canvas document: `canvas.json`; local fragment link: `canvas-url.txt`. Uses the built-in dialog and listItem components. Browser rendering was unavailable because the Codex browser connector failed authentication; native rendering is validated separately.

## Validation

Both distributions passed 8 focused JVM tests (4 move-report cases and 4 existing batch move safety cases) and 2 instrumented UI tests on the public API 32 x86_64 emulator. The UI suite checks a zero-success result, its reason, dismissal, an 18-item report at 1.5x text, scrolling, and a reachable Close button in light/dark themes. The localized suite also asserts the Chinese title after supplying Compose LocalResources explicitly.

ARM Debug packages, x86_64 emulator packages and instrumentation packages built successfully. F-Droid incremental packaging initially failed while adding the emulator ABI; the identical packaging retry passed without code changes. No Release/R8 build was required for this UI reporting change. The existing emulator was reused and left running; installs retained app data. Tests used synthetic reports and move callbacks, not the affected user's passkeys or real remote-account moves.

Screenshots: `android-single.png`, `android-light.png`, `android-dark.png`. Structured results: `test-results.json`.
