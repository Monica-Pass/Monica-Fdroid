# ADR-0034: Native Operation Coordinator

- Status: Accepted
- Date: 2026-07-25

## Context

MDBX2 assigns one finite client intent to one `CommitOperation`. The UniFFI
write facade already enforced bounded command counts, payload sizes, intent
hashing, change summaries, and atomic repository dispatch. Native Rust callers
had to reproduce those rules by hand with `CommitContext`, which allowed
different clients to describe the same kind of user action inconsistently.

Keeping the generic protocol in FFI also made attachment composition depend on
private FFI helpers. The storage layer is the owner of repository semantics and
must expose the same bounded boundary to native adapters such as mail,
bookmark, and Steam importers.

## Decision

`mdbx-storage::repo` exposes a typed `WriteCommand` model,
`WriteOperationLimits`, `WriteOperationRequest`, and `OperationCoordinator`.

`OperationCoordinator::prepare` performs all connection-independent checks:
operation identity, command count, per-command and total payload bytes, UUIDs,
object and relation identifiers, schema versions, JSON syntax, and a bounded
streaming SHA-256 digest. It also produces the normalized change summary and
scope used by `CommitOperation`. Preparation can therefore finish before a
client acquires its vault write lock.

`OperationCoordinator::execute` is the native convenience entry point. It
passes one prepared command set through `CommitContext::run_operation`, so
repository mutations, commit metadata, branch heads, and synchronization
materialization retain one transaction and one idempotent operation identity.
`execute_prepared` and `PreparedWriteOperation::apply` provide a composition
seam for another bounded command family, such as the existing attachment
composite operation, while leaving that family's semantics separate.

The command enum keeps the historical tagged JSON field order and raw payload
strings. This preserves the intent digest used by existing UniFFI operations;
an exact retry created before the coordinator was introduced still resolves to
the original commit. Private prepared commands hold parsed `ObjectTypeId`,
`RelationKindId`, and JSON values for transaction execution.

Preparation also derives the repository commit kind before execution. A
homogeneous operation retains `change`, `restore`, or `move`; a command set
that combines repository kinds uses `multi`. This lets restore-then-update
remain one stable operation without changing the stored request identity while
the transaction is already running.

## Compatibility

The change adds no table, column, snapshot field, synchronization-wire field,
or physical MDBX1 representation. Existing repository methods, including
single-object MDBX1-compatible writes, remain available. Existing UniFFI
method names and records remain an FFI concern and continue to expose the same
limits and result shapes after delegating to storage.

Attachment command semantics remain outside this responsibility. No implicit
time-window commit grouping is introduced; clients choose each finite
operation boundary explicitly.

## Consequences

Native adapters share one resource and retry contract with FFI clients. A
failed command rolls back the complete operation, and a repeated operation ID
with a different command digest is rejected. Large imports still require
several explicitly bounded operations, which preserves cancellation and
progress boundaries in history.
