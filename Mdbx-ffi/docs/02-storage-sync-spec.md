# MDBX Storage And Sync Specification

Version: `MDBX-1-DRAFT`

This document defines the single-file container strategy, internal persistence rules, incremental update behavior, sync model, and attachment storage behavior.

## 1. Container Strategy

MDBX SHOULD use a single portable `.mdbx` file as the user-visible vault artifact.

Inside the `.mdbx` file, the preferred engine is:

- `SQLite + custom encryption layer`

`LMDB` MAY be explored later, but SQLite is the preferred baseline because of tooling maturity, portability, recovery tooling, and schema evolution support.

### Vault Creation Lifecycle

Vault creation MUST atomically reserve a path that does not exist. An existing regular file, SQLite database, MDBX vault, or same-name SQLite WAL/SHM sidecar MUST be rejected without changing its contents. A client-side existence check may improve the error message, but the storage reservation remains authoritative.

Creation remains pending until schema creation, vault metadata, the genesis commit, the initial branch, the device head, the initial key epoch, and the first unlock method have all succeeded. Failure before that point MUST close the SQLite connection and remove the main database plus any WAL and SHM sidecars created by the same attempt. Opening or upgrading an established vault uses the open and migration interfaces, never the create interface.

### Existing Vault Open Lifecycle

Open and explicit upgrade MUST first inspect the file through a read-only SQLite handle. The preflight must confirm an initialized `vault_meta` row, a supported MDBX format generation, and the absence of unknown critical extensions before any writable handle, WAL mode change, migration, or compatibility cleanup is allowed.

The writable handle MUST use read-write flags without SQLite create permission. A missing path or an uninitialized SQLite database is an error and must remain unchanged. Connection-only settings such as foreign-key enforcement and busy timeout may be applied before migration; persistent WAL and secure-delete settings plus legacy plaintext-index cleanup are applied only after identity validation and a successful transactional migration.

### Portable Backup Lifecycle

A portable backup is a transactionally consistent, self-contained `.mdbx` file produced from a live vault. The storage layer MUST use SQLite's online backup API or an equivalent database snapshot mechanism so committed pages still present only in the source WAL are included. Copying the source main file while WAL is active is not a complete backup operation.

The backup MUST be built in a temporary file in the destination directory, converted to a non-WAL journal mode, checked with SQLite integrity verification, and inspected as a supported initialized MDBX vault. The copied format and schema metadata plus `vault_id` MUST equal the source. The temporary file MUST be synchronized before publication, and publication MUST use no-clobber semantics.

The destination main file and its same-name `-wal` and `-shm` sidecars MUST all be absent. Any existing destination artifact is preserved and causes the operation to fail. A successful portable backup has no required sidecars and can be opened independently with the source vault's existing unlock methods.

The storage facade is authoritative for these guarantees. An already opened Rust vault uses `BackupService::create_portable_copy`, while a client-controlled migration uses the read-only `BackupService::create_portable_copy_path` before writable open. UniFFI exposes the same distinction through `MdbxVault.create_backup` and top-level `create_portable_backup`. The reference CLI uses the read-only path operation for `mdbx backup <output>`, so backup neither requires unlock credentials nor triggers automatic migration.

Read-only path backup MUST preserve a supported MDBX1, MDBX1 draft, or MDBX2 generation in the result. It MUST leave persistent source database and WAL bytes unchanged. SQLite MAY update transient read marks in an existing SHM coordination file while reading a live WAL source; SHM is rebuildable and is not part of the portable result.

## 2. Internal Storage Goals

The internal layout MUST support all of the following:

- append-friendly writes
- partial updates
- crash recovery
- attachment metadata storage
- attachment binary storage indirection
- version history
- conflict detection metadata
- future migration hooks

## 3. Minimum Internal Logical Tables

The minimum logical schema MUST reserve space for at least these record classes:

