# ADR-0002: Additive Capability Profiles

- Status: Accepted
- Date: 2026-07-20

## Context

MDBX2 serves password managers, bookmarks, mail, Steam `mafile`, and future domains. Deployments have different size and attack-surface requirements. A password client may need KDBX interoperability and legacy search, while a mail engine may only need the encrypted database core and its own external adapter.

Compile-time trimming must preserve MDBX1 upgrades and the security meaning of an MDBX file. Removing a feature cannot create a weaker reader, an alternate history implementation, or a second synchronization format.

## Decision

Every supported build compiles the mandatory database core. The mandatory core contains MDBX1 migration, authenticated encryption, TIGA policy and audit, key epochs, generic objects and metadata, versioned Collection Profiles, commits, conflicts, snapshots, recovery, backup, and synchronization.

`mdbx-storage` defines additive Cargo features for `kdbx-import`, `kdbx-export`, `derived-search-index`, and `benchmarks`. Default features enable all current behavior. The explicit core profile uses `--no-default-features --features core`. The `benchmarks` feature enables `derived-search-index` because the benchmark suite measures search behavior.

`mdbx-cli` forwards the same optional capabilities. Commands backed by an excluded capability are omitted at compile time. `mdbx-ffi` depends on the mandatory core profile because its exported interface currently contains no KDBX, benchmark, or derived-search methods.

`CapabilitySet::current()` provides runtime introspection for compiled capabilities. Domain records remain opaque and durable when their interpreting adapter is absent. Their exact CollectionTypeIds, Collection Profiles, ObjectTypeIds, payload schema versions, ciphertext, relationships, labels, history, and synchronization state remain available to the core.

## Safety Rules

1. Cargo features are additive and never disable encryption, TIGA, migration, history, recovery, or synchronization.
2. The core profile reads and upgrades the same MDBX1 and MDBX2 files as the full profile.
3. Unknown namespaced ObjectTypeIds are preserved exactly.
4. Derived indexes can be removed and rebuilt; they never contain the only copy of user data.
5. MDBX1 `project_tags` remain in snapshot and synchronization state even when the legacy search API is excluded.
6. Unknown critical storage extensions still reject writable open before any migration or mutation.

## Consequences

Applications can ship smaller domain-specific builds while sharing one encrypted file format and one compatibility implementation. Default consumers retain the previous API and CLI behavior. Build systems must select optional adapters explicitly when default features are disabled, and callers can inspect the resulting capability set before exposing adapter-specific behavior.
