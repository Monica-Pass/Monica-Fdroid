# Passkey 兼容与认证修复：提交验证（2026-10-06）

普通版和 F-Droid 包含相同的 Passkey 修复：保留 UUID、Base64URL、Bitwarden `b64.` 的凭据字节，保留备注正文与显式清空，严格匹配允许列表和最终平台请求，取消认证时结束请求，账户选择不显示私人备注。正常生物识别和主密码回退保留。

两版 `assembleDebug -PincludeX86TestAbi` 均成功。每版定向 JVM 回归运行 77 项：72 通过、5 失败、0 错误、0 跳过。新增的编解码、备注、请求策略和映射测试每版 14 项全部通过，合计 28 项。映射测试使用真实 EC/RSA 密钥反复往返并签名验签，包含 uint32 最大计数器和带分隔线、空白的备注。

5 项失败为已有源码守卫断言；将它们读取的文件替换为本次补交前 Git HEAD 的只读快照后，两版均复现相同方法、相同失败信息。对照基线为普通版 `e597bd26af`、F-Droid `fb7c826e`。没有删除或跳过这些测试，也没有将完整测试集标记为通过。

| 已有失败 | 范围 |
| --- | --- |
| authenticatorAndPasskeyShareOneDockDestinationWithBidirectionalControls | 旧导航源码断言 |
| mdkWrapperRebuildHandlesInvalidatedAndUnrecoverableKeystoreKeys | MDK 包装恢复源码断言 |
| emptyRuntimeMdkCacheCannotMaskReadableKeystoreWrapper | 空 MDK 缓存恢复源码断言 |
| pageSwitchHotPathsDoNotRunAuthOrBitwardenSyncWorkOnMainThread | 页面切换源码断言 |
| autofillPasswordSelectionReturnPathDoesNotBlockMainThread | 自动填充源码断言 |

普通版的两项 Bitwarden 测试文件被既有 `bitwarden/` 忽略规则匹配，本次显式纳入版本控制。测试结果详见同目录 `PASSKEY-COMPATIBILITY-20261006.json`；完整构建、测试及基线快照保留于工作区 `.codex-tasks/publish-passkey-317/`。

本轮包含 JVM/Robolectric（请求策略和映射使用 API 34）及 Debug 构建，未复测 Android 14 真机系统凭据面板和生物识别硬件交互。
