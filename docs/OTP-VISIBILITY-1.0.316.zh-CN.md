# 验证器可见性与数据保留修复（1.0.316）

## 修复范围

- 非空的密码验证器密钥无法解密时，继续显示对应验证器，保留原始 payload；不把空密钥或受保护密文当作可用验证码。解锁状态或源数据改变后重新读取。
- 不可读卡片使用占位内容，复制、二维码和编辑操作提示重新解锁；不生成伪造的 `000000` 验证码，不打开空白编辑表单。验证码选择器过滤不可读项。
- mOTP 身份判断加入 PIN。相同账号及密钥、不同 PIN 的记录保持分开显示；完全相同的绑定映射仍只显示一份。删除一个 PIN 的验证器不会清空密码项目内使用不同 PIN 的密钥。
- 读取侧不修改记录或加密材料，不改变数据库 schema、备份格式或 MDBX1 的退役策略。这不是对永久丢失密钥的恢复功能。

## 验证结果（2026-10-01）

普通版与 F-Droid 均打包成功；公共 Android 32 / x86_64 AVD 的隔离用户中，两版分别通过 17 项设备测试和 10 项相关 JVM 测试。安装前后核对 APK SHA-256，测试结束恢复原用户及设置，保留公共数据盘。

| 测试类 | 每版用例数 | 覆盖内容 |
| --- | ---: | --- |
| AuthenticatorVisibilityIntegrityTest | 5 | 不可读项仍可见、原值保留、同密文解锁恢复、不同 PIN 显示及删除保护 |
| UnreadableTotpCardTest | 2 | 标准卡片和磁贴禁止不可用操作，数据可读后恢复复制、编辑与二维码 |
| TotpListInteractionTest | 2 | 正常列表与磁贴的选择、二维码和删除确认 |
| LocalOtpRestoreIntegrityTest | 7 | 恢复失败保留、事务回滚、旧字段与未知元数据保留、不同 OTP 不丢失 |
| SensitiveMigrationConcurrentEditTest | 1 | 加密迁移不覆盖同时编辑后的 OTP |
| TotpUnreadableDataTest / KeePassTotpDisplaySourceTest | 10（JVM） | 不可读密文解析与显示去重边界 |

## 版本

- 普通版基础版本 1.0.316，versionCode 12，保留现有构建后缀规则。
- F-Droid 1.0.316，versionCode 23；版本字段和新 changelog 检查通过。
- 发行说明已重置为 1.0.316，包含上述修复及后续完成的改动，并保持未发布；已发布标签和历史 changelog 不变。

上述验证未创建 Git 标签或 GitHub Release。测试记录保存在工作区 `.codex-tasks/otp-visibility-316/validation.json`，设备原始输出保存在 `.codex-tasks/cloud-request-diagnosis-315/*-otp-316-final.log`。
