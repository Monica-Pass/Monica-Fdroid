# 数据库详情返回时的空状态闪烁

视频中的空状态属于退出中的详情页，不是数据库列表真正清空。

`MdbxManagerScreen` 的 `selectedDatabase` 从导航目标 `page` 计算。返回 Source 页面会让它立即变成 null，但 AnimatedContent 仍保留 Detail 内容执行退出动画。旧详情此时进入 EmptyMdbxState 分支，短暂显示“暂无 MDBX Vault”。

修复：动画内容从其 `current` 页面自己的 databaseId 查找数据库。页面外的当前选择继续用于导航和操作副作用；动画内的详情、健康、附件、维护等内容保持对应数据库，直到退出完成。真实空列表与数据库删除逻辑不变，不额外延迟或缓存数据库数据。

回归使用真实 MdbxManagerScreen 与隔离的内存 Room 数据库，每 32ms 检查一个返回中间帧，覆盖顶部返回按钮、系统返回及再次进入详情。旧版 `1.0.313-26092012-05` 在第一个检查帧检出“暂无 MDBX Vault”，测试按预期失败。修复版 `1.0.313-26092012-06` 同一测试通过。


## 验证

- Debug 与 AndroidTest APK 完整构建成功。
- 公共 Android 32 x86_64 虚拟机上 3 项测试通过（10.178 秒）：返回动画中间帧（顶部返回 + 系统返回）、来源列表滚动位置保留、真实空列表的全部打开/新建入口。
- [返回中间帧](mdbx-return-device/mid-transition.png) 中详情与列表正常交叠，没有“暂无 MDBX Vault”；[动画结束](mdbx-return-device/settled.png) 后列表与底部操作正常。
- 主仓库与 F-Droid 同步修复及发行说明，保留 F-Droid 原有来源菜单差异；F-Droid 未单独构建。
- 日志：工作区 `.codex-tmp/mdbx-return-baseline.log`、`mdbx-return-fixed.log`、`mdbx-return-build.log`。
