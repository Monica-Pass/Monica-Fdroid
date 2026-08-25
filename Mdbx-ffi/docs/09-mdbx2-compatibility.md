# MDBX2 Compatibility and Migration Specification

Version: `MDBX-2`

MDBX2 is the second MDBX format generation. It preserves the **4ever And 4ever** rule through ordered, additive, and transactional migration.

## Compatibility Contract

- MDBX2 implementations MUST read and upgrade `MDBX-1` and `MDBX-1-DRAFT` vaults.
- Migration MUST preserve stable IDs, ciphertext, commit history, object versions, tombstones, snapshots, key epochs, and attachment content.
- A failed migration MUST leave the original format marker and data unchanged.
- Schema migration MUST NOT implicitly rotate keys or re-encrypt the entire vault.
- Unknown formats or critical extensions MUST prevent writable open.

MDBX2 guarantees that the new reader understands the previous generation. Already released old binaries cannot safely write arbitrary future semantics; upgraded vaults therefore declare `min_writer_version = MDBX-2`.

## MDBX2 Metadata

An upgraded vault records:

```text
format_version      = MDBX-2
schema_version      = 17
min_reader_version  = MDBX-1
min_writer_version  = MDBX-2
tiga_policy_version = 2
```

`schema_migrations` records each ordered migration exactly once.

## Automatic Upgrade

On writable open, MDBX2 reads format metadata, rejects unsupported critical extensions, starts an immediate transaction, applies additive schema changes, records the migration, validates the result, and updates `format_version` last. Reopening an upgraded vault is idempotent.

Tiga1 profiles are mapped to Tiga policy version 2 in the same transaction. Existing weaker project or entry profiles become deterministic remediation exceptions. An unlock configuration that does not yet satisfy the new profile is marked `remediation-required`; migration never rewrites KDF parameters or wrapped vault-key bytes and does not deny access solely because remediation is pending.

Early MDBX2 vaults with schema versions 2 or 3 upgrade in place to schema version 4 without changing the `MDBX-2` format marker. Schema 4 adds operation-level commit metadata and atomic per-device sequence state while retaining the original `commits` table and DAG as the MDBX1-compatible projection. Schema 4 vaults then upgrade additively to schema 5, which adds nullable Tiga audit correlation and policy-evidence fields. Existing audit rows remain valid with null values. Schema 5 vaults upgrade additively to schema 6, which adds a nullable `commit_operations.branch_id` and its lookup index. Existing operation rows retain a null branch ID because their V1 request hashes and integrity tags authenticate only `branch_name`.

Schemas 6 through 11 continue as ordered additive migrations. Schema 7 adds generic relations, labels, and assignments; schema 8 adds tombstone delete proof and device acknowledgements; schema 9 adds permanent purge receipts; schema 10 adds Attachment Tiga scopes; schema 11 adds one-to-one Collection Profiles. These migrations preserve the physical `projects` and `entries` tables and the legacy public interfaces.

Schema 10's policy-table rebuild also carries forward bounded, additive columns that are not known to the current reader when their definitions are nullable or have safe literal defaults. Unsupported definitions fail the transaction before the old tables are replaced, so a non-critical field is never silently discarded.

Schema 12 adds a local stable commit inventory whose migration preserves commit identity and backfills parent-before-child order. Schema 13 adds the state-delta batch inventory, its normalized commit associations, bounded versioned envelope rules, and a bootstrap floor fixed at the migration commit watermark. Schema 14 adds transaction-local logical mutation capture for every synchronized core state family. Before each outer write transaction commits, MDBX deduplicates those keys, materializes a bounded state body, and stores either a commit-associated or auxiliary batch atomically with the domain rows. Bootstrap mutations generated while creating or upgrading a vault are discarded in the same transaction because their state is covered by the floor. Historical deltas are not invented during migration; checkpoints before the floor continue to require bounded complete-state bootstrap.

Schema 15 adds `sync_state_extensions` for bounded unknown top-level complete-state fields. Apply upserts only keys present in the incoming state, in the same transaction as the commit and domain rows. A missing key never means deletion, so an older peer cannot erase a future extension merely by omitting it. Collection restores stored values in key order, and migration plus current-schema validation enforce 256 fields, 128-byte keys, a 64 KiB aggregate budget, and the existing nesting-depth limit.

Schema 16 adds `header_integrity_profile` and `header_integrity_tag` to
`vault_meta`, plus triggers that invalidate an established tag whenever a
protected header field changes. MDBX1 and earlier MDBX2 vaults retain their
ciphertext, unlock wrappers, and identities while entering the migration-only
`pending` state. The first successful unlock seals the header with the vault
integrity subkey. Later protected mutations must refresh that HMAC in the same
transaction, and unlock plus health verification fail closed on invalidation or
tag mismatch.

Schema 17 adds the authenticated `snapshot_lifecycle` companion table without
changing the six-column MDBX1 `snapshots` table or rewriting any snapshot row.
A snapshot with no lifecycle row is permanently treated as a protected manual
recovery point. Only authenticated automatic rows with an elapsed RFC3339
retention timestamp can enter a storage-issued, bounded prune plan. Each
authorized prune rechecks the exact plan in its transaction, deletes at most
200 automatic snapshots, and records one idempotent operation commit. Snapshot
lifecycle remains local recovery state and is not added to `SyncStatePayload`.

The storage core treats each extension value as opaque JSON: it validates, stores, and forwards the value but does not interpret or decrypt it. Opaque does not mean automatically encrypted. Non-secret capability or version metadata may use ordinary JSON; any value containing passwords, mail content, tokens, or other sensitive material MUST be an authenticated ciphertext envelope produced before it enters the unknown extension. This contract lets a locked older reader preserve future sensitive state without creating plaintext itself.

