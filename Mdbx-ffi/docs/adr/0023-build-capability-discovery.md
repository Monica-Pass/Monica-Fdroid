# ADR-0023: Build Capability Discovery

## Status

Accepted

## Context

MDBX2 can be compiled as a full development binary or as a trimmed client
library. JSON KDBX interoperability, the derived search index, benchmarks, the
filesystem Blob Provider, and zstd are optional Cargo features. Mandatory
format, encryption, history, recovery, and synchronization invariants remain
present in every supported build.

Before this decision, Rust code could inspect `CapabilitySet`, but generated
clients and command-line tooling had no stable way to distinguish an omitted
module from a damaged vault, unsupported data, or a permission failure. API
probing is ambiguous and becomes harder as mail, bookmark, Steam, and other
Adapters are added.

## Decision

MDBX exposes the versioned `mdbx-build-capabilities-v1` manifest without
opening a vault. The manifest contains the engine version and separate storage
and synchronization inventories. Each inventory reports enabled capability IDs
and known optional IDs omitted from that build in canonical order.

Storage IDs are namespaced under `mdbx.storage.*`. KDBX features are named
`kdbx-json-import` and `kdbx-json-export` because the current module handles an
interoperability JSON representation, not binary `.kdbx` files. Synchronization
uses the existing protocol capability IDs; `bundle-zstd-v1` is enabled only
when the codec is compiled in.

Rust callers use `CapabilitySet::build_manifest`, generated clients use the
top-level UniFFI `mdbx_build_capability_manifest`, and tooling uses
`mdbx capabilities` or `mdbx capabilities --json`. None of these paths opens,
creates, migrates, or unlocks a vault.

Four capability planes remain distinct:

1. The build manifest describes code compiled into the current binary.
2. `set_extension_capabilities` declares domain Adapter code active in one
   process and gates profiled Collection mutations.
3. Vault critical extensions declare semantics required to write a database
   safely.
4. Hello/HelloAck capabilities negotiate one synchronization session.

No plane grants key access or substitutes for another.

## Compatibility

The manifest is read-only process metadata. It changes no SQLite schema, vault
bytes, migration rule, protocol message, or existing public method. Older
clients continue to operate without querying it. Future incompatible inventory
semantics require a new manifest profile and additive API rather than silently
reinterpreting v1 fields.

## Consequences

Clients can hide unavailable optional workflows before a vault is selected and
can produce precise diagnostics for trimmed deployments. The manifest does not
prove that an external Provider is reachable, that an Adapter is trustworthy,
or that a peer supports the same capability; those checks remain at their
existing boundaries.
