# Monica Password List Rust Core

这是 Monica Android 性能重构中的 Rust 运行时边界：**密码列表的无明文元数据筛选、投影与保守去重**。

## 当前落地方式

密码列表原先会把筛选后的条目逐条解析/解密，再把结果送进 Compose。列表卡片实际只依赖标题、用户名、网站、备注、更新时间、来源与图标，因此登录密码不应成为列表渲染的前置工作。

当前重构采用以下边界：

1. Room 仍可返回条目原始密文，以保证复制、移动、同步等显式操作语义不变；
2. 普通密码列表不再为每一行解析登录密码；只有幽灵条目判定、智能去重等确实需要比较 secret 的候选小组才按需解析；
3. 搜索时通过 `rust-jni` 一次批量传入 ID、标题、用户名、网站、应用名和包名，由本 crate 完成元数据筛选；
4. JNI 不可用时 Kotlin 自动回退，不阻塞应用功能；
5. Compose、生命周期、Room、Android Keystore、生物识别及存储 provider 继续留在 Kotlin/Android。

## 安全边界

`PasswordListRecord` 没有密码字段。Android JNI 运行时也不会把 `PasswordEntry.password`、TOTP secret、银行卡敏感字段等传入 Rust。

可选的 `secret_fingerprint` 只能是 Android 数据层在写入/导入时产生的、不可逆的带密钥摘要；不能传明文，也不能把随机化密文当成指纹。

去重采用保守原则：

- 没有明确来源身份的本地条目永不合并；
- 未知指纹不视为相同；
- 同一副本组、同一存储目标内出现多条记录时，视为用户有意保存的多密码兄弟条目；
- 只有满足既定来源身份和 secret 语义的条目才允许折叠。

## FFI 规则

Android 接入位于相邻的 `../rust-jni` crate。规则如下：

- 一次列表快照/搜索只做一次批量 JNI 调用，禁止逐行跨 FFI；
- FFI DTO 只包含展示/检索元数据；
- native 加载或调用失败必须回退 Kotlin；
- R8 必须保留 `takagi.ru.monica.rustcore.RustPasswordListCore`，因为 JNI 使用静态 `Java_*` 导出符号；
- Release/Preview CI 必须为项目支持的 ABI 生成 `libmonica_rust_jni.so` 后再执行 Gradle 打包。

## 校验

```bash
cargo fmt --all -- --check
cargo clippy --all-targets --all-features -- -D warnings
cargo test --all-targets --all-features

cargo fmt --manifest-path ../rust-jni/Cargo.toml --all -- --check
cargo clippy --manifest-path ../rust-jni/Cargo.toml --all-targets --all-features -- -D warnings
```
