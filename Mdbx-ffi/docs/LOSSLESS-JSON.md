# Lossless business JSON

Use `mdbx_core::json::from_str` or `from_slice` when decoding an opaque object
payload or extension into `serde_json::Value`. The core enables exact numbers
and raw values itself; a consuming application's feature selection must not
decide whether stored numbers are rounded.

Enabling `serde_json/arbitrary_precision` alone is insufficient. The default
Value deserializer interprets literal object keys such as
`$serde_json::private::Number` and `$serde_json::private::RawValue` as internal
markers. These are valid business keys and must remain objects with their
original values. The shared parser reads raw container members first and only
uses the primitive Value parser for actual primitive JSON tokens.

The parser supports nested arrays and objects, precise integers and decimals,
Unicode, null and empty values. It rejects malformed/trailing input, invalid
UTF-8 and excessive nesting. Formatting and object key order are not part of
the contract. Existing canonical serialization and authenticated storage remain
unchanged; this is not a database schema or wire-version change.

Keep this boundary in FFI reads/writes, operation preparation, merge inputs,
exports and client adapters. Typed records with arbitrary extension fields also
need protection before serde flatten can buffer and reinterpret those fields;
`SyncStatePayload` demonstrates the raw-field approach. Typed fields without
opaque JSON continue to use their existing typed deserializers.

Regressions must check exact numbers alongside literal marker keys (including
escaped spellings, nested keys and non-numeric marker values), malformed input,
depth limits, write/reopen, merge and sync-state re-encoding. Cross-client
expected values must come from the original fixture and protocol, not from the
client being tested. Include corrupt outputs to prove the verifier rejects
lost fields, wrong collection identity and wrong payload versions.
