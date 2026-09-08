# ABI snapshot and compatibility tests

## 目标

冻结 MDBX2 发布 bindings 的 UniFFI contract、native symbols 和 API checksums，并在 MDBX3 动态库构建后逐项验证。

## 设计

1. 使用 MDBX2 Runtime 最后一个设计前提交 `1070dee` 构建动态库。
2. 使用 UniFFI 0.31.1 生成 Kotlin bindings。
3. 从 bindings 提取 contract version、全部 external native function 和 checksum 期望值。
4. 以版本化 JSON 保存冻结基准。
5. Python 校验器使用 `ctypes` 加载 MDBX3 DLL、SO 或 dylib，检查符号并调用 checksum 函数。
6. 新增 MDBX3 API 可以附加；MDBX2 基准中的任一项缺失或变化都会失败。

## 验收

1. 当前 MDBX3 DLL 通过冻结 MDBX2 bindings 基准。
2. Linux CI 构建 `libmdbx_ffi.so` 后执行相同验证。
3. MDBX1 release、MDBX1-DRAFT 和 MDBX2 migration fixture 测试通过。
4. MDBX2 capability fallback 与增量同步测试通过。
5. 完整 workspace 测试通过。
