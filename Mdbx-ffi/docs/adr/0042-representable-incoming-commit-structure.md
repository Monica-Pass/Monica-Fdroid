# ADR-0042: Representable Incoming Commit Structure

- Status: Accepted
- Date: 2026-07-26

## Context

Commit integrity authenticates the serialized bytes, but authentication alone
does not prove that the current storage engine can represent or consume those
bytes without changing their meaning.

Three first-insertion cases violated that boundary. `commit_parents` uses
`(commit_id, parent_commit_id)` as its primary key while synchronization inserted
parents with `INSERT OR IGNORE`. A signed parent list containing the same parent
twice was therefore reduced to one physical row, making later commit-integrity
verification disagree with the original parent count. The commit vector clock
was stored without parsing even though local child creation later requires a
`BTreeMap<String, u64>`. Finally, the public model uses `u64` for `local_seq`
while SQLite INTEGER is signed 64-bit; an `as i64` cast deferred rejection until
after SQL mutation had started.

These inputs can be internally authenticated and still create an unusable or
non-equivalent local projection.

## Decision

After verifying the incoming commit integrity tag and before the first SQL
mutation, new commit insertion performs a structural preflight:

1. Convert `local_seq` with checked `i64::try_from`. Values above `i64::MAX`
   return an explicit validation error.
2. Parse `vector_clock` as `BTreeMap<String, u64>`. Malformed JSON, non-object
   shapes, negative values, floats, and non-integer values are rejected.
3. Require every parent ID to occur once in the serialized parent list.

Parent order remains insignificant because commit integrity canonicalizes it by
sorting. Duplicate membership is invalid because the relational schema stores a
set and cannot preserve multiplicity.

The parser intentionally accepts an empty `{}` vector clock. MDBX1 and migrated
history may contain that legacy representation. This decision validates the
shape that current causal algorithms consume; it does not retrofit a modern
self-device clock entry into historical commits.

The preflight returns the checked signed sequence used by both `commits` and
`commit_device_sequences`, removing unchecked integer casts. `insert_commit`
owns the validation as defense in depth for every internal caller. A failure
occurs before commit, inventory, parent, sequence, tombstone, payload, device
head, or branch state is written.

Existing commit replay remains governed by ADR-0041. An already accepted legacy
record is compared byte-for-byte with its local identity and is not reinterpreted
through new first-insertion rules.

## Compatibility

This decision adds no table, column, schema migration, MDBX format generation,
bundle version, synchronization DTO field, critical extension, key format, or
Tiga rule. Current writers already emit unique parents, representable sequences,
and parseable map clocks. Legacy `{}` clocks continue to synchronize.

A peer that signs duplicate parents, malformed clock text, or a sequence outside
SQLite's integer domain receives a validation error instead of creating a
partially useful history record. No safe compatibility conversion exists for
these cases: deduplicating parents changes authenticated identity, inventing a
clock changes causal meaning, and wrapping a sequence changes device order.

## Consequences

New synchronized commits are both authenticated and exactly representable.
Later history verification observes the same parent identity that was signed,
local child creation can parse every newly stored clock, and sequence values are
never changed by integer conversion.

The validation remains deliberately narrower than a future versioned causal
clock policy. It establishes the minimum consumable shape while retaining MDBX1
history that lacks modern vector-clock detail.

## Verification

Tests cover duplicate authenticated parents, an authenticated malformed vector
clock, `u64::MAX` local sequence, legacy empty clock acceptance, and the absence
of commit, inventory, parent, sequence, and branch side effects after rejection.
The complete synchronization apply suite covers exact replay, late payload
repair, conflicts, key rotation, audit correlation, purge receipts, and
incremental state deltas with the preflight enabled.
