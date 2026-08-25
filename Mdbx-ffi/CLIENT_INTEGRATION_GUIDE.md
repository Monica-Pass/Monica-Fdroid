# MDBX Client Integration Guide

This document is for implementers who want to support Monica MDBX in another client.

It does not replace the full format specifications. It answers three practical questions:

- What counts as a correct MDBX integration.
- Which management screens a user-facing client must provide.
- Which shortcuts will break sync, history, snapshots, recovery, or cross-client consistency.

Read these lower-level specs as well:

- `docs/01-product-spec.md`
- `docs/02-storage-sync-spec.md`
- `docs/03-security-spec.md`
- `docs/06-sqlite-schema-v1.zh-CN.md`

## 1. Integration Boundary

MDBX is not just a password table stored in a SQLite file.

A correct client must treat MDBX as a complete vault format, including:

- vault metadata
- unlock / key epochs
- Tiga security mode
- projects, folders, entries, and attachments
- tombstones
- commit DAG
- object versions
- snapshots
- conflicts
- sync state
- diagnostics and maintenance

A client may be read-only. Once a client supports writes, it must preserve history, tombstones, snapshots, and conflict metadata.

## 2. Recommended Integration Levels

### 2.1 L0: Read-Only Viewer

A read-only viewer may implement only:

- open `.mdbx` files
- unlock vaults
- read projects, folders, and entries
- read attachment metadata
- show the current head state

A read-only viewer must not:

- modify SQLite tables
- prune tombstones
- create commits
- fake snapshots
- auto-resolve conflicts

The UI should clearly mark the vault as read-only.

### 2.2 L1: Basic Read/Write Client

A basic read/write client must implement:

- vault creation
- open / unlock
- create, update, and delete entries
- create, update, and delete folders or project containers
- tombstone writes
- commit generation for every user-level mutation
- object version updates
- device head / branch head updates
- basic snapshot maintenance
- local display cache refresh

A basic read/write client must not create unnecessary per-object commits.

For example, when the user batch-moves 100 passwords into MDBX, that is one user-level operation. The client should create one batch commit whose changed object list contains all affected objects, not 100 commits and 100 automatic snapshots.

### 2.3 L2: Sync Client

A sync client must additionally implement:

- sync state read/write
- commit DAG merge
- parent commit validation
- concurrent edit detection
- conflict records
- three-way merge or field-level merge
- tombstone anti-resurrection logic
- attachment chunk / external hash reference verification
- pending upload flush
- remote state download and apply/replay

A sync client must not overwrite the whole vault by timestamp alone.

### 2.4 L3: Full Monica-Compatible Client

A full client should implement:

- Monica local category / quick-folder mapping
- nested folder create, move, and copy
- snapshot structure preview
- current-vs-snapshot structure comparison
- commit history details
- field-level change display
- conflict merge UI
- database diagnostics / maintenance UI
- WebDAV / OneDrive / local external file compatibility
- background preload for the currently selected vault, without preloading all configured vaults

## 3. Recommended Code Entry Points

The Rust workspace is split by responsibility:

- `crates/mdbx-core`
  - core domain types
- `crates/mdbx-crypto`
  - encryption, KDF, and key material handling
- `crates/mdbx-sync`
  - sync payload / object payload model
- `crates/mdbx-storage`
  - SQLite schema, vault initialization, repositories, search, snapshots, conflicts, recovery
- `crates/mdbx-ffi`
  - generic UniFFI facade for non-Rust clients; expose or extend this boundary before falling back to client-side SQL

Clients should prefer storage / repository APIs over hand-written SQL.

When using `mdbx-ffi`, treat it as the client boundary for Vault, Collection Profile, and ObjectRecord operations. A domain Adapter registers the ExtensionCapabilityIds actually present in the current process before mutating a profiled Collection. Missing Adapters preserve unknown ciphertext and must not be bypassed by false capability declarations. If a client needs tags, attachments, sync, conflicts, snapshots, or diagnostics through FFI, add explicit facade methods and tests instead of writing the corresponding SQLite tables from the client.

### 3.1 Bounded Collection Discovery

After reopening a vault, use `get_collection_summary`, `list_collection_summaries`, and `list_deleted_collection_summaries` to build the top-level navigation tree. These methods discover password, bookmark, mail, Steam, and future adapter Collections without remembering IDs or reading complete Projects. Each page contains at most 200 payload-free summaries and returns an opaque cursor no larger than 4096 bytes; the cursor is tied to its active/deleted query and must be discarded after metadata mutations.

