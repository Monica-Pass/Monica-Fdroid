# ADR-0045: Bounded Binary KDBX Adapter

- Status: Accepted
- Date: 2026-07-23

## Context

MDBX previously exposed a `KdbxEntry` JSON projection but could not read or
write encrypted `.kdbx` files. Binary interoperability belongs to an optional
domain Adapter: making a parser mandatory would enlarge core and FFI builds,
while changing the existing JSON feature IDs would break build discovery for
current clients.

KDBX input is attacker-controlled encrypted data. File size alone does not
control password-derivation cost, decoded field or attachment volume, nesting,
or destination publication behavior. Import also has to finish validation
before repository mutations begin. A projected document may expand into many
projects, entries, attachments, and content writes; exposing every internal
mutation as a separate commit would make one user import noisy and would allow
partial state after a later repository failure.

## Decision

MDBX uses `keepass 0.13.17` behind independent `kdbx-binary-import` and
`kdbx-binary-export` features. Import accepts KDBX3/KDBX4 and export writes
KDBX4. The existing JSON features and commands remain unchanged.

Import reads at most the configured encrypted-byte limit plus one byte. It
parses the public header first and rejects excessive KDBX3 AES rounds or KDBX4
AES/Argon2 parameters before invoking the full parser. The decrypted database
is projected into `KdbxEntry` only while enforcing entry, field, attachment,
group-depth, per-item, and aggregate-byte limits. Repository import starts only
after the complete projection succeeds.

Repository application exposes an additive atomic path. `import_entries_atomic`
prepares every payload and count before mutation, derives a domain-separated
length-framed SHA-256 digest over the complete source projection, and supplies
that digest as the immutable operation intent. All repository writes execute in
one `CommitContext::run_operation` with one Commit2 commit. A repository error
rolls back every object, attachment chunk, history row, head update, and sync
delta. Operation retries are idempotent for identical input and reject changed
input. Binary and JSON CLI imports use this path with a fresh operation UUID
created after parsing or decryption.

The original `import_entries` method remains unchanged as a best-effort
compatibility API. It continues to attempt entries independently and convert
some later failures into warnings. Existing callers retain that behavior, but
the method is not an atomicity boundary.

Export validates the same projection constraints and writes KDBX4 with
Argon2id using 64 MiB, three iterations, and two lanes. CLI secrets come from a
hidden prompt or bounded standard input and are held in zeroizing strings.
Encrypted output is synchronized in a temporary sibling and published with a
no-clobber persist operation.

Build discovery reports the binary features separately from the JSON bridge.
The Adapter does not claim lossless preservation of KeePass history, autotype,
custom icons, recycle-bin state, plugin fields, or passkey plugin structures.

## Consequences

Full builds gain real encrypted KDBX interoperability while core and custom
builds can omit either direction. Existing MDBX formats, migrations, sync
payloads, APIs, and JSON commands remain compatible. New CLI imports create one
clear history commit per user action instead of one commit per internal object,
while legacy API callers keep the established partial-import behavior.

The `keepass` parser currently performs gzip decompression internally before
MDBX can check projected plaintext totals. The encrypted input and returned
projection are bounded, but peak decompression memory is not independently
bounded. Services processing untrusted files under a strict memory ceiling
must isolate the parser process or replace this dependency path with bounded
streaming decompression. This limitation remains explicit in the compatibility
specification and must not be represented as complete decompression-bomb
resistance.
