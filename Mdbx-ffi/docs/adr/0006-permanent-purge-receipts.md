# ADR-0006: Permanent Purge Receipts

- Status: Accepted
- Date: 2026-07-20

## Context

Schema 8 established the retention, delete-commit, conflict, and device-acknowledgement conditions required before physical cleanup. Removing an eligible row and its tombstone without durable evidence would still allow an old replica or snapshot to restore the same stable object identity.

MDBX2 is a generic encrypted database. Cleanup therefore has to cover every synchronized physical object family and its shared metadata without depending on password-specific payload meaning. Projects, entries, attachments, relations, labels, and assignments also have different ownership dependencies that make arbitrary deletion order unsafe.

Physical cleanup of the active database is distinct from erasing historical snapshot files, exported copies, filesystem remnants, and external backups. Those media can retain older ciphertext after the current vault has removed an object.

## Decision

Schema 9 adds the monotonic `purge_receipts` table. Each receipt binds:

1. A deterministic purge identity and the original tombstone identity.
2. The exact physical target type and stable target ID.
3. The delete commit, delete vector clock, and retention eligibility time.
4. The purge commit, executing device, and execution time.
5. An HMAC integrity tag authenticated by the vault keyring.

Permanent cleanup is a Tiga administration operation. Execution rechecks the complete eligibility state inside the same immediate transaction, rejects remaining dependants, creates one deterministic purge commit, writes the receipt, removes owned state, and commits atomically. Retrying the same tombstone returns the existing receipt and purge commit.

Project, Entry, and ObjectLabel use a child-first rule. Their cleanup is blocked while dependent objects remain. The successful transaction removes the active row, ObjectVersions, tombstone acknowledgements, the tombstone, object-scoped Tiga overrides, and relevant project-label or attachment-chunk state. The receipt remains as the only current-vault identity guard.

Complete synchronization state carries purge receipts as an optional additive field. Receivers apply receipts before ordinary object state and remove stale local rows in dependency order. Every object-family application path and complete tombstone replacement checks the receipt guard. A conflicting rewrite of an existing receipt is rejected.

Snapshot restoration checks the same receipt guard. A snapshot may still contain an older object, but restoration skips that physical type and stable ID together with owned chunks and project-label rows. Explicit local creation methods that accept stable IDs also reject reuse after permanent cleanup.

Recovery diagnostics verify receipt integrity and report a critical issue when a receipt coexists with an active physical row or a tombstone.

## Consequences

Old synchronization state and snapshots cannot revive a permanently cleaned stable identity in the current vault. Cleanup remains generic across password, bookmark, mail, Steam `mafile`, and future object domains because the proof is keyed by physical object family and stable ID.

The receipt is a durable logical-erasure proof, not a claim that every storage medium has been securely overwritten. Stronger erasure requires later object-key destruction, snapshot lifecycle controls, and deletion of external backup media.

MDBX1 public interfaces and the physical `projects` and `entries` tables remain present. Older MDBX2 schemas migrate automatically through schema 9 and later compatible schema upgrades, and old synchronization payloads remain readable because the receipt collection is optional during deserialization.
