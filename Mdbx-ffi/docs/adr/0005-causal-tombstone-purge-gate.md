# ADR-0005: Causal Tombstone Purge Gate

- Status: Accepted
- Date: 2026-07-20

## Context

MDBX1 exposed `TombstoneRepo::purge`, which deleted a marker immediately. MDBX2 supports multiple devices and generic encrypted objects, so deleting a marker before every active replica has observed the deletion can allow an old object state to return. A retention date alone cannot prove replica observation.

`device_heads` records the latest commit attributed to each device. It is useful as an active-device registry, but it is not an acknowledgement record: a device can receive a deletion without authoring a later commit. Device revocation is also security state and must remain monotonic when later commits from that device arrive.

## Decision

Schema 8 adds `tombstones.delete_commit_id` and `tombstone_acknowledgements`.

The delete commit is a causal proof whose object version certifies the deleted state. New local deletion paths store the real commit vector clock and commit ID. MDBX1 migration backfills the proof from the head commit of an object that is still deleted. A migrated tombstone also records an acknowledgement for its deleting device.

Each acknowledgement identifies the tombstone, device, observed commit, and
acknowledgement time. Local deletion acknowledges the deleting device.
Receiving a per-commit tombstone acknowledges both the deleting device and the
receiving device. Complete synchronization state carries acknowledgement rows
as an optional additive field; old state payloads remain valid.

Runtime acknowledgement writes follow ADR-0044. When `delete_commit_id` is
present, the observed commit must equal or causally contain it. Descendant
evidence advances an ancestor, an ancestor cannot replace a descendant,
concurrent valid evidence uses a deterministic canonical tie-break, and
acknowledgement time never decreases. These rules make the row itself a valid
input to the cleanup gate instead of relying on the gate to compensate for an
unvalidated upsert.

Eligibility evaluation checks all of the following:

1. A valid retention time exists and has elapsed.
2. The target row still exists and remains deleted.
3. No unresolved conflict exists for the same physical object.
4. The delete commit exists.
5. Every non-revoked device has an acknowledgement whose observed commit causally contains the delete commit.

The MDBX1 `purge` symbol remains available for source compatibility and returns a constraint error. MDBX2 physical deletion requires a future authorized execution API that rechecks eligibility in one transaction, records a commit and TIGA audit event, deletes dependent content, and preserves a permanent deletion proof.

Synchronization may update the head and last-seen time of a revoked device, but it never clears the local revoked flag.

## Consequences

Retention policy and causal confirmation are separate, inspectable facts. Devices that have not acknowledged a deletion prevent physical cleanup. Revoked devices cease blocking cleanup without regaining authority through synchronization.

Schema additions remain compatible with MDBX1 physical tables and old serialized payloads. Actual content removal remains disabled until MDBX2 has a permanent purge receipt that prevents old replicas from reintroducing a removed object.
