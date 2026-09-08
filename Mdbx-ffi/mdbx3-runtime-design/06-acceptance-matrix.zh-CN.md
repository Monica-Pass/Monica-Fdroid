# MDBX3 验收矩阵

## 1. 发布判定

MDBX3 候选 SO 只有在本矩阵全部强制项通过后才能替代 MDBX2 SO。体积收益不能抵消兼容、安全、数据或性能失败。

## 2. 产物与 ABI

| 编号 | 测试 | 通过条件 |
|---|---|---|
| ABI-01 | Android ELF 架构 | arm64-v8a、armeabi-v7a、x86_64 与目录一致 |
| ABI-02 | 动态库 basename | 应用内全部为 `libmdbx_ffi.so` |
| ABI-03 | SONAME | 与 MDBX2 装载契约兼容 |
| ABI-04 | 导出符号 | MDBX2 符号集合为 MDBX3 符号集合子集 |
| ABI-05 | UniFFI metadata | 既有函数、Record、Enum 和 Error 完全一致 |
| ABI-06 | 动态依赖 | 不增加未声明的 MDBX 动态库 |
| ABI-07 | Android 页大小 | ARM64/x86_64 支持 16 KiB page；仅面向旧 32 位进程的 ARM32 保持 NDK 4 KiB 对齐 |
| ABI-08 | build ID | 每个 strip 后 SO 保留唯一 build ID |

## 3. 零客户端修改

建立一份只使用 MDBX2 生成 bindings 的冻结 Android 测试应用。测试过程只替换三种 ABI 的 SO 文件，不修改 Kotlin、Gradle、JNI 目录或数据库。

| 编号 | 测试 | 通过条件 |
|---|---|---|
| DROP-01 | 应用启动 | 动态库正常装载，无符号缺失 |
| DROP-02 | 创建与打开 | 新 vault 创建、关闭和重新打开成功 |
| DROP-03 | 现有 vault | schema 17 vault 原位打开成功 |
| DROP-04 | CRUD | 既有 Collection、Entry、Object 和 Attachment 方法成功 |
| DROP-05 | 历史 | commit、object version 和 history 返回保持一致 |
| DROP-06 | Tiga | Sky、Multi、Power 和拒绝行为保持一致 |
| DROP-07 | snapshot | 创建、预览、恢复和清理成功 |
| DROP-08 | 同步 | 完整状态、增量、resume 和 Blob 操作成功 |
| DROP-09 | 错误 | 旧 bindings 能解码全部既有 Error 变体 |
| DROP-10 | 反向恢复 | 换回 MDBX2 SO 后仍能打开未使用新关键能力的 vault |

## 4. Vault 与迁移

| 编号 | 输入 | 验证内容 |
|---|---|---|
| DB-01 | MDBX1 release fixture | inspection 无写入，升级后稳定 ID、密文、附件、snapshot 和历史保持 |
| DB-02 | MDBX1-DRAFT fixture | 顺序升级到 MDBX2，重复升级幂等 |
| DB-03 | MDBX2 schema 2 至 16 fixture | 逐级迁移到当前 schema，header 最后更新 |
| DB-04 | MDBX2 schema 17 fixture | 打开和关闭不产生无关写入 |
| DB-05 | 未知非关键扩展 | 保存、同步、备份和恢复后字节保持 |
| DB-06 | 未知关键扩展 | 读取检查可用，写入明确拒绝 |
| DB-07 | 损坏 header 或 HMAC | 首次迁移写入前失败，原文件保持 |
| DB-08 | 迁移中断注入 | 事务回滚，format 和 schema 标记保持一致 |
| DB-09 | external Blob reference | 数据库引用与 Provider inventory 一致 |
| DB-10 | content manifest | MDBX2 token 继续验证，新写入使旧 token 失效 |

## 5. 混合版本同步

至少建立 MDBX2 与 MDBX3 两设备、三设备和五设备测试。交付顺序覆盖顺序、逆序、重复、延迟和并发。

| 编号 | 场景 | 通过条件 |
|---|---|---|
| SYNC-01 | MDBX3 发送给 MDBX2 | 协商到共同协议，数据一致 |
| SYNC-02 | MDBX2 发送给 MDBX3 | 旧 bundle 和 state 可读 |
| SYNC-03 | MDBX3 新 capability 被省略 | 采用兼容表示，关键状态不丢失 |
| SYNC-04 | commit 重放 | 相同身份幂等，不同认证内容拒绝 |
| SYNC-05 | state delta 乱序 | 最终状态与交付顺序无关 |
| SYNC-06 | tombstone acknowledgement | 因果规则和设备撤销保持单调 |
| SYNC-07 | 中断恢复 | transfer ID、segment 和 digest 绑定正确 |
| SYNC-08 | 大型 bootstrap | 分段有界，重启后继续 |
| SYNC-09 | Blob transfer | manifest、lease、chunk 和 checkpoint 一致 |
| SYNC-10 | capability 缺失 | 自动采用 MDBX2 完整状态方式 |

## 6. 安全

