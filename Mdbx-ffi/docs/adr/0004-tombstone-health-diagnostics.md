# ADR-0004: Tombstone Health Diagnostics

- Status: Accepted
- Date: 2026-07-20

## Context

MDBX2 uses typed tombstones to preserve deletion across synchronization. A row can become inconsistent with its marker through damaged storage, incomplete external tooling, premature marker purge, or a defective future migration. SQLite integrity and foreign-key checks cannot detect this semantic inconsistency. The existing health report also had no native-client interface.

An unresolved delete-versus-modify conflict intentionally permits one temporary state: the local row may remain active while the incoming typed tombstone is retained until resolution. Treating every active row with a marker as damaged would make valid conflicted vaults unhealthy.

## Decision

The recovery verifier checks six synchronized object families: Project, Entry, Attachment, ObjectRelation, ObjectLabel, and ObjectLabelAssignment.

For each exact object type and identity:

1. A deleted row requires one current typed tombstone.
2. An active row requires no typed tombstone, except while an unresolved conflict for the same object includes the `deleted` field.
3. More than one typed tombstone is an error.

Unknown tombstone types remain untouched for extension compatibility. Branch tombstones remain outside row-state validation because branches do not expose a deleted column. Tombstones whose object row is absent may represent retained deletion history and are preserved.

`TombstoneTargetType` is a closed physical-family selector, distinct from extensible business `ObjectTypeId` values stored in Entry rows. TombstoneRepo parses every stored family explicitly. Unknown values return a conversion error and never fall back to Project. A future physical family therefore requires a critical storage extension and reader implementation before typed access.

All violations use the `tombstones` category and Error severity, making the overall report unhealthy. UniFFI exposes additive health result, issue, and severity types through `MdbxVault::health_check`; the CLI continues to render the same storage report.

## Consequences

Operators and native applications can distinguish physical database integrity from semantic deletion-state integrity. Missing markers, stale markers, duplicate markers, and unsupported physical families become visible before they cause resurrection, misclassification, or inconsistent synchronization. Valid unresolved deletion conflicts continue to pass health checks. The diagnostic remains read-only and therefore cannot silently rewrite history or choose a conflict result.
