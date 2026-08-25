# ADR-0018: Bounded Generic Write Operations

- Status: Accepted
- Date: 2026-07-20

## Context

MDBX2 maps one user intent to one `CommitOperation`, which prevents batch imports, moves, and editor saves from creating a commit per internal mutation. The UniFFI operation facade already provided atomic rollback and intent-based retry identity, but accepted an unbounded command vector and unbounded JSON payload strings. It also validated command object types through the MDBX1-only entry parser, so mail, bookmark, Steam, and other namespaced objects could not use the batch boundary.

## Decision

Existing `execute_write_operation` and `execute_write_operation_on_branch` methods keep their signatures and apply this default contract:

| Resource | Default | Hard ceiling |
|---|---:|---:|
| Commands | 256 | 4,096 |
| One JSON payload | 1 MiB | 16 MiB |
| All JSON payloads | 8 MiB | 64 MiB |
| Serialized operation intent | 16 MiB | 128 MiB |

`default_write_operation_limits` exposes the default profile. Additive `*_with_limits` methods accept an explicit profile only within the hard ceilings. The per-command payload limit cannot exceed the total payload limit.

Command count and payload bytes are checked before parsing payload JSON. The complete command list is serialized directly into a bounded SHA-256 writer, avoiding a second complete command buffer. These steps finish before acquiring the vault mutex or opening the SQLite write transaction.

Operation commands accept both MDBX1 entry names and validated namespaced `ObjectTypeId` values. Existing single-entry compatibility methods retain their MDBX1-only parser; generic single-object callers continue to use the object facade.

## Failure And Retry Semantics

Resource, syntax, identity, capability, or repository failures create no partial object, commit, operation row, device head, or branch-head change. A successful command list creates one commit. Retrying the exact operation ID, kind, branch, and commands returns the original commit without executing commands again. Reusing an operation ID with different commands is rejected even when both requests are individually valid.

Limits are execution policy and are not part of user intent. A retry may use a different valid limit profile, but it must carry the exact original command list.

## Consequences

Adapters can batch namespaced objects without producing noisy history or unbounded client-controlled work. Imports larger than one accepted profile must be split into distinct operations, giving clients explicit progress, cancellation, and retry boundaries.

This decision does not add domain-specific adapters or change the physical `projects` and `entries` compatibility schema. Attachment and relation/label batch commands remain separate future extensions.