`MdbxCollectionSummary` contains the Collection ID, title, optional CollectionProfile type/version, group/icon references, favorite/archive state, attachment count, head commit, deletion state, and update time. It never contains `summary_ct` or CollectionProfile payload. Call `default_presentation_metadata_limits` to discover the fixed display contract: 64 KiB title plaintext, 512-byte label name plaintext, and 4096 UTF-8-byte group/icon references. New screens should page summaries, then page Object and Label summaries; they should not use complete list methods as a default navigation path.

For Object navigation, use `list_object_summaries(collection_id, object_type_id,
page_size, cursor)` for active rows. Deleted rows have two additive paths:
`list_deleted_object_summaries` is scoped to one Collection and
`list_all_deleted_object_summaries` is a global tombstone view. Both return the
same payload-free `MdbxObjectSummary` record and 1–200 row bound. Cursors are
opaque and bound to active/deleted state, Collection scope, and optional
ObjectTypeId; never pass an active cursor to a deleted method or reconstruct one
with offsets. The CLI `entry deleted` command follows the global bounded path.
The complete `list_objects`/`list_deleted_entries` APIs remain available for
explicit payload workflows and are not removed for MDBX1 compatibility.

For attachment navigation, call `list_attachment_summaries(collection_id, object_id, page_size, cursor)` with `object_id = None` for a Collection or an Object ID for an Object, and call `list_deleted_attachment_summaries` for tombstones. `get_attachment_summary` is the metadata-only by-ID path. `default_attachment_presentation_limits` reports the 4096-byte file-name limit, 512-byte media-type limit, shared 128 KiB ciphertext-envelope allowance, 200-row page ceiling, and 4096-byte cursor ceiling. These pages never read attachment chunk or external-blob payloads, so a content-corruption report does not prevent the list screen from opening. Cursors are live positions: discard them after metadata changes and restart from the first page.

MDBX1 Collections without a Profile are valid and return empty type/version fields. A legacy title, label name, or reference outside the fixed presentation limit returns a resource-limit error only through the bounded summary path. The complete compatibility APIs remain available for explicit repair/export workflows and must not be removed or reinterpreted.

### 3.2 Bounded Conflict Queue Navigation

Conflict-management screens should call `list_unresolved_conflict_summaries`
with an optional core object type (`project`, `entry`, `attachment`,
`object-relation`, `object-label`, or `object-label-assignment`), a page size
from 1 through 200, and the cursor returned by the same query. Call
`default_conflict_summary_limits` at startup to discover the 200-row,
4096-byte-cursor, 64 KiB-field-JSON, 256-path, and 4096-byte-per-path
contract.

`MdbxConflictSummary` is for queue navigation and selection. It includes
stable object and commit identities, bounded field paths, resolution state, and
creation time, but it does not authorize plaintext disclosure or replace the
typed resolution methods. Cursors are live keyset positions ordered by
`created_at DESC, conflict_id DESC`; discard them after a sync, conflict
resolution, or other metadata mutation, and never reuse one with another type
filter. A malformed or oversized legacy field list may fail on this bounded
surface; the complete `list_unresolved_conflicts` method remains available for
an explicit repair/export path.

### 3.3 Bounded Snapshot Navigation

Snapshot-management screens should call `get_snapshot_summary(snapshot_id)`
for a metadata-only detail and `list_snapshot_summaries(page_size, cursor)`
for the queue. Call `default_snapshot_summary_limits` once to discover the
fixed 1–200 row page ceiling, 4096-byte cursor ceiling, and 4096-byte UTF-8
metadata-field ceiling. Pass the returned cursor only to the same list query;
it is a live keyset position ordered by `created_at DESC, snapshot_id DESC`,
not a snapshot token or authorization credential. Discard it after snapshot
creation, pruning, synchronization, or another metadata mutation.

`MdbxSnapshotSummary` contains the snapshot ID, base commit ID, descriptor hash,
creation time/device, and `snapshot_ciphertext_bytes`. The byte count is a
storage-size projection and does not prove that the encrypted payload is valid
or decryptable. The summary path never selects, decrypts, deserializes, or
verifies `snapshot_ct`, so a corrupt or very large payload does not block the
navigation screen. If the selected snapshot needs structure inspection,
integrity verification, export, or restore, call the existing complete
snapshot API explicitly and handle its authentication result separately.

The CLI `snapshot list` command already follows this bounded path. Existing
`SnapshotRepo` complete reads, creation, verification, and restore methods stay
available for MDBX1 clients and explicit recovery/repair workflows; a summary
resource-limit error does not delete, migrate, or reinterpret the underlying
snapshot row.

See `crates/mdbx-ffi/README.md` for the current exported API, JSON payload contract, UniFFI binding generation commands, iOS packaging notes, and rules for extending the facade.

