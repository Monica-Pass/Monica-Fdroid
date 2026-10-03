# Local vault recovery (1.0.316, unreleased)

The primary target is the offline Monica local database, including unsynced data. This is not cloud account recovery. No database, preference file, or Android Keystore alias may be deleted because an open failed.

## Design

- After a successful password unlock, create an independent AES-GCM recovery envelope. PBKDF2-HMAC-SHA256 (600,000 rounds, random 32-byte salt) protects a random recovery key. That key protects a typed snapshot of Monica secure preferences, including the password-wrapped MDK and exportable Passkey private keys.
- Keep the legacy secure preference names and item ciphertext format during normal operation. Mirror secure-preference changes into the envelope, including newly created Passkeys. Never store an unencrypted password, MDK, token, or private key in the recovery directory.
- Password changes atomically replace the recovery header and preference snapshot before mirroring the new credentials to the legacy store. A credential generation mismatch blocks the old store until recovery completes. Old passwords must not remain usable via a stale recovery header.
- Recovery verifies the password, envelope authentication and MDK before preparing a new, separately named secure-preference generation. Validate it before atomically selecting it; preserve the original store and keys. If Android cannot create any new secure key, stop with originals intact.
- Isolate unreadable Bitwarden preference settings from the local vault. Do not silently overwrite those settings or enable automatic cloud synchronization.
- Existing C2/V2 fields depend on non-exportable device keys. They need verified, resumable conversion to the existing MDK format while those keys still work. A lost hardware-only Passkey cannot be reconstructed. Do not report full recovery for these cases without verification.

## Migration and recovery behavior

Authenticated maintenance starts on the IO dispatcher after the existing 15-second delay. SQL conversion uses 64-row batches, checks cancellation/unlock state, verifies the plaintext round trip, and updates only when the original ciphertext still matches. This preserves concurrent edits, timestamps, row IDs and synchronization ownership. Interrupted conversion resumes from the remaining legacy values. Invalid ciphertext stays unchanged. Historical OTP JSON can contain nested device-encrypted strings, including inside an MDK-encrypted outer object; authenticated nested values are converted without dropping unknown members.

The scan covers password secrets, OTP bindings, legacy payment secrets, SSH data, secure-item payloads, custom fields, password history, attachment content-key wrappers, operation-log payloads, saved KeePass/MDBX passwords and Steam secrets. Common-account and generator-history DataStores and managed KeePass keyfile copies have their own verified conversion. Each recovered preference wrapper is tied to its active generation: an old manager cannot write over a newly selected recovery store.

## Device validation, 2026-10-02

Public AVD: `Monica_Issue136_API_32`, Android 12L / API 32, x86_64. Tests used dedicated Android users 11 and 12, retaining Owner and user 10 data. Key deletion was explicitly restricted to the synthetic fixture's app UID; the original preference XML was compared byte-for-byte before and after recovery.

The old APKs are **pre-change 1.0.316 builds**, not published 1.0.315:

| Distribution | Old version | SHA-256 |
| --- | --- | --- |
| Ordinary | 1.0.316-26100112-01, code 12 | `15d2a36aab9fbcfc8a555a146338484e2bb49e313112c800a9b3fcfc4ff39d30` |
| F-Droid | 1.0.316, code 23 | `8630cb906c39c00d1d6fda86e1b6d63328bc870444dbc13451f4d1156d3326f1` |

`LegacyVaultCorpusTest` creates the records by invoking the **installed old APK's** storage APIs, rather than generating a new-version database and calling it an upgrade. Separate instrumentation invocations and APK cover-installs provide real process death between phases; app data is never cleared.

| Corpus | Coverage |
| --- | --- |
| 9 password records | Explicit multiple-password group, Wi-Fi, API Key, GPG, SSH, legacy SSO, archive and trash; whitespace, line breaks, Unicode and a NUL in a protected custom field |
| One full password project | All five content-block kinds: API Key, API Token, SSH, GPG and QR code; full bank card, document, billing-address and Markdown-note copies, content order, history, OTP, contact/payment fields and custom icon |
| 19 secure items | All five OTP types, bank card, billing address, note, all five document types and all six payment-account types |
| Binary assets | 26 attachment/card-face blobs checked byte-for-byte; actual embedded copy service used, with manifest SHA-256 checks |
| Cryptographic material | Generated Ed25519 SSH, RSA OpenPGP and EC Passkey; recovered Passkey signs a challenge verified against the old public key |
| Other local stores | Native MDBX2 API Token with binary attachment, saved MDBX unlock password, Steam secrets, common account, generated-password history and managed KeePass keyfile |

