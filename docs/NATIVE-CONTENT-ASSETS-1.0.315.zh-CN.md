# 证件副本与原生 API 令牌附件 · 1.0.315（未发布）

## 范围

密码项目支持完整证件副本的编辑和详情；原生 API 令牌支持附件及带卡面、照片、附件的卡片／笔记副本。验证器任意自定义字段不纳入本轮；原有 OTP 参数、备注和关联保持现有能力。Bitwarden/Vaultwarden 的 SSO 与 Passkey **账号登录**不纳入本轮，这与 Monica 为其他服务提供通行密钥无关。

## 证件副本

继续使用 `monica.content.wallet.document` 自定义字段中的 `EmbeddedWalletContent` JSON 字符串，`kind=DOCUMENT`；没有引入另一套证件格式。复用 `AddEditDocumentScreen` 和 `DocumentDetailScreen`，内嵌编辑不显示独立数据库选择、不修改原始证件。

- `data` 保留 `DocumentData` 的已知键及未知键；姓名、结构化姓名、地址各级、证件号、签发和有效期、联系方式、自定义字段分别保留。
- 普通修改只覆盖发生变化的键。未知自定义字段类型及其元数据原样保留；无法理解的非数组字段容器禁止被新增字段覆盖。
- 卡面、正面、反面和其他附件都保存独立原始字节；显示名不作为文件系统路径。副本不依赖来源条目的存在。
- 所有图片／附件准备并校验成功后才提交新副本。取消或读取失败保留原副本、原文件及可重试草稿。

## 原生 API 令牌

原生类型仍为 `api-token`，正文使用既有 `ApiTokenPayload`，保持 API Token 与 API Key 概念独立。卡片副本和备注／自定义字段继续放在 `monica:api-token:fields:v1` 标签元数据里，不把 Android 扩展写进严格 Gateway payload。

附件使用 MDBX 的原生附件对象，归属真实 token entry ID。应用内记录：`id, fileName, mimeType, size, sha256`。`sha256` 是原始字节的 SHA-256；不是设备内密文哈希。副本中的资产通过独立 `wallet-*` 文件名映射，保留 `displayName, role, size, sha256`。

保存将正文、标签元数据及附件写入同一个 MDBX composite 操作。先核对编辑时的原条目与附件集合，避免覆盖同步期间发生的变更；先读取校验所有新增文件，成功后再替换引用。跨库移动先完成目标写入和校验，再带原版本检查删除来源；失败保留来源。

当前边界：每次原子保存中新增／移动的文件总量不超过 64 MiB，每个令牌最多 128 个附件。超限明确失败，不截断、不删除原始条目。已有普通附件不会在仅编辑文字时被重新写入。

## 数据库 ZIP 导出／恢复

数据库加密 ZIP 的 `native_api_tokens.json` 延续 token 列表格式，新增可选 `attachments` 与 `attachmentCount`，旧的无附件条目仍可读取。附件对象为：

```json
{
  "fileName": "wallet-example",
  "mimeType": "application/octet-stream",
  "size": 3,
  "sha256": "原始字节的64位十六进制SHA-256",
  "contentBase64": "AQID"
}
```

这是归档封装，不能当作 MDBX token 正文。`database_export.json` 同时记录 `nativeTokenCount`：存在此计数时，恢复必须拒绝原生令牌文件缺失、重复、位置不符、列表数量不符等归档；没有此字段的旧归档继续兼容。还原前核对数量、大小、摘要和副本引用，随后通过原生原子写入恢复；即使条目会被去重，也必须先验证附件。归档内原生令牌附件合计限制为 64 MiB；编码 JSON 上限为 96 MiB，导出和导入都执行上限。带附件的令牌要求启用图片／附件导出且使用加密 ZIP；不满足时明确失败。尚不支持保真还原附件的 KDBX 导出必须拒绝带附件原生令牌，普通无附件令牌保持原有能力。

这里的 ZIP 是数据库导出归档；应用全量备份是另一种格式，不表示全量备份已经包含全部原生数据库。WebDAV/OneDrive 的传输、MDBX 数据库同步及 MDBX 原生备份也是独立链路，不能用一个通过代替另一个。旧客户端可能忽略新归档附件扩展，不宣称旧版本能完整还原。

## 原生运行库修复

基于既有 MDBX commit `90005c8c608c952093a4522ffa507a562e2e39a4` 和四个既有补丁，加上 `90005c8-composite-commit-kind.patch`。原复合接口错误地固定使用 `change`，带附件的移动因此被拒绝；现在按命令组合推导提交类型，在需要时采用 `multi`，保持一个事务、一个提交。没有改变数据库格式、FFI 接口、条目身份或 TiGA 权限。普通版记录三 ABI 库哈希，F-Droid 通过 `buildMdbxFfiFromSource` 从 vendored Rust 源码构建，不引入预编译库。

## 验证

2026-09-30，两版 Debug 主包及测试包构建通过；各 55 项单元测试、25 项设备回归、1 项真实本机 WebDAV 集成测试通过，无失败或跳过。

- 设备：复用公共 Android32 x86_64 AVD。覆盖证件完整副本和真实编辑／详情、取消与失败保留、未知字段、原生令牌文件复制和移动、快照重开／同步、加密数据库 ZIP 恢复及损坏／缺文件／缺清单拒绝恢复；同时收尾二维码模板和默认内联 OTP 测试。
- 两版正常 MainActivity 冷启动成功，启动后持续观察 10 秒进程仍存活；这是有限启动冒烟检查，不替代长期稳定性验证。
- WebDAV：使用隔离的本机 WsgiDAV 服务及合成数据，验证两实例收发、原始附件字节、带附件令牌移动、冲突和重开。不是用户云服务验证；本轮没有执行真实登录的 OneDrive 测试。
- 原生 Rust：59 项 FFI 主机测试通过，晚期附件错误回滚用例另行重跑通过；实际绑定的 239 项校验和与 contract30 验证通过。普通版及 F-Droid 三 ABI 产物均通过 539 个导出符号检查，ARM64／ARM32 未做设备运行验证。
- 未清除应用数据、Keystore 或公共 AVD 数据盘。不承诺未测试旧客户端、新版本格式或其他 Android 版本完全兼容。
- 普通版首轮连续验收在第 10 项开始时遇到一次 ART `ClassLinker::DoResolveType` SIGSEGV，前 9 项已通过。相同 APK 的两项单独复测及原顺序完整重跑通过，没有改代码或清缓存；根因尚未确定，保留崩溃日志与中断记录，不把首轮计为通过，也不据此保证其他设备绝无崩溃。

逐项日志、安装包及源码哈希见 [验证记录](pending-content-315-verification.json)。设计见 [本地 M3E Canvas 草图](design/pending-content-315/README.md)。