- `projects`
- `entries`
- `attachments`
- `attachment_chunks`
- `commits`
- `commit_parents`
- `device_heads`
- `branches`
- `tombstones`
- `snapshots`
- `key_epochs`
- `conflicts`
- `unlock_methods`
- `object_versions`
- `project_tags`

An MVP MAY omit some secondary indexes or optional tables, but MUST NOT omit `projects` or `attachments`.

## 4. Project-Oriented Schema Rules

The `projects` table is mandatory.
The `entries` table MUST reference `project_id`.

This means:

- every password-like secret belongs to a project
- queries MUST be able to fetch a project and then its child entries
- sync and merge logic MUST preserve project membership

## 5. Attachment Schema Rules

The `attachments` table is mandatory from version 1.
The `attachment_chunks` table SHOULD be present from version 1 even if chunked storage is only partially used in MVP.

The schema MUST support:

- attachment owned by project
- attachment optionally owned by a specific entry
- content hash
- chunked binary data or external content reference
- soft delete via tombstone or delete marker
- integrity verification

## 5.1 Object Payload And Large-Content Boundary

`entries.payload_ct` is the bounded structured-data plane for a generic ObjectRecord. It is suitable for password fields, bookmark properties, message headers and normalized small bodies, contacts, `mafile` documents, and versioned JSON owned by domain adapters. An object may reference large content through stable attachment or blob IDs, but arbitrary binary or source-document bytes must not all be forced into one payload.

Policy-authorized object disclosure returns at most 8 MiB of plaintext by default. A client may select a resource profile from 1 byte through the 64 MiB hard ceiling. Storage checks ciphertext length before loading the BLOB and verifies the actual plaintext length after authenticated decryption. This read boundary does not alter MDBX1 complete-record compatibility APIs or existing database bytes.

Large message bodies, raw MIME/EML, saved-page archives, files, and media SHOULD use `attachments` / `attachment_chunks` or an encrypted blob provider. That path MUST provide bounded chunks, streaming transfer, content hashes, ownership, and lifecycle, and routine object edits MUST NOT rewrite the large content.

## 5.2 Generic Metadata Selection Boundary

Relation, label, and label-assignment navigation MUST use bounded summary projections for large client views. Relation and label summaries MUST NOT select their encrypted payload columns. A label summary MAY decrypt its validated display name; an assignment summary contains identifiers and causal metadata only.

Pages contain 1 through 200 items, use descending update time plus stable ID keyset ordering, and return a versioned opaque cursor bound to the exact direction, owner, collection, and optional relation-kind filter. By-ID relation and label summaries retain deleted-state visibility, while list pages contain active rows. Complete-record repositories remain compatibility and explicit-payload interfaces, not the default collection or graph traversal path.

### 5.2.1 Attachment Metadata Navigation

Attachment list and deleted-item navigation MUST use a payload-free `AttachmentSummary` projection. The projection may authenticate the file name and optional media type, but MUST NOT select `attachment_chunks.chunk_ct` or `external_uri_ct`; corrupt binary content or an unavailable external provider must not block a metadata-only page. Collection pages and Object pages use the same 1-to-200 keyset contract, and deleted pages use a separate query-bound cursor. The by-ID summary retains deleted-state visibility.

The fixed display limits are 4096 UTF-8 bytes for a file name and 512 UTF-8 bytes for a media type. SQL MUST check ciphertext length and conditionally project the BLOB before materialization, using the shared 128 KiB envelope allowance. Storage MUST authenticate/decrypt the bounded field, recheck the exact plaintext byte length, and reject invalid UTF-8. Existing complete attachment repositories remain the MDBX1-compatible and explicit content/repair path.

## 5.3 Generic Metadata Disclosure Boundary

An explicit relation payload read MUST authorize `RevealSecret` against both endpoint Entry scopes, preserving the source and target decisions independently. An explicit label payload read MUST authorize the owning collection's Project scope. These rules reuse existing scopes; Relation and Label MUST NOT be persisted as new scope types merely to implement payload disclosure.

