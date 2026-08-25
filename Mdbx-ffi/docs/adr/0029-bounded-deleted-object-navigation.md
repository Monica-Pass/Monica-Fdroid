# ADR-0029: Bounded deleted-object navigation

## Status

Accepted

## Context

`entries.deleted` is a compatibility tombstone flag, not a reason to load a
complete record. The legacy deleted-list APIs materialize and decrypt every
field, which is unsafe as a default for a generic vault containing mail,
bookmarks, Steam `mafile` objects, or large extension payloads. A deleted row
must remain identifiable even when its payload is damaged or intentionally
unavailable.

## Decision

MDBX2 adds payload-free deleted summary pages alongside the existing active
summary page:

- `ObjectSummaryRepo::list_deleted_by_collection`
- `ObjectSummaryRepo::list_deleted_all`
- UniFFI `list_deleted_object_summaries`
- UniFFI `list_all_deleted_object_summaries`

Pages use the same 1–200 row bound, `updated_at DESC, object_id DESC` keyset
ordering, 4096-byte cursor ceiling, and 64 KiB title presentation limit as
active object summaries. SQL projects title length and a bounded title BLOB but
never selects `payload_ct`. The cursor records whether the query is active,
collection-deleted, or all-deleted, plus its collection/type filters and
position. Reusing it with a different scope fails closed.

The first active cursor representation remains readable for compatibility: a
missing query discriminator means collection-active and the legacy string
collection field is accepted. Newly generated cursors use the explicit query
discriminator and return a storage error when serialization would exceed the
bound; cursor generation never panics.

The CLI `entry deleted` command and new clients use bounded pages. Complete
`EntryRepo::list_deleted*`, `list_deleted_entries`, and other MDBX1-compatible
methods remain available for explicit restore, export, and repair workflows.

## Consequences

Deleted navigation does not decrypt payloads, so corrupt payload ciphertext no
longer blocks the tombstone list. Titles are still authenticated and bounded
because they are presentation metadata. Cursors are live-view positions rather
than snapshot tokens; clients discard them after metadata changes and restart
from the first page.
