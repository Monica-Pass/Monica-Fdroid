# 自动填充候选索引与验证

## 实现边界

`BitwardenLikeAutofillMatcherNg` 缓存每条记录的包名、域名、根域名和规范化标题。256 条及以上使用 `RustAutofillCore` 保存候选索引；后续查询只传当前目标，返回当前列表中的位置。Kotlin 仍执行严格模式、域名策略、包名开关、评分、去重和排序。启发式模式保留完整扫描，原生库不可用或输入超限时回退到 Kotlin。

索引不接收密码、账号、TOTP 或令牌。缓存也不持有 `PasswordEntry`，返回值始终取自本次传入的记录，因此密码、账号、收藏和更新时间的变动不会使用旧值。条目重排时按 ID 复用已解析元数据，并更新原生位置；新增、删除和元数据变化会重建索引。

加载库、建立索引和查询仅在后台执行。锁定、熄屏、服务销毁或断开连接时清理缓存；清理不会等待正在进行的大列表匹配，且会使正在构建的缓存失效。主线程触发的原生索引释放也转到后台。协议校验限制行数、输入字节、字段长度和索引展开大小，长域名的后缀不能无限扩大索引。

## 实测

2026-09-13，Android API 32 x86_64 模拟器，Debug Kotlin、Release Rust。使用合成数据，在同一匹配器中比较禁用缓存、仅 Kotlin 元数据缓存、缓存加真实 JNI 候选索引。每组预热 3 次、采样 11 次，交替测量顺序，包含有匹配和无匹配的不同查询；每次都核对完整输出。测试必须观测到成功的原生查询，静默回退不能通过。

预热后的完整匹配耗时（中位数 / p95，毫秒）：

| 条目数 | 无缓存 Kotlin | Kotlin 缓存 | Rust 索引 |
| --- | ---: | ---: | ---: |
| 1,000 | 15.039 / 19.136 | 0.517 / 0.896 | 0.212 / 0.536 |
| 10,000 | 109.534 / 121.828 | 4.552 / 5.998 | 0.682 / 1.528 |
| 50,000 | 655.620 / 1074.874 | 26.149 / 32.640 | 3.001 / 3.853 |

首次匹配包含元数据准备和建立索引，单次观测如下（毫秒；原生库已加载）：

| 条目数 | 无缓存 Kotlin | Kotlin 缓存 | Rust 索引 |
| --- | ---: | ---: | ---: |
| 1,000 | 17.31 | 15.42 | 22.62 |
| 10,000 | 243.54 | 196.38 | 267.24 |
| 50,000 | 553.04 | 665.81 | 894.13 |

主要收益来自重复请求减少解析和候选扫描。首次建立索引有成本；上述结果不包含 Room 读取、来源筛选、解密和系统建议面板渲染，也不能等同于真机 Release 的整体响应提升。11 个样本的 p95 使用最大样本，仅用于本次对照，不作为性能门槛。

## 覆盖范围

- `BitwardenLikeAutofillMatcherNgTest`：现有匹配规则。
- `AutofillMetadataCacheTest`：更新凭据和排序、重排、删除、清理与构建并发。
- `AutofillNativeIndexInstrumentedTest`：真实 JNI 与无缓存实现对照，32 组策略组合、多网站、Unicode、Android 包名、域名边界、修改、重排、清理、超限回退及性能。
- Rust `autofill` 单元测试：协议损坏、长度边界、后缀预算、句柄隔离和幂等释放。

## 复现

先按 `rust-jni/README.md` 为目标 ABI 构建原生库，再运行：

```sh
./gradlew :app:testDebugUnitTest \
  --tests 'takagi.ru.monica.autofill_ng.BitwardenLikeAutofillMatcherNgTest' \
  --tests 'takagi.ru.monica.autofill_ng.AutofillMetadataCacheTest'
./gradlew -PincludeX86TestAbi :app:assembleDebug :app:assembleDebugAndroidTest
adb shell am instrument -w -r \
  -e class takagi.ru.monica.autofill_ng.AutofillNativeIndexInstrumentedTest \
  takagi.ru.monica.test/androidx.test.runner.AndroidJUnitRunner
```

安装对应应用和测试 APK 后执行仪器测试。逐次样本保存至应用外部文件目录的 `autofill-performance/audit-comparison.json`。本地仓库嵌套在其他 Cargo workspace 中时，将 `rust-jni` 和 `rust-crypto` 原样复制到隔离 workspace；构建后核对源文件及 APK 内原生库的哈希。原生 `.so` 是构建产物，不提交。
