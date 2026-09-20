# SSH / GPG：Android 与 CLI 对接补充

日期：2026-09-20。范围：Android 与共享 Rust MDBX2 格式；不修改 CLI。
原始 `ssh-key-cross-client-handoff.md` 保留作为历史分析，本文件修正其不完整的结论。

## 结论与 Android 修改

确实需要修 Android，但不是只改三行：旧 MDBX 和 Rust MDBX2 是两条写入路径，数据库完整导出、导入及凭据交换的内部投影也需要携带 SSH 数据。

Android 本次补齐上述路径的 `ssh_key_data`。缺失/null 时保留既有 Room 材料；显式空字符串表示清空。接受 `sshKeyData` 别名及 JSON 对象值，正常写出一律为 snake_case 的 JSON 字符串。两个非 null 键并存时 snake_case 优先（包含显式空字符串）。非法字段类型拒绝导入。

SSH 编辑器保留 schema、未知内层 JSON 属性、原有 format 和私钥末尾换行；私钥不再进入可恢复的界面实例状态。无需 Room 迁移。注意：未知**外层** payload 属性仍不保证经过 Android 编辑后保留，不能据此声称任意扩展无损。

原文关于 `portableSensitiveValueForMdbx` 添加 `mdbx:v1:` 前缀的说法不符合当前源码：当前实现识别并解开 Android 本机密文，解密失败会报错。共享 payload 不能塞入 Android 本机加密字符串。SSH JSON 和 GPG 私钥受 MDBX 的加密封装保护；不要在日志中输出 payload。

## 共同类型与身份契约（必须实现）

SSH、GPG 均使用原生 `entry_type = "login"`，分别设置 `login_type = "SSH_KEY"` / `"GPG_KEY"`。不要新建原生 `ssh-key` / `gpg-key` 类型，否则 Android 现有密码导入路径不识别。

CLI 创建条目时：

1. 创建逻辑 ID `password:<随机 UUID>`，写入 payload 的 `monica_entry_id`。
2. native entry ID 使用 Android 的 `mdbx2PhysicalEntryId`：对 UTF-8 字节 `monica-entry:<vaultId>:<逻辑ID>` 求 MD5，将 digest 第 6 字节高四位置为 3、第 8 字节高两位置为二进制 10，格式化 UUID。这等于 Java `UUID.nameUUIDFromBytes`，**没有 namespace 前缀**，不能直接用普通库的 namespace UUID-v3 函数。
3. `room_id = 0`。不要借用 Android Room 数字 ID。
4. 原生 collection ID 与 `mdbx_folder_id` 指向同一文件夹；先采用明确创建的文件夹，避免根目录约定差异。
5. 编辑已有 Android 条目时保留物理 ID、`monica_entry_id`、分类、其他字段和自定义字段。

身份算法测试向量：vaultId=`11111111-1111-1111-1111-111111111111`，逻辑 ID=`password:22222222-2222-2222-2222-222222222222`，物理 ID 应为 `48f94648-e2a0-3d3d-91b8-77ff9895b136`。

只有随机原生 UUID、没有上述 adapter 元数据的普通 login 尚未验证可安全在 Android 编辑，存在生成第二条记录的风险。API token 的原生身份支持不能推导为 login 的兼容性。

## SSH payload

```json
{
  "kind": "password",
  "monica_entry_id": "password:<uuid>",
  "room_id": 0,
  "mdbx_folder_id": "<native collection uuid>",
  "login_type": "SSH_KEY",
  "website": "",
  "username": "",
  "password_plain": "",
  "notes": "user@host",
  "ssh_key_data": "<下方对象序列化为 JSON 字符串>"
}
```

内层：

```json
{
  "schema": "monica.ssh-key.v1",
  "algorithm": "ED25519",
  "keySize": 256,
  "publicKeyOpenSsh": "ssh-ed25519 <base64> user@host",
  "privateKeyOpenSsh": "<完整 PEM，保留末尾换行>",
  "fingerprintSha256": "SHA256:<公钥 wire blob 的 SHA256，无填充 Base64>",
  "comment": "user@host",
  "format": "OPENSSH"
}
```

旧数据可不含 schema，按 v1 读取。内层键名保持 camelCase；保存时不要丢弃未知属性。Android 生成 Ed25519 或 RSA 2048/3072/4096（默认 3072）。Ed25519 私钥头为 OPENSSH PRIVATE KEY，RSA 为 RSA PRIVATE KEY（PKCS#1）；现有 format=OPENSSH 并不表示 RSA 必须使用 openssh-key-v1 容器。以实际 PEM 头识别容器，不要批量改写旧密钥。

私钥不做 trim，不强制重新编码。公钥行的注释、条目标题和外层 notes 是不同概念。编辑 comment 时应遵循产品语义同步公钥注释，而不要改变公钥 wire blob 或指纹。

## GPG payload：沿用现有实现

无需引入 `gpg_key_data`。使用 `login_type = "GPG_KEY"`；私钥 ASCII armor 位于 `password_plain`，公钥-only 时为空字符串。Android Room 对私钥进行本机加密，共享格式不能传输该本机密文。已有 OpenPGP 口令保护保留在私钥 armor 内，不自动去除。

公钥和元数据存入 `custom_fields` 数组，每项为：

```json
{"title":"monica_gpg_type","value":"GPG_KEY","is_protected":false,"sort_order":0}
```

