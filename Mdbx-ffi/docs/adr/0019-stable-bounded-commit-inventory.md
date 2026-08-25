# ADR-0019: Stable Bounded Commit Inventory

- Status: Accepted
- Date: 2026-07-20

## Context

Protocol-v2 synchronization advertises every known commit ID in `Hello` and the CLI exports every commit on each run. Filtering that list alone would be incorrect: the current final commit carries a complete `SyncStatePayload`, while ordinary object payloads do not materialize every core state family during fast-forward apply. A smaller commit list could therefore silently omit objects, attachment chunks, Tiga policy, labels, key epochs, or purge state.

SQLite `rowid` is also unsuitable as a durable checkpoint because maintenance such as `VACUUM` can assign different row IDs. Incremental negotiation needs a stable local order, a fixed snapshot watermark, bounded pages, and a compatibility path for existing protocol-v2 peers before commit-local payload deltas are introduced.

## Decision

Schema 12 adds the local derived table `commit_inventory`:

```sql
CREATE TABLE commit_inventory (
    inventory_seq INTEGER PRIMARY KEY AUTOINCREMENT,
    commit_id TEXT NOT NULL UNIQUE,
    FOREIGN KEY (commit_id) REFERENCES commits(commit_id) ON DELETE RESTRICT
);
```

Migration backfills existing commits in parent-before-child topological order. It MUST fail and roll back the complete format migration if remaining rows cannot make progress. It MUST NOT change commit IDs, commit content, or parent edges. An insert trigger assigns every future local or imported commit a monotonic sequence. A parent-edge trigger rejects an edge whose parent does not already precede its child, preventing later writes from invalidating issued checkpoints.

Inventory sequence values are local derived ordering. They MUST NOT be synchronized as commit identity, included in commit integrity input, or treated as equivalent across vault replicas.

`CommitInventoryRepo` exposes ascending pages with these limits and bindings:

- page size MUST be between 1 and 512;
- checkpoint and cursor tokens MUST NOT exceed 4,096 bytes;
- tokens bind the vault ID and exact commit anchor;
- a cursor additionally binds the starting checkpoint, frozen watermark, and last returned position;
- every referenced anchor MUST still exist with the same sequence and commit ID;
- commits inserted after the first page's watermark MUST NOT enter that paging session;
- the completed watermark checkpoint resumes strictly after the previous session.

Protocol version remains 2. Hello request and response add an optional `capabilities` list that defaults to empty and is omitted when empty. Existing constructors keep their signatures and old JSON shape. New peers use `commit-inventory-paging-v1` only when both sides advertise it. Page request and response DTOs repeat the storage limits and validate them after wire decoding. Old peers continue using the existing known-commit-ID path until a bounded fallback client is delivered.

## Failure And Compatibility Semantics

MDBX1, MDBX1 draft, and earlier MDBX2 vaults upgrade transactionally to schema 12. A cyclic or damaged commit DAG leaves the original generation marker and schema unchanged. Repeated migration is idempotent. Current-schema validation requires a complete causally ordered inventory and both maintenance triggers.

Foreign-vault, oversized, malformed, unknown-field, missing-anchor, checkpoint-mismatched, or out-of-range tokens fail closed. Invalid capability identifiers, oversized capability lists, non-causal page sequences, and oversized page DTOs are rejected as invalid protocol messages.

Empty capability lists preserve protocol-v2 compatibility. Capability negotiation is additive: a peer MUST NOT send inventory page messages unless both peers negotiated the capability.

## Consequences

MDBX now has a bounded and resumable way to enumerate a fixed commit snapshot without relying on `rowid` or unbounded Hello vectors. This establishes the ordering and negotiation foundation for incremental synchronization.

This decision does **not** make state transfer incremental. Until commit-local deltas cover every core state family and apply atomically, the existing complete `SyncStatePayload` remains the convergence mechanism. CLI filtering, bundle v4 checkpoints, and removal of unbounded Hello commit vectors belong to later decisions and MUST NOT be claimed as complete here.
