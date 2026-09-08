# MDBX3 Runtime 实施 Epic

## 目标

在不改变 MDBX2 文件格式和既有 UniFFI ABI 的前提下，实现 MDBX3 Runtime。Android 客户端可以仅替换应用内 `libmdbx_ffi.so`，继续使用现有 bindings 和 vault。

## 交付边界

1. 保留 `mdbx_ffi` crate/library name、SO basename 和 SONAME 契约。
2. 继续读取和写入 MDBX2 schema 与同步协议。
3. 新增 runtime、ABI、build capability 和 feature manifest。
4. 按基准降低 SO 体积、启动时间、内存峰值和大数据同步耗时。
5. Tiga、加密、commit、历史、恢复、冲突和附件能力保持。

## 子任务依赖

`SUBTASKS.csv` 是本 Epic 的唯一状态来源。所有子任务完成后，执行完整验收矩阵。
