# ADR-0017: Bounded Sync State Payloads

- Status: Accepted
- Date: 2026-07-20

## Context

MDBX synchronization carries a complete `SyncStatePayload` inside a serialized commit so a new or divergent vault can materialize projects, generic objects, attachments, metadata, policy state, tombstones, and key epochs. The surrounding `mdbx-sync` bundle has a bounded reader, but the state payload previously went straight to JSON deserialization. A malicious or accidentally oversized state could therefore allocate a large buffer before storage validation.

The state object also uses reserved object type and ID values. Treating a reserved type with an arbitrary ID or mismatched associated data as an opaque application payload weakens the boundary between core synchronization and ordinary Adapter data.

## Decision

`mdbx-storage` defines `SyncStateLimits` with two independent limits:

| Resource | Default | Hard ceiling |
|---|---:|---:|
| Encoded state bytes | 96 MiB | 512 MiB |
| Logical state rows | 250,000 | 2,000,000 |

The default `collect_sync_state_payload` and `decode_sync_state_payload` APIs use the default limits. Desktop or controlled transport callers may pass an explicit `SyncStateLimits` through `collect_sync_state_payload_with_limits`, `decode_sync_state_payload_with_limits`, `SyncApplyRepo::apply_batch_with_limits`, or the mutable equivalent. Constructors reject values outside the hard ceiling.

Outbound collection performs a SQL row-count preflight before loading state vectors. Serialization writes through a bounded writer, so the encoded buffer cannot exceed the selected byte limit. Inbound decoding checks ciphertext length before `serde_json::from_slice`, then validates the total logical row count before `SyncApplyRepo` applies any state rows.

Recognized core state types (`mdbx-storage/state-v1` and the legacy `mdbx-cli/state-v1`) require object ID `state` and associated data equal to the exact object type. Unknown object types continue through ordinary opaque conflict handling.

## Failure Semantics

Every limit or reserved-identity violation returns `StorageError::ResourceLimit` or `StorageError::Validation`. Sync apply performs state decoding inside the existing immediate transaction, so a failure removes the inserted incoming commit, tombstone acknowledgements, branch changes, and object mutations together.

State format v1, state format v2, and the legacy CLI state format keep their existing fields and deserialization behavior. The limits constrain resource use; they do not alter the payload schema or synchronize new state fields.

## Consequences

Malformed or unexpectedly large complete-state payloads fail with a bounded diagnostic instead of consuming unbounded decoder memory. Large vaults receive an explicit capacity result and can later use a future incremental state transfer protocol without changing current object identity or history semantics.

The complete-state transfer remains a bounded snapshot rather than a streaming protocol. Clients should select a batch limit that leaves room for surrounding commit metadata when writing an offline bundle.