For the current Monica for Android MDBX 1.0 integration reference, see `docs/android/README.md`. It documents the Android-side `MdbxRepository` / `MdbxVaultStore` boundary, Room indexes, working-copy model, WebDAV, OneDrive, legacy test-version vaults, and future FFI migration path.

Unless you are implementing the low-level storage library itself, client code should not directly write these tables:

- `commits`
- `commit_parents`
- `object_versions`
- `tombstones`
- `tombstone_acknowledgements`
- `snapshots`
- `key_epochs`
- `conflicts`
- `device_heads`
- `branches`
- `project_tags`
- `collection_profiles`

Direct writes to these tables often produce vaults that appear to save correctly but disagree across clients, lose delete history, explode history size, or fail snapshot recovery.

For Android integration, avoid treating MDBX as a set of ordinary Room tables. Entry/project/attachment create, edit, delete, move, and copy flows should use repository/storage APIs. User-visible tag changes should use tracked tag APIs. Conflict resolution should use the entry/project/attachment resolution APIs. Updating only `conflicts.resolution`, or editing `project_tags` directly, is not a complete write because it skips commits, object versions, device heads, branch heads, or sync state.

The current storage-core boundary does not require mandatory hardware keys by default and does not add extra unlock prompts. Sky is a flexible and portable Tiga mode that remains secure, suitable for cloud-drive sync and recovery-first multi-device use. Hardware keys can strengthen Multi/Power policies without making Sky portability unsafe.

## 4. Write Rules

### 4.1 User-Level Operations Map To Commits

Commit granularity should follow user intent, not internal object count.

These operations must be coalesced into one commit:

- batch delete
- batch move
- batch copy
- batch import
- importing a KDBX folder
- migrating a group of entries from Monica local storage
- moving a folder together with its children

These operations may become multiple commits:

- the user explicitly saves multiple times
- a long transaction is interrupted and resumed
- the client must split work for memory reasons and the UI clearly presents multiple batches

MDBX2 writing clients SHOULD generate a stable `operation_id` when a user action starts and submit
it through `CommitOperation` / `CommitContext::create_operation_commit`. A retry after a timeout or
process restart must reuse that ID; storage returns the original commit idempotently. The same ID
must never be reused for different request content.

Treat the complete initial request as immutable retry state. Persist or retain
the original command list, message, parents, branch selector, and optional
`intent_hash` until the outcome is known; rebuilding a retry from the current UI
state can produce a different request and will be rejected. Clients do not
calculate or persist the database `request_hash`. Current storage derives a
versioned identity before mutations and compares it on every retry, including
requests without an explicit `intent_hash`.

`CommitOperation` also carries the `operation_kind`, target `branch_name`, object types, actions,
and field summaries. Storage atomically allocates the device `local_seq`, merges parent vector
clocks, writes the legacy `commits` compatibility projection, and advances device and selected
branch heads. Clients must not calculate `MAX(local_seq)+1` themselves.

For editor autosave, batch move, and batch import, clients SHOULD wrap one complete user action in
`CommitContext::run_operation`. Multiple `ProjectRepo`, `EntryRepo`, or `AttachmentRepo` writes in
the closure share one commit; a failed closure rolls the whole action back, and retrying a completed
operation returns its original commit without running the writes again. Keep the transaction bounded
to one finite user action, not the lifetime of an editor page. Two explicit user saves should be two
operations rather than an unbounded transaction.

UniFFI clients should use `execute_write_operation` or its branch-specific equivalent for bounded
multi-object changes. The compatibility methods use defaults of 256 commands, 1 MiB for one JSON
payload, 8 MiB for all JSON payloads, and 16 MiB for the serialized operation intent. Controlled
clients may call the additive `*_with_limits` methods, but limits cannot exceed the compiled hard
ceilings. Validation and streaming intent hashing finish before the vault write lock and SQLite
transaction. A larger import must use multiple new operation IDs; retrying one batch reuses its
original operation ID and exact command list. Operation commands accept both MDBX1 entry names and
namespaced ObjectTypeIds such as `com.monica.mail.message`.

#### Adapter Payload Schema Migration

MDBX file-format migration and domain payload migration use separate interfaces. MDBX1, SQLite schema, and field-ciphertext upgrades always use the storage-core migrator; clients do not convert them. The Adapter for an ObjectTypeId interprets that type's domain payload.

