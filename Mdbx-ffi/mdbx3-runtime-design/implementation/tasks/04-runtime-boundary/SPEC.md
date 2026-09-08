# Vault runtime boundary and read/write model

## 任务形态

- `single-full`

## 目标

1. 在 `mdbx-storage` 中建立唯一的 Vault Runtime 边界，拥有连接生命周期、reader generation 和 single writer 协调。
2. 让 Compatibility FFI 只做参数/结果转换，不再直接拥有 `rusqlite::Connection` 或 raw SQL 查询。
3. 保持全部 MDBX2 UniFFI 符号、checksum、DTO、错误和 `libmdbx_ffi.so` 装载契约不变。
4. 通过公共接口证明写后读可见、写入串行、并发读取安全，并为 key epoch/session/Tiga 失效留出统一 generation 边界。

## 非目标

1. 本阶段不改变 MDBX-2 文件格式或 schema 17。
2. 不引入异步 FFI、连接池第三方依赖或新的客户端必调 API。
3. 不改变 Tiga、加密、commit、snapshot、restore、sync apply 的语义。
4. 不在本阶段实现历史清理、分段 bootstrap 或长操作句柄。

## 约束

1. 只修改 `C:\Users\joyins\Desktop\Monica-all\mdbx`。
2. 使用 Rust 1.86.0，保持 MSRV 与冻结 MDBX2 ABI。
3. 测试通过公共 storage/FFI 接口，不依赖私有锁调用次数。
4. 所有写路径必须经过同一个 writer gate；旧 reader 不得跨 generation 复用安全状态。
5. `.codex-tasks/` 保持原状，不提交构建产物。

## 风险

1. `MdbxVault` 现有 `Mutex<VaultConnection>` 覆盖大量 FFI 方法，迁移容易造成遗漏或锁嵌套。
2. `VaultConnection` 内含 keyring/session 状态，不能简单为并发读取 Clone。
3. SQLite WAL、snapshot/restore 和 key epoch 对多连接行为有严格要求。
4. FFI ABI 冻结意味着只能改变内部字段和实现，不能修改已有导出签名。

## 交付物

1. storage-owned Vault Runtime 深模块及公共读/写闭包接口。
2. reader generation 与 writer serialization 的行为测试。
3. Compatibility FFI 内部迁移与 ABI 回归验证。
4. 本阶段事实文件、验证记录和已推送提交。

## 完成条件

1. `cargo test -p mdbx-storage --no-default-features --features core` 通过。
2. `cargo test -p mdbx-ffi` 通过。
3. `cargo test --workspace --no-fail-fast` 通过。
4. production library 通过冻结的 535 symbols、237 checksums 与 contract 30。
5. 并发测试证明 single writer、并发读与 generation 失效行为。

## 最终验证

```powershell
cargo fmt --all -- --check
git diff --check
cargo clippy --workspace --all-targets --all-features -- -D warnings
cargo test -p mdbx-storage --no-default-features --features core
cargo test -p mdbx-ffi
cargo test --workspace --no-fail-fast
```
