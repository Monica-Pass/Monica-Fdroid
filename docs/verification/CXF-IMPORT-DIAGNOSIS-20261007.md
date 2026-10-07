# 应用间凭据导入调查（2026-10-07）

> 本文保留修复前的调查结果；后续实现和最终测试见[修复验证记录](CXF-IMPORT-FIX-20261007.md)。

本轮仅调查、运行合成解析测试并记录结果，未修改生产代码。Bitwarden 导入目标已经确认为 KDBX，因此导入后显示 KeePass 格式属于正常行为。

## 已确认的问题

### 接收页的「无法导入」混合统计多种原因

反馈截图中，Bitwarden 显示 524 个密码、23 个 Passkey、72 项无法导入；Google Play 服务显示 5 个密码、0 个 Passkey、4 项无法导入。

`ImportDataScreen.kt` 在系统传输返回后调用 `CxfCredentialCodec.decode`，接收页直接显示解析结果的 `passwordCount`、`passkeyCount` 和 `skippedCount`。这个阶段尚未向目标数据库写入，不能将此处的跳过归因于 KDBX。

解析器仅识别 `basic-auth`、`note`、`passkey`。其他类型（例如 TOTP）计入 `UNSUPPORTED_CREDENTIAL`；Passkey 还可能因扩展、缺少必填字段或无法解析私钥而跳过。计数主要按凭据累计，并不等于被跳过的密码条目数或 Passkey 数量。同一条目可以成功接收密码，同时跳过其中另一类凭据。

底层已有原因分类，但 `CredentialTransferUi.kt` 只展示总数，当前传输日志也没有记录成功接收后的分类统计。因此，仅凭截图无法确定 72 项或 4 项分别是什么。

### Passkey 扩展判断过粗

`CxfCredentialCodec.kt` 对 `fido2Extensions` 的现有判断是：字段缺失或空对象允许导入，任何非空对象或其他显式值均跳过整条 Passkey。

合成数据已经复现：合法的 `{"payments":false}` 也会被归为 `PASSKEY_EXTENSION`。`null` 同样被跳过，但显式 `null` 不属于所核对版本的 FIDO CDDL 允许类型，不能将接受它说成规范要求。

真正包含 PRF／hmac-secret 状态的凭据不能通过删除扩展强行导入。原始种子必须保留并用于后续认证，否则可能出现能登录、却无法解密原站点数据的问题。`credBlob`、`largeBlob` 等扩展也需要逐项明确支持及保留策略，不能照搬其他客户端丢弃扩展的处理。

### `CXF Android apps` 原始 JSON 被当作普通字段展示

截图对应的调用链已确认：

1. CXF 条目的 `scope.androidApps` 包含应用包名、应用名称及可选签名证书指纹。
2. `TargetedImportCoordinator.exchangeContent` 将非空数组保存为名为 `CXF Android apps` 的普通自定义字段，`isProtected=false`。
3. `PasswordDetailScreen.kt` 的内部字段过滤未排除该字段，因此详情页显示整段 JSON。
4. `CredentialExchangeExporter.kt` 会读取它，在再次导出时恢复关联应用及签名约束。

这些内容不是账号密码或 Passkey 私钥。只有源数据带有非空 Android 应用范围时，才会生成这个字段，因此只有部分条目出现。

`CxfAndroidAppScope.bindings` 会校验包名；提供了证书时，还要求本机安装应用的签名匹配（支持 SHA-256、SHA-512），不会无条件忽略证书建立绑定。未建立本机关联时仍保存原始范围信息。

后续应将这种数据作为内部元数据保留，在用户界面通过已有应用关联能力展示可理解的信息；详情、编辑及再次导出都需要覆盖。不能为消除 JSON 显示而直接删除字段及其内容。

## Google 的证据边界

目前没有取得本次用户的真实 Google CXF 数据或解析原因分类，用户本人也尚未充分复现，故不能声称已经定位这 4 项的实际根因，更不能宣称 Google Passkey 普遍无法导入。