Register every ExtensionCapabilityId required by the CollectionProfile and keep an active authenticated vault session before calling `PayloadMigrationRepo::create_plan` or the UniFFI `create_payload_migration_plan` method. Both compatibility methods use a conservative Standard device context; clients that can report real device assurance should call `create_payload_migration_plan_with_device_context` and `execute_payload_migration_with_device_context`. Plan creation performs the `MigratePayload` Tiga administration authorization before loading or decrypting source payloads, so Multi and Power fresh-authentication, factor, and device rules apply.

A plan contains bounded decrypted payloads and must remain in protected process memory rather than logs, caches, files, or sync metadata. Its disclosure audit is correlated by `plan_id` and has no commit. The Adapter supplies exactly one output for every planned object and then calls `PayloadMigrationRepo::execute` or the corresponding UniFFI method. Execution reauthorizes, rechecks every binding, applies the whole batch, creates one idempotent commit, and records one commit-correlated audit in the same transaction. Any denial, concurrent object/Profile/branch change, missing capability, malformed output, or limit failure leaves the batch unchanged. Retrying the exact completed plan returns the original commit without a second successful audit. When `remaining_count` is greater than zero, create a fresh plan for the next batch.

#### Steam mafile Adapter

Steam mafile support is optional. A client that ships the
`mdbx-adapter-steam` crate should register its `extension_profile()` in the
process-local ExtensionProfile registry and separately activate
`com.monica.steam.store` when it is ready to perform user-visible writes.
Profile registration is not permission and does not replace Tiga authorization.

Clients that want storage mapping should also ship the independently removable
`mdbx-adapter-steam-storage` crate. Its registration helper registers only the
process-local descriptor; the client still activates the capability separately
and creates or updates the persisted CollectionProfile through the ordinary
tracked storage API.

Treat every mafile as untrusted JSON. Use `SteamMaFileImportPlan::prepare` for
storage imports instead of writing a second parser, UUID mapper, or
create/update decision tree in the UI. The bridge delegates per-document
parsing to the pure Adapter, whose default contract is 1 MiB input, depth 32,
512 aggregate fields, 512 items per array, 8,192 aggregate nodes, 64 KiB per
string/key, and 1 MiB aggregate string/key bytes. The Adapter rejects duplicate
keys, returns static non-disclosing errors, and preserves unknown fields in
canonical JSON. A client may lower limits for a smaller device, but cannot
raise the hard ceilings.

Use the canonical JSON bytes as the opaque payload of an object whose exact
ObjectTypeId is `com.monica.steam.mafile`. The bridge projects the Adapter's
domain-separated identity into a deterministic RFC-variant version-8 UUID;
never derive identity from an account name, title, path, secret, or list
position. It sorts by that UUID, rejects duplicates, and uses payload-free
summaries to choose create, update, or restore-then-update. An existing object
must belong to the requested Collection and retain the exact type and payload
schema version. Missing source documents do not mean delete.

The import default is 128 documents and 8 MiB aggregate source bytes; hard
ceilings are 2,048 documents and 64 MiB. Per-document and generic write limits
still apply. Keep the request and prepared plan in protected process memory,
never log source or canonical bytes, and execute the plan through the provided
generic coordinator path. One successful batch produces one commit, including
restore-then-update. If execution outcome is uncertain, retry the same prepared
plan. Re-reading files or rebuilding a plan after vault state changes is a new
planning action and must not be presented as the retry of the earlier plan.

The pure Adapter performs no Steam network action, Android integration, token
refresh, or storage write; the storage bridge only prepares and executes
generic MDBX writes. If a build removes either layer, existing Steam objects
stay available as opaque records for synchronization, backup, restore, and
recovery; the client must not delete or reinterpret them.

### 4.2 Deletion Must Use Tombstones

Deleting an object must:

- mark the object deleted or remove it from the visible index
- write a tombstone
- write a commit
- write an object version
- update the device head

Sync clients must use tombstones to prevent old remote state or another client from resurrecting deleted objects.

Clients must not only remove a row from the current list.

Tombstone acknowledgements are storage-owned synchronization evidence. Clients
must not insert, overwrite, or pre-merge acknowledgement rows. Submit the
original authenticated commit and state payload through storage apply; storage
records deleting-device and receiving-device evidence, verifies that the
observed commit contains the deletion, and preserves the strongest proof.

### 4.3 Folders And Paths

Clients must preserve stable folder IDs. Do not rely only on title or path strings.

Nested folders must preserve parent relationships. When the user enters `a/b/c`, breadcrumbs or path displays must be able to recover the full chain, not `a/c`.

Folder lists should:

- show folders before regular items
- keep stable ordering among siblings
- indicate nesting with indentation or guide lines
- treat expand/collapse as UI state only

Move, copy, and create-entry flows must be able to target MDBX folders, not only the vault root.

### 4.4 Attachments

Attachments are first-class MDBX objects.

