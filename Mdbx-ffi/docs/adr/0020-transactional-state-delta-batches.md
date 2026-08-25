# ADR-0020: Transactional State Delta Batches

- Status: Accepted
- Date: 2026-07-20

## Context

MDBX2 Commit2 represents user-visible history. One logical operation is coalesced into one
commit so a file import, snapshot restore, policy change, or other multi-row operation does not
fill history with implementation-level commits.

The complete `SyncStatePayload` is currently attached to the last exported commit. Selecting
only commits after a checkpoint would therefore lose state. Several state families also cannot
be reconstructed from the commit DAG alone:

- attachment content replacement deletes the previous chunk rows;
- key epoch rotation changes `key_epochs` and `vault_meta` before creating its commit;
- collection profiles and project tags use their owning project commit but have no commit column;
- branches and device heads retain only their current value;
- rejected Tiga decisions and non-mutating disclosures create authenticated audit rows without a
  commit;
- Provider-local Blob maintenance intentionally creates no logical object commit.

Generating a visible maintenance commit for each such row would reverse Commit2 coalescing and
would mix user history with synchronization bookkeeping. Ignoring rows without a commit would
also regress the convergence provided by the existing complete-state transfer.

## Decision

MDBX separates the user history unit from the synchronization materialization unit:

- a **Commit2 commit** remains the immutable, user-visible causal history record;
- a **state delta batch** is the immutable result of one outer SQLite write transaction;
- a batch records the commits created or imported by that transaction and may contain no commit;
- a batch with commits is a commit-associated delta; a batch without commits is an auxiliary
  state delta;
- auxiliary batches are synchronization records, not commits, and never appear in commit history
  or participate in the commit DAG.

Every storage-controlled outer write transaction records changed logical keys. After its closure
succeeds and before SQLite `COMMIT`, MDBX deduplicates those keys, collects their final rows,
encodes a bounded versioned delta, and inserts the immutable batch in the same transaction. A
failure in collection, encoding, authentication, or resource validation rolls back both the
domain mutation and the batch.

Nested repository transactions contribute to the surrounding outer transaction. `run_operation`
and incoming sync apply use the same finalization contract even though they manage SQLite
transactions manually. Applying an incoming batch validates and applies the complete batch,
stores the received immutable batch for deduplication and forwarding, and discards capture rows
caused by incoming materialization before the transaction commits. A divergent apply retains
capture beginning with its first locally created merge commit so that local merge state receives
its own outgoing batch.

Batch identity is stable across replicas. Local batch inventory sequence numbers are derived
ordering and are never synchronized as identity. The encoded envelope authenticates its format,
batch ID, vault ID, associated commit IDs, logical row count, payload digest, and payload bytes.
Configurable defaults remain below hard byte and logical-row ceilings.

## State Ownership Rules

| State family | Delta inclusion rule | Atomic apply rule |
| --- | --- | --- |
| Projects | Include the exact `object_versions` project snapshot for each changed project commit. | Merge by object clock and head; retain the `projects` compatibility projection and record the accepted version. |
| Entries and opaque Adapter objects | Include the exact entry version, object type, schema version, and encrypted payload. Unknown non-critical types remain opaque. | Merge by object clock and head; preserve unknown payload bytes and record the accepted version. |
| Relations, labels, assignments | Include their exact object-version snapshot for each changed commit. | Merge by object clock and head and record the accepted version. |
| Attachments | Include the exact attachment metadata version and a complete chunk-set replacement captured at transaction completion. | Validate mode, hashes, indexes, sizes, embedded ciphertext or encrypted external references before replacing all chunks atomically. |
| Collection profiles | Include the complete profile or an explicit deletion marker whenever its project changes. | Replace or delete the profile after validating collection type and capability identifiers. |
| Project tags | Include the complete sorted tag set, including an empty set, whenever tracked tags change. | Replace the complete set so deletion of the final tag converges. |
| Tombstones | Include rows created or updated by the transaction and retain `delete_commit_id`. | Apply causal deletion rules and reject unavailable or inconsistent delete commits. |
| Tombstone acknowledgements | Include each new or updated `(tombstone_id, device_id)` row. | Apply ADR-0044 after references exist: require observation of the delete commit, advance descendant evidence, reject ancestor regression, select concurrent evidence deterministically, and preserve the later acknowledgement time. |
| Purge receipts | Include immutable receipts by `purge_id`. | Verify integrity, referenced commits, retention evidence, and dependency order before permanent deletion. |
| Vault Tiga state | Include the complete singleton when any policy or compliance field changes. | Merge toward the stricter compatible policy and reject unsupported policy versions. |
| Tiga overrides | Include the complete scoped row or an explicit deletion marker. | Verify integrity and scope identity, then replace or delete the exact scope. |
| Tiga exceptions | Include the complete exception row when inserted, revoked, or otherwise changed. | Verify integrity and immutable identity fields before insert or monotonic revocation. |
| Security audit | Include every inserted authenticated event, including events without a commit. | Verify evidence and optional commit correlation, then insert immutably by event ID. |
| Key epochs | Include the complete authenticated epoch state whenever epoch rows or the active epoch changes. | Require mutable unlocked apply for changes, verify wrappers and legal transitions, then refresh verified keyrings. |
| Branches | Include each changed branch row with stable `branch_id`. | Match by branch ID, preserve branch-name uniqueness, and advance only to an available commit. |
| Device heads | Include each changed device head, including revocation state. | Apply ADR-0043: require commit authorship, advance by device-local sequence across branches, and merge observation time and revocation monotonically. |

`object_versions` is the historical source for commit-owned logical objects; it is not copied as
an unrelated second history stream. The receiving side records accepted versions while applying
the logical rows. Attachment chunks, profile deletion, empty tag sets, singleton state, and
unknown Adapter payloads are explicit because reconstructing them from current tables at a later
export would be lossy.

## Bootstrap And Compatibility

Schema migration cannot manufacture truthful historical deltas for old attachment contents,
branch values, unassociated audit events, or metadata deletions. Schema 13 therefore records a
bootstrap floor at the current commit-inventory watermark and starts transactional capture only
for later writes. A request whose base predates that floor uses the existing bounded complete
state as bootstrap. After bootstrap, commit-associated and auxiliary batch checkpoints advance
independently.

MDBX1 and earlier MDBX2 vaults continue to upgrade transactionally. Existing `projects`,
`entries`, commits, bundle v1-v3, and sync-state v1-v2 formats remain unchanged. Old peers continue
to receive bounded complete state. A new peer must not advertise incremental state convergence
until it supports both commit-associated batches and the auxiliary batch inventory.

## Consequences

Commit history remains readable and compact while synchronization can represent every persistent
vault change. A transaction that records only a rejected authorization decision produces an
auxiliary state batch and no visible commit. A transaction that replaces attachment content
produces one Commit2 commit and one complete attachment delta.

The stable commit inventory remains necessary for causal commit exchange, but it is not a complete
state-change inventory by itself. Schema capture, bounded envelopes, and atomic storage apply are
implemented. Bundle v4 and the offline CLI now carry both checkpoint classes, resume bounded
segments through a digest-bound transfer chain, and apply each segment atomically. Complete
`SyncStatePayload` remains the bootstrap and old-peer fallback. The reusable synchronization client
requires commit paging, delta paging, bundle v4, and resume capabilities as one contract, omits the
legacy known-commit vector for paging peers, and advances checkpoints only after durable segment
acknowledgement.
