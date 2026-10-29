# WebDAV 备份目录检查故障（F-Droid 1.0.323，2026-10-29）

## 中文

与普通版同步修复 `WebDavHelper.webDavPathExists`：仅确认 404 时返回不存在；超时、断线和未知服务器错误原样上抛，使 `uploadBackup` 在目录状态未确定时停止，而不是继续 MKCOL 和 PUT。未配置的客户端也不会被当作目录不存在。没有修改现有数据、密钥、证书校验或备份密文格式。

回归通过实际上传入口、真实 OkHttp/Sardine 与 MockWebServer，覆盖 HTTP 500、元数据超时、响应断线、真实 404 及已有目录。只替换 Robolectric 无法提供的系统密钥存储；F-Droid 测试按既有夹具方式临时允许回环 HTTP，并在结束后恢复偏好，生产默认禁止 HTTP 的策略保持原样。

- 新增 5 项备份上传回归全部通过。故障场景只有 PROPFIND，没有 MKCOL 或 PUT；正常场景上传字节保持原样，并保留创建新备份时的条件写入头。
- 2 项既有 MDBX 目录检查与 3 项超时分类测试通过，共 10 项，无跳过。
- 主体源码编译与 Debug 打包通过。没有交付或发布安装包。

初次测试被 F-Droid 的 HTTP 安全策略挡在网络请求之前；调整仅涉及测试夹具，没有放宽生产策略。构建与测试原始记录保留在工作区 `.codex-tmp/cloud-errors-323/fdroid-webdav-final-2.log`。

用户原来的“找不到文件”反馈缺少具体操作与设备日志，不能据此认定其根因就是本次缺陷，也不能将多个云服务的失败统一归为网络问题。

## English

Directory checks now return “missing” only for a confirmed 404. Timeouts, disconnects and unknown server failures stop the actual backup-upload entry point before speculative MKCOL or PUT requests. Data, keys, certificate verification and encrypted backup formats are unchanged.

All 10 focused tests passed: five new real-transport upload regressions, two existing MDBX directory tests and three timeout-classification tests. The F-Droid fixture temporarily permits loopback HTTP and restores the preference afterward; the production cleartext policy remains unchanged. Source compilation and Debug packaging passed.

The original user report remains unconfirmed without its triggering operation and device logs. No APK was delivered or published.
