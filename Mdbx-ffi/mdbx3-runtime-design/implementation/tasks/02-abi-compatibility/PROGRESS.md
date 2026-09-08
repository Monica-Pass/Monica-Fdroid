# ABI snapshot and compatibility tests 进度

任务：冻结 MDBX2 UniFFI bindings，并验证 MDBX3 动态库保持兼容。

形态：`single-full`

进度：5/5

当前：子任务完成。

事实文件：`mdbx3-runtime-design/implementation/tasks/02-abi-compatibility/TODO.csv`

下一步：父 Epic 进入生产构建 profile 和体积测量子任务。

## 约束

1. 基准来源必须早于 MDBX3 Runtime manifest。
2. 新增 API 允许存在，旧 symbols 和 checksums 必须全部保持。
3. 校验器只加载动态库并调用无参数 contract/checksum 函数，不打开 vault。
4. `.codex-tasks/` 保持原状。

## 验证记录

1. 从 `1070dee` 独立 worktree 构建真实 MDBX2 DLL，并生成 Kotlin bindings。
2. 基准包含 535 个 native symbol、237 个 checksum，UniFFI contract version 为 30。
3. 真实 MDBX2 DLL 与当前 MDBX3 DLL 均通过冻结基准。
4. 人为修改一个 checksum 后，校验器明确失败并报告期望值与实际值。
5. MDBX1 release 和 MDBX1-DRAFT fixture 迁移测试通过。
6. 旧 peer complete fallback 与旧 bundle fallback 测试通过。
7. `cargo test --workspace --no-fail-fast` 全部通过，零失败。
8. `python -m py_compile`、`cargo fmt --all -- --check` 和 `git diff --check` 通过。
