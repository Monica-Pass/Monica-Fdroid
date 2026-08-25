# ADR-0007: Pre-Migration Integrity Gate

- Status: Accepted
- Date: 2026-07-20

## Context

MDBX2 automatically upgrades MDBX1 and older MDBX2 schemas for compatibility. Clients can also inspect, back up, and explicitly upgrade a vault through Rust or UniFFI.

The previous preflight checked initialization metadata, format versions, and critical extensions before opening the file for writable migration. It did not verify SQLite page, B-tree, index, constraint, or foreign-key integrity. A damaged legacy database could therefore enter migration before the client received a corruption diagnosis.

Migration is infrequent and changes the durable schema generation. The stronger safety boundary is to diagnose source corruption before any migration write while preserving the original file for backup and recovery tools.

## Decision

Every path-based migration inspection that reports an upgrade requirement opens the database read-only and runs:

1. `PRAGMA integrity_check` for page, B-tree, index, and constraint consistency.
2. `PRAGMA foreign_key_check` for relational ownership consistency.

MDBX1 files may contain the known `project_titles_fts` FTS5 plaintext search index. SQLite's FTS5 full integrity callback attempts an internal write even on a read-only connection. Because this index is derived, non-authoritative data and is removed during open, the gate ignores only the exact callback diagnostic that names `main.project_titles_fts` and the read-only write attempt. Every other result from the same full integrity scan remains authoritative, and the independent foreign-key check still runs.

Diagnostic output is bounded to the first sixteen issues so corrupted files cannot produce unbounded client errors. An empty or non-`ok` integrity result fails closed. Foreign-key diagnostics identify the table, row ID, referenced parent, and foreign-key definition.

`preflight_existing_vault` reuses the same read-only inspection for automatic open and explicit path upgrade. `upgrade_to_latest` repeats the integrity gate on the actual connection immediately before migration writes. This second check closes the interval between read-only inspection and writable execution and protects direct storage-core callers that already own a connection.

Current-schema files do not run the migration integrity scan during ordinary open. Full health diagnostics remain available separately. Unknown critical extensions continue to be reported before migration because their storage semantics are unavailable to the current reader.

## Consequences

Page corruption, malformed indexes, constraint damage, and orphaned foreign keys block migration with a specific validation error. The previous format marker and database bytes remain available for backup or external repair.

Migration of a large vault performs a full integrity scan once per required schema upgrade. This increases upgrade time in exchange for stronger source preservation. MDBX1 public methods, physical tables, automatic compatibility behavior, and healthy legacy upgrades remain unchanged.