The storage apply path recognizes authenticated `mdbx-storage/state-delta-v1` object payloads. A commit-associated envelope must be carried by its final associated commit, every referenced commit must be available, and the commit, sparse state rows, device heads, authorized deletions, received batch, and capture cleanup succeed or roll back together. Fast-forward, divergent, and late-payload repair paths share this boundary. Bundle v4 and its compressed v6 representation, plus their authenticated v8/v10 envelopes, apply commit-associated and auxiliary batches in one outer transaction, so a failed tail batch rolls back the complete segment without creating user-visible commits. These additions do not change the `projects`, `entries`, commit DAG, sync-state v1-v2, or bundle v1-v6 formats.

The CLI uses bounded complete state for bootstrap and bundle v4 after a paired commit/delta checkpoint. A partial v4/v6/v8/v10 transfer stores its transfer ID, next segment index, and previous logical payload digest in the checkpoint file; authentication and compression do not change that logical SHA-256 identity. Legacy checkpoint JSON without resume fields remains readable. The transport-neutral synchronization client selects v4 semantics only when both peers advertise commit paging, delta paging, bundle v4, and resume; paging-capable Hello messages omit the legacy complete commit-ID vector. Zstd is negotiated separately through `bundle-zstd-v1`. Keyed transport authentication is independently negotiated through `authenticated-bundle-v1`; it is intentionally not a fifth incremental requirement. Old or partially capable peers therefore retain bounded complete-state and v1-v6 fallback behavior.

Authenticated complete/incremental envelopes use versions 7/8, while their zstd representations use versions 9/10. Their existing logical payload SHA-256 trailer is followed by HMAC-SHA-256 keyed with the vault integrity subkey. The tag binds a versioned domain, magic, version, the bounded 20-byte header area, and the logical payload digest. The key is never stored in or transported with the bundle. This proves that the envelope was produced by a holder of the shared vault key and binds its metadata; it does not identify a particular device, encrypt the transport, replace inner field/delta encryption, or make a bundle safe to disclose. CLI export remains legacy v3/v4 by default, writes v5/v6 only with `--compression zstd`, and selects v7-v10 only with explicit `--authenticated`. CLI apply supplies the opened vault key automatically and continues to read v1-v6.

The implemented `IncrementalIntegrityRoot` profile is additive and intentionally
separate from the bundle capability. It did not allocate a schema version when
introduced at schema 16 and creates its metadata, leaf, and sparse-node tables
only after explicit verified-unlocked opt-in. Establishment records
`authenticated-state-root-v1` as a critical extension, so a pre-profile MDBX2
writer rejects the vault before writable open. The root updates in the same
outer transaction as sync-delta capture. Without that opt-in, current and
legacy vault behavior is unchanged. The O(vault-size) content manifest remains
the exact schema checkpoint; external Provider bytes and unregistered physical
extension tables are not silently claimed by the incremental root.

Protocol-v2 root exchange is additive: Hello and HelloAck omit the checkpoint
unless `authenticated-state-root-v1` is configured and both peers provide a
bounded checkpoint. Legacy JSON remains unchanged, and the capability is not
added to the four mandatory incremental-sync capabilities. Storage, not the
transport parser, authenticates the checkpoint under the vault integrity key
and checks per-peer monotonic generation and inventory anchors. Clients retain
the last verified remote value outside the vault; local and remote root hashes
are not required to match because inventory order can differ across replicas.

Future generations MUST migrate sequentially. For example, MDBX3 opening MDBX-1 executes `MDBX-1 -> MDBX-2 -> MDBX-3`.

### Release Golden Vault and Old Reader Boundary

The repository freezes both `crates/mdbx-storage/test-data/mdbx1-release-1.0.mdbx` and `mdbx1-draft-golden.mdbx`. The release fixture was generated by the historical `MDBX1.0` tag at commit `1a43fa9e8e87eebf6d0e1b84543c3291d0b25142`; the DRAFT fixture was derived by that same historical reader changing only `vault_meta.format_version` before checkpointing. Each manifest records the immutable SHA-256, test-only unlock credential, and stable project, entry, attachment, and snapshot IDs.

The shared migration regression runs against both exact byte sequences, verifies that inspection is read-only, upgrades schema 1 to the current schema, unlocks with the original MDBX1 credential, and compares the legacy commit and object-version identities before and after. It also verifies project metadata, entry payload, project tags, inline attachment bytes, snapshot identity, and repeated-upgrade idempotence.

As an additional release-binary observation, the `MDBX1.0` CLI successfully listed the project and entry from a copy already upgraded by the current reader. This demonstrates that the MDBX1 physical projection remains readable. It does not make the old binary a safe MDBX2 writer: old code does not enforce `min_writer_version`, cannot preserve future semantics, and MUST NOT be used for writes once the vault declares `min_writer_version = MDBX-2`.

### Bounded Navigation Compatibility

Collection, Object, and Label summary APIs are additive reader surfaces and do not change schema bytes. `CollectionSummaryRepo` reuses the MDBX1 `projects` table plus an optional left join to `collection_profiles`; an MDBX1 Collection therefore remains discoverable with no profile type/version. Summary queries never select the legacy Project summary or CollectionProfile payload.

New navigation applies fixed field and page limits. A legacy row outside those limits returns a resource-limit error only through the bounded summary surface. Existing complete Project, Entry, and Label repositories and FFI methods retain their historical behavior so an explicit repair/export tool can still inspect the row. The CLI and new clients use summaries by default; compatibility methods are not silently removed or redefined.

