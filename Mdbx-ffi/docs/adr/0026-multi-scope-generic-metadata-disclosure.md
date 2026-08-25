# ADR-0026: Multi-scope generic metadata disclosure

## Status

Accepted

## Context

MDBX2 separates bounded relation/label navigation from encrypted payload disclosure. The preserved complete-record APIs are required for MDBX1 and early MDBX2 compatibility, but they decrypt payloads directly and do not define which Tiga policy owns a relation or label.

A relation connects two independently protected ObjectRecords. Authorizing only the source or only the target could disclose cross-boundary metadata that the other endpoint policy forbids. A label belongs to one Collection, which is represented by the existing Project policy hierarchy. Adding Relation and Label variants to `TigaScope` would require new policy-row constraints, schema migration, sync interpretation, audit bindings, FFI variants, and downgrade rules before their ownership semantics were mature.

## Decision

MDBX2 reuses existing scopes and adds a composite authorization execution boundary.

Relation payload disclosure evaluates `TigaOperation::RevealSecret` for the source Entry scope and then the target Entry scope. Both decisions are preserved in that order. The plaintext action runs only when both outcomes are `Allow` or `AllowWithConstraints`. Label payload disclosure evaluates the owning collection's Project scope. ObjectLabelAssignments have no encrypted payload and therefore receive no disclosure API.

Scope routing reads only relation endpoint IDs or the label collection ID. Routing, all policy evaluations, audit writes, deleted-state checks, resource gates, authenticated decryption, and the returned plaintext share one immediate transaction. A denied composite does not inspect deletion state or payload length and does not load or decrypt payload ciphertext. It records every component authorization decision under one shared non-commit operation ID so audit readers can reconstruct the attempted boundary. A successful execution records the component decisions required by their resolved policies.

The composite primitive accepts one through sixteen unique existing scopes and returns typed scoped decisions without flattening reasons or constraints. It does not add a new serialized Tiga scope.

Relation and label disclosure use `ObjectMetadataDisclosureLimits`: 8 MiB plaintext by default, configurable from 1 byte through a 64 MiB hard ceiling. After authorization and deletion checks, SQLite `length(payload_ct)` rejects ciphertext larger than the selected limit plus a 128 KiB envelope allowance before BLOB materialization. Authenticated plaintext length is checked again before return. The active session is renewed only when plaintext is actually returned.

UniFFI exposes additive metadata-disclosure limits, a reusable scoped-decision record, typed relation/label disclosure results, and default/device-context/explicit-limit reveal methods. A policy denial is a successful typed result with `relation = None` or `label = None`; validation, missing data, deletion, resource, database, and crypto failures remain errors.

## Compatibility

The physical schema, schema version, Tiga scope enum, policy override rows, audit row shape, sync/snapshot payloads, commits, conflicts, and object versions remain unchanged. Existing `get_object_relation`, relation lists, `list_object_labels`, and internal complete repositories keep their signatures and complete-payload behavior.

MDBX1 and earlier MDBX2 databases are not rewritten to add this boundary. Existing optional audit `operation_id` fields carry composite read correlation without a commit association. Older clients continue to ignore the additive methods and fields they do not call.

## Consequences

Mail-thread edges, bookmark links, and other cross-object metadata cannot reveal payloads by satisfying only one endpoint policy. Label adapters inherit one stable collection policy instead of creating a parallel label-policy hierarchy. Clients can distinguish which scope requires fresh authentication, another factor, or stronger device capability and can enforce every returned constraint.

The legacy complete APIs remain intentionally unsafe as default disclosure paths and must be treated as compatibility or trusted internal interfaces. Future metadata kinds should first define ownership as a composition of existing scopes; a new persisted scope type requires a separate compatibility design.
