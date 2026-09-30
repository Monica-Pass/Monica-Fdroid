# 多密码项目与 MDBX 同步修复（1.0.315，未发布）

## 数据语义

独立密码记录不再按标题、账号、网站推断合并。仅编辑器明确创建的多密码项目使用 `password_group_id` 关联；该字段与 MDBX 对象 ID 独立。旧的非 MDBX 编辑器 UUID 分组兼容读取。Room 78→79 只增加可空列，不重建或清空密码表。

MDBX login payload 使用可选 `password_group_id` 字符串；同数据库、同标记的多条 login 组成一个项目。缺少标记的记录独立显示，不猜测关系。MDBX 保留每条 login 独立对象 ID；其他客户端应保留该字段。归档与 WebDAV 全量备份也携带此标记。此处不承诺 KeePass/Bitwarden 原生文件的分组扩展兼容。

## 移动与同步

移动写入 MDBX 前完整解开本应用可认证的历史多层密文，最多 8 层；无法解密时阻止写入，不把本机密文冒充可跨端明文。这里的明文是传入 MDBX 加密引擎的内容，MDBX 文件与同步流仍加密。

打开本地 MDBX 或执行远端同步前，尝试修复原实例可解密的历史 `password_plain` 密文。修复仅替换该字段，保留未知字段、数字精度和对象元信息；提交前比较完整 payload，避免覆盖并发修改。已修复记录不重复写入；无法认证的记录保持原样。

应用分身拥有独立密钥。历史数据应先在仍能读出密码的原应用实例上更新并同步，再由分身同步。接收端无法凭空恢复另一实例的密钥。旧版已丢失分组标记的记录不会按内容自动合并；本次复现中两条原始密码均仍存在，只是显示分组失效。

## 交互

多选以整个项目为单位：一个勾选框、不显示密码 1/密码 2 按钮，选中数量按项目计数。批量移动和删除仍操作该项目的全部成员记录。

设计草图与截图见 [多密码整项选择](design/multi-password-315/README.md)。在线 Canvas 受已保存浏览器权限限制，保留离线可编辑 JSON 和分享链接；实际界面通过 Compose 截图检查。

## 验证

测试使用合成数据和公共 API 32 虚拟机，保留已有应用数据。最终普通版 12 项、F-Droid 13 项全部通过；两版 Debug 与 instrumentation APK 均构建成功，运行前后校验 APK 哈希。原生导出/重复导入、同步重建、移动后编辑、追加第三条密码、备份分组、失败时源数据保留、未知字段及高精度数字保留、修复幂等性、整项选择 UI 和 JNI 分组一致性均已覆盖。真实 WebDAV 测试使用本机 HTTP 服务和 adb reverse，普通版上传、F-Droid 独立沙箱接收，并验证接收端不能解密原实例密钥探针。

跨沙箱验证已通过：普通版经真实 HTTP WebDAV 发布 bootstrap 和增量流；F-Droid 下载并重建后，两条密码均正确、项目仍含两个成员，且原实例密钥探针无法在接收端解密。该测试验证独立密钥隔离场景，不代表已经覆盖所有厂商分身实现或真实用户文件。

复现日志保存在工作区 `.codex-tasks/experimental-final-315/`：`main-multipasswordrepro`、`main-multipasswordnestedrepro`、`fdroid-multipasswordeditrepro` 为修复前失败；`main-multipasswordcomplete`、`fdroid-multipasswordcomplete` 为最终通过。
