# MDBX Domain Context

## Purpose

MDBX is a local-first advanced encrypted database core for authenticated, versioned objects and binary content. Password management is one domain adapter, alongside bookmarks, mail, Steam `mafile`, and future application domains. The core keeps encryption, collections, object records, attachments, commits, synchronization, conflicts, snapshots, and policy independent from product-specific payload meaning.

The database core accepts opaque application payloads and provides durable security properties around them: authenticated encryption at rest, stable object identity, atomic commit operations, causal synchronization, recovery, audit, key epoch rotation, and MDBX1 compatibility. Application meaning and presentation stay in optional adapters.

## Domain Vocabulary

### Vault

A `Vault` is one encrypted MDBX database with a stable vault identity, unlock methods, Tiga policy, key epochs, commit history, and synchronization state.

### Collection

A `Collection` is a stable container for object records. Password projects, bookmark folders, mailboxes, and Steam account groups are domain presentations of this concept. MDBX1 stores collections in the physical `projects` table; the generic Module hides that compatibility implementation from new callers.

### CollectionProfile

A `CollectionProfile` is the optional versioned semantic description attached to one Collection. It declares a stable namespaced CollectionTypeId, an encrypted adapter-owned payload, the ObjectTypeIds accepted by the Collection, and the ExtensionCapabilityIds required for user-visible writes. MDBX1 Collections have no profile and retain legacy behavior.

Profile existence is monotonic and CollectionTypeId is immutable. Payload schema versions and declarations can advance through one tracked Collection mutation. A missing profile field in a legacy object version or synchronization payload means that producer made no assertion about the profile; it does not delete a profile already known by the receiver.

### CollectionTypeId

A `CollectionTypeId` is the exact namespaced identity of a Collection contract, such as `com.monica.mail`, `com.monica.bookmark`, or `com.monica.steam`. It is separate from ObjectTypeId because one Collection can accept several related object contracts.

### CollectionSummary

A `CollectionSummary` is the bounded, payload-free discovery projection of a Collection. It contains stable identity, decrypted title, optional CollectionProfile type/version, group and icon references, favorite/archive state, attachment count, head commit, deletion state, and update time. It never selects the legacy Project summary or the CollectionProfile payload.

Active and deleted Collections use separate keyset-paginated queries. This lets a reopened generic client discover password, bookmark, mail, Steam, and future adapter Collections without remembering IDs or loading complete Projects.

### ExtensionCapabilityId

An `ExtensionCapabilityId` identifies adapter code available in the current client process, such as `com.monica.mail.store`. Capability declarations are not persisted as authority and do not grant key access. A connection registers the capabilities its adapters actually provide; the Generic Object Module rejects user-visible writes when a CollectionProfile requires capabilities absent from that connection. Locked synchronization, backup, snapshot, health inspection, and opaque preservation remain available without the adapter.

### ObjectRecord

An `ObjectRecord` is one encrypted, versioned item inside a collection. Password entries, bookmarks, mail messages, mail contacts, and `mafile` documents are ObjectRecords. MDBX1 stores ObjectRecords in the physical `entries` table.

### ObjectSummary

An `ObjectSummary` is the bounded list projection of an ObjectRecord. It contains stable identity, collection, exact ObjectTypeId, optional decrypted title, payload schema version, head commit, deletion state, and update time, but never the object payload. Summary pages use query-bound keyset cursors so collection screens do not decrypt every password, mail body, bookmark document, or extension payload.

Active and deleted navigation share the projection but not the cursor scope.
`list_object_summaries` lists active rows; additive deleted pages distinguish
Collection-scoped tombstones from the global tombstone view. Their cursors bind
active/deleted state, Collection, optional ObjectTypeId, and keyset position.
The CLI and new clients use these pages, while complete MDBX1 list APIs remain
available for explicit payload consumers.

### ObjectTypeId

An `ObjectTypeId` is the exact stable identifier for an ObjectRecord payload contract. MDBX legacy identifiers such as `login`, `note`, and `totp` remain valid. Extension identifiers use a namespaced form such as `com.monica.bookmark`, `com.monica.mail.message`, or `com.monica.steam.mafile`.

The core preserves every valid ObjectTypeId exactly. An unknown identifier remains unknown and must never be converted to a password type or another fallback. Interpretation belongs to a domain adapter.

### PayloadSchemaVersion

