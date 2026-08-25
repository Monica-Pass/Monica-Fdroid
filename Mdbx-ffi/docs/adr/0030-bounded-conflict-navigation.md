# ADR-0030: Bounded conflict navigation

## Status

Accepted

## Context

The legacy `ConflictRepo::list_unresolved` and UniFFI
`list_unresolved_conflicts` methods materialize the complete unresolved queue,
including the stored `conflicting_fields` JSON for every row. That behavior is
part of the MDBX1 compatibility surface, but it is not a safe default for a
generic vault that can contain a large mail, bookmark, Steam, password, or
extension conflict queue. A malformed or oversized field list must not cause
unbounded allocation in a normal conflict-management screen.

Conflict navigation and conflict resolution are separate responsibilities.
The queue needs bounded metadata for selection; a resolution operation still
needs the complete typed state and must remain an atomic tracked mutation.

## Decision

MDBX2 adds `ConflictSummary`, `ConflictSummaryPage`, and
`ConflictSummaryRepo::list_unresolved_summaries` alongside the complete
conflict repository. The new page optionally filters by `ConflictObjectType`,
orders by `created_at DESC, conflict_id DESC`, reads at most one sentinel row,
and returns an opaque query-bound keyset cursor.

The fixed contract is:

- page size: 1 through 200 rows
- serialized cursor: at most 4096 bytes
- stored conflicting-fields JSON projected to Rust: at most 64 KiB
- decoded field paths: at most 256
- one field path: at most 4096 UTF-8 bytes

SQL always projects the stored JSON byte length and returns the text only when
it is within the 64 KiB ceiling. Storage then strictly parses object type,
resolution, and JSON, verifies that only unresolved rows were returned, and
checks the decoded count and per-path byte limits. Oversized, malformed, or
inconsistent rows fail closed on the bounded path.

The cursor binds the query kind, optional object type, and last keyset
position. Reusing it with a different filter or unsupported version returns an
error. Cursors are live-view positions, not snapshots or authorization tokens;
clients discard them after conflict or synchronization mutations.

UniFFI exposes `MdbxConflictSummary`, `MdbxConflictSummaryPage`,
`default_conflict_summary_limits`, and
`list_unresolved_conflict_summaries`. Existing
`list_unresolved_conflicts`, `ConflictRepo::list_unresolved`,
`ConflictRepo::list_by_object`, and every typed resolution method remain
unchanged.

## Compatibility

This is additive. It changes no schema, row, format marker, commit, snapshot,
synchronization field, ciphertext, or key epoch. MDBX1 bytes and complete API
behavior remain available for explicit resolution, export, repair, and older
generated bindings. A legacy row outside the new limits fails only when a
caller selects the bounded summary surface.

## Consequences

Generic clients can page the unresolved queue with bounded allocation and can
filter by core conflict family before loading any domain-specific comparison
state. The summary is not a mutation API and does not authorize plaintext
disclosure. After selection, clients use the existing typed resolution methods
so merge commits, object versions, heads, tombstones, and conflict state remain
atomic.
