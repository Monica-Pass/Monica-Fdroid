# ADR-0038: Exact Commit Kind Round-Trip

- Status: Accepted
- Date: 2026-07-25

## Context

MDBX repositories already persist `move`, `copy`, and `restore`, while the
native operation coordinator can persist `multi`. The core `CommitKind` model
previously represented only `change`, `merge`, `snapshot`, and
`key-rotation`. Commit history therefore rejected valid rows, and CLI bundle
loading silently converted every unrecognized value to `change`.

Commit kind participates in commit integrity calculation. A conversion to
`change` does not merely simplify presentation; it changes authenticated
input and can make a synchronized commit unverifiable. It also erases the
user-level meaning that MDBX2 introduced to avoid histories filled with
internal commits.

## Decision

The stable commit-kind set is:

- `change`
- `merge`
- `snapshot`
- `key-rotation`
- `move`
- `copy`
- `restore`
- `multi`

Append `Move`, `Copy`, `Restore`, and `Multi` after the four existing Rust enum
variants. Do not insert or reorder variants. Bincode discriminants 0 through 3
therefore retain their legacy meaning; the appended variants use 4 through 7.

The core owns exact string formatting and strict parsing. Storage history and
CLI SQL loading delegate to this parser. Known values are preserved exactly.
Unknown strings return an explicit validation or SQL conversion error and are
never replaced by `change`.

Synchronization bundles continue to serialize `CommitKind` directly. No new
bundle version or envelope field is introduced. New readers preserve all eight
known variants and continue to decode old fixtures. A reader that does not
know a newer discriminant fails closed.

The UniFFI history facade continues to expose the exact string rather than a
second native enum. This avoids duplicating the protocol mapping at the ABI
boundary and lets clients localize labels without changing stored data.

## Compatibility

This change adds no database table, column, schema migration, file-format
version, sync-state field, bundle-version field, critical extension, key
format, or Tiga rule. MDBX1 and MDBX1-DRAFT files retain their existing text
representation; any extended values already present are accepted without a
rewrite. Appending enum variants keeps legacy binary discriminants stable.

Forward compatibility is explicit rather than fabricated: a current reader
can read legacy data, while a reader that does not support an exact kind must
reject it. A pre-fix reader that coerces extended values is not safe for export
or apply of that history. Rewriting a new kind to an old value is prohibited
because that would break integrity and semantic fidelity.

## Consequences

Commit history, CLI export, synchronization bundles, and FFI history now agree
on the exact commit kind. Move, copy, restore, and multi-operation history can
be inspected and transported without integrity loss.

Future commit kinds must be appended, documented, and covered at every public
round-trip boundary. Introducing a future value requires coordinated reader
support; a default fallback branch is not acceptable.

## Verification

Tests cover exact core display/parse/serde behavior, fixed binary
discriminants, storage history verification, CLI database loading, sync bundle
round-trip, unknown-value rejection, and a real UniFFI move operation observed
through both paged and detail history APIs.