Clients must:

- preserve attachment IDs
- preserve project / entry ownership
- verify content hashes
- support chunk metadata
- distinguish embedded, chunked, and external hash reference modes

Clients must not rewrite unrelated attachment content when editing an entry title or password.

New attachment screens should use the bounded summary pages above. The complete `get_attachment`, `list_attachments`, and `list_deleted_attachments` methods remain available for MDBX1 compatibility, explicit repair/export, content reads, and integrity verification. A summary resource-limit error means that the legacy display field is outside the new UI contract; it does not mean that the attachment bytes have been deleted or migrated.

### 4.5 Snapshots

Snapshots support recovery and structure comparison. They are not ordinary logs.

Clients should:

- support manual snapshots
- support automatic snapshots
- support pruning automatic snapshots
- require confirmation before snapshot rollback
- show snapshot structure preview

Batch operations should avoid producing many automatic snapshots.

## 5. Required User Management Screens

Any user-facing client that lets users manage MDBX should provide the following screens.

### 5.1 MDBX Format Management Home

Purpose: manage vaults by storage location.

It must show:

- local MDBX
- WebDAV MDBX, if supported
- OneDrive / cloud MDBX, if supported
- vault count per source
- create vault
- open existing vault

Opening "MDBX format management" should land on this home screen, not automatically enter the last opened database detail page.

The password list may remember the current vault for preload. The format-management entry should remain neutral.

### 5.2 Database Detail Page

Purpose: manage one vault.

It must show:

- vault name
- storage path
- storage type
- Tiga mode
- default status
- sync status
- health status
- commit count
- snapshot count
- tombstone count
- attachment count and size

It must provide:

- sync
- conflict management
- snapshots
- commit history
- diagnostics / maintenance
- delete vault

Normal user UI should not expose developer tools such as raw bundle import/export, benchmarks, or low-level chunk debugging. They may stay behind developer mode or internal tooling.

### 5.3 Folder / Structure Management

Purpose: manage vault organization.

It must support:

- root directory
- nested folders
- create child folder
- rename folder
- move folder
- delete folder
- expand / collapse
- breadcrumb path
- quick status bar

When the user creates a password while inside an MDBX subfolder, the create screen should default to that MDBX database and folder.

### 5.4 Move / Copy Target Picker

Purpose: move or copy items to another category or vault.

Recommended flow:

1. Select storage category or database.
2. Select target folder.
3. Confirm the operation.

The picker must support MDBX folder targets.

After a target is selected, the multi-select menu should close and progress should be shown by a quick status bar or background task panel. Do not leave the UI looking like the operation has not started.

### 5.5 Conflict Management

Purpose: resolve concurrent edits.

It must show:

- conflicted object title
- object type
- local version
- remote / incoming version
- conflicting fields
- creation time
- related commit

It must support:

- keep local
- use remote
- field-level merge, if supported
- write a new commit after resolution

Conflict display should use parsed field diffs, not raw JSON or SQL code blocks.

For a large queue, populate the page with `list_unresolved_conflict_summaries`
and load complete typed state only after the user selects one row. Resolve via
the existing entry/project/attachment/relation/label/assignment methods; never
mark only `conflicts.resolution` and never treat a summary page as a mutation
or authorization result. Show resource-limit or malformed-row errors as a
repair-needed state rather than retrying with an unbounded list by default.

### 5.6 Commit History

Purpose: explain what changed.

It must show:

- commit sequence or short ID
- commit time
- device ID
- operation type
- affected object count
- change summary

Details should show field-level unified-diff-style changes:

```text
Title:
-   null
+   example.com

Username:
-   old@example.com
+   new@example.com
```

This is a unified diff structure, not a code view. The UI should parse fields and values so regular users can understand it.

Deleted objects should be shown as "deleted password entry / folder", not primarily as a low-level `deleted: true/false` field change.

Treat `commit_kind` as authenticated protocol data. Current exact values are
`change`, `merge`, `snapshot`, `key-rotation`, `move`, `copy`, `restore`, and
`multi`. Localize them only for display; retain the original string and never
save an unknown value as `change`. Storage and bundle readers reject unknown
values so integrity failures cannot be hidden by a UI fallback.

Apply the same rule to `change_scope`. Exact values are `project`, `entry`,
`attachment`, `object-relation`, `object-label`, `object-label-assignment`,
`vault-meta`, `key-epoch`, `multi`, `snapshot`, and `branch`. `multi` means a
real operation spans known families; it is not a generic fallback. Clients
must preserve the original string and surface an unsupported-data error for an
unknown scope instead of exporting or writing a substituted value.

### 5.7 Snapshots

