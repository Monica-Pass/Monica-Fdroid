# Vault runtime boundary and read/write model 进度

任务：把连接、reader generation 与 single writer 收口到 storage-owned Vault Runtime。

形态：`single-full`

进度：6/6

当前：已完成并通过全量验证，提交 `ac7ebd6` 已推送。

验证：storage core 683+4 项通过；runtime 5 项、FFI 58 项、workspace clippy 与 ABI gate 通过。

文件：`mdbx3-runtime-design/implementation/tasks/04-runtime-boundary/TODO.csv`

下一步：进入父任务 05：Incremental bootstrap and history lifecycle。

## Context Recovery

- 当前里程碑：4 — 将兼容 FFI 迁移到 Runtime 且冻结 ABI
- 当前状态：DONE
- 上一项：3 — storage-owned VaultRuntime 与 single writer
- 当前事实文件：`TODO.csv`
- 关键上下文：新增 `mdbx_storage::runtime::VaultRuntime`，兼容后端先保持一个串行连接；generation 合约独立于后续 read snapshot 实现。
- 已知边界：生产 FFI 保留 142 个兼容 `.lock()` 调用，但锁定对象已是 `VaultRuntime`；后续可按模块继续显式分类。
- 下一动作：进入父任务 05：Incremental bootstrap and history lifecycle。

## 已确认基线

1. `MdbxVault` 当前直接拥有 `Mutex<VaultConnection>`。
2. production FFI 的 142 个旧式 `.lock()` 调用现在锁定 `VaultRuntime`；生产 raw SQL 查询已下沉为 0 处。
3. `VaultConnection` 同时拥有 SQLite、keyring、active session、epoch keyrings 和 extension registry，不能无验证地拆成多个连接。
4. 第一兼容后端保留串行连接，以 storage-owned `read/write/reader generation` 接口隐藏实现。

## 约束

1. TDD 每次只推进一个可观察行为。
2. 测试使用真实临时 vault，不 mock 内部 storage 模块。
3. `.codex-tasks/` 不修改、不提交。
