# Local credential-use leases

Date: 2026-09-29

The Rust storage API supports an explicitly approved credential-use session for a trusted local broker. It allows a model proxy to continue after ordinary reveal authentication becomes stale, without modifying stored Tiga policy or the ordinary reveal/export/MCP path.

## API and trust boundary

1. After human consent for a specific object, client audience and duration, call `TigaService::authorize_credential_use` with the active, freshly authenticated connection. Never issue a lease in response to a model request.
2. Keep the opaque `CredentialUseLease` in process memory. It cannot be serialized or constructed by callers. Its audience identifies the broker's exact grant; the broker must independently check routes, upstream origin, grant expiry, call budget and revocation.
3. Use `ObjectDisclosureService::use_with_lease_and_limits` inside the trusted broker. Authorization and decryption share one immediate transaction. This is not permission to display, export, persist, or send secrets to the model client.
4. Call `TigaService::evaluate_credential_use` throughout response delivery. It checks without decrypting payloads, issuing audit rows or extending session timestamps.
5. Revocation, connection lock, keyring/session replacement or process termination ends access. Stop pending work, drop plaintext and cease forwarding promptly after denial. Requests accepted upstream may still execute or be billed.

Supply truthful device capabilities and current wall time after obtaining serialized runtime access. A monotonic deadline is enforced too. A failed recheck permanently closes the lease, including clock rollback, wrong audience, changed device, source version, collection, object type, deletion, or resolved policy. Relocking and reattaching the same session ID does not restore it: connection-owned random epochs invalidate old leases.

## Lifetime and policy

Admission requires an allowed `RevealSecret` decision and fresh authentication, even on profiles where ordinary reveal does not require freshness. Existing factor, device and disclosure constraints apply and are retained. Subsequent use additionally requires `NoPlaintextPersistence`.

Requested duration is 1–86400 seconds. The effective deadline is the earlier of the requested deadline and the original authentication time plus the resolved policy's absolute maximum lifetime. Default Multi allows at most two hours from unlock. Brokers must also enforce their lifetime and grant expiry and show the actual deadline.

Approved use has a separate lifetime from ordinary inactivity and reveal freshness. It does not update authentication/activity timestamps or change ordinary authorization results. Shorter sessions, explicit locks and stricter source policies still apply. Existing callers retain the original five-minute default unless they opt into this API.

## Storage and old-client compatibility

No schema migrations, table/column changes, payload changes, persisted policy fields, new operation enum values, FFI changes or sync wire changes. Leases create no commits or sync records and never travel with the vault. Existing `RevealSecret` (`reveal-secret`) audit records cover admission and actual broker disclosures, using existing outcomes and constraints. Admission records an authorization decision, not a completed upstream request. Old readers see ordinary disclosure audit records without a lease-specific label. Per-request routing remains in the broker's local audit.

Only Rust clients choosing this additive API use leases. Other clients can retain their engine while exchanging the same database format. Tests check unchanged SQLite schema and commit count, readable audit values and integrity, original reveal denial, absolute expiry, revocation, clock handling, and connection/source/policy invalidation. An Android application build is not required for this Rust-only addition.