Purpose: recovery and structure inspection.

It must show:

- manual snapshots
- automatic snapshots
- creation time
- creating device
- base commit
- full / incremental marker
- prune automatic snapshots
- create snapshot
- revert snapshot

Snapshot revert must require confirmation.

### 5.8 Snapshot Structure Preview

Purpose: inspect snapshot structure like a file explorer.

It must support:

- folder display
- folders before regular items
- expand / collapse
- nesting guide lines
- current path title
- snapshot version node status

Landscape or wide mode should support side-by-side comparison:

- left: current version
- right: snapshot version
- center: simple divider, no heavy card wrapper needed

### 5.9 Diagnostics / Maintenance

Purpose: help users and support staff judge vault health.

It must show key metrics:

- readable or not
- sync status
- pending sync count
- unresolved conflict count
- commit count
- snapshot count
- tombstone count
- entry count
- folder / project count
- attachment count and size
- file path

It must show advanced details:

- format version
- default Tiga mode
- active key epoch
- branch count
- device head count
- dangling parent
- dangling branch head
- dangling device head
- attachment chunk mismatch
- external hash reference count

It must provide maintenance actions:

- refresh diagnostics
- sync
- upload pending writes
- verify attachment chunks
- prune automatic snapshots

The diagnostics page should stay concise. Put low-frequency details in a secondary section. Do not dump benchmarks, raw bundles, or low-level payloads into the normal user page.

### 5.10 Unlock And Security

It must support:

- password unlock
- Tiga mode display
- Tiga mode selection or default-mode explanation
- retry / lockout messaging, if implemented
- biometric or system credential wrapping, if supported by the platform

Clients must clearly distinguish:

- user-visible unlock method
- cryptographic key material actually used by the storage layer

Clients should present Tiga unlock policy with these semantics:

- `Sky`: flexible and portable, not unsafe. Clients may use password, PIN, platform credential wrapping, or security-key unlock, but must still use MDBX KDF, AEAD, and keyring handling. This mode fits cloud-drive sync, frequent cross-device use, and recovery-first vaults.
- `Multi`: balanced default. Clients should recommend adding a security key, but must keep a clear recovery path such as a strong password. A cloud-synced `.mdbx` file can be opened on a new device through any configured portable unlock path, or through a security-key path when the required hardware key or equivalent platform credential is available.
- `Power`: strongest protection. Clients should guide users toward a password + security key combined unlock method. If standalone password or PIN unlock remains configured, clients should clearly warn that it weakens Power-mode resistance to offline brute-force attacks.

Tiga2 is more than profile display. After unlock, clients must retain the `VaultSession` and provide truthful `DeviceContext` evidence for every sensitive operation. A client must not claim hardware assurance, secure clipboard support, or screen-capture protection merely to satisfy Power policy.

Client-owned actions must call `TigaService::authorize_operation` and honor every returned constraint:

- secret reveal: `RevealSecret`
- secret copy: `CopySecret`
- attachment plaintext handling: `DecryptAttachment`
- background access: `BackgroundAccess`
- locked ciphertext sync: `SyncCiphertext`

Only `Allow` and `AllowWithConstraints` permit the action. `RequireFreshAuthentication`, `RequireAdditionalFactor`, and `Deny` cannot be bypassed with a confirmation dialog.

Storage-owned high-risk operations must use their authorized APIs:

- KDBX export: `KdbxExporter::export_all_authorized` / `export_one_authorized`
- snapshot restore: `SnapshotRepo::restore_snapshot_authorized`
- unlock-method add/change/reset/remove: the `UnlockService` `*_authorized` methods
- Tiga profile and sparse policy changes: the `TigaService` `*_authorized` methods
- data-key epoch rotation: `KeyEpochService::rotate_authorized` in Rust or `MdbxVault.rotate_key_epoch` through UniFFI

The first unlock method may use the bootstrap path. Once a method exists, bootstrap APIs reject further additions. `remediation-required` relaxes only the unlock-method repair workflow; it does not weaken Power export, reveal, or other operations.

When a security key participates in unlock, clients must not log or cache the hardware key itself, challenge responses, derived key material, or replayable equivalent material. Hardware-key support does not make cloud-drive storage unsafe or unusable by itself; portability depends on the configured unlock paths. A vault configured only with security-key unlock and no portable unlock path will require the same hardware key or equivalent platform credential on a new device, so clients should explain this recovery impact before enabling that configuration.

Clients must not log master passwords, derived keys, or epoch keys.

## 6. Performance Requirements

### 6.1 Startup And Open

Clients should:

