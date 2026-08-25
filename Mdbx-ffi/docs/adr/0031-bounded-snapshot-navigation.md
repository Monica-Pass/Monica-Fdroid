# ADR-0031: Bounded snapshot navigation

## Status

Accepted

## Context

The legacy `SnapshotRepo::list_all` method materializes every snapshot row,
including the encrypted `snapshot_ct` payload. That behavior is part of the
MDBX1 compatibility surface and remains necessary for explicit export,
integrity, and recovery workflows, but it is not a safe default for a generic
vault that may contain large mail, bookmark, Steam `mafile`, password, or
extension snapshots.

Snapshot selection and snapshot restoration are separate responsibilities. A
management screen needs enough metadata to choose a recovery point without
allocating or interpreting an encrypted payload. A damaged or unusually large
payload must not prevent that navigation screen from opening.

## Decision

MDBX2 adds `SnapshotSummary` and `SnapshotSummaryPage` alongside the complete
`Snapshot` model. `SnapshotSummaryRepo::get` and `SnapshotSummaryRepo::list`
return only stable identifiers, creation metadata, the snapshot hash, and the
projected ciphertext byte length. The summary never contains `snapshot_ct` and
does not claim that the payload has been decrypted or integrity-verified.

The fixed resource contract is:

- page size: 1 through 200 rows
- serialized opaque cursor: at most 4096 bytes
- each required text metadata field: at most 4096 UTF-8 bytes
- ordering: `created_at DESC, snapshot_id DESC`

The list query uses a keyset position and reads at most one sentinel row. SQL
projects `length(snapshot_ct)` as an integer but never selects the BLOB for the
summary path. Required metadata is projected only when it is within the text
limit; malformed, missing, or oversized metadata fails closed on the bounded
surface. Cursors bind the query kind and last keyset position. They are live
positions rather than snapshot tokens or authorization credentials, so clients
discard them after snapshot creation, pruning, synchronization, or other
metadata changes.

UniFFI exposes `MdbxSnapshotSummary`, `MdbxSnapshotSummaryPage`,
`MdbxSnapshotSummaryLimits`, `default_snapshot_summary_limits`,
`MdbxVault::get_snapshot_summary`, and
`MdbxVault::list_snapshot_summaries`. The CLI `snapshot list` command iterates
these bounded pages while preserving its existing output and command shape.

## Compatibility

This is additive. It adds no schema migration, does not rewrite existing
snapshot rows, and changes no format marker, commit, synchronization field,
ciphertext, or key epoch. `SnapshotRepo::get_by_id`, `list_all`, snapshot
creation, integrity verification, and restore APIs retain their published
behavior for MDBX1 clients and explicit recovery/repair tools. A legacy row
outside the summary limits fails only when the caller selects the bounded
navigation surface; the complete API remains available for deliberate payload
consumption.

## Consequences

Generic clients can render large snapshot queues with bounded per-page
allocation and can select a recovery point even when a payload is corrupt or
very large. After selection, a client must call the existing complete read or
authorized restore API for payload-dependent work and must handle its
integrity/authentication result separately. The summary plane therefore keeps
navigation cheap and payload meaning in the storage/recovery boundary, while
preserving the exact MDBX1 physical projection and compatibility semantics.
