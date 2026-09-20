# GPG result actions verification — 2026-09-19

Build: 1.0.313-26091912-02, debug, x86_64. Android 32 shared AVD Monica_Issue136_API_32.

- assembleDebug + assembleDebugAndroidTest: PASS.
- GpgKeyFlowTest: 8 PASS (card/more menu, all copy/export callbacks, actual clipboard, create entry, public-only actions, dark 1.5x text, generator routing, save/reload).
- GpgCryptoDeviceTest: 3 PASS.
- GpgStorageDeviceTest: 1 PASS (real KDBX write/reopen, stale public chunk removal, unrelated custom field retention, encrypted private storage).
- VaultOverviewNativeTest: 4 PASS (real Rust JNI correctness and timing).
- Final instrumentation: OK (16 tests), 38.716 seconds.

Visual review: current/result-actions.png and current/result-actions-dark-large.png. Editable M3E Canvas: gpg-m3e.md.

Export menu callbacks are covered; an end-to-end system document picker file-write test was not performed in this run. Export retains the existing private-key passphrase protection.
