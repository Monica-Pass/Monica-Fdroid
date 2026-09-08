# MDBX3 Runtime 设计目录

MDBX3 定义为第三代运行库，继续使用 `MDBX-2` 文件格式。Android、Linux 和其他原生平台的新产物继续采用 `libmdbx_ffi.so` 运行时文件名，使采用现有 UniFFI bindings 的客户端可以原位替换 MDBX2 SO。

本目录保存 MDBX3 的规范、限制、边界、实施记录和验收要求。实现不得修改 MDBX2 文件语义，也不得用删除功能换取体积缩减。

## 冻结决策

1. 产品名称与运行库主版本为 MDBX3。
2. vault 的 `format_version` 继续为 `MDBX-2`。
3. MDBX1 与 MDBX1-DRAFT 仍按现有事务迁移器升级到 MDBX2。
4. Android 应用内文件名、动态库名称和 UniFFI namespace 继续使用 `libmdbx_ffi.so` 与 `mdbx_ffi`。
5. MDBX3 SO 必须接受 MDBX2 生成的 bindings、参数、DTO、错误变体和调用顺序。
6. 新公开能力只能采用附加形式；既有函数、字段、枚举序号和返回语义保持稳定。
7. 体积优化只移除开发代码、重复实现和无法到达的代码，不移除已发布的用户能力。
8. Tiga、密钥管理、历史、同步、冲突、snapshot、附件和恢复属于完整构建的固定能力。

## 文件索引

| 文件 | 内容 |
|---|---|
| `SPEC.md` | 设计任务范围和完成条件 |
| `TODO.csv` | 任务状态事实文件 |
| `PROGRESS.md` | 调查证据、当前状态和恢复信息 |
| `01-drop-in-contract.zh-CN.md` | SO 原位替换、ABI、FFI 和数据库兼容契约 |
| `02-boundaries-and-decisions.zh-CN.md` | MDBX3 的限制、边界与设计决策 |
| `03-architecture-and-size.zh-CN.md` | 运行时结构、构建配置和体积控制 |
| `04-performance-and-experience.zh-CN.md` | 启动、读写、同步、内存和长操作体验 |
| `05-implementation-plan.zh-CN.md` | 分阶段实施顺序和提交边界 |
| `06-acceptance-matrix.zh-CN.md` | 兼容、ABI、安全、性能和发布验收矩阵 |
| `07-android-abi-distribution.zh-CN.md` | Android/F-Droid 单 ABI 薄包、全量包和跨架构边界 |
| `android-smoke/` | 只验证 APK linker、`libmdbx_ffi.so` basename 和 UniFFI contract 的最小 Android loader smoke 工程 |

## 规范优先级

本目录同时保留设计决策和实施证据。发生冲突时，当前 MDBX2 安全规范与兼容规范继续约束现有实现；MDBX3 的实现状态以 `implementation/PROGRESS.md`、事实文件和发布报告为准。正式发布前仍需完成真实设备与产品客户端验收。
