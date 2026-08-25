# ADR-0043: Monotonic Device-Local Heads

- Status: Accepted
- Date: 2026-07-26

## Context

`device_heads` is the current position of each device in the commit history.
Its order is device-global: every commit authored by one device receives a
unique `local_seq`, even when that device writes on different branches.

Two synchronization paths previously used different rules. Commit ingestion
unconditionally replaced the stored head, so a delayed lower-sequence commit
could move it backward. State-delta ingestion used commit ancestry, so a higher
sequence sibling authored on another branch could fail to advance it. The
delta path also accepted a row whose claimed device differed from the author of
the referenced commit.

These states weaken device diagnostics and make the active-device registry
depend on delivery order instead of authenticated commit identity.

## Decision

MDBX defines one device-head merge for commit ingestion and state-delta
ingestion.

1. The referenced commit must exist and its `commits.device_id` must equal the
   device-head `device_id`. A currently stored head is checked by the same rule.
2. Ordering uses `commits.local_seq`, not DAG ancestry. A device may author
   commits on different branches while retaining one global sequence.
3. A higher sequence advances the head. A lower sequence remains accepted
   history but cannot move the head backward. Reapplying the same commit is
   idempotent. Different commit IDs at one device sequence are invalid.
4. New commit insertion checks sequence reuse before its first INSERT and
   returns a storage validation error. The existing
   `uniq_commits_device_seq` index remains the relational backstop.
5. `last_seen_at` keeps the later known value. `revoked` is merged with logical
   OR, so synchronization cannot reactivate a revoked device.
6. A missing commit remains a constraint violation. A wrong-device reference,
   negative sequence, or sequence identity conflict is a validation failure.

The recovery health check reports a dangling head, a head whose commit belongs
to another device, and a head whose sequence is below a later accepted commit
from the same device. Health reporting is read-only; it does not silently
rewrite historical state.

This decision replaces the ancestry-based device-head sentence in ADR-0020.
Branch heads continue to use commit ancestry because they represent a DAG
reference rather than a device-global author sequence.

## Compatibility

No table, column, index, migration, format generation, synchronization DTO,
bundle version, or capability bit is added. MDBX1 already stores `device_id`,
`local_seq`, `head_commit_id`, `last_seen_at`, and `revoked`, and already has the
unique device-sequence index.

Delayed MDBX1 commits remain importable. They are stored and forwarded normally
while the receiver preserves its newer device head. Existing exact commit
replay remains governed by ADR-0041. A valid old vault with a healthy device
head needs no migration or rewrite.

## Consequences

Device-head state converges independently of commit delivery order and branch
shape. Commit and delta synchronization cannot disagree about the current
device position, and revocation remains monotonic across both paths.

An old or externally modified vault with a wrong or regressed head now receives
an explicit health issue. A peer that reuses one device sequence for a second
commit receives a stable validation error instead of a raw SQLite uniqueness
failure.

## Verification

Tests cover a higher-sequence commit followed by a delayed lower-sequence
sibling, a higher-sequence delta across branches, wrong-device delta ownership,
local revocation preservation, explicit sequence-reuse rejection, and health
diagnostics for wrong-device and regressed heads.