字段：

| title | value |
|---|---|
| monica_gpg_type | GPG_KEY |
| monica_gpg_fingerprint | 主密钥指纹 |
| monica_gpg_user_id | 用户标识 |
| monica_gpg_encoding | base64 |
| monica_gpg_public_0000 | 公钥编码第 1 块 |
| monica_gpg_public_0001 | 第 2 块（如需） |

将**完整公钥 armor 的 UTF-8 字节一次性 Base64 编码**后，每 2000 字符分块。索引从 0000 开始，4 位补零且连续无缺口。不要逐块独立 Base64 编码。Android 解码上限 1 MiB，兼容旧的无 encoding 原文分块，但新实现应写 base64。替换公钥时删除所有旧分块，保留无关自定义字段。私钥绝不存入这些公钥自定义字段。

Android 当前生成 RSA 3072/4096、签名主密钥及加密子密钥，SHA256，可选 AES256 私钥口令保护。导入其他算法需分别验证，不能以生成器能力推断全部互通范围。

## CLI 会话建议

请依此实现存储适配；保留原有 CLI 权限及秘密输出约束。本文件不授权修改既有 AI/MCP 安全策略。SSH/GPG 私钥仅在用户明确要求时输出，不落命令行参数、日志或错误文本。

验收：CLI 新建 → Android 导入 → Android 只改标题/备注 → CLI 重开，同一条记录、同一分类、私钥/公钥/指纹及扩展不变；反方向同样测试。还需检查 GPG 有口令/无口令/仅公钥、缩短公钥后的旧分块清理、旧 SSH 缺字段不清空，以及 OpenSSH 工具读取 Ed25519/RSA。

Android 的模拟 CLI 格式测试只证明共享文件契约，并非实际 CLI 可执行程序互通。Windows、Avalonia、KMP 不在本轮修改和实测范围。

## 本轮验证

- JVM / Robolectric API 29：`MdbxKeyPayloadTest` 5 项通过，0 失败、0 跳过。
- 实际调用 Windows OpenSSH `ssh-keygen -y` 和 `ssh-keygen -E sha256 -lf`：Android 生成的 Ed25519 和 RSA 2048 私钥可读取，公钥 wire blob 与指纹一致。RSA 的 PKCS#1 容器不需要为互通而强制改成 OpenSSH 容器。
- 覆盖缺失/null/显式清空、snake/camel 别名、对象导入、非法类型拒绝、schema 与内层扩展保留、私钥换行及确定性 ID 向量。
- 公共虚拟机 `Monica_Issue136_API_32`：`MdbxKeyInteropTest.nativeKeysImportEditReopenAndExportWithoutLoss` 通过（1 项，4.903 秒）。通过 Rust 原生写接口创建符合上述 CLI 契约的 SSH 与带口令 GPG 条目 → 无 Room 兜底时完整导出 → Android snapshot 导入到 Room → Android 编辑保存 → 重新打开 MDBX2 → 再次完整导出。验证两条记录未重复、SSH 公私钥及指纹和未知字段保留、GPG 私钥与分块公钥保留。
- 测试没有启动实际 CLI，也没有实测其他桌面客户端、旧 MDBX 的设备往返或全部 UI 操作；这些不能算本轮已通过项目。
- Android 最终 `assembleDebug` 与 `assembleDebugAndroidTest` 构建成功，版本保持 1.0.313；已检查最终 SSH 编辑器字节码包含状态修正。设备兼容性测试使用前一轮同数据层构建，最后调整仅涉及 SSH 编辑器恢复状态，未追加 UI 自动化测试。
- F-Droid 对应 9 个生产源码文件及本次测试、说明已同步并核对；本轮没有另跑 F-Droid 构建。
- 证据：工作区 `.codex-tmp/key-interop-build.log`、`key-interop-final-build.log`、`key-interop-device.log`，以及 Android `app/build/test-results/testDebugUnitTest/TEST-takagi.ru.monica.repository.MdbxKeyPayloadTest.xml`。设备临时数据库已清理，本次启动的公共虚拟机已停止，保留 AVD 配置和数据盘。

## 代码定位

均相对 `Monica-main/Monica for Android/`：

- `app/src/main/java/takagi/ru/monica/repository/MdbxKeyPayload.kt`：兼容读入规则。
- `app/src/main/java/takagi/ru/monica/repository/Mdbx2Repository.kt`、`MdbxVaultStore.kt`：两条写入路径。
- `app/src/main/java/takagi/ru/monica/viewmodel/MdbxViewModel.kt`：导入 Room。
- `app/src/main/java/takagi/ru/monica/transfer/DatabaseExportSnapshot.kt`：数据库完整导出。
- `app/src/main/java/takagi/ru/monica/data/model/SshKeyModels.kt`：schema 和扩展保留。
- `app/src/main/java/takagi/ru/monica/data/model/GpgEntryFields.kt`：现有 GPG 编码契约。
- `app/src/main/java/takagi/ru/monica/repository/MdbxRepositoryFactory.kt`：逻辑与物理 ID 映射。
- `app/src/test/java/takagi/ru/monica/repository/MdbxKeyPayloadTest.kt`：单元与 OpenSSH 验证。
- `app/src/androidTest/java/takagi/ru/monica/repository/MdbxKeyInteropTest.kt`：共享 MDBX2 文件往返。
