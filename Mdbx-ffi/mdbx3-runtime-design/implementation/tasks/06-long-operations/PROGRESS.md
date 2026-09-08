# Long operations and structured diagnostics 进度

进度：4/4

已完成：

- FFI 的 bootstrap/export/check/restore/benchmark 入口按 read/write 语义
  经过 `VaultRuntime`。
- 既有 segment durable ack、resume、tamper、replay 和取消行为保持不变。
- diagnostics 已由 storage-owned typed aggregate counts 提供，避免 FFI raw SQL
  与 payload disclosure。

当前：已完成实现与验证，提交 `22d5a6f` 已推送。
