# 密码内容块设计

API Key、API 令牌保持独立入口；支持重复内容块、直接拖动排序和删除确认。编辑使用完整底部面板，取消不修改原值；详情摘要隐藏密钥，点击查看完整字段和复制／导出。GPG 位于更多。

[可编辑 Canvas JSON](canvas.json)。在线 https://lnkiai.github.io/m3e-canvas/ 被已保存的浏览器站点权限禁止访问，本轮保留本地 Canvas 组件格式草图，未验证在线预览；原生 Compose 页面已实际渲染和测试。

以下截图使用合成测试数据。详情截图展示底层摘要页面；底部详情面板通过实际点击、默认隐藏和显示原值的 UI 断言验证，不将背景截图作为面板渲染证据。

| 普通版编辑 | 普通版详情摘要 |
| --- | --- |
| ![连续分组](main-editor.png) | ![详情摘要不显示密钥](main-detail-hidden.png) |

| F-Droid 编辑 | F-Droid 详情摘要 |
| --- | --- |
| ![连续分组](fdroid-editor.png) | ![详情摘要不显示密钥](fdroid-detail-hidden.png) |
