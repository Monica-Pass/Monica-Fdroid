# MDBX Android Integration

Language: [简体中文](README.zh-CN.md) | [English](README.md)

This document describes how Monica for Android currently integrates MDBX 1.0.
Use it as a reference implementation note, not as the normative format spec.

Normative format rules live in [`docs/`](../README.md). General client
rules live in [`CLIENT_INTEGRATION_GUIDE.md`](../../CLIENT_INTEGRATION_GUIDE.md).
The generic UniFFI boundary lives in
[`crates/mdbx-ffi/README.md`](../../crates/mdbx-ffi/README.md).

## Android Release Assets

To package already-built ABI libraries without compiling again, run:

```powershell
pwsh -File scripts/package-mdbx2-android-release.ps1
```

The script writes the four stable release names under
`target/release-assets/`:

```text
libmdbx_ffi_arm64-v8a.so
libmdbx_ffi_armeabi-v7a.so
libmdbx_ffi_x86_x64.so
mdbx2-android-release.zip
```

The public `x86_x64` spelling is kept for compatibility; the ZIP keeps the
standard Android `x86_64/` directory and `libmdbx_ffi.so` filename inside.
Only release staging assets are replaced; the canonical `jniLibs` files are
left unchanged.

## Principles

Android MDBX integration must preserve:

- 4ever And 4ever compatibility: old test-version vaults remain readable.
- Data safety before convenience: failed preparation, import, sync, or conflict resolution must not leave half-written state.
- Sky portability without treating Sky as unsafe.
- No default mandatory key file or hardware key.
- No direct MDBX table writes from UI or ViewModel code.
- Folder-aware moves for Password, TOTP, Note, Document, Card, and Passkey records.
- Local, WebDAV, and OneDrive writes through a local working copy followed by source-specific flush.

## Current Boundary

Monica for Android does not currently call `mdbx-ffi` directly. It has an
Android-side facade:

- `MdbxRepository`
  - App-facing MDBX operation boundary.
  - Declares folder, entry, secure item, passkey, attachment, tag/search, history, snapshot, sync bundle, conflict, and diagnostics operations.
- `MdbxVaultStore`
  - Current Android SQLite facade implementation.
  - Owns MDBX 1.0 metadata, legacy preparation, locks, transactions, commits, object versions, tombstones, snapshots, conflicts, sync state, and working-copy flush.
- `MdbxViewModel`
  - Owns UI state, local/remote create/open flows, Room import, and active-vault preload.
  - Must not become the owner of low-level format rules.
- `LocalMdbxDatabase`
  - Room-side local index, not the MDBX vault itself.
  - Stores file path, working-copy path, source type, source id, Tiga mode, unlock method, and sync status.
- `MdbxFileSource`
  - File-source abstraction for WebDAV and OneDrive.
  - Reads/writes remote bytes but does not interpret MDBX internals.

If Android later migrates to `mdbx-ffi`, keep `MdbxRepository` stable and
replace `MdbxVaultStore` internals gradually. Do not let UI code call raw FFI
or SQL directly.

## Important Android Files

In the Monica for Android repository:

- `app/src/main/java/takagi/ru/monica/data/LocalMdbxDatabase.kt`
- `app/src/main/java/takagi/ru/monica/repository/MdbxRepository.kt`
- `app/src/main/java/takagi/ru/monica/repository/MdbxVaultStore.kt`
- `app/src/main/java/takagi/ru/monica/viewmodel/MdbxViewModel.kt`
- `app/src/main/java/takagi/ru/monica/utils/MdbxFileSource.kt`
- `app/src/main/java/takagi/ru/monica/utils/WebDavMdbxFileSource.kt`
- `app/src/main/java/takagi/ru/monica/utils/OneDriveMdbxFileSource.kt`
- `app/src/main/java/takagi/ru/monica/ui/screens/MdbxManagerScreen.kt`
- `docs/MDBX_1_ANDROID_ACCEPTANCE.md`

## Data Mapping

Android keeps two layers:

1. The `.mdbx` vault file
   - The source of truth for projects, entries, attachments, commits, tombstones, snapshots, conflicts, and sync state.
2. Android Room indexes
   - `local_mdbx_databases` records how this device finds and syncs a vault.
   - `mdbxDatabaseId` / `mdbxFolderId` fields on app records support Android lists and move/copy UI.

Writes must update both the MDBX vault and the Android-visible indexes. Root
folders should not be persisted as ordinary folder ids; non-root folders must
round-trip through payload, project/object metadata, and Room import.

## Create, Open, And Legacy Preparation

New Android-created MDBX 1.0 vaults must write:

- `format_version = MDBX-1`
- `release_label = MDBX-1.0`
- capability flags such as `android-official-1.0`, `sky-portable`, `tiga-selectable`, and `legacy-test-compatible`

Opening an old test-version vault must remain additive:

- `MDBX-1-DRAFT` remains readable.
- Preparation may add release labels, capability flags, and missing credential material.
- Preparation must not destroy old data or force a new key-file/hardware-key prompt.

The current Android flows cover local internal files, SAF/local external files,
WebDAV, and OneDrive.

## Working Copy And Remote Flush

Remote vaults should not be edited directly in place. The Android pattern is:

1. Download or create a local working copy.
2. Apply all SQLite writes to the working copy.
3. Hold a per-vault write lock across mutation and flush.
4. Commit/object-version/head/tombstone changes succeed or roll back together.
5. Checkpoint the working copy.
6. Flush to local external storage, WebDAV, or OneDrive according to `sourceType`.
7. Update `lastSyncStatus` and `lastSyncError`.

Avoid reporting success when the remote flush failed.

## Tiga And Unlock

Android exposes:

- `POWER`: strongest protection.
- `MULTI`: balanced default.
- `SKY`: flexible, portable, low-friction, and still secure.

Unlock methods include master password, key file, master password + key file,
and device key. Sky must not require a key file by default. Hardware keys can
strengthen Multi/Power policies without making Sky portability unsafe.

## UI Requirements

A complete Android integration should provide:

- MDBX 1.0 management home.
- Local / WebDAV / OneDrive create and open flows.
- Database detail and diagnostics.
- Folder / structure management.
- Move / copy target picker.
- Conflict management.
- Commit history and diff.
- Snapshot create, preview, and restore.
- Tags/search.
- Pending sync and flush actions.

The MDBX management entry should open the management home, not silently jump
into the last opened vault detail screen.

## Tests And Acceptance

Keep these layers:

- JVM guard tests such as `MdbxAndroidIntegrationGuardTest`.
- Instrumentation tests such as `MdbxVaultStoreInstrumentedCompatibilityTest`.
- Device/manual acceptance recorded in Monica Android's `docs/MDBX_1_ANDROID_ACCEPTANCE.md`.

Run instrumentation only on a dedicated test device or disposable AVD. Do not
install, uninstall, clear app data, or run instrumentation on a user's daily
AVD without explicit approval.

## No Regressions

Do not regress these behaviors:

- `release_label = MDBX-1.0` stays separate from the low-level schema token.
- Legacy test-version vaults remain minimally readable.
- Key files and hardware keys are not mandatory by default.
- Old sync bundles without `project_tags` do not clear local tags.
- Folder-aware moves preserve both `mdbxDatabaseId` and `mdbxFolderId`.
- UI, ViewModel, and Room DAO code do not directly maintain MDBX commit/tombstone/snapshot/conflict/project_tags tables.
- Remote flush failures are not reported as complete success.

## Future FFI Migration

Recommended path:

1. Keep `MdbxRepository` as the Android app-facing facade.
2. Replace create/open/unlock/project/entry internals with FFI first.
3. Extend FFI for tags, attachments, sync bundles, conflicts, snapshots, and diagnostics.
4. Add Rust FFI smoke tests and Android guard tests for every new FFI method.
5. Keep Room indexes as Android UI cache, not as the MDBX format source of truth.
6. Continue opening `MDBX-1-DRAFT` and Android-created `MDBX-1.0` vaults during migration.