`PayloadSchemaVersion` is the unsigned version of the payload contract owned by an ObjectTypeId. It is independent from the MDBX database schema version. A domain adapter migrates its own plaintext payload after authenticated decryption; the core stores and synchronizes the declared version.

### PayloadMigrationPlan

A `PayloadMigrationPlan` is a bounded, short-lived description for advancing one ObjectTypeId payload contract. Before loading or decrypting any source payload, the storage core authorizes `TigaOperation::MigratePayload` against the owning Collection's Project scope. It then creates the plan from one consistent database snapshot and binds it to the CollectionProfile, branch head, object heads, source schema version, and digests of payload bytes obtained after authenticated decryption. The plan returns source payload bytes only to the Adapter that registered the Collection's required capabilities, and its authorization audit is correlated by the transient `plan_id` without a commit.

The Adapter interprets each source payload and returns target payload bytes. Execution reauthorizes `MigratePayload`; policy evaluation, binding checks, object updates, one idempotent CommitOperation, its security audit, and sync-delta materialization share one immediate transaction. Plans are not persisted because they contain decrypted Adapter payloads and become stale whenever bound state changes. Source and target plaintext must never enter audit rows, commit metadata, synchronization state, or another persistent cache.

### ObjectRelation

An `ObjectRelation` is a typed directed edge between stable objects. It represents mail thread membership, reply relationships, bookmark aliases, label membership, contact links, Steam account ownership, or future cross-domain references. Relation kinds use stable namespaced identifiers and participate in commit, tombstone, snapshot, and synchronization rules.

### ObjectLabel

An `ObjectLabel` is a stable searchable classification attached to an ObjectRecord. Labels support mail labels, bookmark tags, and domain-neutral organization. They are user-visible metadata and therefore participate in commits and synchronization.

### Attachment

An `Attachment` is authenticated binary content or an external content reference owned by a Collection and optionally by an ObjectRecord. Mail attachments and `mafile` source documents use the same attachment integrity rules as password-vault files.

An `AttachmentSummary` is the bounded, payload-free navigation projection of an Attachment. It contains ownership, authenticated file name/media type, storage mode, content hash, sizes, chunk count, head commit, deletion state, and update time, but never attachment chunk bodies or external URI ciphertext. Collection, Object, deleted, and by-ID summary queries use query-bound keyset cursors. File names are limited to 4096 UTF-8 bytes and media types to 512 bytes; complete Attachment reads remain the explicit MDBX1-compatible content/repair path.

### ExtensionProfile

An `ExtensionProfile` is the bounded canonical process-local declaration supplied by one loaded domain Adapter. It declares an ExtensionId, profile version, CollectionTypeIds, custom ObjectTypeIds, relation kinds, capabilities, optional indexes, import/export Adapters, and presentation hints. Every identifier belongs to the extension namespace. The profile is never persisted, synchronized, or treated as key, Tiga, SQL, or write authority.

### ExtensionFeatureId

An `ExtensionFeatureId` is a namespaced non-authority identifier for an optional index, import/export Adapter, or presentation hint declared by an ExtensionProfile. It describes code or presentation behavior present in the process and is separate from ExtensionCapabilityId, which gates user-visible Collection mutations.

### SteamMaFileAdapter

`SteamMaFileAdapter` is the optional pure-Rust Adapter in
`crates/mdbx-adapter-steam`. It declares the `com.monica.steam` ExtensionProfile
and interprets `com.monica.steam.mafile` only as a bounded JSON document. The
Adapter has no network, Android, SQLite, key, or Tiga dependency. It preserves
unknown mafile fields in deterministic canonical output, rejects duplicate
keys, and exposes a stable non-secret object ID derived from a canonical
SteamID and serial number. A document may provide its own SteamID; clients may
also supply the authenticated account SteamID when a mafile variant omits it.
The same domain-separated digest has a deterministic RFC variant, custom
version-8 UUID projection for the Generic Object Module.
The Adapter's profile and parser are process-local code metadata; neither is
stored in a Collection, snapshot, or synchronization wire payload.

### SteamMaFileStorageBridge

`SteamMaFileStorageBridge` is the independently removable mapping in
`crates/mdbx-adapter-steam-storage`. It accepts bounded mafile sources, uses
the pure Adapter for parsing and stable UUID identity, reads existing state
through payload-free ObjectSummaryRepo, and prepares existing generic
WriteCommands for one OperationCoordinator commit. It owns create, update,
restore-then-update, deterministic ordering, aggregate limits, duplicate
identity rejection, and exact prepared-plan retry rules, but it adds no Steam
schema, synchronization field, key access, raw SQL, or Tiga authority.

