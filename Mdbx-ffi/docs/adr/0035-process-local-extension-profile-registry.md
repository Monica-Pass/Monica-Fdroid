# ADR-0035: Process-Local Extension Profile Registry

- Status: Accepted
- Date: 2026-07-25

## Context

MDBX2 is a generic encrypted database whose password, mail, bookmark, and
Steam behavior belongs in optional domain Adapters. A loaded Adapter needs a
bounded way to declare the Collection types, custom Object types, relation
kinds, capabilities, optional indexes, import/export paths, and presentation
hints that it understands.

The build capability manifest only reports code compiled into the current
binary. A persisted `CollectionProfile` describes one Collection, while the
existing process-local capability set only gates writes. Neither surface can
answer which loaded Adapter owns a namespaced semantic identifier. Persisting
the loaded-Adapter inventory in a vault, snapshot, or synchronization message
would also make an optional client component part of the durable format and
would weaken unknown-data compatibility when that component is absent.

## Decision

`mdbx-core` defines a canonical, versioned `ExtensionProfile`. Every declared
identifier must belong to the extension's namespace. Extension Adapters cannot
claim MDBX1 legacy ObjectTypeIds. Optional index, import/export Adapter, and
presentation-hint identifiers use `ExtensionFeatureId`, which is deliberately
separate from the write-gating `ExtensionCapabilityId`. Descriptor collections
are bounded, sorted, and deduplicated.

Each `VaultConnection` owns an in-memory `ExtensionRegistry` containing at most
256 profiles. Exact duplicate registration is idempotent. A changed profile for
an existing extension, duplicate ownership, malformed input, or a resource
limit leaves the previous registry unchanged. Bulk replacement is atomic, and
unregistration rebuilds all ownership indexes. Opening or reopening a vault
starts with an empty registry; clients register the Adapters loaded into that
process through the native or UniFFI facade.

Profile registration and capability activation are independent semantic
planes. Registration describes what an Adapter claims to understand;
`set_extension_capabilities` declares which write-gating capabilities are
currently executable. Registration does not activate a capability, accept a
critical extension, negotiate synchronization, expose raw SQL, grant key
access, or authorize a Tiga operation.

When a registered descriptor owns a `CollectionTypeId`, a user-visible
`CollectionProfile` write must declare only ObjectTypeIds and required
capabilities contained in that descriptor. Later user mutations revalidate the
stored contract and the active capability set. A descriptor that is absent
does not erase, rewrite, or reinterpret stored data. Opaque reads,
synchronization, backup, restore, and recovery retain their existing behavior,
including preservation of unknown extension data.

## Compatibility

The registry adds no table, column, schema migration, snapshot field,
synchronization field, or critical extension. It does not change MDBX1 physical
representations or existing method signatures. Older and generated clients that
never register a profile retain their previous behavior, and removing an
Adapter from a later build does not delete its Collections or Objects.

All Adapter mutations continue through repository APIs and, where required,
the `OperationCoordinator` and Tiga policy boundary. The registry may tighten
validation for a loaded Adapter's user writes, but it cannot broaden storage or
security authority.

## Consequences

Mail, bookmark, Steam, and future domain packages can publish one discoverable
semantic contract without coupling their implementation to the MDBX schema.
Conflicting ownership fails before a partial registration becomes visible, and
feature-trimmed builds can omit an Adapter while retaining opaque data safety.

Clients must register profiles again after every open or process restart and
must activate capabilities separately. The registry is not a durable catalog
or a peer-negotiation protocol; either responsibility would require a distinct
versioned design.