Scope routing may select stable endpoint or collection IDs, but it MUST NOT select payload ciphertext. Routing, all scope evaluations, denial or success audit rows, deleted-state checks, size gates, and authenticated decryption MUST share one immediate transaction. If any required scope does not allow, the result contains every scoped decision and no payload; deletion state, `length(payload_ct)`, BLOB materialization, and decryption are skipped. Related relation decisions use one shared non-commit operation ID.

Relation and label disclosure share an 8 MiB default plaintext limit, a 64 MiB hard ceiling, the pre-load ciphertext-length gate with 128 KiB envelope allowance, and the post-decrypt exact plaintext check. Active-session idle time is renewed only when plaintext is returned. Existing complete metadata reads remain byte/API compatible and are not reclassified as policy-aware methods.

## 6. Write Path Requirements

Routine small edits MUST avoid full logical rewrite of the entire vault contents.

A compliant write path SHOULD:

1. update changed project or entry rows only
2. append a commit or oplog record
3. update lightweight head metadata
4. avoid touching unrelated attachment rows
5. avoid touching unrelated large binary pages

## 7. WAL And Append Strategy

The preferred implementation SHOULD use SQLite WAL mode or an equivalent append-friendly strategy.

Design goals:

- small edits generate small write deltas
- cloud sync tools can propagate small changed regions where supported
- periodic compaction is explicit and infrequent

The implementation MUST document how it preserves durability during power loss or crash.

## 8. Commit And History Model

MDBX MUST maintain a Git-like logical history.

Minimum requirements:

- each local mutation produces a commit-like history record
- commits reference one or more parent commits
- device-local order is monotonic
- concurrent histories remain representable until merged

A commit record SHOULD include:

- commit ID
- device ID
- local sequence number
- parent commit IDs
- changed object references
- exact authenticated commit kind
- exact authenticated change scope
- timestamp
- optional merge metadata
- integrity data

The stable ChangeScope values are `project`, `entry`, `attachment`,
`object-relation`, `object-label`, `object-label-assignment`, `vault-meta`,
`key-epoch`, `multi`, `snapshot`, and `branch`. `multi` is used only when one
finite operation spans known families. Readers and writers MUST reject an
unknown scope instead of converting it to `multi`, because kind and scope are
inputs to commit integrity. A new local CommitOperation MUST validate both
taxonomies before its write transaction begins.

Every new `CommitOperation` MUST persist the immutable submitted-request
identity before executing repository mutations. The existing `request_hash`
BLOB carries version 1 as the eight-byte `MDBXORI1` prefix followed by the
existing 32-byte canonical request digest. The digest is derived after stable
branch resolution. Commit coalescing MAY update the resulting commit metadata
and encrypted change summary, but MUST preserve this initial 40-byte identity.

Retry processing MUST verify operation integrity before using stored metadata.
A versioned identity MUST match the submitted request exactly, including when
`intent_hash` is absent. Existing untagged 32-byte hashes remain legacy input:
direct operations and explicit legacy intents still compare exactly, while a
legacy coalesced operation without explicit intent retains historical retry
behavior because its original digest may have been overwritten by an earlier
writer. Every other length or 40-byte value with an unknown prefix MUST fail
closed. Synchronization MUST carry either recognized representation as opaque
authenticated bytes without normalization.

An incoming commit whose `commit_id` already exists MUST match the stored
device ID, local sequence, exact kind and scope, encrypted changed-object IDs,
vector clock, optional message, creation time, integrity tag, and canonical
parent set before any payload is applied. First insertion verifies the tag with
the active connection keyring. Replay compares the complete incoming identity
and tag with the already accepted local bytes; it MUST NOT reinterpret or
regenerate historical commit metadata.

Late object payloads and state-delta envelopes MAY be applied after that exact
match. Incoming operation metadata is additive when the local commit has no
operation row. When a row already exists under either the operation ID or
commit ID, both IDs plus operation kind, branch identity and name, encrypted
summary, request identity, creation time, and integrity tag MUST match exactly.
An older bundle MAY omit operation metadata without deleting or weakening the
local row. Any mismatch MUST fail before payload mutation.

