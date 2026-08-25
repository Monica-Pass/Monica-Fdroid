# MDBX

Language: [简体中文](README.md) | [English](README.en.md)

This directory contains the Rust workspace and implementation notes for Monica MDBX.

MDBX is Monica's local-first advanced encrypted database core. It provides durable authenticated storage, Git-like logical history, synchronization conflict handling, snapshots, and Tiga security policy for versioned Collections, ObjectRecords, and binary content. Passwords, bookmarks, mail, Steam `mafile`, and future domains are trimmable domain Adapters.

The current format generation is **MDBX2**, stored as `MDBX-2`. MDBX2 automatically and transactionally upgrades `MDBX-1` and `MDBX-1-DRAFT` vaults. See `docs/09-mdbx2-compatibility.md` for the compatibility contract.

For the normative format documents, see `docs/`.

The MDBX rule is **4ever And 4ever**: old vaults must remain readable, compatibility paths must be preserved whenever possible, and data safety comes before convenience.

## Workspace Layout

- `crates/mdbx-core`
  - Core domain types.
- `crates/mdbx-crypto`
  - Encryption, KDF, and key material handling.
- `crates/mdbx-sync`
  - Sync payload and object payload model.
- `crates/mdbx-storage`
  - SQLite schema, vault initialization, repositories, search, snapshots, conflicts, recovery, and sync state.
- `crates/mdbx-ffi`
  - Generic UniFFI boundary exposing Vault, Collection Profile, and ObjectRecord operations; domain payload semantics remain owned by each Adapter.
- `crates/mdbx-cli`
  - CLI entry point for local testing and development.
- crate-local `tests/`
  - Compatibility, crypto-vector, concurrency, and recovery scenarios live beside the crates they validate.

## Documents In This Directory

- `CLIENT_INTEGRATION_GUIDE.md`
  - English guide for implementing MDBX support in another client.
- `CLIENT_INTEGRATION_GUIDE.zh-CN.md`
  - Chinese guide for implementing MDBX support in another client.
- `crates/mdbx-ffi/README.md` / `crates/mdbx-ffi/README.zh-CN.md`
  - UniFFI boundary reference for non-Rust clients.
- `docs/android/README.md` / `docs/android/README.zh-CN.md`
  - Current Monica for Android MDBX 1.0 integration structure, working-copy model, Room indexes, and future FFI migration notes.

## Specification Documents

Read the spec set in `docs/` before changing storage behavior:

- `docs/README.md` / `docs/README.zh-CN.md`
- `docs/01-product-spec.md`
- `docs/02-storage-sync-spec.md`
- `docs/03-security-spec.md`
- `docs/06-sqlite-schema-v1.zh-CN.md`
- `docs/09-mdbx2-compatibility.md`

The `docs/` directory defines the format and product constraints. The Rust workspace implements those constraints and documents practical integration.

## Client Support Levels

MDBX support should be labeled honestly:

- **Read-only support**
  - Open and unlock a vault.
  - Display folders, entries, and attachment metadata.
  - Do not write tables, commits, tombstones, snapshots, or conflicts.
- **Basic read/write support**
  - Create and edit entries and folders.
  - Preserve commits, object versions, tombstones, snapshots, branch heads, and device heads.
- **Sync support**
  - Merge commit DAGs, preserve tombstones, detect conflicts, and apply sync state safely.
- **Full Monica-compatible support**
  - Provide the required management screens, diagnostics, snapshot structure preview, field-level history, and folder-aware move/copy/create flows.

See `CLIENT_INTEGRATION_GUIDE.md` for the complete checklist.

## Required User-Facing Management Screens

A full user-facing client should include:

- MDBX format-management home
- database detail page
- folder / structure management
- move / copy target picker
- conflict management
- commit history
- snapshots
- snapshot structure preview
- diagnostics / maintenance
- unlock and security

The format-management entry should always land on the MDBX management home. It should not automatically enter the last opened vault detail page.

Normal users should not see raw developer tools such as sync bundle import/export, benchmarks, or low-level chunk debugging. Keep those behind developer mode.

## Development Commands

From this directory:

```powershell
cargo test
```

Run the CLI during local development:

```powershell
cargo run -p mdbx-cli -- --help
```

## Merge Gates

Pushes and pull requests targeting `master` run `.github/workflows/ci.yml` on
Linux with Rust 1.86.0. All eight gates must pass:

```text
cargo fmt --all -- --check
git diff --check <empty-tree> HEAD
cargo clippy --workspace --all-targets --all-features -- -D warnings
cargo test --workspace --no-fail-fast
cargo test -p mdbx-storage --no-default-features --features core
cargo check --workspace --all-targets
cargo check -p mdbx-cli --no-default-features --features core
cargo check -p mdbx-ffi --no-default-features
```

The two `--no-default-features` checks and the core-profile test exist to catch
trimmed-build breakage that an all-features workspace build hides. A green
workspace build is not a reason to skip them.

The current `mdbx-cli` is a development and validation entry point for this Rust workspace. It covers:

- `init` / `unlock`
- basic project, entry, and attachment CRUD
- `snapshot create/list/restore`
- `sync bundle/apply`
- `health`
- `capabilities` / `capabilities --json`
- `benchmark`
- `import-kdbx` / `export-kdbx`
- `import-kdbx-json` / `export-kdbx-json`