Profile registration and `com.monica.steam.store` capability activation are
separate client actions. Missing input documents do not imply deletion.
Removing the bridge removes this mapping only; removing both Steam crates
still leaves stored objects preservable as opaque generic records.

### CapabilitySet

A `CapabilitySet` is the compile-time and runtime set of optional adapters present in a build. Core readers, MDBX1 compatibility, encryption, commits, and synchronization are mandatory. KDBX import/export, benchmarks, mail indexes, bookmark indexes, and Steam adapters can be excluded when unused.

### CommitOperation

A `CommitOperation` is one finite user intent executed atomically and represented by one commit whenever practical. Importing one `mafile`, moving a bookmark group, or applying one mail synchronization batch can contain multiple row mutations without producing a commit per internal row.

### OperationRequestIdentity

`OperationRequestIdentity` is the immutable identity of the submitted
`CommitOperation`, distinct from the final aggregate change summary. New rows
encode it in the existing `request_hash` BLOB as the eight-byte `MDBXORI1`
prefix followed by the existing 32-byte canonical request digest. The identity
is derived after stable branch resolution and before repository mutations, then
remains unchanged while coalescing updates commit metadata. Operation integrity
authenticates the complete encoded value, and synchronization transports it as
opaque bytes.

Existing untagged 32-byte request hashes remain legacy-compatible. Exact
comparison is mandatory for new versioned identities and direct legacy
operations. A legacy coalesced operation without an explicit intent retains
historical retry behavior because an earlier writer may have replaced its
initial digest with the final aggregate digest. Unknown lengths or version
prefixes fail closed.

### ExistingCommitReplay

`ExistingCommitReplay` is a synchronization input whose `commit_id` is already
present locally. The commit row and canonical parent set are immutable: device,
sequence, exact taxonomy, encrypted changed IDs, vector clock, message,
timestamp, and stored integrity tag must equal the accepted local values before
any payload is processed. Historical tags are compared byte-for-byte rather
than recomputed with the connection's current verification state.

Transport payloads may arrive later for that exact commit. Previously omitted
operation metadata may also be added after its integrity is verified. An
existing operation row is a one-to-one mapping between operation and commit and
must match kind, branch, encrypted summary, request identity, timestamp, and
integrity tag exactly. A legacy replay may omit operation metadata without
removing the local row.

### IncomingCommitStructure

`IncomingCommitStructure` is the minimum authenticated shape that a new
synchronized commit must satisfy before storage mutation. Its `local_seq` fits
SQLite's signed INTEGER, its vector clock parses as a string-to-`u64` map, and
its parent IDs are unique. Authentication precedes this preflight; successful
authentication never authorizes a lossy relational projection.

Parent order is canonicalized, while duplicate membership is rejected because
`commit_parents` stores a set. Legacy `{}` vector clocks remain consumable and
are not rewritten to invent causal detail. Existing exact replay uses its
already accepted local identity rather than reapplying first-insertion rules.

### CommitGraph

`CommitGraph` is the storage-owned causal relation over immutable commits and
their canonical parent sets. It provides commit existence, ordered parent
reads, ancestor checks, and nearest known common-parent discovery. Branch
fast-forward decisions, conflict bases, deletion observation proofs, permanent
cleanup eligibility, and health diagnostics use this same relation rather than
reconstructing causality from timestamps or device heads.

### DeviceHead

`DeviceHead` is the current accepted position of one device's global commit
sequence. Its commit must be authored by the same device. Ordering uses
`local_seq`, not DAG ancestry, because one device can author commits on
different branches while retaining one sequence.

A higher sequence advances the head; a delayed lower sequence remains history
without moving the head backward. The same commit is idempotent, while a second
commit at the same device sequence is invalid. `last_seen_at` keeps the later
known value and revocation is monotonic. Commit ingestion and state-delta
ingestion share this rule. Health diagnostics report dangling, wrong-device,
and regressed heads.

### CommitKind

`CommitKind` is authenticated commit semantics, not a presentation hint. The
stable values are `change`, `merge`, `snapshot`, `key-rotation`, `move`,
`copy`, `restore`, and `multi`. Database history, synchronization bundles,
CLI serialization, and native facades preserve the exact value. Unknown
stored or transported values fail closed because replacing one with `change`
would alter the authenticated commit input. The four extended values are appended
after the four legacy binary enum variants so existing bundle discriminants
remain unchanged.