| 编号 | 测试 | 通过条件 |
|---|---|---|
| SEC-01 | KDF 参数 | 三种 Tiga 模式与 MDBX2 规范一致 |
| SEC-02 | 明文披露 | 授权先于 SQL BLOB 载入和解密 |
| SEC-03 | key epoch | 轮换期间旧数据可读，完成后引用一致 |
| SEC-04 | 会话失效 | 超时、后台锁定和撤销影响全部 reader |
| SEC-05 | 设备证据 | 未验证设备能力不能满足较高保证 |
| SEC-06 | 日志检查 | 无密码、密钥、payload、token 和附件明文 |
| SEC-07 | 异常输入 | bundle、cursor、manifest、KDBX 和 mafile 保持有界 |
| SEC-08 | panic 边界 | 畸形输入返回错误，客户端进程保持可用 |
| SEC-09 | audit | Tiga 决定、例外、恢复和管理操作审计完整 |
| SEC-10 | 降级尝试 | 未授权的较弱策略和协议被拒绝或明确记录 |

## 7. 功能完整性

以 MDBX2 FFI 导出清单和 capability manifest 生成机器可读比较。每个既有函数至少保留一个成功测试和一个失败测试。批量操作还需验证只产生一个用户级 commit。

功能组至少包括 vault、Collection、Object、Relation、Label、Attachment、History、Conflict、Snapshot、Security、Lifecycle、Sync、Blob 和 generic write operation。

## 8. 体积与性能

| 编号 | 指标 | 强制条件 |
|---|---|---|
| PERF-01 | strip 后 SO | 每个 ABI 不大于 MDBX2 基准 |
| PERF-02 | SO 装载 | p95 不慢于 MDBX2 |
| PERF-03 | vault 打开 | 排除 KDF 后 p95 不慢于 MDBX2 |
| PERF-04 | metadata 首屏 | p95 不慢于 MDBX2，返回集合一致 |
| PERF-05 | 批量写入 | p95 不慢于 MDBX2，commit 数量一致 |
| PERF-06 | 增量同步 | 时间和峰值内存均不退化 |
| PERF-07 | 大型 bootstrap | 峰值内存保持有界并支持恢复 |
| PERF-08 | Blob | 峰值内存与 chunk 大小相关 |
| PERF-09 | 数据库增长 | 相同操作后的页数和字节增长不显著增加 |
| PERF-10 | 能耗 | Android 长同步 CPU 时间不高于 MDBX2 |

第一阶段优化目标采用 `03-architecture-and-size.zh-CN.md` 与 `04-performance-and-experience.zh-CN.md` 中的比例。强制条件负责阻止退化，目标比例负责评价优化效果。

## 9. 平台与工具链

1. Rust 版本、Cargo.lock、Android NDK 和 linker 版本固定并写入报告。
2. Linux CI 执行 fmt、clippy、workspace tests、core feature tests 和 ABI manifest tests。
3. Android 三 ABI 执行构建、ELF 检查和冻结应用 smoke tests。
4. Windows、Linux 和 macOS 执行原生库加载与主要 FFI 测试。
5. sanitizer、fuzz 和 crash injection 使用非发布构建执行，结果关联候选 commit。

## 10. 发布资产

发布可以使用以下外部名称：

```text
mdbx3-runtime-android-arm64-v8a.so
mdbx3-runtime-android-armeabi-v7a.so
mdbx3-runtime-android-x86_64.so
mdbx3-runtime-android-jniLibs.zip
```

ZIP 内部保持：

```text
android-jniLibs/arm64-v8a/libmdbx_ffi.so
android-jniLibs/armeabi-v7a/libmdbx_ffi.so
android-jniLibs/x86_64/libmdbx_ffi.so
```

每个资产同时发布 SHA-256、runtime manifest、ABI manifest、capability manifest、工具链版本和体积性能报告。

### 10.1 ABI 薄包

Android 的单个 ELF SO 不得声明为跨 CPU 通用库。正式发布还必须生成三个独立薄包，使 APK、F-Droid variant 或 App Bundle split 只安装一个匹配 ABI 的 `libmdbx_ffi.so`。

| 编号 | 测试 | 通过条件 |
|---|---|---|
| DIST-01 | 薄包成员 | 每个 ZIP 恰好包含一个 ABI 的 SO |
| DIST-02 | ELF 身份 | SO 的 machine 与包名、目录一致 |
| DIST-03 | 报告绑定 | ZIP、独立 SO 与 artifact report 的 SHA-256 一致 |
| DIST-04 | ABI 一致 | 三个薄包均通过同一 MDBX2 ABI baseline |
| DIST-05 | 全量回退 | universal ZIP 仍含三 ABI 标准 jniLibs 结构 |
| DIST-06 | 单设备口径 | 单设备下载与安装体积只统计一个 ABI |
| DIST-07 | APK linker smoke | `android-smoke` APK 只加载匹配 ABI 的 `libmdbx_ffi.so`，并读到 contract 30 |

完整分发规则见 `07-android-abi-distribution.zh-CN.md`。

## 11. 完成定义

MDBX3 Runtime 进入正式发布状态需要同时满足：

1. 本矩阵全部强制项通过。
2. 三 ABI 的冻结 Android 应用仅替换 SO 后运行成功。
3. MDBX1、MDBX1-DRAFT 和 MDBX2 fixture 无数据差异。
4. MDBX2 与 MDBX3 混合版本同步通过。
5. 每个 ABI 的 SO 体积不大于 MDBX2 基准。
6. 启动、读取、写入和同步没有统计意义上的退化。
7. Tiga、密钥、审计和授权规则保持。
8. 发布资产、内部 basename、bindings 和文档一致。
