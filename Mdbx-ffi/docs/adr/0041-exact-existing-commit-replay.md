# ADR-0041: Exact Existing Commit Replay

- Status: Accepted
- Date: 2026-07-26

## Context

Synchronization supports receiving transport payloads after their commit row is
already present. A complete commit may arrive first without its state delta, and
a later segment can repair that payload while reporting the commit itself as
skipped. An older bundle may also omit `CommitOperationMetadata`, followed by a
newer bundle that supplies the original authenticated metadata.

The existing-commit branch previously tested only whether `commit_id` was
present. It returned before the normal insertion path verified commit integrity
and compared no local commit fields or parents. Payload application could
therefore continue for a different vector clock, device sequence, message, or
other commit metadata under the same ID. If operation metadata was present,
the duplicate check compared only `commit_id` and `request_hash`; operation
kind, branch, encrypted summary, creation time, and integrity tag could disagree
without an error.

Late payload repair is necessary, but a commit ID and operation ID are immutable
authenticated identities. Repair must add transport material to the exact
existing identity, never select between two meanings for the same identifier.

## Decision

When an incoming `commit_id` already exists, storage loads the local commit and
requires exact equality for:

- device ID
- local sequence
- commit kind
- change scope
- encrypted changed-object IDs
- vector clock
- optional encrypted message
- creation time
- integrity tag
- canonical parent set

Parent order is not significant because commit integrity already sorts parents;
duplicate or different parent membership is significant. The comparison occurs
inside the immediate apply transaction and before any payload mutation.

First insertion continues to recompute and verify the incoming commit integrity
tag with the active connection keyring. Existing replay instead requires the
incoming tag and all authenticated bytes to equal the already accepted local
record. It does not recompute a historical tag with the connection's current
verification state, which preserves commits admitted through earlier compatible
verification modes.

If incoming operation metadata is present, its integrity is verified before
storage. Existing rows selected by either `operation_id` or `commit_id` must
match all fields exactly:

- operation ID and commit ID
- operation kind
- branch ID and branch name
- encrypted change summary
- request identity
- creation time
- integrity tag

The two IDs therefore retain a one-to-one mapping. If the local commit has no
operation row, the exact authenticated incoming metadata may be inserted as an
additive repair. If an older incoming bundle omits operation metadata, an
existing local row remains unchanged.

Only after these checks may the existing-commit branch process late object
payloads or state-delta envelopes and return `Skipped` for the commit row.

## Compatibility

This decision adds no table, column, schema migration, MDBX format generation,
bundle version, synchronization DTO field, critical extension, key format, or
Tiga rule. MDBX1 and MDBX1-DRAFT commits continue through the same legacy
projections. Bundle v1 can still omit operation metadata, and a later compatible
bundle can supply the original row.

Exact commit bytes already define integrity and history identity, so current
rows require no rewrite. Existing payload-repair behavior remains available for
an exact commit. A peer that reserializes or regenerates commit metadata instead
of retransmitting the authenticated values receives a validation error.

## Consequences

Commit replay becomes deterministic. Synchronization cannot silently accept
equivocation under an existing commit or operation ID, and conflicting metadata
fails before payload application. Late state-delta repair and additive operation
metadata repair remain idempotent.

The duplicate operation query now checks both unique directions. A reused
operation ID pointing to another commit and a reused commit ID pointing to
another operation are both explicit validation failures rather than database
constraint side effects.

## Verification

Tests cover an internally signed vector-clock change and changed parent
membership under an existing commit ID, conflicting local operation metadata,
exact late operation-metadata repair, legacy replay that omits a local operation
row, and existing late state-delta repair. The complete synchronization apply
suite covers key rotation, audit correlation, purge receipts, conflicts, state
extensions, and incremental segments under the strengthened replay check.