Attachment navigation follows the same additive rule. `AttachmentSummaryRepo` and the UniFFI `MdbxAttachmentSummary` methods page active attachments by Collection or Object, page deleted attachments separately, and expose by-ID metadata without selecting chunk/blob payloads. File-name plaintext is limited to 4096 UTF-8 bytes and media type plaintext to 512 bytes; encrypted projections reserve the shared 128 KiB envelope allowance and recheck the exact plaintext after authenticated decryption. A legacy attachment outside those limits remains readable through `AttachmentRepo::get_by_id`, `list_by_project`, `list_by_entry`, `list_deleted`, and the existing complete FFI methods. `attach list` and `attach deleted` use the bounded pages, while content export, repair, and integrity verification keep the complete paths.

Conflict navigation follows the same additive rule. `ConflictSummaryRepo` and
the UniFFI `MdbxConflictSummary` methods page only unresolved conflict metadata,
optionally filter by core conflict object type, and bind an opaque cursor to
that filter and the `created_at DESC, conflict_id DESC` keyset. The bounded
projection caps the stored `conflicting_fields` JSON at 64 KiB, the decoded
path count at 256, and each path at 4096 UTF-8 bytes; SQL does not materialize
an oversized JSON value. `default_conflict_summary_limits` publishes the
contract. Existing complete conflict reads and typed resolution methods remain
the explicit resolution, repair, and export path, and a cursor from one type
filter cannot be reused for another.

Snapshot navigation follows the same additive rule. `SnapshotSummaryRepo::get`
and `SnapshotSummaryRepo::list` expose stable snapshot/base-commit identities,
the descriptor hash, creation metadata, and `length(snapshot_ct)` without
selecting, decrypting, deserializing, or verifying `snapshot_ct`. Pages contain
1–200 rows, use the `created_at DESC, snapshot_id DESC` keyset, and return a
query-bound cursor no larger than 4096 bytes. Required snapshot metadata text is
limited to 4096 UTF-8 bytes. `default_snapshot_summary_limits` publishes the
same fixed limits through UniFFI; `snapshot list` uses the bounded pages while
keeping its existing command and output shape. The ciphertext length is a
storage size, not an integrity or payload-validity claim. Existing complete
`SnapshotRepo` reads, creation, verification, and authorized restore methods
remain unchanged for MDBX1 clients and explicit recovery/repair workflows, so
a corrupt or oversized payload affects only the bounded row that is selected
through that complete path.

No format marker, schema version, commit, object version, synchronization field, snapshot field, ciphertext, or key epoch is changed merely by adding or reading a summary. This preserves both automatic MDBX1 upgrade guarantees and the physical projection observed by the historical reader.

## MDBX2 Consistency Changes

- Snapshot creation and restore are atomic.
- Restore recreates the exact active set while retaining post-snapshot rows as tombstoned history.
- Restored objects receive one causal restore head and object-version records.
- New snapshots include project tags and attachment chunks without clearing fields absent from legacy snapshots.
- Verified-unlocked snapshots use the `MDBXSN2` payload profile and a versioned
  HMAC descriptor that binds their base commit and row metadata. Existing
  64-hex SHA snapshots retain their original AAD and restore semantics. The
  first new-profile snapshot registers `snapshot-record-auth-v1`, so an older
  MDBX2 reader rejects the unknown critical extension rather than silently
  applying legacy decryption rules.
- Tiga mutations atomically update commits, rows, heads, and object versions.
- Tiga2 policy state, scoped overrides, exact exceptions, and typed audit events are synchronized. Concurrent policy conflicts merge toward the stricter value.
- Authorized Tiga mutations record the exact Commit2 `operation_id` and `commit_id` in the same transaction. Rejected decisions and non-mutating disclosures have no commit association.
- New audit events record the Tiga policy version and a SHA-256 fingerprint of the resolved policy used for the decision. The evidence is captured before a policy mutation changes the active policy.
- Audit synchronization authenticates the new fields, verifies that the operation and commit identify the same `commit_operations` row, and rejects immutable-event rewrites. MDBX1 and early MDBX2 audit rows retain null correlation and evidence fields.
- Commit2 adds idempotent operation IDs, typed change summaries, stable branch identity, merged vector clocks, and atomic device sequence allocation without rewriting historical commits.
- Offline sync bundle version 3 adds an explicit payload length and bounded decoding. MDBX2 readers continue to convert version 1 bundles without operation metadata and read version 2 bundles with operation metadata.
- Offline sync bundle version 4 adds paired incremental inventories, authenticated base validation, bounded resumable segments, and atomic commit-plus-auxiliary application while preserving the version 1-3 readers.
- Offline sync bundle versions 5 and 6 add optional bounded zstd representations for complete v3 and incremental v4 logical payloads. Their trailers hash the uncompressed bincode payload, both declared lengths are independently bounded, and feature-trimmed builds retain v1-v4 while explicitly rejecting v5/v6.
- Offline sync bundle versions 7 and 8 add keyed HMAC-SHA-256 envelopes for complete and incremental payloads; versions 9 and 10 combine the same authentication contract with zstd. The authentication trailer binds the versioned bounded header and logical payload digest, while the digest remains stable for incremental resume. Readers retain v1-v6, and authenticated versions fail closed without the matching vault integrity key.
- CLI bundle application delegates to `mdbx-storage::SyncApplyRepo`; the duplicate CLI SQL apply engine was removed.
- Storage accepts bounded authenticated state-delta payloads atomically, persists received batches for forwarding, preserves sparse local tombstones, and merges device revocation monotonically. Complete-state payloads remain supported and cannot be mixed with a delta on one commit.
- Unknown complete-state extensions survive decode, transactional apply, storage, collection, and re-encoding. Present keys update atomically; absent keys preserve the local value.
- Portable backup uses SQLite online backup so committed WAL pages are included, verifies SQLite and MDBX metadata plus `vault_id`, converts the result to a sidecar-independent file, and refuses to replace any destination artifact.