For a commit that is not yet present, successful integrity verification MUST be
followed by structural preflight before the first SQL mutation. `local_seq` MUST
fit a signed 64-bit SQLite INTEGER, `vector_clock` MUST decode as a JSON object
whose values are unsigned 64-bit integers, and every parent ID MUST occur once.
Parent order MAY differ because integrity canonicalizes it; duplicate parent
membership MUST be rejected because the relational parent table cannot preserve
multiplicity. The legacy empty clock `{}` remains valid. Structural rejection
MUST leave commit, inventory, parent, sequence, payload, tombstone, device-head,
and branch state unchanged.

The `(device_id, local_seq)` identity MUST remain unique. A new commit that
reuses an accepted sequence for a different commit ID MUST receive a validation
error before insertion; the existing unique index remains a storage backstop.

A device head MUST reference a commit authored by the same device and represent
that device's greatest accepted local sequence. Ordering uses `local_seq`, not
DAG ancestry, because one device can author commits on different branches. A
higher sequence advances the head; a delayed lower sequence remains in history
without moving the head backward; the same commit is idempotent. `last_seen_at`
keeps the later known value and `revoked` merges monotonically. Health checks
MUST report dangling, wrong-device, and regressed heads.

A tombstone acknowledgement MUST be causal evidence. Its observed commit MUST
exist and, when the tombstone has `delete_commit_id`, MUST equal or descend from
that delete commit. For one tombstone and device, descendant evidence advances
ancestor evidence, ancestor evidence cannot replace a descendant, and
concurrent valid evidence selects the greater
`(acknowledged_at, observed_commit_id)` pair after causal comparison. The stored
acknowledgement time MUST keep the later known value. A legacy tombstone with no
delete commit requires an existing observed commit but MUST NOT be assigned an
invented causal ancestor.

Commit parent reads and ancestry used by synchronization, conflict-base
discovery, acknowledgement validation, cleanup eligibility, and health
verification MUST have one storage-owned interpretation. Timestamps and device
heads MUST NOT substitute for commit ancestry.

## 9. Conflict Detection

MDBX MUST detect concurrent edits using causal metadata, not timestamp alone.

Minimum acceptable mechanisms:

- version vectors
- device sequence graph
- per-record revision lineage
- field-level conflict markers where necessary

Different-field concurrent changes within the same project MAY auto-merge when safe.
Same-field concurrent secret changes MUST create an explicit conflict.

### 9.1 Key Epoch State

The key epoch field in sync state MUST remain optional so MDBX1 and early MDBX2 payloads continue to deserialize. When present, it carries the active epoch ID, every active and retired row in canonical ID order, and a state integrity tag.

A fast-forward rotation selects the incoming active epoch. Concurrent rotations compare candidate activation time and epoch ID deterministically, retain the union of valid wrappers, and retire candidates that are not selected. Rewriting wrapper bytes, profile, creation time, or activation time under an existing epoch ID is rejected.

Changing epoch state requires a verified-unlocked mutable connection. The apply transaction verifies the state tag and wrappers before writing object ciphertext that depends on the new epoch, then refreshes active and historical epoch keyrings after commit. Older payloads without this field do not clear or roll back local epoch state.

### 9.2 Transactional State Deltas

After the bootstrap floor, an outer write transaction SHOULD materialize one bounded immutable state-delta batch. A batch with associated commits is attached to the final associated commit; a transaction without a commit produces an auxiliary batch and MUST NOT add a user-visible history record.

The receiver MUST authenticate vault and batch identity, payload digest, row count, commit ownership, and resource limits before accepting state. Every associated commit MUST be available. A recognized delta cannot be mixed with complete sync state or a second delta on the same serialized commit. Commit insertion, sparse state application, attachment chunk replacement, device-head merge, authorized deletion, received-batch persistence, and incoming capture cleanup MUST commit or roll back together.

