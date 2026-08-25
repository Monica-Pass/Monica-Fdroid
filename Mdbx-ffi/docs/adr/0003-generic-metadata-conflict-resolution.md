# ADR-0003: Generic Metadata Conflict Resolution

- Status: Accepted
- Date: 2026-07-20

## Context

MDBX synchronization records typed conflicts when concurrent replicas change ObjectRelations, ObjectLabels, or ObjectLabelAssignments. The conflict table previously allowed a caller to mark these rows resolved without writing the selected state back to the metadata table. That left history, heads, object versions, tombstones, and visible state inconsistent with the declared resolution.

Label assignments add a second identity problem. Two replicas can independently create different assignment UUIDs for the same logical object-label membership. The receiving replica detects the duplicate pair using its local assignment ID, while the incoming ObjectVersion originally remains indexed by the remote assignment ID.

## Decision

Generic metadata uses typed resolution operations for LocalWins, IncomingWins, and Custom state.

Every successful resolution runs in one immediate transaction and performs these actions:

1. Load an unresolved conflict of the expected object type.
2. Load and authenticate the current and selected ObjectVersion.
3. Validate stable IDs, relation kinds, payload schema versions, collection ownership, active references, label deletion prerequisites, and assignment uniqueness.
4. Create one merge commit whose parents are the current local head and incoming head.
5. Write the selected state with a new object clock, head, timestamp, and resolving device.
6. Create or remove the current tombstone according to the selected deletion state.
7. Record the resolved ObjectVersion and mark the conflict row resolved.

Custom relation and label values enter storage as plaintext domain metadata and are encrypted inside the transaction. Label collection identity and assignment object-label identity are immutable during resolution.

When two assignment UUIDs represent one logical membership, sync records an additional incoming candidate under the local assignment identity. IncomingWins therefore keeps one stable local row and avoids creating a duplicate active assignment.

UniFFI exposes conflict listing, typed local or incoming selection, and custom entry or generic metadata resolution. Clients never receive authority to modify conflict, commit, object-version, or tombstone tables directly.

The MDBX1 `ConflictRepo::resolve` symbol remains available for source compatibility but returns an explicit constraint error because its original signature lacks the CommitContext and selected object state required for a valid resolution.

Project, Entry, Attachment, ObjectRelation, ObjectLabel, and ObjectLabelAssignment resolution all use the same typed tombstone reconciliation rule. Selecting a deleted state creates the exact object-type marker when absent. Selecting an active state removes every current marker for that object type and identity.

Synchronization state includes an optional complete tombstone collection. New producers emit `Some(collection)`, where `Some([])` explicitly represents no current tombstones. Payloads created by older MDBX2 or MDBX1-compatible producers deserialize the missing field as `None`, preserving their previous behavior. Receivers replace the complete collection only while applying a conflict-free fast-forward commit. Per-commit tombstones remain supported as compatible delete-event records and continue to be additive during divergent synchronization.

## Consequences

Conflict state, visible metadata, causal history, and synchronization state now agree after resolution. Invalid custom states roll back without consuming a commit or changing the conflict. Label deletion may require resolving or deleting active assignments first. Assignment identity remains stable even when replicas created different UUIDs for the same membership.

A merge resolution can now propagate revival by sending an authoritative empty or reduced tombstone collection. Reapplying the same resolved state is idempotent. Divergent synchronization cannot erase a local deletion marker merely because the incoming replica lacks that marker.
