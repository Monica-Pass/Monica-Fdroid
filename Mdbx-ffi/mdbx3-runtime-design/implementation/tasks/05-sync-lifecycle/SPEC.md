# Incremental bootstrap and history lifecycle

## Scope

- Keep MDBX-2 files, schema 17, and the existing sync wire contract.
- Allow a paired empty inventory checkpoint to identify the first bounded
  bootstrap segment.
- Require a paired non-empty checkpoint for every resumed segment.
- Advance resume state only after authenticated bytes are durably applied.
- Keep complete-state transfer as the automatic fallback for MDBX2 peers.

## Invariants

1. One empty token without the other is invalid.
2. A bootstrap segment has index zero and no previous-segment digest.
3. A resumed segment must match transfer ID, segment index, previous digest,
   vault ID, and the saved base checkpoint.
4. Missing commit parents abort the whole segment transaction.
5. History deletion and compaction are not part of bootstrap or checkpoint
   generation; they require a later independently audited capability.

## Acceptance

- Storage can export and apply an empty-checkpoint bootstrap in bounded pages.
- Existing incremental, replay, stale-resume, wrong-vault, Blob, and complete
  fallback tests remain green.
- Existing FFI DTOs represent bootstrap with two empty strings without adding
  ABI fields.