### ChangeScope

`ChangeScope` is the authenticated primary family affected by a commit. Its
stable values are `project`, `entry`, `attachment`, `object-relation`,
`object-label`, `object-label-assignment`, `vault-meta`, `key-epoch`, `multi`,
`snapshot`, and `branch`. `multi` means one finite CommitOperation spans more
than one family; it is not a fallback for an unknown value. Snapshot lifecycle
and branch tombstone-retention operations use their exact scopes. New local
operations validate both CommitKind and ChangeScope before opening their write
transaction, while unknown stored or transported values fail closed.

### ConflictResolutionOperation

A `ConflictResolutionOperation` selects local state, incoming state, or a validated custom state for one conflicted object. It atomically writes the selected state, creates a two-parent merge commit, advances the object clock and heads, records a new ObjectVersion, reconciles tombstones, and marks the conflict resolved.

### ConflictSummary

A `ConflictSummary` is the bounded, payload-free navigation projection of one
unresolved conflict. It carries stable object and commit identities, the
bounded conflicting-field paths, resolution state, and creation position, but
it is not a resolution payload. Summary pages use a 1–200 row keyset contract
ordered by `created_at DESC, conflict_id DESC`; their cursors bind the
unresolved query and optional conflict object-type filter. The projection
limits the stored field JSON to 64 KiB, the path count to 256, and each path to
4096 UTF-8 bytes. Complete conflict reads remain the explicit compatibility
and resolution path.

### SnapshotSummary

A `SnapshotSummary` is the bounded, payload-free navigation projection of one
snapshot. It carries the snapshot ID, base commit ID, descriptor hash, creation
time/device, and the projected ciphertext byte length; it never contains
`snapshot_ct` and does not claim payload decryption or integrity verification.
Summary pages contain 1–200 rows ordered by `created_at DESC, snapshot_id DESC`
and use query-bound cursors no larger than 4096 bytes. Required metadata text is
limited to 4096 UTF-8 bytes. Complete Snapshot reads, verification, creation,
and restore remain the explicit MDBX1-compatible recovery path.

### TombstoneState

`TombstoneState` is the complete current deletion-marker collection projected into synchronization state. Per-commit tombstones remain compatible delete-event records. A present complete collection, including an empty collection, is authoritative only during conflict-free fast-forward application and therefore communicates both deletion and revival without discarding divergent local deletion state.

### TombstoneAcknowledgement

A `TombstoneAcknowledgement` records that one registered device observed a
deletion commit. It is separate from `DeviceHead`, because receiving a commit
does not require the device to author a later commit. A tombstone can enter the
authorized cleanup stage only after every non-revoked device has a causally
valid acknowledgement.

For a tombstone with `delete_commit_id`, the observed commit must equal that
commit or causally descend from it. One `(tombstone_id, device_id)` row keeps
the strongest known proof: a descendant advances an ancestor, an ancestor
cannot replace a descendant, and concurrent valid proofs select the greater
`(acknowledged_at, observed_commit_id)` pair after causal comparison.
`acknowledged_at` itself keeps the later known value. A schema-8 legacy
tombstone with no delete commit retains its historical representation and
requires only that the observed commit exists.

### PermanentPurgeReceipt

A `PermanentPurgeReceipt` is the monotonic authenticated proof that one stable physical object identity completed authorized cleanup. It binds the tombstone, target type and ID, delete commit and clock, retention time, purge commit, executing device, and execution time. The receipt remains after the active row, object versions, tombstone, acknowledgements, and owned binary chunks are removed.

Receipts participate in complete synchronization state and snapshot recovery guards. Once a receipt exists, the same physical type and stable ID cannot be recreated from an old commit, tombstone collection, snapshot, or explicit local create operation.

### MigrationIntegrityGate

The `MigrationIntegrityGate` is the read-only verification performed before an MDBX1 or older MDBX2 file enters a writable schema migration. It runs SQLite `integrity_check` and `foreign_key_check`, reports bounded diagnostic samples, and leaves the source generation unchanged when verification fails. The exact read-only callback error emitted by the known non-authoritative legacy FTS5 index is ignored while every other result from the same integrity scan remains authoritative; the index is removed during open.

### BoundedSyncBundle

A `BoundedSyncBundle` is an offline commit transport with a hash-checked payload and an explicit resource contract. Version 3 records the encoded payload length before the body, applies a configurable reader limit and a hard decoder ceiling, and rejects reserved-header changes or trailing bytes. MDBX2 continues to read bundle versions 1 and 2 through a bounded legacy reader.

