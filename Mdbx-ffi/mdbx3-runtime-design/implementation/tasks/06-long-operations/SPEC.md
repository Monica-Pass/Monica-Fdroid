# Long operations and structured diagnostics

## Scope

Long-running FFI workflows must remain bounded and recoverable while keeping
the MDBX2 ABI and DTO shapes unchanged.

1. Export and inspection operations use the runtime read boundary.
2. Apply, manual restore, and benchmark writes use the runtime write boundary.
3. Segment progress advances only after authenticated durable apply.
4. A cancelled or failed operation leaves its source/target publication and
   database transaction unchanged.
5. Diagnostics return typed aggregate counts and never disclose ciphertext or
   plaintext payloads.

## Compatibility

No new required client call and no DTO change is introduced. Existing clients
continue to call the same UniFFI methods and receive the same error variants;
the runtime boundary only changes internal scheduling and invalidation.

## Acceptance

- FFI regression suite passes with explicit read/write routing.
- Segment tamper, stale resume, cancellation, and replay tests remain green.
- Workspace clippy and ABI verifier remain green.
