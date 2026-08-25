# ADR-0032: Authenticated snapshot lifecycle and bounded retention

## Status

Accepted

## Context

MDBX1 stores every recovery point in the six-column `snapshots` table. Those
rows do not distinguish a user-created recovery point from an automatic one,
so treating an old row as retention-eligible would make a compatibility
upgrade destructive. A generic encrypted database also needs bounded cleanup:
mail, bookmark, mafile, attachment, and password workloads can all create
large snapshot histories.

## Decision

Schema 17 adds the companion table `snapshot_lifecycle` without changing any
MDBX1 snapshot column or payload. A missing companion row always means a
protected manual snapshot. New authenticated rows contain:

- `snapshot_kind`: `manual` or `automatic`
- `retention_eligible_at`: required only for automatic rows
- `integrity_profile`: `hmac-sha256-v1`
- a 32-byte vault-key HMAC

The HMAC binds the vault ID, snapshot ID, base commit, snapshot descriptor,
classification, normalized retention time, creation metadata, and ciphertext
byte length. Automatic lifecycle creation therefore requires a verified,
unlocked integrity key and is exposed through the TIGA `CreateSnapshot`
operation.

`SnapshotLifecycleRepo::plan_automatic_prune` keeps an explicit number of the
newest automatic snapshots and returns at most 200 elapsed, authenticated
candidates. `keep_latest` is capped at 10,000. The query projects
`length(snapshot_ct)` but never selects, decrypts, or deserializes the BLOB.
The opaque SHA-256 plan token binds the vault, policy parameters, exact
candidate metadata, and continuation state.

Authorized execution rechecks the token inside an immediate transaction and
uses the TIGA `ManageSnapshotRetention` operation. One plan creates one
operation-level commit, deletes at most 200 snapshot rows, and records one
correlated successful security audit event. The operation ID is derived from
the plan token, so a retry returns the original commit and deleted IDs without
another deletion, commit, or successful audit event.

Lifecycle rows are local recovery state. They are not added to
`SyncStatePayload`; synchronizing application objects must not silently create
or delete local recovery points.

## Compatibility

The migration is additive and transactional. It does not rewrite existing
snapshot rows, snapshot ciphertext, descriptors, commits, or restore payloads.
MDBX1 and earlier MDBX2 snapshots have no lifecycle row and remain manual
forever unless a new, authenticated lifecycle action explicitly registers
metadata. Existing complete snapshot APIs, bounded summary APIs, restore
semantics, and CLI command shapes remain available.

## Consequences

Clients can implement automatic recovery policies without product-specific
schema changes, while legacy recovery points fail safe. Cleanup cost and commit
history remain bounded per user action. A client must retain and submit the
storage-issued plan token; it cannot construct candidate IDs or authorize
deletion by itself.
