# ADR-0025: Bounded generic metadata summaries

## Status

Accepted

## Context

MDBX2 generic metadata uses encrypted ObjectRelations, ObjectLabels, and ObjectLabelAssignments. The first UniFFI interfaces returned complete relation and label records in unbounded vectors. Listing a mail thread, bookmark graph, or collection classification therefore decrypted every relation or label payload even when the client needed only identity and presentation metadata. One corrupt payload could also prevent the whole navigation view from loading. Assignment rows contain no payload, but their original lists were still unbounded.

The existing interfaces are already part of the compatibility surface and are also useful to internal services that intentionally consume complete records. Replacing them would break generated clients and would confuse selection with payload disclosure.

## Decision

MDBX2 adds payload-free core types for `ObjectRelationSummary`, `ObjectLabelSummary`, and `ObjectLabelAssignmentSummary`, each with a bounded page type. `ObjectMetadataSummaryRepo` owns their SQL projections.

Relation summaries include stable relation/source/target IDs, relation kind, payload schema version, head commit, deletion state, and update time. Label summaries include stable label/collection IDs, decrypted display name, payload schema version, head commit, deletion state, and update time. Assignment summaries include stable assignment/object/label IDs, head commit, deletion state, and update time. Relation and label summary SQL never selects `payload_ct`.

By-ID relation and label summaries include deleted rows for tombstone presentation. List pages include active rows only. Relations can be traversed from either endpoint with an optional kind filter. Assignments can be traversed by object or label.

Every page uses descending `updated_at` and stable ID ordering, accepts 1 through 200 items, and reads at most one sentinel row beyond the requested page. The opaque JSON cursor is limited to 4096 bytes and binds its version, query kind, scope ID, optional relation kind, update time, and stable item ID. A cursor from another endpoint, direction, collection, assignment owner, or relation-kind filter is rejected.

The cursor is a bounded live-view keyset cursor, not a snapshot token or authorization credential. Clients refresh their first page after local or synchronized metadata mutations.

UniFFI exposes additive by-ID relation/label summary methods, relation pages from/to, label pages, and assignment pages by object/label. Existing complete `get_object_relation`, `list_object_relations_*`, `list_object_labels`, and `list_object_label_assignments` methods retain their behavior.

## Compatibility

The physical schema and schema version remain unchanged. Schema 7 already indexes relation endpoints and kinds, label collections, and assignment owners; the bounded projections reuse those filters. MDBX1 and earlier MDBX2 files are not rewritten merely to add client-side summary APIs.

No serialized sync, snapshot, commit, conflict, or object-version shape changes. The new core and UniFFI records are additive and contain no relation or label payload field.

## Consequences

Mail threads, bookmark graphs, and classification screens can grow without decrypting every metadata payload or constructing an unbounded result vector. Relation or label payload corruption no longer blocks enough metadata to identify and navigate the affected record; the preserved complete read still reports its authentication failure.

Label names remain decrypted presentation metadata. Tiga ownership and explicit relation/label payload disclosure are defined separately by ADR-0026; the complete methods remain compatibility interfaces rather than the recommended policy boundary.
