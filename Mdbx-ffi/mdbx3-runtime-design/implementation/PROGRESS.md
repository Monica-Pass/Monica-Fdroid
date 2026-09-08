# MDBX3 Runtime 实施进度

任务：实现 MDBX3 Runtime，并保持 MDBX2 文件、同步和既有 FFI 兼容。

形态：`epic`

进度：10/10 子任务完成

当前：全部实施子任务完成，进入最终验收审计。

事实文件：`mdbx3-runtime-design/implementation/SUBTASKS.csv`

下一步：执行最终全量验收矩阵并核对远端提交状态。

## 实施证据

1. 当前 `mdbx-ffi` library name 为 `mdbx_ffi`，crate type 包含 `cdylib`。
2. 当前 Android 应用内文件名为 `libmdbx_ffi.so`。
3. 当前 FFI build manifest 已经提供 storage 与 sync capability inventory。
4. 当前存储格式为 `MDBX-2`，schema 版本由 vault header 维护。
5. 子任务 1 提交 `d6076b0` 已推送，完整 `mdbx-ffi` 测试通过。
6. 子任务 2 已完成 ABI 冻结、负向校验、fixture 回归和 workspace 回归，准备提交。
7. 子任务 8 已生成三个单 ABI 薄包、独立 SO、发布索引和兼容全量包；每个薄包与正式 artifact report、ELF machine 和冻结 ABI 绑定。
8. MDBX3 Android 正式构建已端到端执行，release gate 与 ABI split gate 均返回 `status=ready`；提交 `1457390` 绑定的 artifact report 记录 `source_tree_state=clean`，构建脚本使用 `--untracked-files=no`，因此不会把既有 `.codex-tasks/` 作为 MDBX3 源码变更。
9. 强化 release gate 已验证 SONAME、NEEDED、ABI page alignment 和 strip 后唯一 SHA-1 build ID；旧无 build ID 产物会被明确拒绝。
10. x86_64 ABI 的最小 Android APK 已在 Pixel Fold API 35 AVD 安装运行，实际加载 MDBX3 SO 并读取 contract 30。