- preload only the currently selected vault
- avoid opening every configured vault at startup
- use stale-while-revalidate caches for list pages
- avoid clearing a list before repopulating it, which causes blank flashes and sort jumps

If a user manages many MDBX vaults, the client must not unlock all vaults, read all history, and scan all attachments on startup.

### 6.2 Writes

Clients should:

- batch writes
- use one transaction for one user-level operation
- use one commit for one user-level operation
- refresh UI incrementally after writes

Clients should not:

- open / close the vault once per item
- create one snapshot per item
- delete and rebuild the full UI cache

### 6.3 Sync

Sync should run in the background and report progress through a status bar or task panel.

At minimum, sync state should include:

- waiting
- uploading
- downloading
- merging
- conflicts pending
- complete
- failed

Complete sync state has a resource contract independent of the surrounding bundle. The default encoded-state limit is 96 MiB and the default logical-row limit is 250,000. Desktop callers that need larger batches should pass the same `SyncStateLimits` to state collection, decoding, and apply; the hard ceilings remain 512 MiB and 2,000,000 rows. A limit or reserved-identity failure rolls back the complete sync transaction.

Complete state is the initial bootstrap and old-peer fallback. After bootstrap, bundle v4 transfers bounded commit and state-delta inventory segments from a paired checkpoint. Clients must persist the returned checkpoint together with any transfer ID, next segment index, and previous payload digest, and must advance it only after the full segment is durably applied. A failed segment is retryable because commit-associated and auxiliary deltas are idempotent and share one storage transaction.

Use the transport-neutral `mdbx_sync::SyncClient` for capability selection and checkpoint state. Incremental v4 requires all four additive capabilities: commit inventory paging, delta inventory paging, bundle v4, and incremental resume. Paging-capable Hello messages omit the legacy complete commit-ID vector. The client facade creates bounded commit/delta page requests and keeps the old checkpoint while a segment is only validated; call its acknowledgement method only after storage reports durable apply. Missing capabilities select complete-state fallback.

When retransmitting a commit that the receiver may already have, reuse the
original serialized commit fields, integrity tag, and parent set exactly. A
later segment may attach a missing object payload or state delta, and a newer
bundle may supply operation metadata that an older bundle omitted. Do not
rebuild the vector clock, timestamp, message, taxonomy, branch metadata, change
summary, or request identity from current application state. Storage rejects a
different authenticated meaning for an existing commit or operation ID before
applying the late payload.

For a newly received commit, authentication does not replace structural
validation. Producers must emit each parent ID once, encode the vector clock as
a JSON object with unsigned 64-bit integer values, and keep `local_seq` within
SQLite's signed 64-bit INTEGER range. The empty `{}` clock remains the legacy
compatibility representation. Do not deduplicate parents, repair clock text, or
wrap a sequence in the transport layer, because each conversion would change
the authenticated commit meaning.

Treat device heads as storage-owned device-sequence state. Do not derive them
from branch ancestry or update `device_heads` in client SQL. The receiver checks
that the referenced commit was authored by the claimed device and advances by
`local_seq` even when commits are siblings on different branches. Out-of-order
lower commits remain valid history without moving the head backward, and local
revocation remains set. A sequence-reuse validation error indicates conflicting
device identity; stop that synchronization input and surface diagnostics rather
than renumbering or regenerating the commit.

Treat tombstone acknowledgements as storage-owned causal evidence. Do not pick
the newest row by timestamp, derive acknowledgement from a device head, or
rewrite `observed_commit_id` in the transport layer. Storage first compares
commit ancestry, then uses acknowledgement time and commit ID only to choose
between valid concurrent proofs. A causal-validation error identifies an
invalid synchronization input and must be surfaced without retrying a locally
rewritten proof.

Key epoch rotation has a security-sensitive ordering rule. After a successful rotation, distribute the rotation commit and authenticated key epoch sync state before uploading or broadcasting `MDBXFE2` fields written under the new epoch. A receiver that changes epoch state must be verified-unlocked and use the mutable apply entry that refreshes the connection keyring. Older payloads without epoch state preserve local state. Concurrent rotations retain every wrapper and accept the active epoch selected by storage.

Each rotation request is a new security-administration action and does not use ordinary `operation_id` retry semantics. When the response status is unknown, inspect commit history or Tiga audit correlation before requesting another rotation.

## 7. Compatibility Requirements

### 7.1 Format Version

Clients must check `format_version` when opening a vault.

If the vault contains an unknown critical extension, the client must refuse writes. Read-only mode is acceptable if it can be guaranteed safe.

Clients may own migration prompts, pre-upgrade backup placement, progress, and remediation UI, but the storage core must perform the format conversion. Android, iOS, and desktop clients must not maintain separate MDBX1 field-mapping implementations.

