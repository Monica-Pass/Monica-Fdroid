# ADR-0044: Causal Monotonic Tombstone Acknowledgements

- Status: Accepted
- Date: 2026-07-26

## Context

Schema 8 introduced one `tombstone_acknowledgements` row per tombstone and
registered device. The row is security-sensitive evidence used by the
permanent-cleanup gate: it claims that a replica observed the deletion commit.

The relational foreign keys prove only that the tombstone and observed commit
exist. Runtime writers previously used independent unconditional upserts. A
commit preceding the deletion could therefore be accepted as observation
evidence, a delayed ancestor could replace a stronger descendant, a descendant
could lower `acknowledged_at`, and two concurrent valid proofs could produce
different stored rows according to arrival order. Health verification could not
identify an already stored non-causal proof.

Commit parent traversal was also repeated in synchronization, tombstone purge
eligibility, and recovery. Separate walkers make a security property depend on
several implementations of the same causal relation.

## Decision

MDBX defines a single crate-private `TombstoneAcknowledgementRepo` as the
runtime write boundary for acknowledgement evidence. Schema migration backfill
remains the only direct historical insertion path.

An incoming proof is accepted only when:

1. The tombstone exists.
2. The observed commit exists.
3. When the tombstone has `delete_commit_id`, that commit exists and is equal to
   or an ancestor of the observed commit.

The row for one `(tombstone_id, device_id)` merges as follows:

1. A valid incoming proof replaces an invalid local proof.
2. Equal observed commits preserve the same proof identity.
3. A descendant proof replaces an ancestor proof.
4. An ancestor proof cannot replace a descendant proof.
5. Concurrent valid proofs select the greater
   `(acknowledged_at, observed_commit_id)` pair. This tie-break runs only after
   causal comparison and therefore cannot make an ancestor stronger than a
   descendant.
6. The stored `acknowledged_at` is the later of the local and incoming values,
   independently of which observed commit is selected.

Local deletion, imported deletion, receiving-device evidence, conflict
resolution, complete-state apply, and state-delta apply use this merge. Apply
retains the existing compatibility behavior of skipping an acknowledgement
whose tombstone or observed commit is not present locally. Once both references
exist, a non-causal proof rejects the enclosing transaction before changing the
stored acknowledgement or cleanup eligibility.

A tombstone whose `delete_commit_id` is `NULL` retains schema-8 legacy behavior.
Its acknowledgement still references an existing commit, but storage does not
invent a deletion ancestor that the historical row cannot prove.

MDBX also defines one crate-private `CommitGraphRepo` for commit existence,
canonical parent reads, ancestry, and nearest-known-common-parent discovery.
Synchronization, conflict-base discovery, tombstone cleanup eligibility,
acknowledgement validation, and recovery share this causal relation.

Full health verification emits an Error in category
`tombstone-acknowledgements` for a stored proof that cannot validate against its
tombstone.

## Compatibility

No table, column, index, migration, database generation, synchronization DTO,
state version, bundle version, or capability bit is added. The schema-8
`delete_commit_id` and `tombstone_acknowledgements` layout and migration backfill
remain byte-for-byte unchanged.

MDBX1 and MDBX1-DRAFT continue to upgrade through the storage-core migration
registry. Legacy tombstones without a reconstructed delete commit remain
readable. Missing-reference complete-state rows retain their historical skip
behavior. A current storage core is required for the causal merge and the new
health diagnostic; older readers continue to interpret the same stored and wire
representation with their earlier merge behavior.

## Consequences

A pre-delete commit can no longer satisfy the permanent-cleanup gate. Stronger
causal evidence cannot regress, acknowledgement time cannot move backward, and
concurrent valid evidence converges independently of delivery order.

Wall-clock time remains only a deterministic tie-break between causally
incomparable valid proofs. It never overrides ancestor ordering. Existing
invalid rows are reported read-only by health verification and can be replaced
when a valid proof enters the runtime merge.

## Verification

Tests cover pre-delete rejection, descendant advancement, ancestor rejection,
timestamp monotonicity, concurrent delivery-order independence, missing-reference
compatibility, all runtime acknowledgement producers, cleanup eligibility, and
full health diagnostics.