## Client/Core Boundary

Clients own upgrade prompts, backup placement, progress UI, platform capability evidence, and remediation interactions. The storage core owns format detection, deterministic conversion, transactions, rollback, idempotence, and validation. Clients must not reimplement the MDBX1-to-MDBX2 field mapping.

### Portable Backup API

Clients use `BackupService::create_portable_copy_path` through Rust or top-level UniFFI `create_portable_backup` before writable open. The result reports vault identity, preserved format, preserved schema, and file size. The reference CLI exposes this read-only path as `mdbx backup <output>` without requiring unlock credentials.

`MdbxVault.create_backup` remains the operational backup API for an already opened vault. The path API is the pre-migration archive seam: it accepts supported MDBX1, MDBX1 draft, and MDBX2 files, includes committed WAL pages, and publishes a single file with source format metadata unchanged.

A portable backup is a complete encrypted vault file and retains the source unlock methods. It does not decrypt records. A vault-internal snapshot remains a logical recovery point, while a sync bundle remains an incremental transport artifact. Direct copying of the SQLite main file is invalid while WAL may contain committed frames.

The destination path, `-wal`, and `-shm` names are reserved as one publication set. Existing artifacts are never replaced. Storage verifies integrity, source-equivalent MDBX metadata, and vault identity before publishing the single-file result.

### Epoch-Tagged Field Ciphertext

New field ciphertext written by an officially unlocked connection uses this outer format:

```text
MDBXFE2\0 || epoch_id_len_u16_le || epoch_id_utf8 || MDBXAE1 committed AEAD
```

The inner AEAD uses the record, attachment, metadata, or history subkey for the identified epoch. Length-prefixed AAD authenticates the domain, epoch ID, object type, object ID, and field name. Changing the outer epoch ID, moving ciphertext to another field, or modifying the inner ciphertext fails authentication.

Readers continue to accept existing `MDBXAE1` committed envelopes and earlier nonce envelopes. Before publishing the first `MDBXFE2` field, storage records the critical extension `field-key-epochs-v1` in the same database transaction. Current readers recognize it. Older MDBX2 writers treat it as an unknown critical extension and reject writable open, preventing writes that apply legacy key-selection rules to the new field format.

### Stable Branch Identity

`branch_id` is the immutable internal identity of a branch. `branch_name` is a mutable display attribute and a compatibility selector for interfaces created before schema 6. Multiple branches may have the same display name.

New operation metadata authenticates both the stable ID and the display name recorded at commit time. ID-based requests select exactly one branch and remain retryable after a display-name change. A name-only request is accepted only when the name identifies exactly one branch. Existing operation rows with a null ID continue to use the V1 request-hash and integrity algorithms; migration does not infer or write IDs into those rows.

Synchronization compares branch IDs when both peers provide them. If either peer omits the ID, comparison falls back to the legacy name. The same ID with different names represents one branch, while the same name with different IDs represents separate branches. Serialized branch heads and operation metadata accept a missing `branch_id` for older peers.

### Client-Controlled Migration APIs

The compatibility path `VaultConnection::open` continues to upgrade automatically so simple callers remain generation-compatible. A client that needs consent, backup, and progress orchestration first calls the read-only `mdbx_storage::migration::inspect_migration_path` or UniFFI `inspect_vault_migration`. When upgrade is required, it next calls `BackupService::create_portable_copy_path` or UniFFI `create_portable_backup`. Only after backup publication and consent does it call explicit upgrade. The inspection result reports the current format/schema, minimum reader/writer generations, whether an upgrade is required, and whether critical extensions are unknown.

After the client has obtained consent and completed its backup workflow, it can call `mdbx_storage::migration::upgrade_path` (or UniFFI `upgrade_vault`). The same storage-core transactional migrator performs the conversion. Clients own prompts and progress, never a second MDBX1 field-mapping implementation. Open and explicit upgrade repeat the read-only identity preflight before acquiring a writable handle; missing paths, uninitialized SQLite databases, and unknown critical extensions are rejected without modification.

### Client Operation Write API

Mobile and desktop clients should call `MdbxVault::list_branches` to obtain stable IDs and submit branch-specific multi-step edits through `execute_write_operation_on_branch`. The original `execute_write_operation` method remains available as the main-branch compatibility entry point. The boundary accepts a finite typed command set for project creation and entry create, update, delete, restore, and move operations; it never exposes SQL.

Every create command carries a client-generated stable UUID. The client reuses the same `operation_id` and complete command list for the initial call and retries. Storage executes the command list as one transaction and one commit. A completed operation retry returns the commit ID and the object IDs from the request without running mutations again. Reusing an operation ID with different command content is rejected, and failure of any command rolls back the entire batch.

The existing single-mutation FFI methods remain available as the MDBX1-compatible projection and simple-call entry points. A client action that must appear as one history node should use the operation API.

Native Rust adapters use `mdbx_storage::repo::OperationCoordinator` with the
same bounded `WriteCommand` contract. The UniFFI facade only converts its
records, manages the vault handle, and maps errors; it does not maintain a
second write protocol. `OperationCoordinator::prepare` may complete before a
client write lock, while `execute` and `execute_prepared` preserve one
transaction for generic and composed operations.

### Object Summary and Disclosure Read API

Existing UniFFI `get_object`, `list_objects`, and `list_entries` signatures and complete-payload behavior remain unchanged for MDBX1 and already generated clients. MDBX2 clients use additive `get_object_summary(object_id)` for metadata-only details and `list_object_summaries` for bounded collection screens.

