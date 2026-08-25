# ADR-0021: Bounded external Blob replica plans

## Status

Accepted

## Context

MDBX synchronization carries encrypted `external-hash-ref` rows, while Blob ciphertext travels through a separate Provider channel. A client cannot safely replicate a vault by manually guessing Blob IDs: references are encrypted in the database, retained snapshots may keep old bodies live, and a Provider can be missing, corrupt, or contain a same-ID object with an unexpected size. A complete comparison also must not return an unbounded list.

## Decision

`BlobReplicaService` builds a read-only, bounded comparison from the current attachment chunks and all retained snapshots. It inventories the source and destination `ManageableEncryptedBlobStore` instances, then returns canonical Blob-ID pages for actionable references:

- `transfer-required`: the source body exists within the database-declared ciphertext limit and the destination has no object;
- `source-missing`: the database references a body absent from the source Provider;
- `source-size-invalid`: the source metadata is empty or exceeds the authenticated database read limit;
- `destination-conflict`: the destination has the same content ID with a different stored size.

An exact-size destination object is omitted because content-addressed identity makes the transfer idempotent; a separate audit can verify its ciphertext when required. Planner pages are lexically ordered and carry a SHA-256 token over the vault identity, both Provider namespaces, raw and unique references, and complete source/destination inventories. A follow-up page must present the same token, so Provider or database changes fail closed instead of silently skipping or duplicating bodies.

The planner is independent of sync bundles and performs no writes, Provider leases, commits, or deletions. Transfer executors can consume `transfer-required` items through `BlobTransferService`; clients can choose their own transport and checkpoint storage. The existing Blob lifecycle audit reuses the same reference inventory implementation.

`BlobReplicaService::transfer` is the reusable bounded executor. It resumes an in-progress Blob from its nested transfer checkpoint, processes only a caller-limited number of items and chunks, and stops at the first non-transferable state without advancing beyond it. Publishing a destination object necessarily changes the destination-bound plan token, so the executor clears that token and refreshes the next page; completed objects then disappear from the actionable set. A partial file is excluded from Provider inventory and continues under its exact Blob checkpoint.

## Compatibility

No MDBX1 table, attachment column, snapshot format, sync bundle, or Provider required method changes. Existing read/write-only Providers remain source-compatible. The planner is an additive storage capability and remains available in core builds, while filesystem inventory is still feature-gated.

## Consequences

A database sync client can discover all external bodies needed for convergence without plaintext disclosure or manual ID extraction. Missing and conflicting bodies become explicit operator-visible states instead of being mistaken for successful synchronization. The plan token is a consistency binding, not an authorization credential; transfer and Provider leases remain separate concerns.

The default CLI exposes `blob replica-plan` for human or JSON inspection and `blob replicate` for bounded execution. The latter stores `mdbx-cli-blob-replica-checkpoint-v1` atomically, preserves the transfer owner, retains blocked state for repair, and removes the checkpoint only after all referenced transfer candidates converge.
