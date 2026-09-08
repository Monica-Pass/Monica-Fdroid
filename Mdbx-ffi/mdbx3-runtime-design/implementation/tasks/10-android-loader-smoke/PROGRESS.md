# Android loader smoke 进度

进度：4/4

已完成：

- 最小 Android 工程按 `mdbxAbi` 选择一个 ABI，并复制正式 MDBX3 SO。
- ABI filter 同时限制 JNI shim，APK 不携带其他架构。
- Pixel Fold API 35 AVD 实测安装、启动成功；日志无 linker error，contract version 为 30。

当前：loader smoke 提交 `116549b` 已推送；UniFFI 全行为仍由 workspace/FFI 回归覆盖，产品客户端仍需在其自己的 CI 执行完整冻结应用矩阵。