Delta tombstone rows are sparse and MUST NOT replace unrelated local tombstones. Tombstone acknowledgement rows whose references are present use the same causal and monotonic merge as local deletion and complete-state apply; a non-causal proof rejects the enclosing transaction. Missing-reference rows retain the complete-state compatibility skip behavior. Device-head rows use the same authored device-local sequence rule as commit ingestion, and device revocation merges monotonically. Physical object or tombstone deletion requires a matching authenticated permanent-purge receipt. Key epoch changes require the mutable verified-unlocked apply path; the immutable compatibility path rejects them atomically.

Complete sync state remains the bootstrap and old-peer fallback. Bundle v1-v3 retain their existing formats. Bundle v4 carries bounded commit and delta inventories after a paired checkpoint, binds resumed segments by transfer ID, segment index, and the previous payload digest, and advances the receiver checkpoint only after one segment is durably applied. Commit-associated and auxiliary deltas in a segment share one database transaction. A client MUST NOT claim incremental convergence until it exchanges both checkpoint classes and preserves the segment resume state.

Bundle v5 is the zstd representation of a complete v3 logical payload, and bundle v6 is the zstd representation of an incremental v4 logical payload. Their 20-byte header area stores the compressed length, the uncompressed bincode length, and four zero reserved bytes. The trailer remains SHA-256 of the uncompressed bincode payload, so an incremental resume chain has the same identity regardless of compression. Writers MUST bound both serialization and compressed output without buffering an entire maximum-size logical payload. Readers MUST validate both declared lengths before allocation and cap streaming decompression at the declared uncompressed length plus one byte. Length mismatch, expansion beyond the configured limit, compressed-stream corruption, non-zero reserved bytes, hash mismatch, and trailing data MUST fail.

Complete sync-state decoders preserve bounded unknown non-critical top-level fields during decode and re-encode. Extension keys cannot shadow defined fields, and extension field count, encoded bytes, key length, and nesting depth are bounded. Versioned delta envelopes and cursor tokens remain strict protocol records and continue to reject unknown fields.

Protocol-v2 peers advertise commit inventory paging, delta inventory paging, bundle v4, and incremental resume as four additive capabilities. Incremental v4 is selected only when all four are negotiated. A paging-capable Hello omits the legacy `known_commit_ids` inventory; commit and delta pages carry bounded opaque checkpoint/cursor tokens instead. Missing or partial capability sets MUST fall back to bounded complete state. The transport-neutral `SyncClient` advances its paired checkpoint only through the durable acknowledgement API, never when a segment is merely received or validated.

`bundle-zstd-v1` is an independent optional capability and is not part of the four-capability incremental contract. A transport-neutral sender may select zstd only when the codec is compiled in and both peers advertise this capability. Builds without the codec preserve v1-v4 and return an explicit unsupported-feature error for v5/v6. File export requires an explicit compression choice; the CLI `sync bundle --compression` default is `none`, while apply auto-detects supported versions. This prevents a new writer from silently sending v5/v6 to an old reader, which rejects those versions.

## 10. Merge Model

MDBX SHOULD support:

- fast-forward merge
- three-way merge for non-secret text fields
- conflict record creation for unsafe merges
- user-visible merge resolution later

The merge system MUST preserve both sides when automatic resolution is unsafe.

## 11. Snapshot And Recovery

MDBX MUST support recovery from logical corruption or interrupted sync.

Minimum requirements:

- historical commits remain replayable
- snapshots can be produced periodically
- snapshots can rebuild projects, entries, attachment metadata, and embedded attachment chunks when present
- partially damaged vaults SHOULD still allow partial recovery

A snapshot is a logical recovery point stored inside a vault. It is distinct from a portable backup, which creates an independently openable complete vault file, and from a sync bundle, which carries incremental commit state between replicas. None of these artifacts can be replaced by copying only the SQLite main file while WAL is active.