Passed in **both distributions**: old creation and restart; cover-upgrade and full-column comparison; normal rollback before any key loss; deliberate loss of the old Android Keystore aliases; wrong-password rejection without changing originals; correct-password recovery; another process restart; editing/reloading every data type and embedded content; full encrypted backup application through `BackupRestoreApplier`; restored attachment bytes, manifests and Passkey signature after another restart.

Each distribution also passed **29 safety regressions**: 14 recovery/migration cases plus startup, concurrent-edit and transactional local-replacement cases. These include damaged envelopes, interrupted credential mirrors, password rotation, stale manager writes, older-APK protected writes, missing MDK, failed storage writes, concurrent writes, a recovered generation losing its own key, and cancellation/resumption of 1,024 legacy rows with every value and timestamp checked.

Each distribution passed **10 Compose/IME tests**, covering recovery errors, no automatic recovery attempt, cleared password input after a failed attempt, large-font light/dark action reachability, and password confirmation/IME submission. The first ordinary UI run was obstructed by the newly switched Android user's system keyguard (no window focus/Compose hierarchy); the failed log was retained, and all 10 tests passed after unlocking that isolated user. Screenshots are in `docs/design/local-vault-recovery-316/`.

Encrypted archive upload/download/decrypt round trips passed through local HTTP test servers: ordinary WebDAV and Graph/OneDrive transport (2 cases), and F-Droid WebDAV (1 case). These are **MockWebServer transport tests**, not authenticated live OneDrive or third-party WebDAV account tests. F-Droid does not ship OneDrive.

Both Debug and R8-minified Release builds completed. The x86_64 release outputs were copied/signature-wrapped with the standard local debug key **only for the isolated smoke test**, then cover-installed over the recovered fixtures. Both real `MainActivity` flows accepted the fixture master password and opened the vault with its native token visible; the crash log was empty. No production signing, upload or release publication was performed. The ordinary R8 build emitted Kotlin-metadata compatibility warnings but completed successfully; this run does not claim that all unrelated application code paths were exercised under R8.

Native screenshots: [recovery error](../design/local-vault-recovery-316/main-local-recovery-error.png), [large-text dark screen](../design/local-vault-recovery-316/fdroid-secure-startup-dark.png). Editable local design: [Canvas source](../design/local-vault-recovery-316/canvas.json), [local editor URL](../design/local-vault-recovery-316/canvas-url.txt).

## Backup findings fixed during the corpus test

- Billing addresses/payment accounts must retain their explicit type before legacy `cards_docs` heuristics.
- The real local-backup password query must include archived records while exporting trash through its separate path.
- Restore writes password history through the database it owns, even when a supplied repository lacks the optional history DAO.
- Full-backup root-level and nested trash records are staged with live rows and committed by the replacement transaction. The data-only import option still omits full-backup trash by design.

Initial test failures and immutable before/after snapshots are retained in `.codex-tmp/local-recovery-316/`; they were not counted as passes. The ordinary backup retry restored the archive saved **before** the failed attempt, compared against its original snapshot, and rechecked payloads. Ordered custom-field arrays are compared by ordinal position because backup does not preserve arbitrary gaps/ties in numeric `sortOrder`; new row IDs and attachment encryption wrappers are expected to change during restoration.

## Reproduction

Build `:app:assembleDebug :app:assembleDebugAndroidTest -PincludeX86TestAbi`. Install the old app APK and current instrumentation APK in an empty, dedicated Android test user. Use `-e isolatedRecoveryUser yes` for the corpus phases. Never run the key-loss phase in a real user's app UID.

Run `LegacyVaultCorpusTest#seedOldApk`, `#completeOldApkExtras`, and `#verifyAfterProcessRestart` against the old app. Cover-install the new app; run `LocalVaultUpgradeTest#migrateAndVerifyEveryOldItem`. For rollback, reinstall the old APK and verify, then return to the new APK and migrate again. Run `#loseOnlyFixtureUsersKeystoreKeys`, `#recoverAllLocalDataAfterKeystoreLoss`, the corpus restart verifier, and `#editEveryTypeAndEveryEmbeddedContentAfterUpgrade`. Run `#fullLocalBackupRestoreAfterRecovery` **last**, because it legitimately reallocates row IDs, followed by `#verifyRestoredPayloadsAfterProcessRestart`. Check the expected nonzero test count; adb exit code alone does not establish success.

## Compatibility boundaries

### Connection and external database audit

The additional `ConnectionRecoveryInstrumentedTest` uses uniquely named preference stores, a private filesystem context and an in-memory Room database. It generates historical `C2|` values using the existing compatibility codec/key, then converts them and invalidates the synthetic encrypted preference keyset. This is an additional configuration/file regression, **not another old-APK upgrade or deletion of the real app's Keystore aliases**. The earlier installed-APK corpus supplies the upgrade/key-loss evidence above.

