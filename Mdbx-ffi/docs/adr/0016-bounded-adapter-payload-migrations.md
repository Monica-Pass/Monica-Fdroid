# ADR-0016: Bounded Adapter Payload Migrations

- Status: Accepted
- Date: 2026-07-20
- Security amendment: ADR-0033 requires Tiga authorization before planning and during execution.

## Context

MDBX2 stores opaque ObjectRecord payloads for password, bookmark, mail, Steam `mafile`, and future domains. Each ObjectTypeId owns an independent PayloadSchemaVersion. The storage core can authenticate, version, synchronize, snapshot, and recover these bytes without knowing their application meaning.

Domain evolution still requires transforming old payloads. Placing that transformation in the core would couple MDBX to every application domain. Letting each client update rows independently would bypass capability checks, history, object versions, branch heads, synchronization metadata, and atomic rollback. Updating every object as a separate operation would also produce noisy history.

The protocol must distinguish two unrelated forms of migration. MDBX file-format and SQLite schema migration remain deterministic storage-core behavior and preserve MDBX1 compatibility. Adapter payload migration advances an ObjectTypeId contract after the vault has opened and authenticated its fields.

## Decision

MDBX provides a two-stage payload migration protocol.

`PayloadMigrationRepo::create_plan` reads one consistent SQLite snapshot. The request identifies a Collection, ObjectTypeId, exact source and target schema versions, branch, and item limit. The core verifies the CollectionProfile and registered ExtensionCapabilityIds, then returns a bounded plan containing decrypted source payloads.

Each plan binds:

1. A random plan and operation identity.
2. Collection identity and exact ObjectTypeId.
3. Exact source and target PayloadSchemaVersion.
4. Stable branch identity, name, and head commit.
5. CollectionProfile digest, including its Adapter-owned payload and declarations.
6. Every object identity, head commit, source payload digest, and source bytes.

The Adapter interprets only the source bytes and returns one target payload for every planned object. It cannot add objects, omit objects, change object identity, or choose a different target version during execution.

`PayloadMigrationRepo::execute` validates plan structure and output bounds, starts one immediate write transaction, and rechecks every binding. It then applies all target payloads through `EntryRepo::update` inside `CommitContext::run_operation`. The nested updates share one `change` commit whose structured summary marks `payload` and `payload_schema_version` for every object. The operation intent hash binds the complete plan and canonical target outputs, so a completed retry returns the original commit while altered retry content is rejected.

Plans are memory values and are never stored in the vault. They contain decrypted domain data and are intentionally invalidated by any bound state change.

## Resource Contract

One plan contains at most 256 objects. Each source or target payload is at most 1 MiB. Total source bytes and total target bytes are each at most 8 MiB. A plan may report remaining matching objects so large migrations proceed as several explicit bounded operations.

## Failure Semantics

The complete operation fails and rolls back when any object is missing, deleted, moved, retyped, changed, already migrated, or has a different payload digest. The same rule applies when the branch head or CollectionProfile changes, a required Adapter capability disappears, output coverage differs from the plan, or any resource limit is exceeded.

Synchronization and snapshot formats require no new fields. Successful execution creates ordinary Entry rows, one commit, and ordinary ObjectVersions, so existing MDBX2 synchronization and recovery behavior applies. MDBX1 physical `entries` storage remains unchanged.

## Consequences

The core remains independent from domain payload syntax while enforcing the security and history properties shared by all domains. Mail, bookmark, password, and Steam adapters can evolve separately without receiving raw SQL authority.

Long migrations produce one commit per bounded plan instead of one commit per object. A client may present the whole activity as one application task while preserving explicit transaction and memory limits.

An Adapter must remain available from plan creation through execution and must treat source payload bytes as sensitive plaintext. Clients should avoid logging, caching, or persisting plans outside protected process memory.