### BoundedSyncState

A `BoundedSyncState` is the complete state object carried inside a sync commit. Its JSON bytes and decoded row count are checked independently from the surrounding bundle. The default contract allows 96 MiB and 250,000 logical rows; explicit desktop callers may use the hard ceiling of 512 MiB and 2,000,000 rows. Reserved state object types require the exact `state` object ID and matching associated data. Exceeding a limit fails the enclosing apply transaction before any commit, tombstone, branch head, or object row remains visible.

### IncrementalIntegrityRoot

An `IncrementalIntegrityRoot` is the versioned sparse-Merkle digest of the
authenticated logical state used for synchronization. Its leaves contain
domain-separated stable keys and digests of canonical encrypted/state
representations, never plaintext payloads. The root is updated in the same
outer transaction as the mutation or sync apply through the sync-delta seam.
It covers synchronized logical state, commit/delta anchors, and referenced
ciphertext digests; external Provider bytes, OS state, and unregistered
physical extension tables remain outside its proof and continue to use the
explicit content manifest or Provider audits.

### HealthReport

A `HealthReport` is a read-only structured diagnosis of vault integrity. Each issue has a stable severity, category, and description suitable for CLI output and native client presentation. Tombstone diagnostics compare exact typed markers with the current deletion state of every synchronized object family while recognizing unresolved delete-versus-modify conflicts as a temporary valid state.

## Core Invariants

