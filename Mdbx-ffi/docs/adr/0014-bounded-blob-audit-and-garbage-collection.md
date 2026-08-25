# ADR-0014: Bounded Blob audit and garbage collection

## Status

Accepted

## Context

ADR-0013 introduced immutable encrypted Blob Providers and documented that database rollback can leave encrypted orphan objects. External Blob bodies also have availability and retention rules separate from SQLite rows. Deleting every unreferenced file after a single scan would be unsafe because snapshots can restore older attachment references, Provider contents can change between inspection and deletion, and a Blob may have been written shortly before its database reference commits.

A large Provider must also remain inspectable without returning an unbounded in-memory file list. Maintenance records should remain auditable without adding one logical commit for every deleted Blob.

## Decision

MDBX2 adds the additive `ManageableEncryptedBlobStore` interface. Read/write-only custom Providers remain source-compatible. A manageable Provider supplies a stable namespace identity, a bounded lexically ordered inventory page, and idempotent deletion. The filesystem implementation validates every digest-prefix directory and Blob file, rejects unexpected entries and symbolic links, limits entries per directory, and exposes at most 1,000 items per page.

`BlobLifecycleService` builds a complete bounded reference inventory from current attachment chunks and every retained snapshot. Snapshot ciphertext size, snapshot count, raw reference count, Provider object count, and garbage-collection candidate count have explicit caller limits and hard library ceilings. A malformed or unauthenticated snapshot stops maintenance because deleting a Blob while a retained snapshot cannot be examined would be unsafe.

Audit can verify every Provider object through the normal content-addressed `get` operation. The report separates healthy referenced objects, missing references, corrupt objects, recently created unreferenced objects, and old unreferenced objects eligible for deletion.

Garbage collection uses two phases. Planning receives a fixed orphan cutoff time and returns a SHA-256 plan token. The token binds the vault ID, Provider namespace, cutoff time, sorted decrypted reference inventory with expected size limits, and sorted Provider inventory with sizes and modification times. Execution repeats the scan and rejects any changed token before authorization. After TIGA authorization it acquires an immediate SQLite transaction, repeats the scan again, and deletes only the eligible objects from the matching plan.

Blob deletion uses the existing TIGA `PurgeDeletedObject` administration operation and security auditing. Reusing the persisted operation value preserves compatibility with clients that deserialize the earlier closed operation enum. One maintenance request creates one security audit event. Blob deletion does not create logical object commits, so a large cleanup does not fill the commit history with Provider-local maintenance entries.

Each Provider deletion is idempotent. Individual failures are collected in a partial result while successful deletions remain effective. A retry requires a new plan because the Provider inventory changed. Denied authorization is recorded before any Blob deletion.

The CLI exposes `blob audit`, `blob gc-plan`, and `blob gc-apply`. The default grace period is seven days. Core-only builds retain the generic lifecycle types and return a clear capability error for filesystem commands.

## Consequences

Snapshots participate in Blob liveness, so retaining a snapshot also retains its external encrypted bodies. Removing the snapshot can make those bodies eligible at the next plan.

The plan token detects Provider or database changes and is not an authorization credential. TIGA authorization remains mandatory for deletion.

SQLite and an external Provider still cannot form one atomic transaction. The grace period protects newly written objects, and the second scan protects database changes before deletion. Administrative maintenance should run while other processes refrain from importing or synchronizing external objects. A future Provider lease protocol can offer stronger cross-process coordination.

Garbage collection verifies local references and local Provider state. Provider-to-Provider transfer remains a separate synchronization capability.