Deleted navigation is additive as well. New clients can call
`list_deleted_object_summaries(collection_id, object_type_id, page_size,
cursor)` for one Collection or `list_all_deleted_object_summaries` for a global
tombstone view. These methods return the same payload-free summary record and
never select `payload_ct`; page sizes are 1–200 and cursors are bound to query
state, Collection scope, and ObjectTypeId. An active cursor cannot be reused for
deleted navigation. The CLI `entry deleted` command uses the global bounded
method, while `EntryRepo::list_deleted*` and `list_deleted_entries` retain their
complete-payload compatibility behavior for explicit repair/export consumers.

An explicit plaintext action uses `reveal_object` or `reveal_object_with_device_context`. The returned `MdbxObjectDisclosureResult` contains `object` only for `Allow` or `AllowWithConstraints`, and always contains the typed Tiga authorization decision. Missing/stale sessions and policy denials therefore remain actionable client states without returning payload or allowing corrupt ciphertext to take precedence. Deleted objects and non-authorization storage failures remain errors.

The existing reveal method signatures remain unchanged and use an 8 MiB default plaintext limit. Additive `default_object_disclosure_limits`, `reveal_object_with_limits`, and `reveal_object_with_device_context_and_limits` APIs let a new client choose a smaller or controlled larger resource profile without exceeding the 64 MiB hard ceiling. After policy allows, storage rejects clearly oversized payload ciphertext through a SQL length projection before authenticated decryption, then verifies the actual plaintext length. Tiga denial still precedes those payload checks. MDBX1 large-payload database bytes are not migrated or rewritten, and the original complete-payload compatibility APIs retain their behavior.

Generic metadata selection follows the same additive compatibility rule. New clients use `get_object_relation_summary`, `list_object_relation_summaries_from`, `list_object_relation_summaries_to`, `get_object_label_summary`, `list_object_label_summaries`, and the object/label assignment summary pages. These payload-free pages accept 1 through 200 items and query-bound opaque cursors. Existing complete relation, label, and assignment methods remain unchanged for generated clients and explicit payload consumers.

Explicit relation and label payload access is also additive. `reveal_object_relation*` returns the ordered source and target Entry decisions and includes the relation only when both allow. `reveal_object_label*` returns the collection Project decision and includes the label only when it allows. `default_object_metadata_disclosure_limits` and explicit-limit variants use the same 8 MiB default and 64 MiB hard ceiling as bounded inline object payloads. Composite relation audit rows share an optional non-commit operation ID. No Relation/Label Tiga scope, schema row, sync field, or database rewrite is introduced; old complete metadata methods retain their exact behavior.

### Commit History Read API

The original `MdbxCommitHistoryItem`, `list_commit_history`, and `get_commit_history` interfaces remain unchanged for generated clients from the previous interface generation. MDBX2 clients use `MdbxCommitHistoryItemV2`, `list_commit_history_v2`, and `get_commit_history_v2` to read the optional stable branch ID. Results include operation metadata, branch, parents, typed change summaries, and a compatibility flag; MDBX1 commits without operation metadata remain visible through a compatibility summary. Clients must treat the storage-returned keyset cursor as opaque and must not recreate pagination with offsets.

Operation summaries use `create`, `update`, `delete`, `restore`, `move`, or the compatibility `change` action, with stable domain field names. Repository-generated generic `change` records are placeholders and never overwrite a more specific client-provided summary.

### Tiga Audit Read API

The existing UniFFI `MdbxSecurityAuditEvent` record and `list_security_audit_events` method remain unchanged for generated clients from the previous interface generation. MDBX2 clients use `MdbxSecurityAuditEventV2` and `list_security_audit_events_v2` to read optional operation ID, commit ID, policy version, and policy fingerprint fields.

A present `commit_id` always requires a matching `operation_id`. Storage validates the pair against `commit_operations` on local reads and synchronization. A null pair means that the event predates schema 5 or represents a decision that produced no database commit.

### Key Epoch Rotation API

MDBX2 clients request rotation through Rust `KeyEpochService::rotate_authorized` or UniFFI `MdbxVault.rotate_key_epoch`. The returned `previous_epoch_id`, `active_epoch_id`, `commit_id`, and `rotated_at` are the stable result of one rotation. This is an additive interface and does not change any MDBX1-compatible method signature.

Rotation does not use ordinary operation-idempotency retries. When a response is unknown, inspect commit history or `MdbxSecurityAuditEventV2` commit correlation before calling again; another call creates another epoch and commit. The key epoch field in sync payloads remains optional, so older payloads continue to deserialize and preserve local epoch state.

### Exact Vault Content Manifest

Clients that need an exact content checkpoint, rather than only an append-only
watermark, can use `VaultContentManifestService::issue/verify`, the CLI
`mdbx content-manifest create/verify` commands, or the UniFFI
`MdbxVault.create_content_manifest` and `verify_content_manifest` methods. The
bounded opaque token covers non-internal schema objects, column definitions,
and typed values from every main table, including unknown extension tables and
additive columns.

New tokens use manifest profile v2. V2 includes generated and hidden columns
through SQLite `table_xinfo`, adds canonical typed ordering for nullable or
collation-tied rows, and reads authenticated header metadata, vault identity,
and content from one snapshot. Verification remains profile-aware and accepts
previously issued v1 tokens with the original v1 algorithm. The token stays
opaque at the CLI and UniFFI boundaries, so clients do not need a signature or
storage-format migration.

