# Production build profile and size instrumentation 进度

任务：建立 MDBX3 production profile 与可重复体积报告。

形态：`single-full`

进度：6/6

当前：完成。

事实文件：`mdbx3-runtime-design/implementation/tasks/03-build-profile/TODO.csv`

下一步：进入子任务 4：Vault runtime boundary and read/write model。

## 已完成结果

1. 新增 `mdbx3-release`：`opt-level=3`、fat LTO、单 codegen unit、strip，保留 unwind。
2. Android 三 ABI 构建与报告可重复生成，内部文件名保持 `libmdbx_ffi.so`。
3. MDBX2 基准固定到 `1070dee739dbe564806654359b6c6caa68156de5`。
4. 三种 ABI 均验证冻结的 535 个动态符号。
5. MDBX3 raw 总体积比 MDBX2 小 8.94%，deflate 总体积小 3.49%。
6. MDBX2 一次性编译缓存默认移到系统临时目录，仓库内只保留忽略的报告与 SO 副本。

## 最终验证

1. `cargo fmt --all -- --check`。
2. `git diff --check`。
3. `cargo check --workspace --all-targets`。
4. `cargo clippy --workspace --all-targets --all-features -- -D warnings`。
5. `cargo test -p mdbx-storage --no-default-features --features core`：678+4 项通过。
6. `cargo test --workspace --no-fail-fast`：全部通过。
7. host production DLL：535 个符号、237 个 checksum、UniFFI contract 30 通过。
8. Android 三 ABI：每个 ABI 的 535 个冻结动态符号全部通过。

## 约束

1. 首个候选保持 `opt-level=3` 与 unwind。
2. Android 应用内文件名继续为 `libmdbx_ffi.so`。
3. 报告写入 `target/`，不提交构建产物。
4. `.codex-tasks/` 保持原状。
