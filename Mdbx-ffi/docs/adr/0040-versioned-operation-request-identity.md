# ADR-0040: Versioned Operation Request Identity

- Status: Accepted
- Date: 2026-07-26

## Context

`CommitOperation` uses `operation_id` as its idempotency key and stores a
32-byte `request_hash` beside the resulting commit. Two behaviors weakened that
contract. `run_operation` compared the hash only when the caller supplied an
explicit `intent_hash`, so a retry without one could reuse the same operation ID
with a different message, parent set, or request metadata and still receive
`AlreadyCommitted`. Coalesced repository mutations also rebuilt `request_hash`
from the final aggregate operation, replacing the identity of the submitted
request with a digest of the resulting change summary.

The existing `request_hash` BLOB is already carried by commit history and
synchronization metadata and is covered by operation integrity. Its schema and
wire type do not require a fixed byte length. The initial request identity can
therefore become explicit without adding another database column or protocol
field.

## Decision

New operation rows store `OperationRequestIdentity` version 1 in the existing
`request_hash` BLOB:

```text
"MDBXORI1" || request_digest_sha256
```

The ASCII prefix is eight bytes and the digest is 32 bytes, producing a
40-byte value. `request_digest_sha256` uses the existing canonical request-hash
algorithm. An explicit `intent_hash` remains the caller-defined immutable
intent. Without one, the digest covers the original serialized
`CommitOperation` after stable branch resolution and before repository
mutations are executed.

An active operation retains this 40-byte identity separately from its mutable
aggregate commit metadata. Coalescing may update commit kind, change scope,
parents, changed objects, encrypted summary, and message, but every rewrite
reuses the initial request identity. The operation integrity tag authenticates
the stored identity together with the operation and commit metadata.

Retry handling verifies operation integrity before trusting stored metadata.
A versioned identity always requires an exact digest match, including when the
request omits `intent_hash`. An untagged 32-byte value remains a legacy request
hash. Direct operation commits and legacy requests with an explicit intent
still require an exact match. A legacy coalesced `run_operation` without an
explicit intent retains its historical retry behavior because earlier writers
may have replaced the original request hash with the final aggregate digest.
Any other length or any 40-byte value without the recognized prefix is rejected.

Synchronization treats `request_hash` as authenticated opaque bytes and
preserves the 32-byte or 40-byte representation exactly. No synchronization
layer derives, normalizes, or rewrites the identity.

## Compatibility

This decision adds no table, column, schema migration, MDBX format generation,
bundle version, synchronization DTO field, critical extension, key format, or
Tiga policy. MDBX1 and MDBX1-DRAFT have no operation metadata and continue
through their existing compatibility projections. Earlier MDBX2 operation rows
remain byte-for-byte unchanged and are read through the untagged 32-byte path.

New readers accept both encodings. Earlier MDBX2 binaries can transport the
BLOB as opaque synchronization metadata, but they do not implement the stronger
versioned retry comparison and may reject a direct retry of a 40-byte identity.
Clients that need reliable retry of operations created by the current writer
must use a current storage core. Older writers may continue creating legacy
32-byte rows; current readers preserve their documented compatibility behavior.

The compatibility exception for a legacy coalesced request is intentionally
narrow. The original submitted request cannot be reconstructed after an older
writer replaced its digest. Rewriting those rows during migration would change
authenticated history and is prohibited.

## Consequences

New operations have one stable meaning for request identity throughout their
lifetime. A timeout retry with changed request metadata fails even when the
caller did not provide an explicit intent digest, while an exact retry returns
the original commit without executing mutations again.

The physical column retains its historical `request_hash` name and now accepts
two documented lengths. Code that inspects the database must treat the value as
an opaque versioned identity rather than assuming every row contains a bare
SHA-256 digest.

## Verification

Tests cover changed no-intent retries, tampered stored identities, authenticated
unknown encodings, stable identity across multiple coalesced repository writes,
legacy untagged coalesced retries, direct legacy exact comparison, CLI database
loading, synchronization bundle round-trip, and export/apply preservation into
a second copy of the same vault.
