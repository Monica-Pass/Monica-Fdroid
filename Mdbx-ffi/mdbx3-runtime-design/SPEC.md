# MDBX3 Runtime 设计任务

## 目标

形成一次完整的 MDBX3 运行库设计，使 Android 客户端能够使用现有 bindings 和调用代码，将 MDBX2 的 `libmdbx_ffi.so` 替换为 MDBX3 构建的同名文件，并继续打开、修改、同步和恢复 MDBX2 vault。

MDBX3 应在保持全部已发布体验的前提下减少 SO 体积、启动时间、内存占用和大数据操作耗时。

## 范围

本设计覆盖以下内容：

1. 运行库版本与文件格式版本的分离。
2. Android SO 文件名、SONAME、UniFFI namespace、导出符号和 bindings 兼容。
3. MDBX1、MDBX1-DRAFT、MDBX2 vault 的读取与写入规则。
4. schema、同步协议、密文、Tiga、commit 和未知扩展兼容。
5. 单 SO 模块结构、依赖控制、SQLite 配置和编译优化。
6. 启动、列表、写入、同步、附件和长操作性能。
7. Golden vault、混合版本同步、ABI 和性能验收。
8. 发布资产、校验值、回退和故障报告要求。

## 范围之外

1. 原设计任务不定义客户端 UI 或业务代码；MDBX3 runtime 实现已经在 `implementation/` 完成。
2. 本任务不引入 `MDBX-3` 文件格式。
3. 本任务不改变现有客户端代码或重新生成 bindings。
4. 本任务不承诺浏览器扩展可以加载原生 SO。纯浏览器运行需要 WASM；Native Messaging 需要原生宿主。
5. 本任务不以移除用户能力作为体积优化手段。

## 完成条件

1. 本目录中的设计文件完整且互相引用有效。
2. 原位替换契约具有明确的 MUST、MUST NOT 和验收方法。
3. 设计明确说明单 SO 与可裁剪构建之间的关系。
4. 每个性能目标都具备基准对象、数据规模和失败条件。
5. 每个兼容承诺都具备 fixture 或混合版本测试。
6. 实施顺序允许每个提交独立验证和撤销。
