# ADR-0037: Bounded Steam mafile Storage Bridge

- Status: Accepted
- Date: 2026-07-25

## Context

ADR-0036 deliberately stops the pure Steam Adapter at bounded parsing,
canonical JSON, and stable identity. A client can feed those bytes into the
Generic Object Module, but leaving the complete mapping to every client would
duplicate security-sensitive rules: UUID projection, batch ordering,
create/update/restore selection, aggregate limits, Collection ownership
checks, capability rollback, and idempotent retry behavior.

Putting those rules in `mdbx-storage` would make the mandatory encrypted
database core depend on Steam semantics. Putting them in each UI would make
the same mafile produce different operations on desktop, Android, or future
clients. The mapping therefore needs its own independently removable boundary.

## Decision

Add the default-empty workspace crate `crates/mdbx-adapter-steam-storage`.
It depends only on the pure `mdbx-adapter-steam` crate and the `core` feature
of `mdbx-storage`. Storage, sync, CLI, and FFI do not depend on the bridge.

The pure Adapter keeps the canonical domain-separated SHA-256 identity. Its
generic-object UUID projection uses the first 128 digest bits, sets the RFC
variant, and uses custom UUID version 8. It does not introduce a random ID or
a second digest domain.

`SteamMaFileImportPlan::prepare` applies the following contract before any
mutation:

- validate caller, document, and generic write limits;
- reject an empty batch, duplicate stable identity, invalid JSON, and source
  aggregate overflow;
- parse and canonicalize every document, then sort commands by stable UUID;
- read existing state only through payload-free `ObjectSummaryRepo`;
- select create, update, or restore-then-update without inferring deletion
  from an item missing from the request;
- require an existing object to remain in the requested Collection, have the
  exact `com.monica.steam.mafile` ObjectTypeId, and use payload schema version
  1; and
- prepare existing generic `WriteCommand` values through
  `OperationCoordinator`, adding no Steam-specific repository or SQL path.

The default aggregate import contract is 128 documents and 8 MiB of source
bytes. Hard ceilings are 2,048 documents and 64 MiB. Per-document parser
limits and generic write-operation limits remain independently enforceable.

The prepared plan contains canonical mafile plaintext and is therefore
sensitive, memory-only state. Its Debug output exposes only operation metadata,
opaque object IDs, action categories, and byte counts. The source, request,
plan, and error interfaces never print mafile bytes, SteamIDs, serial numbers,
tokens, or field values.

Execution consumes the exact prepared generic intent and creates one atomic
operation commit. An uncertain retry reuses the same prepared plan and returns
the original commit idempotently. Re-parsing or re-planning after database
state changes is a new planning action, not the retry of the old plan.

Process-local ExtensionProfile registration remains separate from activation
of `com.monica.steam.store`. The bridge helper can register the descriptor and
build a CollectionProfile specification, but it cannot grant capability, key,
raw-SQL, synchronization-peer, or Tiga authority.

Restore-then-update also freezes a generic commit rule. The native coordinator
derives the repository commit kind before execution: ordinary writes use
`change`, a single restore uses `restore`, a single move uses `move`, and an
operation combining repository kinds uses `multi`. `CommitContext` accepts
repository-specific kinds only when the outer operation explicitly declares
`multi`, keeping the stored request identity stable across retries.

## Compatibility

This decision adds no table, column, database schema version, snapshot field,
synchronization-wire field, key format, critical extension, or Tiga policy.
Every persisted Steam object remains an ordinary encrypted ObjectRecord using
existing CollectionProfile, commit, object-version, tombstone, snapshot, and
sync representations.

MDBX1 and MDBX1-DRAFT opening and migration remain owned by the storage core;
the bridge does not create a client-side format converter. Builds without
either Steam crate preserve existing objects through opaque generic paths.
Removing only the storage bridge still leaves the pure parser available.
Removing both crates removes Steam interpretation but does not delete or
retype data.

## Consequences

Desktop, Android, and future native clients can share one bounded mapping
without making Steam mandatory. Batch history follows user intent instead of
producing one commit per object. Capability failure or any later command
failure rolls back the whole batch.

The plan intentionally does not synchronize source-folder membership and does
not delete database objects absent from an import request. A client that wants
mirror semantics must define a separate explicit deletion operation with its
own confirmation, tombstone, limits, and operation identity.

The first bridge is native Rust only. Adding CLI or FFI exposure is a later,
separately reviewed Adapter surface and must preserve the same limits,
redaction, capability separation, and prepared-plan retry rules.

## Verification

Bridge tests use synthetic fixtures only and cover deterministic ordering,
single-commit create, exact-plan retry, update, restore-then-update,
capability-failure rollback, duplicate identity, document and byte limits,
invalid JSON, cross-Collection rejection, and Debug/error redaction. Storage
regression tests cover aggregate restore/update commit kind and the dedicated
move commit kind.
