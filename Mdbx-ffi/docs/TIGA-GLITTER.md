# Glitter native contract

Glitter is an additive Tiga profile. Sky, Multi and Power retain their preset
values and serialized enum positions. Glitter uses policy version 3; existing
profiles continue using version 2.

## Persistent protection

- Every unlock wrapper must combine the password and security-key material.
  Password-only, PIN and security-key-only wrappers are rejected.
- Glitter-v1 supports exactly Argon2id with 512 MiB, 10 iterations, 4 lanes,
  a 32-byte result, and 16–64 byte salts. These are also resource ceilings:
  "stronger" unbudgeted values reject before any KDF. Generic strength-floor
  comparison alone never establishes support or compliance.
- Key-file material must contain at least 32 bytes. Generate random key files
  and keep recovery copies separate from the vault. Hardware binding and
  biometrics are optional client features, never required unlock factors.
- Passwords must be nonempty after trimming and are normalized with trim + NFC.
  The native contract does not impose a 16-character or distinct-byte heuristic.
- `tiga-glitter-v1` is a critical extension covered by the vault header MAC.
  Previous readers reject unknown extensions before migration/writable open.
- Glitter key wrappers use the distinct AEAD domain
  `mdbx-vault-key-wrap:glitter-v1`. Stripping the extension and resetting header
  authentication cannot make them decrypt through a legacy unwrap path.
- Existing vaults cannot be relabeled Glitter. A new vault is required. Profile
  downgrade, weaker sparse overrides and policy exceptions cannot weaken its
  floor. The same rule applies to incoming synchronization state.

Up to eight combined wrappers can be configured. Unlock tries the configured
wrappers so independently enrolled backup credentials are usable. All wrappers
are validated before any KDF runs; one strong wrapper cannot hide a weaker one.
Extra wrappers increase worst-case unlock time. Enrollment preserves the original
session's authentication time and optional platform context instead of renewing them.
Before allocating wrapper fields, a bounded SQL count rejects more than eight
slots; KDF JSON is limited to 4096 bytes and wrapped keys to 256 bytes. Identity,
profile and timestamp strings are also bounded. These guards and the field read
share a SQLite snapshot, and all slots from the snapshot actually used for
derivation are revalidated before the first KDF.

## Client API

Direct atomic creation:

```text
create_vault_with_password_security_key(
    path, password, key_material, device_id, Glitter, device_context)
```

Portable authenticated opening:

```text
open_vault_with_password_security_key(path, password, key_material, device_id)
```

Creation accepts an ordinary `Standard` context without screen/clipboard/hardware
assertions. Context-aware opening remains available; explicitly `Unknown`
contexts fail closed. Native no-context initialize/setup/open APIs select a
Standard portable client. Password-only creation still rejects Glitter before
reserving a file; other errors remove the pending database and SQLite sidecars.
No password-only bootstrap state is exposed by the atomic FFI creation API.

Glitter retains Power's export/print prohibition, combined-factor administration,
audit protection and 10-second clipboard lifetime. It uses Multi's ordinary
session: 600-second idle timeout, 7200-second absolute lifetime, background lock,
and a separate 300-second fresh-authentication window for secret reveal/copy.
The runtime enforces the authenticated session and a nonrenewable monotonic
absolute deadline, not a blanket 60-second hardware ticket. Expired disclosure
freshness requests reauthentication without invalidating metadata browsing.
Keyring/session replacement and lock wipe local acceleration.
`read_session_remaining_secs` remains a disclosure-lifetime hint (including the
freshness window); it never authorizes disclosure or renews the session, and is
not used as the runtime's general handle-lifetime gate.
The handle retains its original Glitter identity: coherently changing the live
file to legacy mode/version/extension metadata locks it closed, rather than
adopting weaker operations. Policy resolution and compatibility getters retain
the same sticky identity checks.

Complete-payload compatibility getters are disabled for Glitter. Clients use
object/relation/label summaries and explicit policy-authorized reveal. Legacy
entry move/restore methods also return existing plaintext and are disabled;
use mutation APIs that return only identifiers or summaries. Historical commit
diff previews and Adapter migration plans are disabled until they support each
affected object's disclosure policy. Commit history and snapshot structure
metadata remain available. Other Tiga profiles retain these compatibility APIs.

## Bounded warm metadata acceleration

Only Glitter reuses title metadata in connection-local memory: up to 64 MiB /
32,768 authenticated title envelopes plus a separate 32 MiB / 256 object-summary
page budget. Eviction, lock, session/key replacement and expiry zero retained
title buffers. No payload, disclosure result or authorization decision is cached.

Summary-page keys include the complete validated query, collection, type, page
size and cursor. SQLite `data_version` detects commits by other connections;
`total_changes()` invalidates local writes even after rollback. A session/key
generation stamp also rejects insertion of a page read under an older session.
Misses are cached
only when both counters remain unchanged across the read. Transactions bypass
page reuse, preserving their own SQL snapshot. Native runtime authorization is
still checked on every call, and cached pages cannot extend the original
monotonic authentication deadline. Diagnostics aggregate both caches' counters,
retained bytes, entry counts and budgets (at most 96 MiB combined).

This optimization targets repeated paged title browsing. It does not promise
faster cold unlock or faster workloads with constant writes/cache invalidation.

## Scope and validation

This contract does not keep decrypted vault keys inside a TEE, prove that the
running application is uncompromised, enforce an external rollback anchor, or
guarantee recovery after either required credential is lost. Clients must explain
key-file backup and memory costs. Cold unlock is intentionally expensive; runtime performance
must be measured independently of KDF cost.

Run focused tests with:

```text
cargo test --release -p mdbx-core -p mdbx-storage -p mdbx-ffi glitter -- --test-threads=1
```

The suite exercises real 512 MiB password derivation, file creation, writes,
reopen under another device ID, portable backup copies, backup credentials,
incorrect/missing factors, every weak wrapper field, sync downgrade attempts,
and a coherent header-reset attack. Synthetic lifecycle tests separately prove
post-60-second browsing, normal idle/max expiry, separate disclosure freshness,
lock/cache invalidation and session-generation cache binding. Optional biometric
features need their own platform tests; no hardware guarantee is inferred here.
