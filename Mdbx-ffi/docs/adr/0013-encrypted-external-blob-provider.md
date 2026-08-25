# ADR-0013: Encrypted external Blob Provider

## Status

Accepted

## Context

MDBX2 is intended to store credentials, mail, bookmarks, web archives, Steam `mafile` data, and future encrypted object domains. Large binary values should not force every database backup, database-page synchronization, or small metadata update to carry the complete object body.

The MDBX1 physical schema already includes `attachments.storage_mode` and `attachment_chunks.external_uri_ct`, and synchronization and snapshots preserve that column. Storage APIs previously treated `chunk_ct` as mandatory, so `external-hash-ref` rows could not be written or read. A filesystem-only implementation would also couple the core format to one deployment environment.

SQLite transactions cannot atomically commit files managed by an independent Provider. External object transfer and database-state synchronization also have different availability and retention rules.

## Decision

MDBX2 defines the `EncryptedBlobStore` interface for immutable opaque ciphertext. A Provider receives a canonical 64-character lowercase SHA-256 identifier and the exact encrypted bytes whose SHA-256 equals that identifier. The interface remains available in the core storage build. The local `FileSystemBlobStore` implementation is controlled by the `filesystem-blob-store` feature and is enabled by default.

External attachment writes require an unlocked encrypted vault. Each plaintext chunk is encrypted with the attachment subkey. The attachment ID and chunk index are included in the field AAD. The ciphertext SHA-256 becomes the Blob ID. The Provider stores that ciphertext, while MDBX encrypts the Blob ID again and stores it in `external_uri_ct`; `chunk_ct` remains `NULL`. The existing plaintext chunk hash and overall plaintext content hash continue to verify post-decryption integrity.

Reads strictly validate the storage shape. Embedded modes require `chunk_ct` and reject `external_uri_ct`. External mode requires `external_uri_ct` and rejects `chunk_ct`. MDBX decrypts and validates the Blob ID, supplies a ciphertext-size limit to the Provider, verifies the returned ciphertext hash again, decrypts the chunk with index-bound AAD, and then verifies declared size and plaintext hashes.

The local filesystem Provider places objects below two digest-prefix directories, rejects caller-controlled path syntax, rejects symlink and non-file Blob targets, uses same-directory temporary files with no-clobber persistence, bounds reads before and during allocation, and verifies existing and newly read objects.

Blob objects are written before their database references commit. A failed reader, Provider call, or database transaction restores the previous database attachment and commit state. Already written ciphertext may remain as an unreferenced immutable Blob. Automatic deletion is excluded because another database copy or future reference may still use the same object. Reference scanning and explicit garbage collection form a separate maintenance capability.

The CLI uses `<vault path>.blobs` for `attach add --external`, export, and verification. A core-only CLI build reports that the filesystem Provider capability is absent before creating attachment metadata.

## Compatibility

MDBX1 table names and columns remain unchanged. Existing embedded attachment APIs and rows retain their behavior. The old byte-vector and streaming read APIs return a typed Provider-required error when called for an external attachment. Provider-aware methods are additive.

Snapshots and synchronization continue to carry encrypted Blob references through `external_uri_ct`. They do not claim to carry Blob bodies. Applications must transfer or mount the corresponding Provider data together with database state. A missing Blob remains an explicit availability error rather than causing fallback to an embedded or plaintext representation.

## Consequences

Large mail bodies, web resources, archives, and application files can live outside the SQLite file while retaining MDBX encryption, authenticated binding, content-addressed verification, and generic attachment metadata. Metadata-only database updates no longer rewrite external object bodies.

Random authenticated encryption means equal plaintext in different attachment chunks normally produces different Blob IDs. This intentionally avoids plaintext equality disclosure and limits cross-object deduplication.

Database backup alone is insufficient for external attachments. Backup and synchronization clients must include the Provider namespace or document that only references are being transferred. Operators need a future reference scanner before reclaiming unreferenced objects.
