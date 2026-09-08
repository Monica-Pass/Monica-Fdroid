# Runtime identity and manifest 进度

任务：增加 MDBX3 Runtime identity 和附加 FFI manifest。

形态：`single-full`

进度：4/4

当前：子任务完成。

事实文件：`mdbx3-runtime-design/implementation/tasks/01-runtime-identity/TODO.csv`

下一步：父 Epic 进入 ABI 快照和兼容测试子任务。

## 约束

1. 现有 `MdbxBuildCapabilityManifest` 的字段保持不变。
2. 当前 MDBX2 schema 常量来自 storage，不在 FFI 重复维护数值。
3. runtime pre-release 版本为 `3.0.0-alpha.1`。
4. `.codex-tasks/` 是既有未跟踪目录，本子任务不修改。

## 验证记录

1. `cargo test -p mdbx-core`：51 项通过。
2. `cargo test -p mdbx-ffi --no-fail-fast`：57 项单元测试、1 项批量测试、37 项 smoke test 和 1 项 divergence test 通过；1 项手动性能测试保持忽略。
3. `cargo check -p mdbx-ffi --no-default-features`：通过。
4. `cargo fmt --all -- --check`：通过。
5. `git diff --check`：通过。
6. 提交 `d6076b0` 已推送到 `origin/master`，远端差异为 0/0。