This is an explicit O(vault-size) checkpoint for backup publication, migration
completion, device handoff, or suspected direct rewriting; it is not part of
the routine small-mutation commit path. Any legitimate write invalidates the
old token and requires client-side reissuance. External Blob Provider bodies,
OS state, and availability remain outside the manifest, and the operation does
not change MDBX1/MDBX1-DRAFT reading or migration semantics.

### Build Capability Discovery

Feature-trimmed binaries expose `mdbx-build-capabilities-v1` through Rust
`CapabilitySet::build_manifest`, UniFFI `mdbx_build_capability_manifest`, and
the vault-independent `mdbx capabilities --json` command. The canonical report
separates storage modules from synchronization protocol support and lists both
enabled IDs and known optional IDs omitted at compile time.

This report is process metadata, not vault metadata. It is never written into
MDBX1 or MDBX2, changes no migration or wire format, and is available without
opening the selected path. It does not replace process-local Collection Adapter
registration, critical-extension validation for writable open, or Hello/HelloAck
negotiation with a peer. Existing clients that never request the report retain
their exact behavior.

### Process-Local Extension Profile Registry

Each open `VaultConnection` can register at most 256 canonical
`ExtensionProfile` descriptors for the domain Adapters loaded in that process.
A descriptor assigns one extension namespace to its CollectionTypeIds, custom
ObjectTypeIds, RelationKindIds, write-gating capabilities, optional indexes,
import/export Adapters, and presentation hints. Exact duplicate registration is
idempotent; changed registration, duplicate ownership, and bulk replacement
fail atomically without exposing a partial registry.

The registry is process metadata and is empty after a vault is reopened. It is
not stored in the schema, snapshots, synchronization state, or critical
extensions. Registration does not activate the descriptor's capabilities;
clients separately call `set_extension_capabilities` for executable Adapter
capabilities. Neither operation grants raw SQL, encryption-key, or Tiga
authority.

When a registered descriptor owns a Collection type, subsequent user writes
validate the stored Collection Profile against that descriptor and the active
capability set. Existing or synchronized unknown data remains readable through
opaque compatibility paths. An absent or removed Adapter does not rewrite or
delete its data and does not prevent synchronization, backup, restore, or
recovery. Consequently, the additive registry preserves MDBX1 and earlier
MDBX2 behavior for clients that never use it.

### Binary KDBX Adapter

Binary KDBX interoperability is an optional Adapter and does not change the
MDBX1/MDBX2 schema, migration, synchronization, or JSON bridge. The independent
`kdbx-binary-import` and `kdbx-binary-export` features advertise
`mdbx.storage.kdbx-binary-import` and
`mdbx.storage.kdbx-binary-export`. Existing `kdbx-import`, `kdbx-export`,
`import-kdbx-json`, and `export-kdbx-json` identifiers retain their original
JSON meaning.

Import accepts KDBX3 and KDBX4. It bounds encrypted source bytes and preflights
KDBX3 AES rounds or KDBX4 AES/Argon2 memory, iteration, and parallelism values
before password derivation. After decryption, entry, field, attachment, group
depth, per-item byte, and aggregate projected-byte limits are checked before
`KdbxImporter` can mutate a vault. Wrong credentials, malformed headers,
unsupported KDFs, and limit violations therefore leave MDBX unchanged.

Repository application has two explicit compatibility contracts. The existing
`KdbxImporter::import_entries` method remains source- and behavior-compatible:
it attempts source entries independently, can retain valid siblings, and
reports later entry or attachment failures as warnings. It is a best-effort
legacy bridge and does not promise per-entry or whole-batch atomicity.

New integrations use `KdbxImporter::import_entries_atomic`. The method prepares
the complete import plan before opening a transaction and derives a
domain-separated, length-framed SHA-256 intent digest from every source field
and attachment byte. All projects, Login and Note entries, attachment metadata,
content, history, object versions, heads, and synchronization deltas then run
under one `CommitContext::run_operation`. Any failure rolls back the complete
batch. A retry with the same operation ID and input returns the existing commit
without duplicating objects; changed input with the same ID is rejected. Both
JSON and binary CLI imports allocate a fresh operation UUID only after parsing
or decryption succeeds, and one successful command creates exactly one Commit2
commit regardless of the number of imported objects.

Export writes KDBX4 with Argon2id, 64 MiB memory, three iterations, and two
lanes. CLI passwords come from a hidden prompt or one bounded UTF-8 line on
standard input; there is no password argument. Publication uses a synchronized
temporary sibling and a no-clobber persist operation, so an existing destination
is retained.

The Adapter preserves title, username, password, URL, notes, OTP value, custom
fields, attachments, group paths, UUIDs, built-in icons, and timestamps when
representable. It does not promise complete preservation of history, autotype,
custom icons, recycle-bin state, plugin fields, or passkey plugin structures.
The selected `keepass` parser decompresses gzip content internally before the
Adapter can enforce projected plaintext limits. Encrypted source size and the
returned projection are bounded, while peak memory during gzip decompression is
not independently bounded by this Adapter version. Untrusted-file services that
require a strict process-memory ceiling must add process isolation or a parser
with bounded streaming decompression.

### 7.15 Bounded Steam mafile Adapter

Steam `mafile` interpretation is an optional, independently removable Rust
Adapter in `crates/mdbx-adapter-steam`. It declares the process-local
`com.monica.steam` ExtensionProfile, the `com.monica.steam.mafile` ObjectTypeId,
the `com.monica.steam.store` write capability, and namespaced import/export
feature IDs. The crate is not a storage, sync, CLI, FFI, Android, or network
dependency; a build that omits it continues to preserve the object as opaque
ciphertext.

