# ADR-0039: Exact Change Scope And Write Validation

- Status: Accepted
- Date: 2026-07-26

## Context

ADR-0038 made CommitKind exact, but the adjacent ChangeScope taxonomy remained
fragmented. The core enum, storage history, CLI database loader, and sync test
helpers each owned a separate mapping. CLI treated every unrecognized value as
`multi`.

That fallback was already wrong for current data. Automatic snapshot pruning
persists `change_scope = "snapshot"`, while the core enum did not contain a
Snapshot variant. Commit history rejected the row, FFI could not display the
prune commit, and CLI bundle loading changed the scope to `multi`. Branch is
also an existing physical object family and tombstone target that can be used
by retention operations.

Change scope participates in the authenticated commit input. Converting it is
therefore an integrity change, not a harmless presentation fallback. The local
CommitOperation interface also accepted arbitrary non-empty commit-kind and
scope strings, allowing current writers to persist taxonomy that the current
sync model could not represent.

## Decision

The stable ChangeScope values are:

- `project`
- `entry`
- `attachment`
- `object-relation`
- `object-label`
- `object-label-assignment`
- `vault-meta`
- `key-epoch`
- `multi`
- `snapshot`
- `branch`

Append `Snapshot` and `Branch` after the nine existing Rust enum variants. Do
not insert or reorder variants. Bincode discriminants 0 through 8 retain their
legacy meanings; the appended variants use 9 and 10.

The core owns exact display, serde, and strict parsing. Storage history and CLI
SQL loading delegate to the core parser. Sync test loading uses the same parser,
and bundle tests freeze exact round-trip and binary ordering. Unknown values
return an explicit error. `multi` is reserved for a finite operation that
actually spans more than one known family.

CommitContext retains the public string-shaped CommitOperation for source and
serialized-request compatibility. Its write seam now parses both CommitKind
and ChangeScope before a direct operation opens its immediate transaction.
Repository commits coalesced into an active operation validate the incoming
taxonomy before changing the aggregate scope or commit kind. The inner commit
path keeps validation as defense in depth.

## Compatibility

This decision adds no database table, column, schema migration, file-format
version, synchronization-state field, bundle-version field, key format,
critical extension, or Tiga rule. Existing database text values remain
unchanged. Old ChangeScope bincode discriminants remain byte-for-byte stable.

Current readers can read legacy rows and bundles. A pre-fix reader that maps
`snapshot`, `branch`, or a future scope to `multi` is not safe for export or
apply of that history. No integrity-preserving down-conversion exists; the
reader must be upgraded.

Rejecting an unknown taxonomy on new local writes is intentional. The previous
interface appeared extensible but produced commits that the same build could
not serialize safely. Future taxonomy additions must first extend the core
reader, append a binary variant, document compatibility, and cover every
round-trip seam.

## Consequences

Snapshot lifecycle commits are visible through storage and FFI history and can
be exported without scope mutation. Branch-family retention commits can use an
exact scope. CLI, history, bundles, and local writes share one taxonomy Module,
increasing locality and removing duplicated fallback behavior.

Callers still construct CommitOperation with strings, so existing source code
does not need a DTO migration. Invalid values now fail earlier and cannot
consume a commit sequence or leave a partially written operation.

## Verification

Tests cover core string/serde parsing, stable bincode discriminants, unknown
local kind/scope rejection with no persisted commit, storage history for
snapshot and branch, CLI exact database loading, all-scope bundle round-trip,
and a real automatic snapshot prune observed through FFI commit history.