1. MDBX2 always reads MDBX1 data and preserves legacy public interfaces.
2. Physical `projects` and `entries` remain compatibility storage; new code uses Collection and ObjectRecord interfaces.
3. Unknown ObjectTypeIds round-trip exactly and remain opaque to adapters that do not support them.
4. The core authenticates storage context and ciphertext without needing to understand domain payload fields.
5. Domain-specific indexes are derived data. They can be rebuilt from authenticated ObjectRecords and must not become the only copy of user data.
6. ObjectRelations and ObjectLabels are first-class synchronized metadata with stable IDs and tombstones.
7. Optional capabilities may be removed from a build only when doing so preserves safe reading or produces an explicit unsupported-extension error.
8. One user intent should create one CommitOperation, avoiding histories filled with internal implementation commits.
9. Every stored payload is opaque to the core and remains protected by authenticated encryption, integrity context, version metadata, and atomic history rules.
10. Optional domain capabilities may add interpretation and rebuildable indexes, but they cannot weaken encryption, history, synchronization, recovery, or compatibility guarantees.
11. Conflict resolution is a tracked object mutation. Marking a conflict row resolved without applying and versioning the selected object state is invalid.
12. Custom conflict state preserves stable object identity and structural ownership. Plaintext custom metadata is authenticated and encrypted by the core inside the resolution transaction.
13. After successful conflict resolution or conflict-free fast-forward synchronization, every deleted object has an exact typed tombstone and every active object has no current typed tombstone. An unresolved delete-versus-modify conflict may temporarily preserve both the active local row and the incoming delete marker until resolution.
14. Health diagnostics must cover generic objects and legacy compatibility objects through the same severity and category model. A healthy report contains no Error or Critical issue.
15. TombstoneTargetType identifies a physical core object family. Unknown stored values require declared reader support and produce an explicit error; they must never be converted to Project or another known family.
16. A tombstone is not eligible for physical cleanup until retention has expired, the object remains deleted, conflicts are resolved, the delete commit exists, and every non-revoked device has causally acknowledged that commit.
17. Device revocation is monotonic security state. Synchronization may advance a revoked device's recorded head but cannot reactivate it.
18. A PermanentPurgeReceipt is monotonic. A different receipt for the same tombstone or physical object identity is an integrity violation.
19. Permanent cleanup rechecks authorization, eligibility, conflicts, acknowledgements, and dependent objects in one transaction before creating one purge CommitOperation.
20. Project, Entry, and ObjectLabel cleanup requires dependent objects to be cleaned first. Attachment chunks, project labels, object versions, tombstone acknowledgements, and object-scoped Tiga overrides are removed with their owner.
21. A permanent receipt prevents the current vault from restoring the same stable identity. Historical snapshot files, exported copies, and external backups remain separate retention media and require independent media erasure or future object-key destruction.
22. Every path-based migration plan, automatic compatibility open, explicit upgrade, and direct storage-core upgrade verifies database integrity before the first migration write. A failed verification preserves the previous format generation.
23. Untrusted sync input must have a byte limit before allocation and deserialization. New offline bundles declare their payload length, while legacy bundles are read through a bounded adapter.
24. Collection and tombstone listing use bounded ObjectSummary pages when payloads are not required. Existing MDBX1 complete-record list APIs remain available for callers that intentionally consume payloads; deleted cursors cannot cross active, Collection, or ObjectTypeId scopes.
25. A CollectionProfile is additive to the MDBX1 project row. Establishing or changing it advances the Collection commit, object clock, head, and ObjectVersion atomically.
26. CollectionProfile existence is monotonic and CollectionTypeId is immutable. Legacy payloads that omit the profile preserve the receiver's current profile.
27. User-visible writes to a profiled Collection require every declared ExtensionCapabilityId and must use an allowed ObjectTypeId. Synchronization and recovery preserve unknown profiled data without requiring its adapter.
28. New synchronization producers emit `mdbx-storage-sync-state-v2`. Readers continue to accept state-v1; older readers encounter an unsupported format instead of silently discarding CollectionProfile semantics.
29. Database-format migration and Adapter payload migration are separate protocols. MDBX1 and storage schema conversion remain mandatory storage-core responsibilities; clients never reimplement them.
30. Adapter payload migration plans are bounded to 256 objects, 1 MiB per source or target payload, and 8 MiB total source or target bytes per operation.
31. A payload migration executes only while its CollectionProfile, branch head, object identity, object head, object type, source schema version, deletion state, and source payload digest still match.
32. One payload migration plan produces one idempotent CommitOperation. Missing outputs, duplicate outputs, unavailable Adapter capabilities, stale bindings, invalid versions, or oversized payloads cause complete rollback.
33. Complete sync state is bounded independently from its surrounding bundle. The default decoder accepts at most 96 MiB and 250,000 logical rows; the hard ceiling is 512 MiB and 2,000,000 rows.
34. Sync state output uses bounded serialization, and input size is checked before JSON deserialization. A resource-limit failure rolls back the enclosing commit transaction.
35. Reserved sync state types require object ID `state` and associated data equal to the exact object type. Unknown object types remain available to ordinary opaque payload handling.
36. Generic write operations are bounded before the vault write lock: defaults are 256 commands, 1 MiB per JSON payload, 8 MiB total JSON payload, and 16 MiB serialized intent. Explicit limits remain under hard ceilings.
37. Native `OperationCoordinator` and the UniFFI facade share the same tagged command serialization, intent digest, namespaced ObjectTypeId support, single-commit behavior, and complete rollback semantics. Existing MDBX1 repository methods remain available.
38. An established IncrementalIntegrityRoot is updated atomically with every covered local or incoming state mutation; a root collection, encoding, authentication, or resource failure rolls back the enclosing transaction and never downgrades to an unverified warning.
39. Attachment navigation is metadata-only and bounded: summary SQL never selects chunk/blob payloads, checks encrypted display-field length before materialization, and rechecks authenticated plaintext limits after decryption. Complete attachment APIs remain available without reinterpretation.
40. Conflict navigation is metadata-only and bounded: unresolved summary SQL projects conflicting-field length before materialization, enforces 64 KiB/256-path/4096-byte limits, and binds cursors to the query filter. Complete conflict reads and typed resolution APIs remain available without reinterpretation.
41. Snapshot navigation is metadata-only and bounded: summary SQL projects `length(snapshot_ct)` without selecting the encrypted BLOB, enforces 1–200 pages/4096-byte cursors/4096-byte metadata text, and leaves complete snapshot reads and restore semantics unchanged.
42. Adapter payload planning and execution require the `MigratePayload` Tiga administration operation. Authorization precedes source payload loading and decryption; execution reauthorizes and atomically couples binding checks, one idempotent CommitOperation, audit correlation, and sync-delta materialization. Decrypted plan bytes are transient and never persisted or synchronized.
43. ExtensionProfile registration is bounded, canonical, and process-local. It cannot claim legacy ObjectTypeIds or identifiers outside its namespace and never changes persisted data merely by registration or omission.
44. The SteamMaFileAdapter is optional and removable. It owns mafile syntax, canonicalization, identity derivation, and parser resource errors; the Generic Object Module owns encryption, history, synchronization, recovery, and opaque preservation.
45. Steam mafile parsing checks input bytes before deserialization and enforces bounded depth, aggregate fields, per-array items, nodes, per-string bytes, and aggregate string/key bytes. Duplicate keys fail, and unknown keys remain available after canonical round-trip.
46. Steam object identity is a domain-separated, length-framed SHA-256 digest of the canonical unsigned SteamID and trimmed case-preserving serial number. Debug and error interfaces never include mafile payload values.
47. CommitKind is authenticated data. Every known value round-trips exactly across storage history, CLI, synchronization bundles, and FFI; unknown values are rejected and never coerced to `change`. Legacy binary discriminants 0 through 3 remain frozen.
48. ChangeScope is authenticated data and `multi` is only a real aggregate scope. Snapshot and Branch are appended after the nine legacy binary variants; history, CLI, synchronization bundles, FFI, and local CommitOperation validation use the core exact parser and never coerce an unknown scope.
49. OperationRequestIdentity is derived from the submitted operation before mutation and is never replaced by aggregate commit metadata. New tagged identities match exactly on every retry; authenticated unknown encodings fail closed, while untagged 32-byte rows retain their documented legacy behavior.
50. ExistingCommitReplay may add transport payloads or previously omitted authenticated operation metadata only after the complete commit and canonical parent identity matches local storage. Reusing a commit or operation ID with different authenticated metadata fails before payload application.
51. IncomingCommitStructure must be authenticated and exactly representable before first insertion: local sequence fits SQLite INTEGER, vector clock parses as a string-to-u64 map, and parent IDs are unique. Structural rejection leaves commit graph and branch state unchanged, while legacy empty clocks remain valid.
52. DeviceHead identifies the greatest accepted device-local sequence, references a commit authored by the same device, advances independently of branch ancestry, and merges observation time and revocation monotonically. Delayed commits cannot move it backward, and health diagnostics report wrong-device or regressed rows.
53. TombstoneAcknowledgement is a causal proof, not a timestamp claim. Its observed commit exists and, when `delete_commit_id` exists, contains that delete commit. Descendant evidence advances, ancestor evidence cannot regress it, concurrent evidence has a deterministic canonical winner, and acknowledgement time never decreases.
54. Commit ancestry has one storage-owned interpretation. Synchronization, conflict-base discovery, branch advancement, tombstone acknowledgement, permanent cleanup eligibility, and health verification use the same canonical parent traversal.

