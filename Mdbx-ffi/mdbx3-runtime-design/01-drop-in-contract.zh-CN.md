# MDBX3 SO 原位替换契约

## 1. 契约目标

MDBX3 构建产物应允许 Android 客户端保留现有 Kotlin bindings、包名、方法调用和数据库文件，仅替换 `jniLibs/<abi>/libmdbx_ffi.so` 后继续运行。

发布资产名称可以包含 `mdbx3` 和 ABI 名称。应用内动态库文件名必须继续为 `libmdbx_ffi.so`。要求客户端把 `System.loadLibrary` 从 `mdbx_ffi` 改为其他名称的产物不符合本契约。

## 2. 二进制身份

MDBX3 完整兼容构建必须满足：

1. `[lib].name` 继续为 `mdbx_ffi`。
2. Android ABI 目录继续使用 `arm64-v8a`、`armeabi-v7a` 和 `x86_64`。
3. 动态库 basename 继续为 `libmdbx_ffi.so`。
4. SONAME 保持 MDBX2 的未设置状态；每次发布必须比较 `SONAME`、`NEEDED` 和目标 ABI。
5. 既有 UniFFI scaffolding namespace 保持不变。
6. 既有导出符号不得删除或改名。
7. 新符号只能附加，禁止复用旧符号表达不同语义。

每种 ABI 都需要单独 SO。一个 ELF 文件不能同时服务 ARM64、ARMv7 和 x86_64。

## 3. UniFFI 兼容

使用 MDBX2 SO 生成的 Kotlin 和 Swift bindings 必须能够调用 MDBX3 SO。为此需要冻结以下内容：

1. 既有顶层函数名、对象方法名和参数顺序。
2. 既有 Record 字段名称、顺序、类型、可空性和默认语义。
3. 既有 Enum 变体名称与序号。
4. 既有 Error 变体名称、字段和抛出条件。
5. `MdbxVault` 的对象生命周期和线程安全语义。
6. 字符串编码、字节数组所有权和时间单位。
7. 分页 cursor、checkpoint、bundle 和 token 的不透明字节语义。

新增稳定错误编号时，需要保留 `MdbxFfiError` 的现有变体。新的结构化诊断应通过附加函数或附加 Record 提供，避免改变旧 bindings 的错误解码。

## 4. 调用行为

原位替换后的既有调用必须保持以下行为：

1. `create_vault` 继续使用默认 Multi Tiga 模式。
2. `open_vault` 继续打开当前 MDBX2 vault，并保持现有错误类别。
3. 旧完整记录 API 继续返回历史语义；新摘要 API 继续保持有界读取。
4. 一次用户操作继续对应一个 CommitOperation。
5. snapshot、conflict、tombstone、object version 和同步状态保持原子规则。
6. 既有资源上限只能保持或加强安全上限；降低兼容上限需要迁移说明和专门验收。
7. Tiga 授权必须发生在明文载入和解密之前。

## 5. 数据库兼容

MDBX3 Runtime 必须：

1. 读取 `MDBX-1`、`MDBX-1-DRAFT` 和所有已发布 MDBX2 schema。
2. 对 MDBX1 系列继续执行 `MDBX-1 -> MDBX-2` 顺序迁移。
3. 保持 `format_version = MDBX-2`。
4. 保持 MDBX2 的稳定 ID、commit DAG、object version、tombstone、snapshot、key epoch、附件和 header authentication。
5. 保持未知非关键扩展和同步扩展，不解释未知 payload。
6. 遇到未知关键扩展时拒绝写入。
7. 迁移失败时保持原文件字节和版本标记不变。
8. schema 迁移不得暗含密钥轮换、全库重新加密或历史重写。

MDBX3 运行库可以提高 schema 序号，但新增结构必须满足 MDBX2 的附加迁移规则。新增结构如果会让现有 MDBX2 writer 误写或擦除关键语义，就需要提升 `min_writer_version`，并在混合版本测试中验证旧端能够明确拒绝写入。

## 6. 同步兼容

MDBX3 与 MDBX2 peer 交互时必须：

1. 通过现有 Hello 和 capability 协商选择双方均支持的协议。
2. 保持旧 bundle、state、checkpoint 和 resume token 的读取能力。
3. 缺少新 capability 时使用现有完整状态或旧增量协议。
4. 保持 commit identity、parent、kind、scope、operation identity 和完整性标签。
5. 保持未知同步扩展，旧 peer 省略字段不表示删除。
6. 新加速数据必须可重建，不能成为 MDBX2 peer 正确同步所必需的状态。

## 7. 发布验证

每个 MDBX3 候选 SO 必须完成：

1. 使用 MDBX2 生成的 Kotlin bindings 运行全部 smoke tests。
2. 使用 MDBX2 Android 示例应用替换 SO，完成创建、打开、写入、删除、恢复、同步和 Tiga 操作。
3. 比较 MDBX2 与 MDBX3 的导出符号清单，旧符号集合必须是新符号集合的子集。
4. 比较 UniFFI metadata，既有类型与函数签名必须完全一致。
5. 对每个 ABI 检查 ELF 类型、架构、SONAME、动态依赖、页大小兼容、唯一 GNU build ID 和 SHA-256。
