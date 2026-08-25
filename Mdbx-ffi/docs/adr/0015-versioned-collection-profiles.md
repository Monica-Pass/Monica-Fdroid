# ADR-0015: Versioned Collection Profiles

- Status: Accepted
- Date: 2026-07-20

## Context

MDBX2 already preserves extensible ObjectTypeIds, but a Collection itself has no stable domain identity. A mail account, bookmark library, Steam account group, and legacy password project therefore share the same physical `projects` row without a durable declaration of their accepted objects, adapter configuration, or write requirements.

Object type identity alone is insufficient. One mail Collection may accept messages, contacts, drafts, and folders, while its encrypted account configuration belongs to the Collection rather than any single ObjectRecord. Storing this information only in a client configuration file would make backup, synchronization, snapshot recovery, and cross-client inspection incomplete.

Replacing `projects` or adding a second Collection identity would break MDBX1 compatibility and duplicate ownership, commit, clock, deletion, and conflict behavior.

## Decision

Schema 11 adds `collection_profiles`, keyed one-to-one by the existing `projects.project_id`. The project row remains the physical Collection identity and lifecycle owner.

A CollectionProfile contains:

1. A stable namespaced CollectionTypeId.
2. An authenticated encrypted adapter payload and independent payload schema version.
3. A bounded canonical list of allowed ObjectTypeIds.
4. A bounded canonical list of required ExtensionCapabilityIds.
5. Creation and update device metadata.

Profile existence is monotonic and CollectionTypeId is immutable. Updating payload or declarations uses one Project-scoped CommitOperation, advances the Project object clock and head, and records a ProjectRow ObjectVersion containing the profile projection.

VaultConnection holds a process-local set of ExtensionCapabilityIds supplied by installed adapters. User-visible Project, ObjectRecord, ObjectRelation, ObjectLabel, ObjectLabelAssignment, Attachment, and conflict-resolution mutations require the capabilities declared by the owning CollectionProfile. ObjectRecord creation and movement additionally require an allowed ObjectTypeId. Capability registration grants no key access and is never persisted as authority.

Synchronization, snapshot restore, backup, health inspection, and opaque reads do not require the domain adapter. These operations preserve authenticated ciphertext and exact identifiers. Sync state format v2 includes the optional profile projection. New readers continue to accept v1. A missing profile in an old ProjectRow means no assertion and preserves any local profile; profile removal is not inferred.

The sync object type remains `mdbx-storage/state-v1` so an older reader recognizes the payload and rejects the unsupported v2 format. Emitting an unknown object type would allow the older fast-forward path to skip the state silently.

## Module Depth

CollectionProfileRepo belongs inside the Generic Object Module. Its Interface combines validation, canonicalization, encryption, capability enforcement, commit creation, Project head advancement, ObjectVersion recording, synchronization projection, snapshot recovery, and health verification.

Deleting this Module would spread the same invariants across every mail, bookmark, password, and Steam Adapter. Keeping it provides leverage to all domains and locality for compatibility and security changes.

## Consequences

MDBX1 Collections remain profileless and keep their existing interfaces. New domain Collections become self-describing without changing the `projects` table or requiring their adapters for safe preservation.

Clients must register only capabilities supplied by code actually present in the process. An absent adapter can still copy, synchronize, back up, inspect, and recover opaque data, but user-visible mutations fail with the missing capability identifiers.

CollectionTypeId cannot be repurposed after creation. A domain conversion requires a new Collection and an explicit tracked object transfer, preventing existing ciphertext from being reinterpreted under a different contract.
