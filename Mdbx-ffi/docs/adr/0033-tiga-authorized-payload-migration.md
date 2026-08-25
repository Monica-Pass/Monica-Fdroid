# ADR-0033: Tiga-Authorized Adapter Payload Migration

- Status: Accepted
- Date: 2026-07-25

## Context

ADR-0016 introduced a bounded two-stage protocol for migrating opaque Adapter payloads. The plan is intentionally transient, but it contains decrypted password, bookmark, mail, Steam `mafile`, or future domain bytes. Plan execution also updates many ObjectRecords and must produce one user-level commit.

Capability registration alone is not authorization. Returning a migration plan without a Tiga decision exposes plaintext outside the normal policy boundary, while evaluating policy separately from the write transaction allows policy, object, branch, or Profile state to change between authorization and mutation. Per-object commits would also recreate the noisy history that `CommitOperation` is meant to prevent.

## Decision

MDBX defines `TigaOperation::MigratePayload` as an administration-class operation. It follows the resolved profile's fresh-authentication, factor, device-assurance, and audit rules. `SecurityChanges`, `SensitiveOperations`, and `AllDecisions` audit profiles include it.

Plan creation authorizes the owning Collection's Project scope before source payload rows are loaded or decrypted. The random `plan_id` is generated before authorization and becomes the audit operation ID. A successful plan audit has no commit reference because planning does not mutate the vault. Denials are audited without returning source bytes.

Execution reauthorizes the same Project scope. Tiga policy evaluation, plan-binding checks, ObjectRecord updates, one idempotent `CommitOperation`, commit-correlated security audit, and sync-delta materialization share one immediate SQLite transaction. `CommitContext::run_operation_in_transaction` lets Tiga own that outer transaction without creating a nested commit boundary. Any denial, stale binding, missing capability, malformed output, or write failure rolls back object and commit changes.

An exact retry uses the plan ID as the operation ID and returns the original commit. It does not rerun object mutations or create a second successful audit event. Reusing the plan ID with different target output is rejected by the operation intent hash.

Existing Rust and UniFFI migration method names remain available. They use the connection's active session and a conservative Standard device context. Additive `*_with_device_context` UniFFI methods let clients report real device assurance and platform capabilities.

Migration plans remain memory-only. Decrypted source bytes and Adapter-produced target bytes must never be stored in audit rows, commit metadata, synchronization state, logs, files, or persistent caches.

## Compatibility

This decision adds no table, column, database-format, snapshot, or synchronization-wire field. The new UniFFI operation variant is appended after existing variants so previously generated enum ordinals do not shift. MDBX1 physical `projects` and `entries` storage remains unchanged, and MDBX2 continues to read and upgrade MDBX1 databases through the storage-core migrator. The change is an MDBX2 API security requirement: payload migration now requires an active authorized session, while ordinary MDBX1 compatibility reads remain unchanged.

## Consequences

Adapters can evolve independently without receiving raw SQL or key authority, and migration history remains one commit per bounded plan rather than one commit per object.

Clients must be prepared for fresh-authentication, additional-factor, or device-assurance decisions before planning and again before execution. Long-running migrations should therefore use small batches, keep plans only in protected memory, and create a fresh plan after any stale-binding failure.

The transaction-aware operation seam is reusable by other Tiga-authorized multi-object mutations, but it remains an internal storage mechanism rather than a new client-managed transaction API.
