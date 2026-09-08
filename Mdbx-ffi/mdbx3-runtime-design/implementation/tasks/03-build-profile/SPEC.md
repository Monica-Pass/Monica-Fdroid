# Production build profile and size instrumentation

## 目标

建立可重复的 MDBX3 production profile、三 ABI Android 构建、runtime capability manifest 和 artifact size report，并与同工具链生成的 MDBX2 release 基准比较。

## 首个候选

1. `opt-level = 3`，优先保持运行速度。
2. fat LTO。
3. 单 codegen unit。
4. strip symbols。
5. 保留 unwind，不使用 `panic=abort`。
6. 内置 SQLite 和现有完整 FFI capability 集合保持。

## 验收

1. host 与 Android 三 ABI 可使用 `mdbx3-release` 构建。
2. 产物报告包含 raw bytes、deflate bytes、SHA-256、ELF 架构、runtime manifest 和 capability manifest。
3. Android ABI 与 ELF machine 严格匹配。
4. 当前产物通过冻结 MDBX2 UniFFI ABI。
5. MDBX2 与 MDBX3 使用相同 Rust、NDK 和 target 生成对比报告。

## 固定工具链

1. Rust / Cargo：`1.86.0`。
2. cargo-ndk：`4.1.2`。
3. Android NDK：`28.2.13676358`。
4. Android API：21。
5. Linker：LLD 19.0.1。
6. ABI：arm64-v8a、armeabi-v7a、x86_64。
7. MDBX2 基准提交：`1070dee739dbe564806654359b6c6caa68156de5`。

MDBX2 与 MDBX3 均在复制到报告目录后执行 `llvm-strip --strip-all`。MDBX2
编译缓存默认放入系统临时目录，避免把约 1 GiB 的一次性基准缓存留在仓库
`target/` 中。

## 已测结果

| ABI | MDBX2 raw | MDBX3 raw | raw 减少 | MDBX2 deflate | MDBX3 deflate | deflate 减少 |
|---|---:|---:|---:|---:|---:|---:|
| arm64-v8a | 8,270,112 | 7,390,048 | 10.64% | 3,479,216 | 3,347,581 | 3.78% |
| armeabi-v7a | 6,129,768 | 5,720,016 | 6.68% | 3,199,139 | 3,120,013 | 2.47% |
| x86_64 | 9,641,232 | 8,781,584 | 8.92% | 3,753,538 | 3,599,717 | 4.10% |
| 合计 | 24,041,112 | 21,891,648 | 8.94% | 10,431,893 | 10,067,311 | 3.49% |

三种 ABI 均包含冻结 MDBX2 bindings 要求的 535 个动态符号。报告同时记录
ELF machine、SHA-256、runtime/capability manifest、工具链、Cargo.lock 和源树状态。
