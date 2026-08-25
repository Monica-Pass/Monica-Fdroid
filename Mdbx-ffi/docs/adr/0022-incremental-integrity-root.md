# ADR-0022: Incremental authenticated synchronization root

## Status

Accepted

## Context

MDBX already authenticates individual fields, commits, snapshots, sync-delta
envelopes, and vault-header metadata. `VaultContentManifestService` can issue
an exact O(vault-size) checkpoint, and `RollbackAnchorService` can detect a
rollback of the commit and delta inventories. Neither is an automatically
refreshed root for routine small writes. Recomputing the complete manifest on
each edit would violate the local-first performance contract and would make a
large mail or bookmark vault pay for unrelated password edits.

The existing transaction-level sync-delta capture is the correct seam: it
already deduplicates changed logical keys, materializes final state, and runs
before the enclosing SQLite transaction commits. A root updater can therefore
share the same atomicity without making every repository know about hashing.

## Decision

The implemented profile uses a sparse Merkle tree over authenticated logical-state
leaves. A leaf contains only a domain-separated logical key and a digest of its
canonical encrypted/state representation; it never stores plaintext payloads.
The key is mapped to a fixed-depth path, and changed or deleted leaves update
only their path to the root. Root metadata records the profile version, vault
identity, schema version, latest commit/delta anchors, counts, and an HMAC under
the vault integrity subkey.

The updater is one internal Module behind the sync-delta finalization seam. A
mutation, incoming sync apply, or explicit rebuild updates the leaf index and
root nodes in the same outer transaction. Any collection, encoding, HMAC, or
resource-limit failure rolls back the user mutation and leaves the previous
root valid. Rebuild is bounded, atomic, and retryable; an interruption restores
the prior established root instead of exposing a partial tree. It is used for
explicit opt-in and repair, not ordinary edits.

## Coverage contract

- Covered: synchronized logical state families already represented by
  `SyncDeltaBody`, including opaque ObjectRecords, relations, labels,
  attachments and referenced ciphertext digests, Tiga state, key epochs,
  tombstones, purge receipts, branches, device heads, and security audit rows.
- Covered metadata: root profile, schema version, vault identity, and the
  latest commit/delta inventory anchors.
- Not covered: external Provider object bytes themselves, OS state, availability,
  or arbitrary unregistered SQLite tables. The existing content manifest
  remains the exact full-schema checkpoint for those physical extensions.
- Unknown synchronized object types remain opaque and are hashed by stable
  type/id plus authenticated bytes; absence of an Adapter never removes them
  from the root.

## Compatibility and capability

The profile is additive. MDBX1 through schema 16 continue to open under their
existing rules. A vault without an established root stays readable and can
explicitly rebuild one after verified unlock. Once a root profile is marked
established, a reader that cannot validate it must fail closed for trusted
writable or synchronization operations rather than silently downgrade.

The root is not added to the four mandatory incremental-sync capabilities. The
peer capability, `authenticated-state-root-v1`, is opt-in and only
selects root exchange when both peers support the same profile. Legacy peers
continue using existing commit/delta checkpoints and complete-state fallback.

The exchanged checkpoint is authenticated under a separate peer-checkpoint
domain with the vault ID and schema version as implicit context. It contains
only profile, generation, leaf count, root hash, commit/delta anchors, and the
tag. Clients persist the last verified value outside the vault, keyed by peer
identity. A later value may keep the same generation only when every field is
identical; a higher generation must not decrease either inventory anchor.
Rollback, same-generation equivocation, foreign-vault values, and tampering
fail closed.

This checkpoint is a rollback/resume anchor for one remote replica, not a
general convergence oracle between replicas. Local inventory ordering may
differ after concurrent offline work, so clients must not require the local
and remote root hashes or inventory sequence numbers to be equal before sync.

The implementation keeps schema 16 unchanged and creates the root metadata,
leaf, and sparse-node tables only when a verified-unlocked client explicitly
enables the profile. Establishment registers the same identifier as a critical
extension, so older MDBX2 writers reject the vault before writable open. The
public status and verification Interface exposes only bounded metadata,
digests, counts, generations, and inventory anchors; Recovery/Health reports
pending, locked, stale, or tampered states without exposing payload bytes.

## Consequences

The root provides O(log N) maintenance for covered logical keys and a compact
authenticated checkpoint suitable for clients that need frequent rollback or
resume verification. It does not replace commit history, content manifests,
rollback anchors, or payload encryption. The explicit exclusions prevent an
incremental logical-state root from being mistaken for proof about external
storage or arbitrary SQLite extensions.
