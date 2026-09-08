# MDBX3 Runtime 设计进度

## 恢复信息

任务：形成 MDBX3 单 SO 原位替换 MDBX2 的完整设计。

形态：`single-full`

进度：设计 4/4；实施 10/10

当前：MDBX3 runtime 实施完成，进入真实设备和产品客户端的发布验收。

事实文件：`mdbx3-runtime-design/TODO.csv`

下一步：按 `06-acceptance-matrix.zh-CN.md` 执行真实 ARM64/ARM32 设备、冻结客户端和多设备同步验收。

## 核查证据

1. 当前 Android 产物在 ABI 目录内固定命名为 `libmdbx_ffi.so`。
2. `mdbx-ffi` 的 crate library name 为 `mdbx_ffi`，crate type 包含 `cdylib`。
3. 当前 FFI 源码包含 46 个 `#[uniffi::export]` 块、133 个 Record 和 24 个 Enum。
4. 当前 vault 格式为 `MDBX-2`，内部 schema 为 17。
5. `mdbx-ffi` 使用 storage 的 `core` 与 `filesystem-blob-store` 功能，不会自动包含 storage 的默认 KDBX、benchmark 和搜索功能。
6. 当前分支为 `master`，核查时 HEAD 与 `origin/master` 都为 `974c517`。
7. `.codex-tasks/` 是核查前已经存在的未跟踪目录，本任务不修改也不提交该目录。

## 设计选择

1. MDBX3 是运行库代际，文件格式继续保持 MDBX2。
2. 零客户端修改要求保留 `libmdbx_ffi.so`、`mdbx_ffi` namespace 和既有 UniFFI ABI。
3. 发布资产可以标注 MDBX3，但进入 `jniLibs/<abi>/` 后必须恢复同名文件。
4. 完整构建保持现有用户能力；产品专用构建只能省略从未暴露且不会影响现有 vault 的领域 Adapter。

## 验证记录

1. 2026-08-17：设计目录首批六个文件存在。
2. 2026-08-17：`git diff --check` 通过。
3. 2026-08-17：首批文件未触碰既有 `.codex-tasks/` 目录。
4. 2026-08-17：`03` 至 `06` 文档完成；占位符检查通过。
5. 2026-08-17：README 内部文件引用检查通过。
6. 2026-08-17：全文覆盖、文件范围和暂存区差异验证通过，输出 `design-validation-ok`。
7. 2026-08-17：设计提交 `d17c8d3` 与 `5fe4e9a` 推送到 `origin/master`，远端差异为 0/0。
