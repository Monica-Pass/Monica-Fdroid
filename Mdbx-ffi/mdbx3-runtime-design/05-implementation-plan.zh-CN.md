# MDBX3 实施计划

## 1. 实施原则

每个提交只承担一个可验证主题。所有提交保持 MDBX2 测试通过，且只修改 `mdbx` 仓库。任何阶段都不得提前改变 `format_version`、SO basename 或 UniFFI namespace。

## 2. 阶段零：冻结基准

产物：

1. 三种 Android ABI 的 MDBX2 release SO、SHA-256、ELF header、SONAME、NEEDED、动态导出符号和 UniFFI metadata。
2. MDBX2 生成的 Kotlin bindings 和不可变测试应用。
3. MDBX1、MDBX1-DRAFT、早期 MDBX2 和当前 schema 17 fixture。
4. raw ELF、strip 后 ELF、APK 压缩、安装占用、启动、读写、同步和内存基准。
5. dependency tree、重复 crate、`cargo bloat` 和 link map。

验收：所有基准材料具有 SHA-256，能够在固定工具链中重复生成。

## 3. 阶段一：运行库身份和 ABI 门禁

产物：

1. MDBX3 runtime manifest。
2. `mdbx-ffi-abi-v1` manifest 生成器。
3. 旧符号集合包含检查。
4. UniFFI metadata 结构比较。
5. MDBX2 bindings 加载 MDBX3 SO 的 smoke test。

实现限制：保留 crate name、library name、basename、namespace 和全部既有导出。

验收：替换测试应用中的 SO 后，无客户端源码和 bindings 变更即可通过现有 smoke tests。

## 4. 阶段二：构建与依赖优化

按以下顺序提交：

1. 排除开发代码和生成器。
2. 统一重复依赖版本。
3. 建立各 crate 优化级别试验。
4. 比较 thin LTO 与 fat LTO。
5. 外置调试符号并保留 build ID。
6. 生成 SQLite capability manifest。
7. 单独审核 SQLite 编译选项。

每个提交保存前后体积、速度和 ABI 报告。体积降低伴随性能退化时，保留两组数据并恢复性能较优配置。

## 5. 阶段三：Vault Runtime

先把 FFI facade 所需查询和写入能力移入 storage-owned Module，再处理连接模型。实施顺序：

1. 冻结 `MdbxVault` 外部 Interface。
2. 建立 Runtime 内部服务边界。
3. 移除生产 FFI 对 raw SQL 的依赖。
4. 加入 statement cache。
5. 建立 single writer。
6. 加入带 generation 的 read snapshot。
7. 绑定 key epoch、session、Tiga 和 revocation 失效规则。

验收：并发、restore、rekey、sync apply 和 session expiry 测试通过，现有方法语义保持不变。

## 6. 阶段四：长历史和大数据

分成独立能力实施：

1. 分段 bootstrap 与 resume。
2. 因果 checkpoint。
3. commit 与 delta 分页导出。
4. 历史保留资格计算。
5. 可恢复 compaction。
6. 大型 Blob constant-memory transfer。

历史删除与 checkpoint 生成不得位于同一首发提交。删除能力需要独立审计、恢复和混合版本同步证明。

## 7. 阶段五：体验 API

通过附加 Interface 增加：

1. 长操作进度、取消和恢复。
2. 稳定结构化诊断。
3. runtime 与 ABI manifest 查询。
4. 可选预热。
5. 性能统计快照，默认关闭且不包含秘密。

旧 bindings 不调用新增能力时，行为继续保持 MDBX2 语义。

## 8. 阶段六：发布候选

每个候选版本执行完整验收矩阵，并生成：

1. 每个 ABI 的 `libmdbx_ffi.so`。
2. 带 MDBX3 名称的下载资产。
3. 保持 `jniLibs/<abi>/libmdbx_ffi.so` 的 Android ZIP。
4. SHA-256、ABI manifest、runtime manifest 和 capability manifest。
5. 外置调试符号和 build ID 索引。
6. 与 MDBX2 的体积、性能和行为比较报告。

## 9. 提交边界

建议提交组如下：

| 提交组 | 内容 | 最低验证 |
|---|---|---|
| A | 基准与 manifest 工具 | fixture 哈希、工具链固定 |
| B | ABI 冻结测试 | 旧 bindings smoke test |
| C | 构建 profile | 三 ABI 体积与性能比较 |
| D | 依赖和 SQLite 优化 | workspace、fixture、ABI 全部通过 |
| E | Vault Runtime 内部重组 | FFI smoke、并发、Tiga、恢复 |
| F | sync bootstrap 与 checkpoint | 混合版本、乱序、resume |
| G | 长操作和诊断 API | 新旧 bindings 同时通过 |
| H | 发布打包 | 资产名、内部 basename、SHA-256 |

每组完成后单独提交并推送。后续组只依赖已经验证的前置组。

## 10. 恢复策略

1. 构建优化失败时恢复上一份 profile，不影响数据库。
2. Runtime 内部重组失败时继续使用原 `Mutex<VaultConnection>` 实现。
3. 新同步能力失败时通过 capability 协商使用 MDBX2 协议。
4. 新 derived index 失败时删除并重建索引，不修改源对象。
5. schema 迁移失败时事务回滚，保持原 header 和数据。
6. 候选 SO 失败时恢复上一份 `libmdbx_ffi.so`；vault 仍保持 MDBX2 格式，可由 MDBX2 runtime 打开。