## Module Architecture

### Generic Object Module

The Generic Object Module is the primary Interface for Collection, CollectionProfile, ObjectRecord, ObjectRelation, ObjectLabel, and Attachment behavior. Its Implementation owns compatibility mapping to existing tables, encryption, capability checks, commit updates, causal metadata, and sync-state projection. This is a deep Module: callers supply stable domain values and receive complete invariant-preserving behavior.

The module also owns bounded payload migration planning and execution and the bounded generic write-operation facade. It exposes decrypted source bytes only after `MigratePayload` Tiga authorization and only in a short-lived plan, treats Adapter output and client commands as untrusted input, and preserves existing ObjectVersion, synchronization, snapshot, and MDBX1 table semantics by applying target payloads through EntryRepo inside one authorized CommitOperation transaction.

### Legacy Password Adapter

The Legacy Password Adapter maps existing EntryType values and MDBX1 methods onto the Generic Object Module. It remains available for old clients and KDBX interoperability. The adapter does not define the generic core vocabulary.

### Domain Adapters

Bookmark, mail, and Steam adapters interpret namespaced ObjectTypeIds and payload schemas. The SteamMaFileAdapter is the first concrete removable Adapter: it exercises the ExtensionProfile seam without making the core understand Steam fields. Domain Adapters may add rebuildable indexes through explicit seams. One adapter alone does not justify a new core interface; shared behavior moves into the core only after at least two adapters need the same seam.

### Conflict Resolution Module

The Conflict Resolution Module loads authenticated local and incoming ObjectVersions, validates identity and ownership constraints, and applies LocalWins, IncomingWins, or Custom state through one transaction. ObjectRelations, ObjectLabels, and ObjectLabelAssignments use the same merge-commit and tombstone rules as legacy projects, entries, and attachments. Duplicate assignment UUIDs for the same logical object-label membership are mapped to the local logical identity before resolution.

Its navigation plane is intentionally separate: generic clients page
`ConflictSummary` records through the bounded repository/FFI surface, then
load complete typed state only for an explicit resolution or repair action.

