# MDBX3 Android ABI 分发设计

## 1. 结论

Android 上不能制作一个同时由 ARM32、ARM64 和 x86_64 原生加载的通用 ELF SO。ELF header 的 `e_machine` 只能声明一种机器架构，Android linker 也不会从单个 SO 中选择多个机器码切片。

MDBX3 的“通用架构版本”因此定义为统一发布契约，而不是跨架构单文件：

1. 三个 ABI 使用同一源码、MDBX2 UniFFI ABI、runtime manifest 和能力集合。
2. 每个设备或 APK 只安装与进程 ABI 匹配的一个 `libmdbx_ffi.so`。
3. 发布索引提供三个薄包及其 SHA-256，客户端构建系统选择目标 ABI。
4. 全量三 ABI ZIP 继续保留，仅用于兼容、CI 和本地测试。

把 ARM32 机器码塞进 ARM64 ELF、在运行时解压另一个 SO、使用指令翻译器或把原生实现改成解释器，都会破坏 Android 装载契约、启动性能或安全边界，不属于 MDBX3 方案。

## 2. 发布资产

`scripts/package-mdbx3-android-abi-splits.ps1` 从已经通过 MDBX3 release gate 的三 ABI 目录生成：

```text
mdbx3-android-arm64-v8a.zip
mdbx3-android-armeabi-v7a.zip
mdbx3-android-x86_64.zip
mdbx3-android-jniLibs-universal.zip
libmdbx_ffi_arm64-v8a.so
libmdbx_ffi_armeabi-v7a.so
libmdbx_ffi_x86_64.so
mdbx3-android-abi-index.json
```

每个薄 ZIP 只包含一个原生库：

```text
android-jniLibs/<abi>/libmdbx_ffi.so
reports/<MDBX3 release reports>
```

外部资产名可以带 ABI，进入 APK 后 basename 必须恢复为 `libmdbx_ffi.so`。这保持旧 `System.loadLibrary("mdbx_ffi")` 和 MDBX2 bindings 不变。

## 3. F-Droid 与直接 APK

F-Droid 或直接分发 APK 时，推荐构建三个 ABI APK variant。每个 variant：

1. 只包含一个 `lib/<abi>/libmdbx_ffi.so`。
2. 使用同一应用版本名称和源码 commit。
3. 使用可排序且互不冲突的 versionCode。
4. 在应用元数据中声明对应 ABI。
5. 运行同一套 MDBX2 bindings smoke test。

构建 APK variant 属于客户端 Gradle/F-Droid 配置，不能由 MDBX SO 自己决定。若分发渠道只接受一个 APK，同时又要求覆盖三种 ABI，则该 APK 必须包含三个 SO，体积无法由 ELF 层消除。

实际产品可以根据设备范围裁剪发布矩阵：现代实体设备优先 `arm64-v8a`，`armeabi-v7a` 用于旧 32 位设备，`x86_64` 主要用于模拟器和少量 x86 设备。是否停止某个 ABI 支持属于客户端产品决策，不改变 MDBX3 Runtime 的三 ABI 构建能力。

## 4. Play 与其他分发渠道

支持 Android App Bundle 的渠道可以把三 ABI 都放入 AAB，由渠道向设备下发一个 ABI split。最终设备同样只收到匹配的 SO。

不支持 App Bundle 的渠道使用多 APK variant。不能把 AAB 的服务端拆分行为假定为 F-Droid 的默认行为。

## 5. 验证边界

`scripts/verify-mdbx3-android-abi-splits.py` 必须验证：

1. 每个薄包恰好包含一个 ABI 的 SO。
2. 全量包恰好包含三个 ABI 的 SO。
3. ELF machine 与 `arm64-v8a`、`armeabi-v7a`、`x86_64` 目录一致。
4. ZIP 内 SO、独立 SO 和正式 artifact report 的长度与 SHA-256 一致。
5. runtime manifest 仍声明 MDBX3、MDBX-2 和 `libmdbx_ffi.so`。
6. ABI report 与 release gate 仍证明 535 个冻结符号、237 个 checksum 和 UniFFI contract 30。
7. 所有薄包携带同一组不可变 release reports 和源码 commit。

页面兼容按 ABI 验证：`arm64-v8a` 与 `x86_64` 的 LOAD segment 至少 16 KiB 对齐；`armeabi-v7a` 是旧 32 位进程产物，按 NDK 的 4 KiB 对齐验证。16 KiB-only Android 设备使用 64 位薄包，不加载 ARM32 SO。

`scripts/build-mdbx3-android.ps1` 在完整 release gate 通过后默认生成这些薄包；仅诊断底层构建时可传入 `-SkipAbiSplitPackaging`。

## 6. 体积口径

需要分别报告三种数值：

1. 全量发布集合：三个 ABI 资产之和，用于仓库和 CDN 成本。
2. 单设备下载：一个 ABI ZIP/APK 的压缩大小。
3. 单设备安装：一个 ABI SO 的实际安装大小。

禁止用全量集合大小描述单设备成本，也禁止用单 ABI 大小声称三 ABI 发布集合只有该大小。ABI split 优化的是每台设备的下载与安装，不会让三份不同机器码在发布服务器上消失。
