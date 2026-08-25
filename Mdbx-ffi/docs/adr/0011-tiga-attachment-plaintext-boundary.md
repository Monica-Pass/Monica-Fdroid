# ADR-0011: TIGA attachment plaintext boundary

## Status

Accepted

## Context

MDBX2 treats attachments as generic encrypted binary objects. Password exports, saved web pages, email messages, MAFile payloads, and future application data all require the same explicit boundary between ciphertext storage and plaintext use.

The attachment repository already provides MDBX1-compatible content readers. Those readers also serve integrity checks, migration verification, snapshot recovery, and internal maintenance, so changing their session requirements would break existing callers and mix storage mechanics with client intent.

## Decision

TIGA gains an `Attachment` scope. An attachment inherits policy from its entry when present, otherwise from its project, followed by an optional attachment-specific override.

Client-owned plaintext access calls `AttachmentRepo::authorize_plaintext_access` before allocating a plaintext buffer, creating a temporary output file, or opening an output writer.

Two purposes are defined:

1. `InMemory` maps to `DecryptAttachment`. Its constraints govern memory-only use and secure temporary processing.
2. `Export` maps to `ExportData`. Its egress policy governs intentional persistent output.

An export performs one `ExportData` authorization. Combining it with `DecryptAttachment` would mix the `NoPlaintextPersistence` constraint with an explicitly permitted persistent export.

Attachment-specific policy changes create an attachment commit, update its head metadata, record an object version, and use `attachment:<attachment_id>` in policy and audit storage.

## Compatibility

The physical `attachments` and `attachment_chunks` tables remain unchanged. Existing `read_content` and `read_content_to_writer` interfaces retain their behavior for MDBX1 clients and internal services. New clients use the authorization interface before calling those readers.

## Consequences

Audit events identify the exact encrypted object and include session, device, policy version, and policy fingerprint evidence. Parent policies can prohibit export for every attachment in a project or entry, while a stricter attachment override can protect a single high-value object.
