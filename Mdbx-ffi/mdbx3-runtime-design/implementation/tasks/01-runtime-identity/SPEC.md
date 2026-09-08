# Runtime identity and manifest

## 目标

增加独立的 MDBX3 Runtime manifest，在不修改现有 `MdbxBuildCapabilityManifest` 的前提下报告运行库代际、MDBX2 文件格式、当前 schema 和 FFI 替换身份。

## 兼容要求

1. 保留现有 `mdbx_build_capability_manifest` 函数和 Record。
2. 新增函数与 Record 只能采用附加方式。
3. library name 继续为 `mdbx_ffi`，Android SO 文件名继续为 `libmdbx_ffi.so`。
4. 可读格式包含 MDBX1、MDBX1-DRAFT 和 MDBX2；可写格式为 MDBX2。
5. manifest 不打开 vault，也不修改任何数据库状态。

## 验收

1. `cargo test -p mdbx-core`
2. `cargo test -p mdbx-ffi`
3. `cargo check -p mdbx-ffi --no-default-features`
4. `cargo fmt --all -- --check`
5. `git diff --check`
