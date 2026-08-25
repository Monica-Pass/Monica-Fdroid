# ADR-0027: Bounded collection navigation

## Status

Accepted

## Context

MDBX1 exposes Collections through complete `Project` reads. Those reads decrypt the title and optional summary and return every matching row in one vector. A reopened generic client therefore had no safe way to discover password, bookmark, mail, Steam, or future adapter Collections without remembering their IDs or materializing complete Projects. The CLI used the same unbounded path for active and deleted project lists.

Object and Label summary APIs already separate navigation metadata from encrypted payloads, but their display ciphertext was selected before its length was checked. A corrupt or adversarially large title or label name could therefore allocate the BLOB before the API rejected it.

The complete repositories and generated FFI methods are part of the compatibility surface. Replacing them would break MDBX1 clients and remove an explicit repair/export path for legacy rows outside new navigation limits.

## Decision

MDBX2 adds `CollectionSummary` and `CollectionSummaryPage`. A summary contains Collection identity, decrypted title, optional CollectionProfile type and payload schema version, group/icon references, favorite/archive state, attachment count, head commit, deletion state, and update time. It never selects `projects.summary_ct` or `collection_profiles.payload_ct`.

`CollectionSummaryRepo` provides by-ID reads plus separate active and deleted pages. Pages use descending `updated_at` and stable Collection ID keyset ordering, accept 1 through 200 items, read at most one sentinel row, and use a versioned query-bound cursor limited to 4096 bytes. A cursor for active Collections cannot be reused for deleted Collections.

Presentation fields use fixed hard limits:

- Collection and Object title plaintext: 64 KiB
- ObjectLabel name plaintext: 512 bytes
- Collection group/icon reference: 4096 UTF-8 bytes
- encrypted display fields: the plaintext limit plus a 128 KiB compatible envelope allowance

Encrypted SQL projections select `length(field)` and return the BLOB through `CASE` only when it is within the ciphertext limit. Storage then authenticates/decrypts the field and checks the exact plaintext byte length again. Plaintext references use byte length, not Unicode scalar count, and are conditionally projected before conversion to Rust strings.

UniFFI exposes collection summary by-ID, active-page, deleted-page, and presentation-limit discovery methods. The CLI streams Collection summary pages for `project list` and `project deleted` instead of loading complete Projects.

## Compatibility

This change is additive and requires no schema migration, row rewrite, key rotation, synchronization field, snapshot field, or format-version change. Existing `ProjectRepo::get_by_id`, `list_all`, `list_deleted`, and complete FFI methods retain their historical behavior.

MDBX1 and earlier MDBX2 rows outside the fixed presentation limits remain readable through those complete APIs for explicit repair or export. They fail only when a caller chooses the bounded navigation surface. Collection Profiles remain optional, so an MDBX1 Collection is returned with no type or profile schema version.

## Consequences

Generic clients can reopen a vault and discover its adapter Collections without loading opaque payloads or unbounded vectors. Corrupt profile payloads do not block Collection navigation. Oversized presentation fields fail with a resource-limit error before their BLOB is materialized, while authenticated plaintext is still checked after decryption.

The cursors represent a bounded live view, not a snapshot or authorization token. Clients refresh the first page after local or synchronized mutations. Complete compatibility reads remain powerful and therefore must not be used as the default list or disclosure path in new user interfaces.
