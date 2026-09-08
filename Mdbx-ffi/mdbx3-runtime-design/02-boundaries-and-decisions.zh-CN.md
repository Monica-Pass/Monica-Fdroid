# MDBX3 限制、边界与设计决策

## 1. 名称边界

`MDBX3` 表示运行库主版本。`MDBX-2` 表示 vault 文件格式。两个名称必须分别出现在版本报告中：

```text
runtime_name      = MDBX3
runtime_version   = 3.x.y
storage_format    = MDBX-2
schema_version    = <current schema>
ffi_abi_profile   = mdbx-ffi-abi-v1
```

现有 `mdbx_build_capability_manifest` 需要通过附加字段或新的兼容函数报告这些值。现有 Record 无法安全增加字段时，应增加 `mdbx_runtime_manifest_v3`，保留旧函数字节和 bindings 语义。

## 2. 零客户端修改边界

零客户端修改包括：

1. 不重新生成 Kotlin 或 Swift bindings。
2. 不修改 `System.loadLibrary` 名称。
3. 不修改 package name、JNI 目录和 Gradle 配置。
4. 不修改现有方法调用、DTO 构造和错误捕获代码。
5. 不复制或转换现有 vault。

零客户端修改不包括：

1. 在旧客户端界面中自动显示 MDBX3 新增方法。
2. 让浏览器扩展加载 ELF SO。
3. 让同一个 SO 跨越不同 CPU ABI。
4. 让已经发布的 MDBX2 writer 理解未来关键语义。

## 3. 功能完整边界

MDBX3 完整构建必须保留当前 `mdbx-ffi` 已经公开的全部能力。CLI 专属命令、benchmark、测试生成器和未进入 FFI 的可选 Adapter 不属于 Android SO 的既有体验。

功能清单以以下三项共同决定：

1. MDBX2 FFI 导出符号与 UniFFI metadata。
2. `mdbx_build_capability_manifest` 返回的编译能力。
3. Android 集成 smoke test 中可观察的行为。

任何体积优化都要证明上述三项没有减少。

## 4. 可裁剪边界

产品专用构建可以省略未公开的领域 Adapter，但每个发布配置需要稳定名称和 capability manifest。省略 Adapter 后仍必须保存、同步、备份、恢复和诊断对应的未知通用对象。

核心固定能力包括：

1. MDBX1 与 MDBX2 兼容读取。
2. SQLite storage、认证加密和 key epoch。
3. Tiga、commit、object version、snapshot、conflict 和 tombstone。
4. 增量同步、完整状态回退和 Blob 引用同步。
5. 通用 Collection、Object、Relation、Label 和 Attachment。
6. UniFFI 基础对象、兼容 API 和 capability discovery。

## 5. 安全边界

MDBX3 的体积和性能优化不得改变：

1. Argon2id 的 Tiga 模式参数和最低要求。
2. AEAD、nonce、associated data、commitment 和 key separation。
3. 明文披露前授权。
4. 资源上限检查顺序。
5. 审计事件、授权例外和策略冲突的严格合并规则。
6. secret、key、payload、token 和附件明文的日志禁令。
7. key material 的 zeroize 和会话过期语义。

KDF 耗时必须与运行时启动耗时分开测量，禁止通过降低 KDF 成本获得启动性能数据。

## 6. SQLite 边界

完整 Android SO 继续内置 SQLite，保持单 SO 自包含交付。SQLite 编译选项只有在 MDBX1、MDBX2、FTS、backup、WAL、迁移和完整性检查 fixture 全部通过后才能省略。

禁止依赖设备厂商提供的 SQLite 行为作为 MDBX3 的唯一实现，因为 Android 系统版本和编译能力存在差异。

## 7. 浏览器边界

原生 SO 服务 Android、Linux 和原生宿主。纯浏览器扩展需要 MDBX3 WASM 构建及 OPFS storage Adapter。Native Messaging 方案可以由原生宿主加载 MDBX3 SO，但浏览器扩展本身仍然不能加载 SO。

WASM 与 SO 可以共享核心 Rust crate、文件格式和同步协议，发布产物和平台 Interface 分别管理。

## 8. 版本提升条件

只有以下变化足以考虑新的 `MDBX-3` 文件格式：

1. 新安全不变量无法由 MDBX2 的附加表、扩展字段或 envelope 表达。
2. commit identity、对象身份或因果语义需要改变。
3. 密文格式必须全面替换且无法保留旧 reader。
4. MDBX2 schema 无法继续安全增量扩展。

SO 体积、性能、内部模块重组、FFI 实现替换和新可重建索引均不构成文件格式升级理由。

## 9. 失败处理边界

1. ABI 检查失败时不得发布候选 SO。
2. MDBX2 fixture 出现数据差异时不得迁移原文件。
3. 性能指标退化时保留 MDBX2 Implementation，直到替代实现通过基准。
4. 新 capability 初始化失败时，只有存在完全等价的兼容实现才能回退。
5. 完整性、认证、因果关系和格式错误必须明确失败，禁止静默修复认证历史。