Clients must treat input mafiles as untrusted JSON and use the Adapter's
bounded parser before creating a generic write operation. Defaults are 1 MiB
input, depth 32, 512 aggregate fields, 512 items per array, 8,192 aggregate
nodes, 64 KiB per string/key, and 1 MiB aggregate string/key bytes. Hard
ceilings are 8 MiB, 64, 4,096, 4,096, 65,536, 1 MiB, and 8 MiB respectively;
clients can lower but cannot disable these limits. Input bytes are checked
before deserialization, duplicate object keys fail, and parser errors contain
no source values.

The Adapter retains unknown fields and emits deterministic canonical JSON, so
an older client can preserve fields introduced by a newer Steam producer. It
derives a stable object digest from a domain-separated, length-framed SHA-256
of a canonical unsigned 64-bit SteamID and trimmed, case-preserving serial
number. The Generic Object projection uses the first 128 digest bits with the
RFC variant and custom UUID version 8. A mafile may carry its own SteamID, or
the client may supply the authenticated account SteamID when that variant
omits it; a mismatch is rejected. The digest and UUID are opaque identities,
not substitutes for encryption, authentication, or a Steam credential.

The Adapter never logs or places payload values in Debug/error text. Clients
must keep parsed documents and canonical bytes in protected process memory and
must pass them through the generic authenticated encryption path before
persistence. Importing several mafiles remains one bounded
`CommitOperation`; the Adapter itself creates no tables, schema columns,
sync fields, commits, or Tiga authority. Removing it must not delete,
retype, or block opaque reads, synchronization, backup, restore, or recovery.

The optional `crates/mdbx-adapter-steam-storage` bridge maps the pure Adapter
to existing generic storage APIs. It has no default features and depends only
on the pure Adapter plus `mdbx-storage` core. Preparation validates the whole
batch, sorts by stable UUID, rejects duplicate identity, and reads existing
state only through payload-free Object summaries. It emits existing generic
create, update, or restore-then-update commands and never adds a Steam table,
column, snapshot field, sync field, critical extension, or key format.

The default bridge batch is 128 documents and 8 MiB aggregate source bytes;
hard ceilings are 2,048 documents and 64 MiB. Exact prepared-plan retry returns
the original commit idempotently, while re-planning against changed vault state
is a new action. Absent input objects are not automatically deleted. Profile
registration and activation of `com.monica.steam.store` remain separate, and
capability failure rolls back the complete operation.

MDBX1 and MDBX1-DRAFT file upgrades remain storage-core migrations. The bridge
does not move compatibility into a client converter. Removing the bridge, or
both Steam crates, preserves existing encrypted generic rows and all legacy
compatibility projections.

### 7.16 Exact Commit Kind Round-Trip

Commit kind is part of the authenticated commit representation. MDBX readers
must preserve these stable values exactly: `change`, `merge`, `snapshot`,
`key-rotation`, `move`, `copy`, `restore`, and `multi`. A reader must not map
an unrecognized database or bundle value to `change`, because the rewritten
value no longer describes the authenticated commit that produced the row.

The core enum retains the original binary order for `change`, `merge`,
`snapshot`, and `key-rotation`; the extended variants are appended after them.
This keeps existing bincode discriminants and legacy bundle fixtures stable.
No schema column, format-version field, bundle-version field, or migration is
added for this repair.

Storage history and CLI bundle loading use the core strict parser. Known values
remain exact; unknown values return an explicit error before export, history
verification, or apply can reinterpret the commit. Bundle decoders likewise
reject an enum discriminant they do not support. New readers continue to read
legacy bundles. A pre-fix reader that coerces an extended database value must
not export or apply that history; upgrading the reader is required because a
safe down-conversion does not exist.

UniFFI history keeps `commit_kind` as the exact string. Native clients may map
known values to localized labels, but must retain the original value for
diagnostics and must never write a display fallback back into MDBX.

### 7.17 Exact Change Scope And Local Write Validation

Change scope is authenticated commit data. Current readers preserve
`project`, `entry`, `attachment`, `object-relation`, `object-label`,
`object-label-assignment`, `vault-meta`, `key-epoch`, `multi`, `snapshot`, and
`branch` exactly. `snapshot` is already produced by automatic snapshot pruning;
`branch` represents the existing branch object family used by tombstone
retention paths.

`Snapshot` and `Branch` are appended after the nine existing core enum variants,
so legacy bincode discriminants 0 through 8 remain unchanged. No schema,
format-version, bundle-version, or migration field is added. New readers keep
decoding old bundles and database rows.

The core owns strict ChangeScope parsing. History verification, CLI database
loading, synchronization tests, and bundle serialization use that exact
contract. An unknown value fails closed; `multi` is never an unknown-value
fallback. A pre-fix CLI that converts `snapshot`, `branch`, or a future scope
to `multi` must not export that history because the converted commit cannot
retain its authenticated identity.

CommitContext also validates both commit kind and change scope before a direct
CommitOperation opens its write transaction. Repository commits coalesced into
an active operation validate their taxonomy before changing aggregate
metadata. The public string-shaped CommitOperation remains source compatible,
but it can no longer persist a value the current core cannot read or transport.

### 7.18 Versioned Operation Request Identity

New operation rows use the existing `request_hash` BLOB as a versioned
`OperationRequestIdentity`. Version 1 is exactly 40 bytes:
`MDBXORI1` followed by the existing 32-byte canonical request digest. Storage
derives it after resolving the stable branch and before executing any repository
mutation. The active operation keeps this initial identity while its final
parents, changed objects, change scope, message, and encrypted summary are
coalesced.

Operation integrity authenticates the complete encoded identity. Retry handling
verifies that integrity before comparing request metadata. A versioned identity
always matches exactly, so reusing an operation ID with changed content is
rejected even when the caller omitted `intent_hash`. An unknown length or an
unknown 40-byte prefix also fails closed.

