# ADR-0009: Streaming Encrypted Attachment I/O

- Status: Accepted
- Date: 2026-07-20

## Context

MDBX2 is a general encrypted database for credentials, mail, bookmarks, Steam `mafile` data, web archives, and future object domains. These domains include binary objects whose size can exceed available memory, especially mail attachments and captured web resources.

The original MDBX attachment API accepted and returned complete byte vectors. The CLI also loaded a complete source file before encryption and loaded the complete plaintext before export. Peak memory therefore grew with object size. A failed CLI import could also leave attachment metadata committed separately from its content, and one user action produced two commit records.

The physical `attachments` and `attachment_chunks` tables are part of the MDBX1 compatibility surface and must remain readable by existing storage and synchronization code.

## Decision

The attachment repository provides a `Read`-based writer and a `Write`-based reader. The writer fills one caller-selected plaintext buffer, computes the overall SHA-256 incrementally, computes a SHA-256 for each chunk, encrypts that chunk, and inserts it before reusing the buffer. All chunk rows, attachment metadata, object versions, and commit changes remain in one SQLite immediate transaction. A reader error returns its original I/O error and restores the previous database state.

The streaming reader validates the declared chunk count, contiguous indices, declared sizes, every chunk hash, and the overall content hash while decrypting one chunk at a time. Writer errors retain their original I/O error kind. Integrity verification uses the same reader with an I/O sink so verification has bounded plaintext memory.

The existing `write_inline_content`, `write_chunked_content`, and `read_content` functions remain available as MDBX1-compatible byte-vector wrappers. A zero chunk size now returns a validation error instead of panicking. The physical schema and encrypted field format are unchanged.

The CLI imports files through the streaming writer with 1 MiB plaintext chunks and exports files through the streaming reader. Attachment metadata creation and content insertion run inside one operation-level commit, so one CLI action produces one commit and any source read failure restores both metadata and content.

## Consequences

Peak plaintext memory is proportional to one chunk instead of total object size. The same attachment structure can support small inline values and large multi-chunk objects without introducing domain-specific tables. Constrained clients can choose smaller chunks, while desktop clients can use larger chunks for throughput.

The database write transaction remains open while the source stream is read and encrypted. Applications importing from slow or unreliable networks should first stage input in a bounded local file, then pass that file to the repository. Resumable remote object transfer and external encrypted object stores remain separate capabilities that can be added without changing the MDBX1 tables or the compatibility wrappers.
