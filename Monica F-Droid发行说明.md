# Monica for Android (F-Droid) 1.0.317

> 未发布 / Unreleased

## 中文

- Android 客户端仅提供 Sky、Multi、Power 三档，暂不接入 Glitter；MDBX 底层格式能力保留。已有 Glitter 数据库保留文件与记录并提示暂不支持，不自动降档或转换。

- 移除拖拽导航栏，统一使用普通导航；保留标签排序、显示隐藏、FAB 和最近使用入口。

- 新增 Shizuku 默认密码管理器入口（Android 14+），可从开发者设置和自动填充设置将 Monica 设为默认，并选择同步设置自动填充、保留其他凭据提供方或恢复上次配置；取代旧的 Passkey HyperOS 兼容开关。

- MDBX2 远程数据库可单独选择手动或自动同步，默认手动；同步开关采用独立圆角设置行与简短说明。

- 银行卡和证件照片支持导入时裁剪，并统一裁剪页操作样式。

- 优化 MDBX 档位选择：星光缓慢闪烁且仅显示在已填充区域；滑块连续跟随手指，松手吸附最近档位，点击切档平滑过渡并提供跨档触感反馈，遵循减少动画和触感设置。

- 原生数据库管理页支持直接新增登录信息、保留未知字段的编辑、删除及附件添加/移除；附件在内存中验证并预览文字或图片，不导出解密文件。

- 整理 Android 版 README 与多语言说明，移除浏览器插件安装、技术栈和其他平台状态说明，明确 Android 应用及手机浏览器的自动填充范围。

- 修复卡包多选拖动排序时卡片互相遮挡、换位不连贯的问题；整行置顶拖动并即时更新独立卡片顺序，保留卡叠分组和排序保存。

- 补齐筛选菜单数据库列表的展开与收起高度动效，保留横向滚动、大量数据库按需加载及底部操作入口。

- 修复后台自动同步已经开始时，面包屑路径上的同步按钮不转圈的问题；普通列表与新版密码库均按当前库的实际执行状态显示动画，结束后停止。

- 修复 MDBX2 已有附件尚未下载时，本地修改会使手动与自动同步卡在“Blob 没有大小信息”的问题；先验证并补齐附件，再上传修改，传输中断后可重试。云端附件缺失或损坏时仍保留未同步状态。

- 升级时仅清理拖拽导航与旧 HyperOS Passkey 旁路设置，不改变其他偏好或密码库；旧备份仍可导入，新备份不再包含拖拽选项。
- 完整删除旧 HyperOS Passkey 验证旁路，保留正常生物识别验证与主密码回退流程；创建通行密钥时，生物识别不可用或报错可使用已设置的主密码验证。

- 开启自动同步后，保存通过持久任务上传增量；应用在前台且已解锁时检查远端更新，空闲检查间隔逐步延长至 5 分钟。连续保存合并排队，上传期间的新修改仍会继续送达。关闭自动同步不会中断已经开始的上传。
- 自动同步与手动同步共用队列；失败保留本地修改并退避重试，避免连续保存取消正在进行的上传。
- 修复外部数据库写回失败后刷新可能遗漏未发布修改的问题。

- 拍照或从图库导入正反面照片后，可以裁剪、旋转、翻转，也可以选择“不裁剪”后继续原有的质量确认流程。
- 自定义卡面仍须裁剪，不提供“不裁剪”选项。
- 裁剪页底部统一使用旋转、翻转、重置控件及确认按钮。

- 修复 WebDAV 条件上传的 503／429 分类，保留等待时间和写入条件后再重试。

## English

- Android offers Sky, Multi and Power only; Glitter integration is deferred while the MDBX engine retains format support. Existing Glitter files and records are preserved with an unsupported-mode message, without automatic downgrade or conversion.

- Remove draggable navigation and use the standard bar, retaining tab order/visibility, FAB and recent items.

- Set Monica as the default password manager with Shizuku (Android 14+) from developer or autofill settings. Optionally set autofill, keep other credential providers, or restore the previous configuration. Replaces the old Passkey HyperOS compatibility toggle.

- Each MDBX2 remote vault can use manual or automatic sync; manual is the default. A separate rounded settings row keeps the switch and explanation compact.

- Crop bank-card and document photos during import, with consistent crop controls.

- Improve the MDBX mode slider with twinkling stars only on the filled track, continuous finger tracking, nearest-mode snapping, smooth tap transitions and detent feedback; respect reduced-motion and haptic settings.

- The native manager supports direct login creation, edits that preserve unknown fields, deletion and attachment changes. Verify and preview text/image attachments in memory without exporting decrypted files.

- Scope the README and its translations to Android; remove extension installation, browser tooling and other-platform status notes, and clarify autofill in Android apps and mobile browsers.

- Fix overlapping wallet cards and delayed placement during selection-mode reordering; lift the whole row and update independent-card order immediately while preserving stack groups and saved order.

- Animate database list expansion and collapse in filter menus while preserving horizontal scrolling, large-list virtualization and footer actions.

- Fix the breadcrumb sync icon staying still during background auto sync. Both vault views now observe the current vault's running task and stop animating when it ends.

- Fix manual and automatic MDBX2 sync getting stuck on missing local attachments after an edit. Recover and verify attachments before publishing changes, and allow interrupted transfers to retry. Missing or corrupt remote attachments still leave changes unsynchronized.

- Upgrade cleanup removes only the two retired settings, preserving other preferences and vault data. Old backups remain supported; new backups omit the draggable option.
- Fully remove the old HyperOS Passkey verification bypass, preserving normal biometrics and master-password fallback. Passkey creation can use an existing master password if biometrics are unavailable or fail.

- With automatic sync enabled, durable jobs upload saved changes. Idle remote checks gradually slow to once every five minutes while foregrounded and unlocked. Bursts share queued work, and edits made during an upload are delivered afterward. Disabling automatic sync lets an active upload finish.
- Automatic and manual sync share the same queue. Failures preserve local edits and retry with backoff; consecutive saves do not cancel an active upload.
- Preserve unpublished edits when refreshing an external vault after a failed write-back.

- Camera and gallery imports for front/back photos support cropping, rotation and flipping, or skipping the crop before the existing quality confirmation.
- Custom card artwork still requires cropping and has no skip option.
- The crop screen uses consistent rotation, flip, reset and confirmation controls.

- WebDAV conditional uploads preserve 503/429 retry delays and write preconditions when retrying.