Use `inspect_migration_path` or the UniFFI `inspect_vault_migration` function for a read-only migration plan. When upgrade is required, create the exact pre-migration archive with `BackupService::create_portable_copy_path` or UniFFI `create_portable_backup`. After backup publication and consent, call `upgrade_path` or UniFFI `upgrade_vault`; both delegate to the same storage-core transactional migrator. `VaultConnection::open` remains an automatic-upgrade compatibility path for simple callers.

The read-only backup contains ciphertext and configured unlock methods without decrypting records, so it does not require user credentials. It preserves the source format generation and includes committed WAL pages. Clients must not call automatic open before this step when the retained artifact must remain MDBX1.

### 7.2 Stable IDs

Clients must preserve:

- vault ID
- device ID
- branch ID
- project / folder ID
- entry ID
- attachment ID
- commit ID
- snapshot ID

Clients must not regenerate object IDs from titles, paths, or sort positions.

### 7.3 Time And Ordering

Clients should use ISO-8601 UTC timestamps.

List ordering should be stable. Refreshing data must not randomly reorder the same set of items because rows were re-imported.

## 8. Minimum Test Checklist

Before claiming MDBX support, another client should pass at least these scenarios:

- Create a vault, close it, and reopen it.
- Back up an MDBX1 WAL vault before writable open and verify that source and backup still report MDBX1.
- Upgrade the source explicitly and verify that the pre-migration backup still reports MDBX1.
- Create an entry in the root directory.
- Create an entry inside a nested folder.
- Create an entry from inside an MDBX subfolder and keep that folder as target.
- Batch move 100 entries into MDBX and create one user-level commit.
- Batch delete 100 entries and create one user-level commit with tombstones.
- Two clients open the same vault and show the same item count.
- One client deletes an item; another client syncs and does not resurrect it.
- Reject an acknowledgement whose observed commit precedes the delete commit.
- Apply descendant and ancestor acknowledgement proofs in both orders and keep the descendant with the later known acknowledgement time.
- Apply concurrent valid acknowledgement proofs in both orders and obtain the same stored row.
- Receive a higher-sequence branch sibling before a delayed lower sequence and keep the higher device head.
- Reject a device-head row that points at a commit authored by another device, while preserving local revocation.
- Concurrent edits to the same field create a conflict.
- Concurrent edits to different fields auto-merge or present a clear merge prompt.
- Create a manual snapshot.
- Prune automatic snapshots.
- Snapshot rollback requires confirmation.
- Snapshot structure shows folders, with folders before entries.
- Attachment chunk verification failure appears in diagnostics.
- Opening MDBX format management lands on the home screen, not the last database detail page.
- Normal user UI does not expose raw advanced tools.
- After rotation, sync the rotation commit and key epoch state before new-epoch ciphertext; another replica can read data from old, new, and concurrent epochs.
- Authorization denial leaves the active epoch and commit count unchanged.

## 9. Common Mistakes

### 9.1 Writing Current Tables But Not History Tables

Result:

- empty commit history
- unusable snapshots
- conflict detection cannot work
- deleted objects may resurrect

### 9.2 One Commit Per Object

Result:

- huge history after batch operations
- huge snapshot count
- slower sync
- unreadable management UI

### 9.3 Storing Folders Only As Path Strings

Result:

- duplicate folder names collide
- moved paths break
- breadcrumbs are wrong
- cross-client target selection fails

### 9.4 Management Page Auto-Enters Last Vault

Result:

- users tap "format management" but cannot see the management home
- users assume only one database can be managed
- multi-vault workflows become confusing

Correct behavior:

- the password list may remember the current vault
- the format-management entry always opens the MDBX management home
- database detail opens only after explicit user selection

### 9.5 Exposing Developer Tools To Normal Users

Result:

- users see benchmarks, raw bundles, or chunk payloads they cannot interpret
- users may import the wrong payload
- the management page becomes noisy

Correct behavior:

- normal users see sync, conflicts, snapshots, history, and diagnostics / maintenance
- raw bundles, benchmarks, and low-level chunk debugging stay behind developer mode

## 10. Completion Standard

A client may claim "Monica MDBX support" only when it can at least:

- open an MDBX vault created by Monica
- display folders, entries, and attachment metadata correctly
- create, move, and copy entries inside nested folders
- write commits, object versions, and tombstones
- show commit history
- show and revert snapshots
- detect and show conflicts
- show diagnostics / maintenance
- avoid generating excessive commits for batch operations
- show the same item count as another client reading the same vault

If the client can only read data and cannot preserve write history, label it as "MDBX read-only support", not full support.
