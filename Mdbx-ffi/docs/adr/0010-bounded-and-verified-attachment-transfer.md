# ADR-0010: Bounded and Verified Attachment Transfer

- Status: Accepted
- Date: 2026-07-20

## Context

Streaming attachment I/O made plaintext memory proportional to one chunk, but total input remained bounded only by SQLite's integer range. A growing file, an endless reader, or an incorrect client size declaration could therefore hold the database write transaction and consume storage far beyond the caller's intended object size.

Streaming export also sent plaintext to the destination before the final attachment hash was known. The CLI opened its destination with truncation, so a damaged attachment could destroy an existing output file before returning an integrity error.

MDBX2 needs resource rules suitable for mail, web archives, Steam `mafile`, and other encrypted object domains while preserving the MDBX1 byte-vector APIs.

## Decision

The storage repository adds `AttachmentWriteOptions` with a chunk size, a maximum plaintext size, and an optional exact plaintext size. Buffer allocation is capped by the smaller of the requested chunk size and the total allowance plus one sentinel byte. The bounded reader reduces its final read request to the remaining allowance plus that sentinel. Oversized input is therefore detected after consuming at most one byte beyond the configured maximum. A shorter or longer exact-size source returns a validation error. All such errors occur inside the existing immediate transaction, restoring previous chunks, attachment metadata, object versions, and commit state.

The original `write_content_from_reader` remains available with the previous SQLite-range capacity. The byte-vector `write_chunked_content` wrapper supplies the known slice length as an exact constraint. This keeps existing callers source-compatible while allowing clients to apply device, account, or object-type limits through the new options API.

The CLI uses the source file metadata length as both the exact size and maximum size. Export creates a securely permissioned temporary file in the destination directory, streams and verifies the complete attachment into it, synchronizes its contents, and then persists it over the destination using the platform replacement primitive provided by `tempfile`. Read, integrity, synchronization, and replacement errors before persistence leave the existing destination unchanged, and dropping the temporary file removes incomplete plaintext.

## Consequences

Client-selected limits now bound database growth and write-transaction duration for a single attachment operation. Exact-size imports detect concurrent source growth or truncation without reading an unbounded suffix. Existing MDBX1 callers continue to use their original methods and physical schema.

CLI exports require temporary free space equal to the plaintext attachment size. This cost provides target preservation and prevents unverified plaintext from replacing a trusted file. Applications with custom output sinks can continue using `read_content_to_writer`; applications that replace filesystem targets should follow the same verify-then-persist pattern.