Existing untagged 32-byte values are preserved without migration. Direct
operation retries and legacy requests with an explicit intent compare exactly.
A legacy coalesced operation without explicit intent retains its earlier retry
semantics because older writers may have replaced the initial request digest
with the final aggregate digest. No migration can reconstruct that lost input
without changing authenticated history.

The operation metadata DTO and bundle versions remain unchanged. CLI export,
synchronization bundles, incoming apply, and database reload carry either byte
representation exactly. MDBX1 and MDBX1-DRAFT contain no operation metadata;
their read and upgrade behavior is unaffected. Current readers accept legacy
rows, while reliable retry of a current 40-byte identity requires a current
storage core.

### 7.19 Exact Existing Commit Replay

Synchronization may receive a state delta or other transport payload after its
commit row already exists. Before applying that late payload, storage compares
the incoming commit with the local row across device, sequence, exact kind and
scope, encrypted changed IDs, vector clock, message, creation time, integrity
tag, and canonical parent membership. A changed value under the same commit ID
is rejected before state mutation.

First receipt of a commit still recomputes its integrity tag with the active
connection keyring. Existing replay compares the incoming bytes and tag with
the already accepted local identity. This distinction preserves historical
commits whose original verification mode cannot be reconstructed from the
connection's current state while still preventing a second authenticated
meaning for the same ID.

Operation metadata remains optional for legacy bundles. A commit first received
without it can later accept the original authenticated metadata. Once present,
the operation ID and commit ID form a one-to-one mapping, and operation kind,
branch ID/name, encrypted summary, request identity, creation time, and
integrity tag must all match. An older replay that omits the metadata leaves the
local row intact.

No database or bundle version changes. MDBX1, MDBX1-DRAFT, and bundle v1 continue
to omit operation metadata. Exact late payload repair remains idempotent; a peer
that regenerates commit or operation metadata must resend the original
authenticated values before synchronization can continue.

### 7.20 Representable Incoming Commit Structure

A valid integrity tag proves the incoming commit bytes are authentic, but the
first local insertion also requires an exact representation in the current
storage model. Before SQL mutation, storage checks that `local_seq` fits a
signed 64-bit SQLite INTEGER, the vector clock decodes as a JSON object of
unsigned 64-bit values, and the parent list contains no duplicate ID.

Parent order remains compatible because commit integrity sorts it. Duplicate
membership has no compatible projection: `commit_parents` stores one row per
pair, so silently deduplicating would change the authenticated parent count.
Malformed clock text cannot be retained because local child creation consumes
the stored clock as a map. A sequence above `i64::MAX` cannot be wrapped without
changing device order.

Legacy `{}` vector clocks remain valid for MDBX1 and migrated history. The
preflight does not require a modern self-device clock entry and does not rewrite
historical causal detail. Existing exact replay continues through section 7.19
and compares the already accepted local bytes instead of applying new
first-insertion rules retroactively.

No schema, format, bundle, or DTO version changes. Current producers already
emit representable values. Invalid new commits fail before commit inventory,
parents, sequence floors, payloads, tombstones, device heads, or branch heads
can change.

### 7.21 Monotonic Device-Local Heads

Device heads now use one storage rule for serialized commit ingestion and
state-delta ingestion. The referenced commit must be authored by the claimed
device. A higher `local_seq` advances the head across branch boundaries; a
delayed lower sequence remains stored and forwardable without replacing the
newer head. Reapplying the same commit is idempotent. Observation time keeps the
later value, and revocation cannot be cleared by synchronization.

The existing `(device_id, local_seq)` unique index already defines one commit
identity per device sequence. New synchronization preflights that identity and
returns a validation error before INSERT when another commit ID owns the slot.
Renumbering is not a compatibility conversion because the sequence is part of
authenticated commit identity and causal order.

Health verification reports device heads that reference a missing commit, a
commit authored by another device, or a sequence below a later accepted commit
from the same device. It reports the legacy state without rewriting history.

No schema, migration, format, bundle, DTO, or capability version changes. MDBX1
and MDBX1-DRAFT delayed commits remain valid input, and current receivers retain
their newer head. A receiver needs the current storage core to obtain the new
delivery-order-independent merge behavior; older receivers continue to use
their historical apply rule because the wire representation is unchanged.

### 7.22 Causal Monotonic Tombstone Acknowledgements

The existing schema-8 acknowledgement row now has one storage-owned runtime
merge. Its `observed_commit_id` must reference an available commit and, when the
tombstone has `delete_commit_id`, must equal or causally descend from that
deletion. A pre-delete commit and a commit concurrent with the deletion are not
observation proofs.

For one tombstone and device, a descendant replaces an ancestor while an
ancestor cannot replace a descendant. Two valid concurrent proofs select the
greater `(acknowledged_at, observed_commit_id)` after causal comparison, so
arrival order does not change the stored row. The acknowledgement time itself
keeps the later value even when the selected commit does not change.

Complete-state and state-delta rows still skip an acknowledgement when its
tombstone or observed commit is absent locally. When both references exist, a
non-causal proof rejects the transaction. Tombstones with a `NULL`
`delete_commit_id` preserve their legacy representation and require only an
available observed commit. Health verification reports a stored proof that does
not satisfy these rules without rewriting it.

No table, column, index, migration, format generation, synchronization DTO,
state version, bundle version, or capability bit changes. The schema-8
migration backfill is unchanged, and MDBX1 plus MDBX1-DRAFT continue through the
same storage-core upgrade sequence. Older readers can parse the same rows and
wire payloads but retain their earlier overwrite behavior; a current storage
core is required for causal validation and delivery-order-independent merging.