New snapshots created by a verified-unlocked writer use the `MDBXSN2` payload
profile and a versioned `hmac-sha256-v1` integrity descriptor. The HMAC binds
the vault ID, snapshot ID, base commit, stored ciphertext digest, creation
timestamp, and creating device in the same transaction as the existing
snapshot commit. The first such write registers the critical extension
`snapshot-record-auth-v1`; readers that do not understand the profile must
reject the vault instead of applying legacy snapshot AAD rules. Existing
64-hex SHA-256 snapshots and their original `payload` AAD remain readable and
restorable. Locked checks can validate the public ciphertext digest and
descriptor shape; keyed metadata verification and payload authentication occur
after unlock.

Offline sync bundle readers MUST enforce a payload limit before allocation and deserialization. Bundle v3 and v4 store the uncompressed payload length in the header and reject non-zero reserved bytes or data after the payload hash. Bundle v5 and v6 apply the same configured limit independently to compressed input and decompressed output. Bundle v1 and v2 compatibility readers MUST cap the underlying reader rather than call an unbounded read. Resource profiles may choose a lower limit; the protocol hard ceiling remains mandatory.

## 12. Attachment Storage Modes

MDBX MUST define these storage modes even if not all are enabled in MVP:

- `embedded-inline`
  - small binary stored directly in attachment payload

- `embedded-chunked`
  - attachment stored in encrypted chunks inside the database

- `external-hash-ref`
  - database stores metadata plus verified external blob reference

Default recommendation:

- small attachments MAY be embedded
- large attachments SHOULD be chunked or externally referenced by content hash

## 13. Attachment Update Rules

Editing project metadata MUST NOT require rewriting large attachment content.
Editing entry fields MUST NOT require rewriting unrelated attachment content.
Renaming an attachment MUST update metadata only.

## 14. Cloud-Drive Optimization

MDBX is designed for sync through tools such as Syncthing, Git, Nextcloud, WebDAV-backed sync layers, Dropbox, and OneDrive.

The implementation SHOULD:

- minimize rewritten regions for small edits
- prefer append-heavy patterns over random rewrite where practical
- compact only when thresholds are met
- keep attachment bodies isolated from routine metadata edits

## 15. Performance Targets

Target goals for a healthy implementation:

- common metadata save under `100 ms`
- project open fast enough for interactive UI
- search clearly faster than large KDBX libraries
- cloud-drive delta for small edit remains in `KB` scale in normal cases

These are product goals and must be tracked with benchmarks.

## 16. Required Indexing

The storage engine SHOULD maintain indexes for at least:

- project title
- project tag membership
- project group membership
- entry type by project
- recent modification time
- attachment ownership
- tombstone lookup
- commit lineage lookup

Full-text search MAY use temporary indexes for decrypted titles during an unlocked session. Persistent FTS tables MUST NOT store decrypted project titles or other secret-bearing text.

Temporary search indexes are not user-visible history and MUST NOT create commits. User-visible project tags are metadata, not temporary search state: tracked tag mutations SHOULD create a project-scoped commit, and sync state SHOULD carry the complete tag set for each project so tag deletion, including deleting the final tag, can be replayed safely. Readers that receive an older sync payload without a tag field MUST preserve local tags instead of treating the missing field as an empty set.

## 17. Compaction Rules

Compaction MAY rewrite larger portions of the vault, but it MUST be:

- explicit or policy-driven
- recoverable if interrupted
- unnecessary for routine edits
- safe for attachment integrity

## 18. Minimum Export Requirements

The storage layer MUST support export paths for:

- full vault export
- project export
- attachment extraction with integrity check
- KDBX export bridge

## 19. Rejection Rules

A storage design is non-compliant if it:

- lacks a first-class `projects` structure
- lacks first-class `attachments` structure
- rewrites the whole vault on ordinary small field edits by design
- cannot represent concurrent histories
- treats an arbitrary existing commit as proof that a device observed a later deletion
- cannot explain recovery after interruption
