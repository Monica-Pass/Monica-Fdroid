# ADR-0012: Paginated object summaries

## Status

Accepted

## Context

The MDBX1 entry list APIs return complete decrypted records. This is compatible and convenient for small password collections, but a generic database may contain thousands of bookmarks or mail messages with larger payloads. A collection screen usually needs only identity, type, title, version, and update information.

Using the complete-record API for listing expands plaintext lifetime, performs unnecessary authenticated decryption, and creates an unbounded result vector. Replacing the existing APIs would break MDBX1 clients and internal services that intentionally consume payloads.

## Decision

MDBX2 adds `ObjectSummary` and `ObjectSummaryPage` as generic object interfaces. `ObjectSummaryRepo::list` queries the physical `entries` compatibility table but selects no `payload_ct` column. It decrypts only the optional title.

Pages use descending `updated_at` and stable object ID ordering. Page size is restricted to 1 through 200. The opaque cursor contains a version, collection ID, optional object type, update time, and object ID. A cursor is rejected when its collection or type differs from the current query.

Deleted-object navigation follows the same projection and ordering contract. The
additive storage methods `list_deleted_by_collection` and `list_deleted_all`
carry an explicit query discriminator in the cursor, so active, collection-
deleted, and globally deleted cursors cannot be exchanged. The parser accepts
the first release's active cursor shape (missing discriminator and a string
collection field), preserving in-flight MDBX2 client pagination across the
upgrade.

The cursor is a bounded live-view keyset cursor. Concurrent object updates can move records across the ordering boundary, so clients refresh the first page after local or synchronized mutations. Snapshot-consistent multi-page reads require a separate read-snapshot interface.

The CLI entry list and deleted-entry view consume summary pages incrementally.
UniFFI exposes `list_object_summaries`, `list_deleted_object_summaries`, and
`list_all_deleted_object_summaries`; existing `list_objects`, `list_entries`,
and `EntryRepo::list_*` methods retain their complete-payload behavior.

## Consequences

Collection screens no longer decrypt every password, mail body, bookmark payload, or extension record. Corruption in an object payload does not prevent listing enough metadata to identify the affected object, while opening that object still detects the authentication failure.

Titles remain plaintext in process memory because they are required for presentation. Clients that need a title-free locked listing require a separate encrypted-index design. Cursor strings are untrusted input and provide pagination state, not authorization.