`import-kdbx` reads KDBX3/KDBX4 and `export-kdbx` writes KDBX4 with Argon2id. A KDBX password is accepted only through a hidden interactive prompt or `--password-stdin`. Export publishes from a temporary sibling and preserves an existing destination. `import-kdbx-json` / `export-kdbx-json` retain the existing JSON intermediate representation and unchanged feature semantics. Once a vault has unlock methods configured, normal CLI operations must pass `--unlock-password` or `--unlock-pin`; otherwise the command is rejected so production writes do not silently fall back to the legacy plaintext compatibility path.

`mdbx capabilities` does not open a vault. It reports the storage and sync
modules compiled into the current binary; `--json` is suitable for installation
checks. The report does not replace Collection Adapter registration, vault
critical-extension validation, or sync-session capability negotiation.

The current CLI does not yet implement real FIDO/WebAuthn/security-key interaction, production session tokens, or audit policy. Security-key support in storage core is a key-material abstraction with policy tests, not an end-to-end hardware-key client.

`mdbx-ffi` provides a generic UniFFI boundary for non-Rust clients that need MDBX core read/write operations. It is not a low-level SQL escape hatch around the storage/repository rules; new cross-client capabilities should extend the FFI facade instead of writing tables directly.

For exported methods, JSON payload rules, binding generation, iOS packaging notes, and extension rules, see `crates/mdbx-ffi/README.md`.

Key capabilities currently verified in the Rust storage core:

- Writable open automatically upgrades MDBX-1 and MDBX-1-DRAFT vaults to MDBX-2; migration is idempotent and unknown critical extensions block writes.
- Tiga2 expands Sky/Multi/Power into versioned runtime policies for sessions, reveal, clipboard, export, attachments, recovery, device assurance, and audit.
- Scoped policy changes strengthen by default. Exact exceptions, authorization decisions, and audit events sync across devices, with stricter conflict resolution.
- MDBX-1 and early MDBX-2 schema 2 vaults upgrade atomically to schema 3. Legacy weak overrides and non-compliant unlock setups enter remediation without locking users out.
- Snapshot creation and restore are atomic. Restore recreates the exact active set, tags, and attachment chunks and records one restore head plus object versions for affected objects.
- CLI sync bundles use the storage-core state payload and `SyncApplyRepo`; the duplicate direct-SQL apply path has been removed.

- Snapshots include and restore active `attachment_chunks`; older metadata-only snapshots remain compatible.
- Entry, project, and attachment rows are recorded in `object_versions` for divergent three-way merge.
- Different-field concurrent entry/project changes write a two-parent merge commit; same-field changes create unresolved conflicts.
- Attachment metadata can merge at field level; concurrent content replacement keeps the local content and records a `content_hash` conflict.
- Entry, project, and attachment conflict resolution now has repository write-back APIs. Resolving a conflict writes a merge commit, updates the object head, records an object version, and then marks the conflict resolved. Attachment incoming-wins never fabricates remote content when the bytes are not locally available.
- High-risk user-visible project, entry, and attachment mutations are wrapped in atomic transactions so commits, object rows, heads, and object versions succeed or roll back together.
- `project_tags` are included in sync state. New payloads carry the complete tag set for each project, while old payloads that lack the tag field do not clear local tags. User-visible tag changes should use tracked tag APIs; temporary session search indexes do not enter history.
- Initial key epochs use a random `mdbx-init-marker-v1` marker; configuring or changing an unlock method binds `mdbx-active-key-epoch-v1` active epoch wrapping. Full key rotation / retirement remains future work.
- New fields written after an official unlock use the epoch-tagged `MDBXFE2` outer envelope. Existing `MDBXAE1` ciphertext remains readable, while the `field-key-epochs-v1` critical extension prevents older MDBX2 writers from overwriting fields that use the new format.

## Implementation Rules

Do not bypass repository/storage APIs from client code unless you are changing the storage layer itself.

Compatibility and recovery are implementation requirements, not polish. New encryption envelopes, tables, indexes, unlock methods, and Tiga policies must keep old vault readability unless a critical security issue requires a deliberate migration.

Client code should not directly write:

- `commits`
- `commit_parents`
- `object_versions`
- `tombstones`
- `snapshots`
- `key_epochs`
- `conflicts`
- `device_heads`
- `branches`
- `project_tags`
- `tiga_policy_overrides`
- `tiga_policy_exceptions`
- `security_audit_events`

Batch user operations should normally produce one user-level commit, not one commit per object.

Android and other clients should use repository/storage APIs for entry/project/attachment CRUD, tracked tag changes, and conflict resolution. Do not only update `conflicts.resolution`, and do not edit `project_tags` directly while skipping commits and sync state.

For the current Monica for Android integration reference, see `docs/android/README.md`.

## Compatibility Checklist

Before claiming full support, a client should verify:

- Monica-created MDBX vaults open correctly.
- Nested folders can be created and selected as targets.
- Batch move/copy/delete creates coalesced commits.
- Tombstones prevent deleted objects from reappearing.
- Two clients show the same item count for the same vault.
- Conflicts are detected and displayed.
- Snapshots can be created and reverted with confirmation.
- Diagnostics expose sync, health, history, tombstone, attachment, and dangling-head status.