Snapshot management has the same separation: generic clients page
`SnapshotSummary` records through the bounded repository/FFI surface, then use
complete snapshot reads, integrity verification, export, or authorized restore
only after an explicit recovery action. Summary navigation never interprets
the encrypted snapshot payload.

The synchronization state carries an optional complete TombstoneState. New producers always emit it. Legacy payloads omit it and retain their existing per-commit delete-event behavior. Receivers replace the complete collection only for conflict-free fast-forward commits; divergent commits continue to preserve local markers until a merge resolution becomes authoritative.

### Commit Graph And Tombstone Acknowledgement Module

The crate-private `CommitGraphRepo` owns read-only commit existence, canonical
parent reads, ancestry, and nearest-known-common-parent discovery. Synchronization,
permanent cleanup eligibility, and recovery call this Module instead of carrying
separate graph walkers.

The crate-private `TombstoneAcknowledgementRepo` is the runtime write boundary
for local deletion, imported deletion, receiving-device evidence, conflict
resolution, and synchronized state. It validates the incoming proof before
mutation and performs the causal, monotonic, delivery-order-independent merge.
Schema migration backfill remains a fixed historical conversion rather than a
runtime merge path.

Tombstone acknowledgements are monotonic synchronized metadata. Per-commit
tombstones acknowledge the deleting and receiving devices; complete state
transfers accumulated acknowledgements. Complete-state rows whose tombstone or
observed commit is absent retain the existing skip behavior. A row whose
references exist but whose observed commit precedes or is concurrent with the
delete commit fails the enclosing apply transaction. `device_heads` supplies
the active-device set but is not treated as proof that a device observed a
deletion.

Permanent purge receipts are applied before ordinary objects during complete synchronization. Applying a receipt removes stale local state in dependency order, and every later object, relation, label, assignment, attachment, version, and tombstone application checks the receipt guard. Snapshot restoration uses the same guard before restoring owner rows, attachment chunks, and project labels.

### Recovery and Health Module

The Recovery and Health Module performs read-only checks for SQLite integrity,
authenticated commit history, snapshots, attachment chunks, references, device
heads, typed tombstones, tombstone acknowledgements, and permanent purge
receipts. It reports dangling, wrong-device, and regressed device heads,
non-causal acknowledgement proofs, missing markers for deleted rows,
unexplained markers for active rows, duplicate markers, invalid receipt
authentication tags, and active rows that contradict a permanent receipt.
Health projection leaves unknown physical tombstone types untouched, while
typed TombstoneRepo reads return an explicit unsupported-type error. Branch
tombstones remain event records because branches have no deleted-row state. The
CLI and UniFFI expose the same underlying structured report.

### Integrity Root Module

The Integrity Root Module owns the IncrementalIntegrityRoot tree, its bounded
rebuild path, HMAC metadata, and verification status. It sits behind the
transactional sync-delta seam rather than requiring each repository or Adapter
to know about tree nodes. The existing content manifest remains the exact
full-schema checkpoint, and the rollback anchor remains the external
append-only inventory proof; neither is silently replaced by this Module.

### Capability Features

Cargo features select optional adapters and tools. Default builds retain current behavior. Minimal builds may remove imports, benchmarks, or domain indexes while keeping the same file reader, compatibility migrator, encryption, and generic object interfaces.

The supported storage profiles are:

| Profile | Cargo selection | Included behavior |
|---|---|---|
| Full | default features | Mandatory database core, KDBX JSON import and export, benchmark harness, and the MDBX1 derived search adapter |
| Core | `--no-default-features --features core` | Mandatory database core only |

Optional storage features are additive:

| Feature | Capability |
|---|---|
| `kdbx-import` | KDBX JSON import adapter |
| `kdbx-export` | KDBX JSON export adapter |
| `derived-search-index` | Legacy password-project search and temporary FTS index |
| `benchmarks` | Local benchmark harness; enables `derived-search-index` |

`CapabilitySet::current()` exposes the compiled capability set to Rust clients. Mandatory fields always report true in a supported build. Optional fields reflect Cargo feature selection. `bounded_sync_state` reports the mandatory complete-state resource contract.

When a domain adapter is absent, the Generic Object Module continues to read, authenticate, preserve, snapshot, synchronize, and recover its namespaced ObjectTypeIds as opaque records. Adapter-specific Rust modules and CLI commands are absent from that build. An absent adapter never authorizes plaintext interpretation, rewrites the type identity, or removes stored data. Unknown critical storage extensions continue to fail before writable open.
