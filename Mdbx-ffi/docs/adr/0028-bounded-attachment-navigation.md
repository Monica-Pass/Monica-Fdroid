# ADR-0028: Bounded attachment navigation

## Status

Accepted

## Context

Attachments are used by password records, Steam `mafile` synchronization, mail, saved pages, and future domain adapters. MDBX1 complete attachment list methods decrypt every matching display field into one vector. A reopened client therefore has no bounded way to render a Collection or Object attachment list, and a damaged chunk or external blob can make a metadata-only screen fail.

The complete attachment repository and existing UniFFI methods are compatibility surfaces. Some legacy rows may also contain display ciphertext larger than a new navigation contract can safely materialize.

## Decision

MDBX2 adds `AttachmentSummary` and `AttachmentSummaryPage`. The summary contains attachment identity, Collection/Object ownership, authenticated file name and media type, storage mode, content hash, sizes, chunk count, head commit, deletion state, and update time. It never contains chunk bodies or external blob references.

`AttachmentSummaryRepo` provides by-ID reads, active Collection pages, active Object pages, and deleted pages. Pages use descending `updated_at` plus attachment ID keyset ordering, return 1 through 200 rows, read one sentinel row at most, and use a versioned cursor no larger than 4096 bytes bound to query kind and scope. Summary SQL projects only `length(file_name_ct)` / `length(media_type_ct)` and conditionally returns those fields; it never selects `attachment_chunks.chunk_ct` or `external_uri_ct`.

The fixed display contract is:

- file-name plaintext: at most 4096 UTF-8 bytes
- media-type plaintext: at most 512 UTF-8 bytes
- encrypted display-field projection: plaintext limit plus the shared 128 KiB envelope allowance

Storage rejects an oversized ciphertext before BLOB materialization, authenticates/decrypts only the bounded display field, then checks the exact plaintext byte limit and UTF-8 validity. A corrupt attachment chunk or external blob therefore does not block a metadata summary.

The UniFFI facade exposes `MdbxAttachmentSummary`, paged reads, deleted-summary reads, and `default_attachment_presentation_limits`. The CLI `attach list` and `attach deleted` commands consume those pages. Existing complete `get_attachment`, `list_attachments`, `list_deleted_attachments`, and `AttachmentRepo` methods remain available for explicit content, repair, and export paths.

## Compatibility

This is additive. It requires no schema migration, row rewrite, key rotation, synchronization-field change, snapshot change, or format-version change. MDBX1 bytes and complete API behavior remain unchanged. A legacy attachment outside the bounded display contract fails only when a caller chooses the new summary surface; clients can still use the complete compatibility API for an explicit repair or export workflow.

## Consequences

Generic clients can reopen a vault and page attachment metadata for password, bookmark, mail, Steam, and future Collections without loading payloads or binary content. Cursors are live-view positions rather than snapshots or authorization tokens, so clients must discard them after metadata mutations and restart from the first page. Content disclosure and integrity verification remain separate, policy-controlled operations.
