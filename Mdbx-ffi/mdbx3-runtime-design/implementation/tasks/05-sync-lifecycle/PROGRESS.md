# Incremental bootstrap and history lifecycle 进度

任务：在不改变 MDBX2 文件格式和旧 peer 回退的前提下，使首次同步也能
使用有界、可恢复的 incremental segment。

进度：4/4

当前：已完成实现与验证，提交 `578eec0` 已推送。

已完成：

- `PeerSyncService` 接受成对空 checkpoint 作为 bootstrap marker。
- 首段固定为 index 0、无 previous digest；后续段仍要求完整 checkpoint。
- FFI 的两个既有 `String` token 用双空字符串表示 bootstrap，无 ABI 变化。
- storage peer_sync 与 FFI incremental 回归通过。

边界：历史删除、compaction、checkpoint 生成仍保持独立能力，不在本阶段
删除任何认证历史。
