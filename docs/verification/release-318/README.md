# F-Droid 1.0.318 发布验证

2026-10-08，versionCode 25，包名 takagi.ru.monica.fdroid。

- `assembleRelease -PfdroidUniversal=true` 成功，包含 arm64-v8a、armeabi-v7a、x86_64；启用 R8 和资源压缩。
- 在公共 API 32 虚拟机用户 10 启动 Release 压缩代码，正常到达主密码界面，未发现启动崩溃。仅测试副本重签 Android debug 身份以保留原测试数据，原始构建未改动；测试后恢复 Debug 包、原用户与屏幕超时设置。
- 发布元数据脚本及其 6 项测试通过；修正 BUILD_DETAIL_TAG 为 25，版本号、versionCode 和静态构建信息一致。
- 普通版和 F-Droid 安全启动设备测试各 16 项通过，见 [安全启动验证](../secure-startup-318/README.md)。
- 姓名和地址建议两版各 13 项单元测试、14 项设备测试通过，见 [建议验证](../common-identity-318/README.md)。
- KDBX 删除和钱包编辑的既有验证见 [KDBX](../kdbx-delete-318/README.md) 与 [钱包编辑](../wallet-content-318/README.md)。

额外的源码文本断言并非全部通过：KDBX 验证中的 MultiPasswordSaveRegressionGuardTest 有 6 项既有失败，建议验证中的页面性能源码检查有 1 项既有失败，均已在原提交复现，详见相应记录。不能将本次发布描述为完整回归套件全绿。

Release 启动检查前几轮因脚本同时取得两个 Android 用户的进程号，以及系统锁屏影响窗口采集而未完成；改为只选择用户 10 的进程，等待系统完成用户切换，并临时关闭测试用户的无密码系统锁屏后重测，通过窗口树确认应用页面。随后恢复系统锁屏设置。未因此修改应用代码。启动检查不代替所有功能的 Release 设备回归；未覆盖所有厂商系统、Android 版本及真实多应用并发写库场景。

GitHub 发布为源码 Release，无 APK 附件。F-Droid 官方仍需从标签构建、签名并完成索引与分发队列。
