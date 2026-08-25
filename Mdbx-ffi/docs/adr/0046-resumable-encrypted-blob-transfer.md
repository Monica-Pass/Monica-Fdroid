# ADR-0046: Resumable encrypted Blob transfer and Provider leases

## Status

Accepted

## Context

MDBX synchronization already carries encrypted `external-hash-ref` metadata, but it does not carry the referenced ciphertext. Mail attachments, web archives, Steam `mafile` data, and other large generic objects therefore require a separate Provider data path. Reading a complete Blob through the original `get` interface would make memory use proportional to object size. Garbage collection can also race another process that is copying an otherwise unreferenced object.

## Decision

MDBX2 adds the optional `EncryptedBlobTransferStore` capability without changing the required methods of `EncryptedBlobStore`. Transfer-capable Providers expose bounded offset reads, ordered durable chunk writes, stable namespace identity, and expiring per-Blob leases. Read/write-only custom Providers remain source-compatible and can omit this capability.

`BlobTransferService` limits total bytes, chunk size, and chunks processed per call. Each call returns a checkpoint binding the source namespace, destination namespace, Blob ID, declared size, and durable destination offset. A caller can persist the checkpoint and continue in another process. The destination accepts only the next exact offset. Completion verifies the ciphertext SHA-256 against the content-addressed Blob ID before publishing the object. Repeating a completed transfer is idempotent.

The filesystem Provider stores incomplete bodies and leases in sibling sidecar directories so Blob inventory remains a strict digest-prefix tree. Partial writes are synchronized before the checkpoint advances. Lease acquisition uses exclusive creation, supports renewal by the same owner, rejects a different live owner, and permits takeover after expiry. Both source and destination are leased for each transfer call. Leases are released on successful, incomplete, and failed calls; a process crash leaves an expiring record.

Checkpoint persistence and Provider writes cannot form one atomic transaction. If a crash leaves the durable partial body ahead of the saved checkpoint, the source replays old chunks. The filesystem Provider compares replayed ciphertext with the already staged range before accepting it. If the complete content-addressed object was published before the checkpoint update, its exact size and SHA-256 identity make the stale replay idempotent.

Garbage collection asks the manageable Provider whether each Blob is leased and refuses deletion while a lease is active. The filesystem `delete` implementation repeats that check immediately before removing the file, covering a lease acquired after planning.

## Compatibility

No database table, attachment reference, bundle, or synchronization format changes. MDBX1 migration and MDBX2 readers are unaffected. Core builds retain the generic transfer contracts and service; the filesystem implementation remains controlled by `filesystem-blob-store`.

## Consequences

Database state and Blob bodies remain separate synchronization streams, but both can now converge without loading a large object into memory. A checkpoint is a consistency binding, not an authorization credential. Providers that need stronger multi-host coordination can implement the same contracts with object-store conditional writes or distributed leases.

The default CLI exposes `blob transfer <blob-id> --size <bytes> --destination <provider-root> --checkpoint <path>`. It processes a bounded number of chunks per invocation, writes a versioned `mdbx-cli-blob-transfer-checkpoint-v1` file atomically, preserves the lease owner across invocations, and removes the checkpoint only after the destination object is complete.
