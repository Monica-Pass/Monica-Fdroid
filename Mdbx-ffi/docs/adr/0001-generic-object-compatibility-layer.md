# ADR-0001: Generic Object Compatibility Layer

- Status: Accepted
- Date: 2026-07-20

## Context

MDBX began with password-oriented names: `projects`, `entries`, and a closed `EntryType` enum. MDBX2 is defined as an advanced encrypted database core rather than a password schema. The physical schema already has useful generic properties: stable collection ownership, authenticated encrypted payload bytes, payload schema versions, attachments, commit history, synchronization, conflicts, snapshots, recovery, audit, and key rotation.

Renaming or replacing the physical tables would create a second source of truth and complicate MDBX1 upgrades. Keeping the closed enum also blocks bookmark, mail, Steam `mafile`, and future ObjectRecords. The current read path additionally converts unknown stored types to `Login`, which destroys domain meaning.

## Decision

MDBX2 adds a Generic Object Module whose Interface uses `Collection`, `ObjectRecord`, `ObjectTypeId`, `ObjectRelation`, and `ObjectLabel` from `CONTEXT.md`.

The Implementation continues to use the MDBX1 `projects` and `entries` tables as compatibility storage. Physical names are treated as an internal adapter detail. Existing EntryRepo and UniFFI methods remain as the Legacy Password Adapter.

The mandatory core owns encryption at rest, integrity context, key epochs, commits, synchronization, conflicts, recovery, audit, migration, and generic object persistence. Password, bookmark, mail, Steam, import, export, presentation, and derived indexing behavior belongs to optional adapters or capabilities.

ObjectTypeId becomes extensible. Legacy short identifiers remain stable; new extensions use namespaced identifiers. Unknown valid identifiers round-trip exactly and remain opaque.

Relations and object labels are additive first-class tables with their own stable IDs, causal metadata, tombstones, snapshot state, and synchronization projection. Domain-specific query indexes remain rebuildable adapter data and do not replace encrypted ObjectRecords.

Optional importers, exporters, benchmarks, and domain indexes use Cargo capability features. Core reading, MDBX1 migration, encryption, commit history, and sync compatibility remain mandatory.

## Module Depth

The Generic Object Module owns encryption, validation, commits, heads, object versions, tombstones, snapshots, and sync projection behind one Interface. The Legacy Password Adapter and future domain adapters call this Module instead of duplicating those invariants.

The deletion test supports this design: deleting the Generic Object Module would spread storage invariants across every domain adapter. Deleting a thin domain adapter removes only that domain's interpretation and optional index behavior.

## Consequences

Existing MDBX1 files and APIs remain usable. New domains avoid schema forks and password-type fallbacks. Physical table names remain visible to migrations and diagnostics, while application code gains clearer domain language.

The compatibility adapter adds a semantic layer and requires careful tests proving that legacy and generic methods see the same records. Extension-specific indexes require explicit rebuild and version rules. Builds that omit a critical extension must reject unsafe writes instead of silently degrading behavior.
