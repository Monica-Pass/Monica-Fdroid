# Android ELF release hardening 进度

进度：5/5

已完成：

- 旧无 build ID 的 MDBX3 SO 被新 gate 明确拒绝。
- 新构建在 strip 后保留三个不同的 SHA-1 GNU build ID。
- `NEEDED` 精确保持 `libc.so`、`libdl.so` 和 `libm.so`，SONAME 保持 MDBX2 的未设置状态。
- ARM64/x86_64 LOAD segment 至少 16 KiB 对齐；ARM32 使用 4 KiB legacy profile。
- ABI split gate 消费同一份 ELF 证据并完成 ZIP/独立 SO 哈希绑定。

当前：提交 `15227a6` 已推送；随后基于最终 clean HEAD 重建正式产物。
