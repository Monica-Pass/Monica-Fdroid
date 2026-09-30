# F-Droid issue #4：Bitwarden / Vaultwarden 两步验证

不可用的认证方式从选择列表隐藏；服务端只提供不支持方式时显示说明，不提供验证码提交入口。

Issue：https://github.com/Monica-Pass/Monica-Fdroid/issues/4

状态：1.0.315 本地修改，未发布，未回复或关闭 issue。

## 可确认的问题与修复

原实现已有 TOTP、邮件验证码，但只读取 `TwoFactorProviders` 来识别认证挑战。若服务端只提供 `TwoFactorProviders2`，客户端会把它当作普通登录错误，或在 HTTP 200 分支尝试读取不存在的加密密钥。现统一识别两种格式，保留未知认证方式作为挑战，不把它误判为登录成功；兼容备用请求路径。

提交验证码原本立即关闭弹窗，错误后难以重试。现在保留弹窗和认证状态直到成功或取消，请求期间禁用重复提交和方式切换；切换方式清空旧验证码，取消释放临时状态。人机验证与两步验证切换时只保持一个活动弹窗。

验证码路径仅允许服务器提供且客户端支持的方式。TOTP、邮件验证码可去除粘贴时的空白；YubiKey OTP 保留内容。WebAuthn、安全密钥交互、Duo 与未知方式不会再显示可提交的通用验证码输入。长方式列表可滚动，底部操作可达。

多种方式先选择再操作；邮件需显式点击发送验证码，发送中禁止重复请求。TOTP 可从 Monica 搜索已有独立验证器和密码内验证器，不受验证器页面筛选影响；点击时生成当前验证码，填入后由用户确认，也可手动输入。

## 支持范围

- 本轮修复主密码后的 TOTP / 邮件两步验证兼容与重试。既有新设备邮件验证和 YubiKey OTP 入口保留。
- **没有新增 SSO、Passkey 无密码登录或 WebAuthn 交互式 MFA。** 仅允许这些方式的组织仍需使用支持对应流程的客户端，不能要求关闭服务端 MFA 来绕过。
- Issue 未提供准确应用版本、服务端版本、完整认证策略或脱敏响应，故不能确认反馈用户一定命中了此兼容缺口。

## 验证

`BitwardenTwoFactorCompatibilityTest`：新旧字段及大小写、数值/字符串方式 ID、未知方式不绕过、真实认证服务识别 HTTP 200/400 挑战、错误 TOTP 后重试并解密 vault key、备用登录请求的现代挑战、邮件请求与 provider=1。认证 API 使用进程内测试替身，无用户账号及真实服务端。

`BitwardenTwoFactorDialogTest`：支持方式优先、交互式方式禁用、请求中防重复提交、错误后可重试、长列表大字体滚动及邮件方式切换。

`BitwardenAuthenticatorSourceTest`：独立验证器和密码内 TOTP 都进入选择器，验证器页面搜索不影响此数据源。UI 测试另外覆盖显式发送邮件、搜索选择及跨时间段生成新验证码。

最终结果见 `bitwarden-two-factor-315-verification.json`；可编辑 M3E Canvas 及真实截图见 `design/bitwarden-2fa-315/`。本轮验证不等同于已完成真实 Vaultwarden、SSO 或 WebAuthn 端到端认证。

Docker 已恢复运行；检查时原容器、镜像与数据卷列表为空。本轮新建独立本机 Vaultwarden 和 Mailpit，使用随机测试账号，不代表恢复了原服务数据。

## Docker 真实服务端补充验证

Docker 真实登录验证：普通版与 F-Droid 均通过 Vaultwarden 1.37.3 的主密码、邮件验证码、TOTP、多方式挑战及错误验证码重试；三条成功路径均校验解密后的密码库密钥。

每版为一个覆盖完整流程的集成测试，使用真实认证服务与 HTTP/SMTP，不是 API 替身；证据见 `bitwarden-live-login-315-verification.json`。测试未驱动完整登录 UI，UI 交互由此前独立设备测试覆盖。未覆盖企业 SSO、Duo、硬件安全密钥、无密码 Passkey 或外部 SMTP/HTTPS 部署。

复测：启动 `monica-mfa-vault` 与 `monica-mfa-mail`，为公共 AVD 设置 18080、18025 的 adb reverse；运行 BitwardenLiveLoginTest 并传入 `-e monicaLiveMfa true`。未传此参数时跳过，避免普通测试误连接服务。测试结束停止这两个容器并移除对应端口转发；测试数据卷保留供复测。
