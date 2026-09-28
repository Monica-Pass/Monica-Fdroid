# 密码条目的 OTP 兼容约定

密码编辑页和独立验证器共用 `OtpType`、`OtpParametersDraft` 与参数控件，当前类型为 TOTP、HOTP、Steam、Yandex、mOTP。类型支持不等于所有数据库软件都能生成这些验证码。

| 类型 | 内部密码载荷 | 必须保留的参数 |
| --- | --- | --- |
| TOTP | 默认参数可用 Base32；非默认使用 `otpauth://totp` | secret、period、digits、algorithm |
| HOTP | `otpauth://hotp` | secret、counter（非负 64 位整数）、digits、algorithm |
| Steam | `otpauth://totp?...&encoder=steam`，兼容 `steam://` | secret；固定 30 秒、5 字符、SHA1 |
| Yandex | `otpauth://yaotp` | secret、period、digits、algorithm、已有 PIN |
| mOTP | `motp://issuer:account?secret=...&pin=...` | 保持 secret 大小写、4 位 PIN；固定 10 秒、6 位 |

## 存储规则

- MDBX 的密码载荷在 `authenticator_key` 保存完整内容，不能只截取 secret。Android 本地缓存按敏感值加密。
- Bitwarden 的 login.totp 使用完整内部载荷，再通过现有条目加密上传。服务端储存能力与其他客户端的验证码生成能力分别看待。
- KeePass 的 `otp` 字段受保护。TOTP/HOTP 同时写入可兼容的原生字段；非 SHA1 或非 6 位 HOTP 不伪造原生 HmacOtp 参数。Steam 保留 encoder 标记。
- Yandex、mOTP 在 KeePass 仅使用受保护的扩展 URI，不伪装成 TimeOtp/HmacOtp。其他客户端可能只能显示原始字段。
- 原生 KeePass 的 OTP 是数据来源；密码投影和备份导出从原生文件重建，不能把旧 Room 缓存视为最新数据。
- 普通密码保存中没有 OTP 载荷时，保留原生条目的其他 OTP 字段；明确解绑沿用现有验证器解绑路径。修改 OTP 时移除旧类型的已知 OTP 字段，保留无关字段。
- 公开二维码默认不包含 PIN；内部存储显式要求包含 PIN。不要把存储 URI 当作可直接公开分享的二维码。
- URI 的名称和查询参数分别编码，不能把完整 URI 放进 `TOTP Seed`。名称含冒号、加号或 `&` 时仍应按原值恢复。

## 验证与边界

`PasswordAuthenticatorDraftTest` 检查参数与验证码往返；`KeePassTotpExtensionsTest` 检查扩展 URI 和 PIN；`PasswordOtpEditorTest` 操作真实密码编辑页；`PasswordOtpStorageInstrumentedTest` 检查 MDBX/KeePass 原生重开、Bitwarden 加密模拟服务上传下载及加密备份。

模拟服务测试不代表真实 Bitwarden/Vaultwarden 线上联调。非标准供应商格式、缺失密钥/PIN、客户端未支持的扩展类型不能承诺自动正确生成。旧客户端主动丢弃未知参数时，新版本也无法恢复已丢失的数据。
