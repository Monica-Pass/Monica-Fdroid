# ADR-0036: Bounded Steam mafile Adapter

- Status: Accepted
- Date: 2026-07-25

## Context

MDBX2 is intended to hold password records, bookmarks, mail, Steam mafiles,
and future application objects through one encrypted generic object model.
The ExtensionProfile registry now provides a process-local seam, but a
declaration without a concrete domain Adapter does not prove that the seam is
usable.

Steam mafiles are attacker-controlled JSON documents containing authentication
secrets and evolving vendor fields. A direct client-side JSON parse would
leave each client to choose different resource limits, duplicate-key
semantics, canonicalization, and object identity rules. Putting those fields
in the Generic Object Module would make the storage core depend on one domain
and would prevent a safe build from removing Steam support.

## Decision

Add the independently removable workspace crate
`crates/mdbx-adapter-steam`, with no default features and no reverse
dependency from storage, sync, CLI, or FFI. The crate has one small external
interface:

- `extension_profile()` declares ExtensionId `com.monica.steam`,
  CollectionTypeId `com.monica.steam`, ObjectTypeId
  `com.monica.steam.mafile`, capability `com.monica.steam.store`, and
  namespaced mafile import/export feature IDs.
- `SteamMaFile::parse` and `parse_with_limits` accept a root JSON object
  and retain the complete value, including unknown fields.
- `canonical_json` emits deterministic object-key order. Duplicate keys are
  rejected rather than silently using last-wins semantics.
- `derive_stable_object_id` and the corresponding document method return a
  lowercase SHA-256 digest over a domain tag and length-framed canonical
  SteamID/serial components. The digest is an opaque object ID, not a secret.
- `derive_stable_object_uuid` and the corresponding document methods project
  the first 128 digest bits into an RFC-variant custom version-8 UUID for
  generic MDBX write commands. They introduce neither randomness nor another
  identity domain.
- The document may contain a root SteamID. A client can provide an
  authenticated account SteamID for variants that omit it; a present,
  conflicting document value is rejected.

The default parser contract is deliberately conservative: 1 MiB input, depth
32, 512 aggregate object fields, 512 items per array, 8,192 aggregate nodes,
64 KiB per string/key, and 1 MiB aggregate string/key bytes. Callers may lower
limits but not exceed hard ceilings of 8 MiB, 64, 4,096, 4,096, 65,536, 1 MiB,
and 8 MiB respectively. Input bytes are checked before deserialization; the
visitor enforces the remaining limits while building the value.

The Adapter's Debug implementation reports only structural counts and
presence flags. Parse and identity errors use static categories and never
include source JSON, field values, tokens, or serials. The Adapter does not
contact Steam, implement login or token refresh, access Android APIs, write
SQLite, or authorize Tiga operations.

## Consequences

A client can parse and canonicalize an untrusted mafile once, then submit the
returned bytes as the opaque payload of one generic
`com.monica.steam.mafile` ObjectRecord. Unknown fields survive an older
Adapter round-trip, so a newer mafile producer does not force immediate
client upgrades. Generic commits, encryption, snapshots, tombstones, and
synchronization remain the storage core's responsibility; importing multiple
mafiles can use one bounded CommitOperation.

Removing the crate removes Steam interpretation and optional import/export
behavior only. Existing ciphertext, ObjectTypeIds, payload schema versions,
history, and sync state remain readable and preservable through generic
opaque paths. The crate is intentionally not wired into the mandatory
CapabilitySet or FFI in this first step; clients that ship it register the
profile and activate its write capability explicitly.

The Adapter retains secrets in its in-memory JSON value until the caller
drops it. Callers must keep that value in protected process memory, must not
log canonical bytes, and must use the generic authenticated encryption path
before persistence. Large binary or archival content does not belong in this
whole-document JSON interface; it uses MDBX attachments or encrypted blob
providers.

## Verification

The crate tests cover profile ownership, unknown-field canonical round-trip,
duplicate keys, every parser resource dimension, hard-ceiling validation,
non-disclosing Debug/error output, deterministic identity, alias conflicts,
and account-ID mismatch. The crate passes workspace formatting, clippy with
warnings denied, normal tests, and a `--no-default-features` check.
