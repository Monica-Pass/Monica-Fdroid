# 内容编辑候选与 main 的兼容边界

> 历史候选阶段记录。确认范围已于 2026-09-29 接入本地 main 1.0.315，当前状态见 [最终可用性验收](FINAL-VALIDATION-1.0.315.md)。验证器新增自定义字段仍暂缓。

## 本轮范围

所有写入仅发生在 `experiments/content-main-integration` 与 `experiments/content-main-integration-fdroid`。两个候选继续使用原有 detached checkout，不改变主仓库、main 引用或其他工作目录；没有提交或推送。

候选已逐文件吸收普通 main `3e2f60beee82c14f409882a8cd72ac772f1346f9` 和 F-Droid `896fc12f012f7fb24eab1ca20a99300902110f5f` 的已提交增量，包括设置搜索与雪豹图标。同时带入本会话此前已验证的 MDBX JSON 精度／字面键修复、契约测试与合成夹具。

未提交主目录改动采用明确清单：仅 MDBX 修复及相关发行说明。F-Droid 其他会话正在调整的布局、生成器、导入等文件没有混入实验候选。精确排除清单见普通候选 `.codex-tasks/content-integration/main-refresh-20260928/excluded-primary-work.json`。

## 实际兼容情况

| 场景 | 当前判断 |
| --- | --- |
| main 的设置搜索与新增编辑样式 | 已适配。结果卡片只导航，不自动切换；支付等原有字段仍可定位。F-Droid 的搜索不再索引已移除的 OneDrive。 |
| main 的 MDBX 引擎、对象类型、密码字段语义 | 保持 main；普通版使用相同库，F-Droid 从对应源码编译。未另建实验存储格式。 |
| 经典与按需添加样式切换 | 默认经典；已保存内容保持可读，详情继续提供复制、显隐、大字、条码、分享与 Send。 |
| 旧验证器进入候选 | 缺少 `customFields` 按空列表读取，保留验证码核心参数。 |
| 候选验证器进入旧 main | 核心参数可读；旧界面不认识新增扩展字段。 |
| 候选验证器经旧 main 重新编辑保存 | **未兼容：旧模型重新序列化会丢失 `customFields`。不得声称可以无损双向编辑。** |
| Bitwarden | 候选覆盖文本／隐藏／布尔字段映射、字段单独变化检测。没有真实远端账户同步验收证据。 |

## 已复现的旧客户端限制

使用两个目录现有编译产物的 `TotpData` 与 `TotpDataResolver`，配合工程锁定的 Kotlin 2.1.20、kotlinx.serialization 1.6.0，执行同一个合成数据读取／序列化探针。未重新构建或修改 main，也未使用用户数据。

- main：成功读取核心验证码参数，但模型没有扩展字段属性，重新序列化后扩展字段消失。
- 候选：成功读取并保留扩展字段与隐藏类型。

main 的 `AddEditTotpScreen` 当前在保存时构造新的 `TotpData`，因此不能依靠 MDBX 文件版本相同来保证旧编辑器无损保存新字段。`item_data` 是已知对象内的 JSON 字符串，此风险不同于未知对象类型的原始数据保留。

探针为 `.codex-tasks/content-integration/LegacyTotpProbe.java`；具体结果、编译产物与源文件哈希保存在 `main-refresh-20260928/legacy-totp-probe.json`。这项证据覆盖模型读取／序列化，不冒充真实旧版 UI 全流程测试。

## 原生依赖核对

普通候选三个 ABI 的库与 main provenance 哈希一致。F-Droid 候选已核对的 166 个 Rust／Cargo 文件与已验证 main 源码逐文件一致；三个 ABI 的 942 个导出名称均与普通版相同。独立目录重新编译的二进制在 strip 后仍不完全相同，因此不宣称逐字节可重现，实际打包哈希与契约回归另行记录。

## 后续合入顺序

1. 先评审共享编辑组件、可选样式和设置搜索兼容层；默认值和 main 的保存路径保持不变。
2. 单独处理验证器扩展字段的旧写入端保护：需要在可写客户端具备保留未知字段的能力后，再开放跨版本双向编辑。不要用在候选中复制一份额外数据、静默恢复已删除字段等方式掩盖旧写入器问题。
3. Bitwarden 真实远端往返另行验收，包含字段删除、冲突选择和不同版本客户端编辑。
4. 获得后续合入授权时重新核对最新 main；只转移候选相对有效 main 基础的实验差异，不能把旧候选目录整体覆盖 main。

本文件记录的是候选验收边界，不是发布许可。F-Droid 的当前发行说明按此前用户决定采用 main 版本，历史说明保留。

## 设计与证据

[可编辑 M3E Canvas 与说明](design.md) 包含 12 页，新增搜索结果到样式设置的流程；`canvas.json` 可继续编辑。设备截图位于 `device/main-refresh`，完整验证汇总和交接状态见 [HANDOFF-20260928.md](HANDOFF-20260928.md)。

本轮同步前已备份两个候选的未提交文件。源状态、160 个文件的逐文件合并记录、排除列表、备份 ZIP 均位于 `.codex-tasks/content-integration/main-refresh-20260928/`。不要重跑旧初始化或全量同步脚本。
