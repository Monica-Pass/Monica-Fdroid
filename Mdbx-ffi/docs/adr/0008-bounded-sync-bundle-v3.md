# ADR-0008: Bounded Sync Bundle Version 3

- Status: Accepted
- Date: 2026-07-20

## Context

Offline synchronization accepts files from USB devices, mail attachments, shared folders, and other untrusted transports. Bundle versions 1 and 2 stored the encoded payload followed by a SHA-256 hash without recording the payload length. The reader used `read_to_end`, so a malicious or accidental oversized file could consume memory before integrity verification. The bincode decoder also had no hard byte ceiling.

MDBX2 is intended to store mail, bookmarks, Steam `mafile`, credentials, and future encrypted object domains. Legitimate bundle sizes therefore vary by device class, while every client still needs a finite resource boundary.

## Decision

Bundle version 3 keeps the 32-byte header and stores the little-endian payload length in the first eight formerly reserved bytes. The remaining twelve reserved bytes must stay zero. The payload remains bincode 2 serde data followed by the SHA-256 payload hash. The outer hash detects transport corruption; commit and object authenticity continue to come from the authenticated MDBX records inside the payload.

The default reader accepts up to 128 MiB. Clients can select a lower limit, while the desktop profile accepts up to the mandatory 1 GiB protocol ceiling. A declared length above the selected limit is rejected before allocation. Allocation uses fallible reservation, the reader consumes exactly the declared payload and hash, and any trailing byte is rejected.

Versions 1 and 2 remain readable. Their missing length prefix is handled through `Read::take` with one sentinel byte beyond the selected limit, replacing the previous unbounded read. All versions use a bincode hard limit equal to the protocol ceiling and require the decoder to consume the complete hash-checked payload.

Writing version 3 uses two streaming serialization passes. The first pass counts bytes and enforces the hard ceiling without allocating a second payload copy. The second pass writes directly to the destination while computing the hash. Destination I/O errors retain their original error kind.

## Consequences

Untrusted bundle input has a finite memory boundary before deserialization. Desktop and constrained clients can choose different limits without changing the file format. Existing v1 and v2 files remain importable, while newly exported files require a v3-capable reader.

The writer serializes the bundle twice, trading CPU time for lower peak memory. Large histories still require future commit-range segmentation and resumable transport; version 3 establishes the resource-safe envelope needed for that work.
