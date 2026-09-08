# Release packaging and replacement gate 进度

进度：4/4

已完成：

- 新增 `scripts/verify-mdbx3-release.py`，对 staging 目录执行可重复的替换
  门禁。
- 构建脚本在 artifact report 后自动调用 gate，失败即终止发布。
- 三 ABI、basename、ELF 架构、manifest、SHA-256、535 symbols、237 checksums、
  contract 30 和体积比较均已验证。
- release gate 同时冻结 MDBX2 `mdbx-ffi` capability 集合：19 个启用 storage、
  6 个禁用 optional storage、9 个启用 sync、1 个禁用 optional sync；能力缺失的
  负向样例会被拒绝。

当前：已完成实现与验证，提交 `c2cfc3e` 与 `56bf9a3` 已推送。