Its matrix covers:

- WebDAV server/account/password and archive password, including significant whitespace; Bitwarden settings, all five stored account key/token fields, server endpoints and failed pending operations. The previous settings file remains unchanged, stale managers cannot overwrite recovered settings, and recovery disables Bitwarden automatic sync and never-lock mode. Normal writes continue to mirror the original preference namespace for pre-failure rollback.
- KeePass 3 and 4 files with password-only, keyfile-only and password+keyfile credentials; managed 32-byte keyfile copies, WebDAV source bindings, pending-upload flags and timestamps. Reopen each freshly created file before migration and compare file bytes after recovery.
- Native MDBX2 files in all four unlock modes, containing a synthetic native project. Read through the production session executor using stored credentials after recovery; preserve file SHA-256 before reopening and registration/sync metadata. The device-key tag stays intact. MDBX1 records/files are not deleted or re-enabled.
- Bitwarden offline fallback secrets and encrypted raw-response records; KeePass credential transitions older than seven days and invalid ciphertext. Invalid values remain unchanged and conversion is idempotent. Only the protected local representation changes, not queue acknowledgements or remote state.

Final device results for this extension: **4/4 connection groups, 29/29 recovery/startup/concurrency/transaction regressions, and the existing complete restored-payload verifier passed in each distribution**. Local HTTP encrypted-archive round trips passed again (ordinary 2/2, F-Droid WebDAV 1/1). `KeePassCredentialSupportTest` passed 5/5 unit cases in each build. Logs are `connections-*.log`, `connections-regression-*.log` and `build-connections-*.log` under `.codex-tmp/local-recovery-316/`. The first KDBX failure and a stale F-Droid APK/new-test mismatch were retained separately; neither was counted as a pass. Final runs installed the rebuilt matching app/test APKs. The emulator crash buffer contained a system Bluetooth-service failure during user switching, with no Monica application crash recorded in that buffer. Both updated Debug and R8 Release builds completed. The new connection/device regressions ran against Debug; the earlier release smoke test above predates this extension. The ordinary release compiler retained its existing Kotlin-metadata warnings. The task-started public emulator was switched back to user 0 and stopped with its data disks and users retained.

An initial KDBX test failed before migration. Investigation of kotpass 0.10.0 found that `Credentials.from(ByteArray)` passes raw 32-byte keys to `EncryptedValue.fromBinary`, which masks the supplied array **in place**. The same pattern existed in production credential creation and candidate construction. All production keyfile credential boundaries now pass independent copies; a unit regression checks repeated exact/candidate creation, every raw candidate and the unchanged original array. This prevents future corruption; it does not reconstruct a previously lost original key file.

WebDAV protected configuration fields now commit as one set before old plaintext preference entries are removed. KeePass credential transitions commit before file rewriting can begin and remain available until the caller verifies/clears them; failed decryption and elapsed time no longer discard the fallback.

**Scope limits:** MDBX keyfile URIs still require the original document/provider permission, and device-key unlock remains subject to native device policy. A local recovery envelope cannot restore deleted external files or revoked SAF grants. Cloud-account revocation/expired tokens and Android KeyChain client-certificate aliases may require reauthorization. No live Bitwarden/WebDAV account is contacted by this fixture.

**Separate existing security finding:** The multi-account `BitwardenRepository.encryptForStorage`/`decryptFromStorage` path currently stores its Room token/key fields using Base64, not cryptographic encryption. Those values do not depend on the lost Android Keystore, and this recovery change preserves their exact existing representation and tests their survival. This is not an endorsement of their at-rest confidentiality: hardening that format needs coordinated reader, backup and rollback changes. The legacy `SecurityManager` Bitwarden credential path uses actual encryption. Do not claim that every historical Bitwarden credential store is encrypted based on its field name.

No schema or backup export format changes are intended. A pre-recovery old APK does not understand the new active-store selector after a device-key failure. An old APK also cannot maintain the recovery envelope; users must successfully unlock in the new version after using an older APK, especially after changing the master password. Standard encrypted backups remain necessary. The recovery envelope contains keys/configuration, not a copy of the database or attachment files.

This is not a guarantee against all device faults. Published 1.0.315, physical OEM/root/keybox environments, every Android API and arbitrary power loss at every filesystem sync boundary were not covered by this run. A device that cannot create any new Keystore key cannot finish recovery. Data whose non-exportable key was already lost before enrollment/conversion, or whose database/attachment files have been deleted, cannot be reconstructed by this mechanism. Complete a password unlock and migration while the old keys still work; preserve normal encrypted backups.