公开的第三方 Bitwarden 分支问题 [nuri-com/bitwarden-passkey-prf #86](https://github.com/nuri-com/bitwarden-passkey-prf/issues/86) 及其[实机复测评论](https://github.com/nuri-com/bitwarden-passkey-prf/issues/86#issuecomment-5034195391) 报告，Google 导出中至少一个 Passkey 带有 `credBlob`／`largeBlob`，且该凭据未带 `hmacCredentials`。这种非空扩展对象会命中 Monica 当前的跳过分支，是相关的公开兼容性线索；它不是本次用户样本，也不能用于推断所有 Google Passkey 的扩展结构。

同一问题最初报告了负时间戳导致另一套 Rust 解析器整批失败。Monica 当前对条目的 `creationAt`／`modifiedAt` 负值使用默认时间；根级 `timestamp` 仍要求非负，否则整份文档失败。反馈截图已经进入接收计数页，不能用那个整批解析错误解释这里的部分跳过。

## 已运行的验证

普通版 JVM 测试共 21 个，失败 0、错误 0：`CxfCredentialCodecTest` 16 个、`ImportDestinationTest` 4 个，以及覆盖下列 12 个情形的诊断测试 1 个。

| 合成输入情形 | 当前结果 |
| --- | --- |
| 未提供扩展 | 接收 Passkey |
| 空扩展对象 `{}` | 接收 Passkey |
| 显式扩展 `null` | `PASSKEY_EXTENSION` |
| `payments:false` | `PASSKEY_EXTENSION` |
| `payments:true` | `PASSKEY_EXTENSION` |
| 包含 HMAC 种子 | `PASSKEY_EXTENSION` |
| `hmacCredentials:null` | `PASSKEY_EXTENSION` |
| 缺少私钥字段 | `INVALID_CREDENTIAL` |
| 编码及长度通过、但不能解析的私钥 | `PRIVATE_KEY` |
| 缺少 username | `INVALID_CREDENTIAL` |
| 1 个密码、1 个 Passkey、72 个不支持类型 | 接收前两项，72 次 `UNSUPPORTED_CREDENTIAL` |
| 带 Android 应用范围的密码 | 完整保留为普通自定义字段 |

72 个不支持类型是人为构造的诊断样本，仅证明混合计数行为，不代表用户跳过的 72 项确实是 TOTP。

普通版与 F-Droid 的解析器、私钥解析、应用范围处理、目标导入转换、计数 UI 及密码详情文件已比对一致（忽略换行差异）。本轮没有运行 F-Droid 专属测试，也没有运行真实 Google／Bitwarden 应用间传输或导入后的站点认证测试；21 个测试通过不等于这些真实流程已验证。

诊断源码通过临时 Gradle init script 加入 test sourceSet，未改项目构建配置。工作区证据保存在 `.codex-tasks/passkey-import-diagnosis-20261007/`：`probe-src/`、`probe.init.gradle`、`probe-build.log` 和 `test-results.json`。

复现命令（从普通版 Android 工程目录执行）：

```powershell
$env:JAVA_HOME = 'C:/jdk-17.0.1'
$env:GRADLE_USER_HOME = 'D:/GradleRepository'
./gradlew.bat :app:testDebugUnitTest `
  --tests '*CxfCredentialCodecTest' `
  --tests '*ImportDestinationTest' `
  --tests '*CxfImportCompatibilityProbeTest' `
  -I 'C:/Users/joyins/Desktop/Monica-all/.codex-tasks/passkey-import-diagnosis-20261007/probe.init.gradle' `
  -PincludeX86TestAbi --max-workers=2 --console=plain
```

## 后续修复建议

1. 接收页展示跳过原因分类，并记录不含凭据内容的分类计数。区分解析跳过与写入失败；无需记录完整 CXF、密码、私钥或种子。
2. 按扩展的具体语义判断支持情况，优先解决 `payments:false` 等过度拒绝；不丢弃真实扩展状态来提高导入数量。
3. 保留 Android 应用元数据及签名约束，修正详情和编辑展示，验证已有导入条目编辑后信息仍保留、再次导出仍完整。
4. 有真实 Google 测试条件后，验证源端导出数量、解析分类、目标写入结果及导入后的认证；依赖 PRF 的场景还需要验证原有解密能力。

## 规范依据

- [FIDO Credential Exchange Format 1.0，2025-08-14](https://fidoalliance.org/specs/cx/cxf-v1.0-ps-20250814.html)：Passkey 必填字段、FIDO2 扩展及 HMAC 凭据语义。
- [Bitwarden CXF Passkey 数据定义，固定提交](https://github.com/bitwarden/credential-exchange/blob/22a87d9852ae64a659ae37774bd9194fd362bdde/credential-exchange-format/src/passkey.rs)：可选扩展省略序列化；该定义不构成 Google 实际输出格式的证据。

本轮不更改发行说明，因为尚未实现修复。
