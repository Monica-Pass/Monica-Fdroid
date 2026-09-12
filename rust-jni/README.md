# Monica Android Rust JNI

`rust-jni` 是 Android 与 `rust-core` 之间的窄桥接层。

当前运行时接入的是密码列表元数据搜索。Kotlin 一次性传入平行数组：ID、标题、用户名、网站、应用名、包名和查询词；Rust 返回保持原顺序的匹配 ID。登录密码、TOTP secret、支付信息等敏感值不进入 JNI。

## 回退

`RustPasswordListCore` 会懒加载 `libmonica_rust_jni.so` 并执行 native self-test。如果动态库缺失或调用失败，调用方返回 `null` 并使用 Kotlin 元数据筛选，应用功能不会因 Rust 不可用而失效。

## Android 构建

```bash
rustup target add aarch64-linux-android armv7-linux-androideabi
cargo install cargo-ndk --version 4.1.2 --locked
cd rust-jni
cargo ndk \
  -t arm64-v8a \
  -t armeabi-v7a \
  -o ../app/src/main/jniLibs \
  build --release
```

生成的 `.so` 是构建产物，不应手工提交。正式 Release 和 Preview 工作流会在 Gradle 打包前执行同样的 native 构建。

## 卡包首屏排序

`RustListSortCore` 通过一个 `LongArray` 传入版本号及每行的收藏标记、排序号、ID、更新时间，返回原列表的索引排列。它不读取字符串、卡号或验证码密钥。批次版本、行宽和收藏值先验证，Kotlin 再校验返回值是完整排列；不可用时保持原 Kotlin 排序。

页面在后台准备列表，小列表直接使用 Kotlin。Android `ListFirstFrameInstrumentedTest` 同时验证两种现有平局规则，并测量包含数组打包、JNI 拷贝和索引映射的完整调用开销，避免只比较 Rust 内部排序时间。

## 卡叠归组

`RustWalletStackCore` 对大列表一次性处理可见成员查找、去重和归组，只接收数字 ID，返回组索引与卡片索引。Kotlin 校验完整排列并保留原对象，小列表或原生调用不可用时使用 Kotlin 回退。整个批次在后台执行，动画帧不调用 JNI。

协议、排序语义、动效保护和包含 JNI 开销的测量方法见 [卡叠性能说明](../docs/wallet-stack-rust-performance.md)。`cargo test` 覆盖协议边界和归组行为，Android 测试对照真实 JNI 与 Kotlin 的完整结果。

## 概览常用项目选择

`RustVaultPickerCore` 为常用卡片和常用项目的选择弹窗保存临时搜索索引。显示元数据在后台准备后批量传入，后续查询只传搜索词和数据库索引，返回保持原顺序的项目索引；勾选不会重复解析卡片或重建索引。少于 512 项时使用 Kotlin，原生库不可用时也保留 Kotlin 回退。弹窗关闭或数据变化时释放索引，主线程不加载或查询原生库。

筛选协议、索引生命周期和实际 JNI 性能测量见[常用项目选择性能说明](../docs/vault-overview-picker-performance.md)。
