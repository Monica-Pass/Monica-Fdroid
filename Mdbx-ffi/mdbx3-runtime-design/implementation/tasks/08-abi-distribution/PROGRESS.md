# Android ABI split distribution 进度

进度：5/5

已完成：

- 明确 Android 单 ELF 不能跨 CPU ABI，通用体验由统一发布索引和单 ABI 安装实现。
- 新增三 ABI 薄包、独立 SO、兼容全量 ZIP 和机器可读索引生成脚本。
- verifier 将 ZIP 内 SO、外置 SO、ELF machine、release reports 和 artifact SHA-256 绑定。
- `build-mdbx3-android.ps1` 在完整 release gate 后默认生成薄包。
- 现有正式产物验证为 ready；薄包压缩大小约 3.1-3.6 MB，全量包约 10.1 MB。

当前：实现提交 `fa5e8b6` 已推送；正式 Android 三 ABI 构建、release gate、薄包生成和 ABI split gate 已端到端通过。
