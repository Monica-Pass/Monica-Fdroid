# 设置搜索

[在 M3E Canvas 编辑](https://lnkiai.github.io/m3e-canvas/#docz=zVjfb9pWFP5XkJ-ZxLUN2Dx2L5umTnvY2xRVDlzAqrGRbSKyKFIVFYVU-aUmaVpg7VJpapWqJNq6tUmTIO1P2bi2eeq_sGN8CxhMe-PmYRIC7vG9x9_5zrnn8_UKZ6u2hrkcd9vQ1byS-PttgjTe9i_3vO6Ve9kdHJ05ux33zXMuyS2aKi7CzMDgvt9znnbIzjE5ufCax__eW3PXzsj6e6_XJo3fyPazjw7-_HCxSbqt_uWW2-46v65_uGh7vX3Sftrv_eIePCGNPwb7Xe9kCzzATYqmUvHhVMuGjmFc1RS7aJgVMCl6wTTUgm9UNGzb-Du87M-smVXNn2qXsb90hSso5l0uV1Q0CwNqwy7fNgrY4nK2WQND3tBtU7FsWGrZ4FMxfZdWWan69zWNml7AvqUI8_w5y5aNKzCuGLZq6GDB9aqJLUtdwtwqBQzOf1rhAFsuGH-VggV6EElA1ySpcK3O5VJJbnn4vVjyb1Mzi0re9xjyg8Z-AnrJ7nZAZuBEzDC54Sfg_H7kdDbI5tmgsQXZDdzI_Bw3C0muBJRUJwK0sGLmy8MIYSUvDheK8KPUVZgGoySnAmXRS-6qum-xcd2G0ZJiqsqQZmpQ8z7Fek3TkpymLGINLo1Is9SfsX_H1YVReNQzCsAgfggGpdIMaNAYzaJRZwUzgiFIUvCX53LpDNSBqmlj5r6G4lFUHZvfqKUyrDGVglqzfjSqgF_6OLxl2LZR8S2zEfFBREImiIjnGSLi4_EL289tP04MWrve4c4oPpSZBSWEco6kDAMoIR4olIDinMo8isi8GMo8zyMGSOLNZR6J_NzUT6VdnEl7RDhpmnYajiAzhJOOmfZhvp2DU_L60NncgCoYPL_vvWiO6Y4oy0wYX5alAjIxt_3VHghJ4odvv084h39RtEOc_XcPnEdn02gj2MyG0Ap-k_ss2uyXNKnEP_fOE976MXnwkhy9Io3G0EClNdhnY8D8LGCJAhYpvyLLtpfGiH1kt2pQXvp83Fy-jJdMQ79jQmvyzTMFLqZmkclUb6Rr9Hs5JpUT_H2666fCoBBiSTCKKUNzshixSRCVIzFL9SgtseC6QUGS5gvS57pSFM9UjESZxpNliieuHLV2nM65s_2QNA-D7gSUf1KWkBDmmxeZ8Ak3xzdC4phwDKvgqXJ5DuVMOoDEMOV8hqXTIjEu5TGkAKXDEIUUS09AMdXqBtQAUfHKyhJVL6bHxAn5YmqvtlEqafiOUSyy9laUnWqubH0splIFxwc4R7jPXjuttcGrx_3zLhzKSOel13syWN_s97rO_syzlxCBm8qVLF5DFJAUswAYTgFUpGThWueACZn6Px4EqMjJ6WsdBWKKXHA6hDrovzueroCIzstTpZOlNO0BLJXLoy8B52xckebp6PXEuCIidhZPlUvOUHxMD1R8TOXyTg78zxAZ2d1yX5w6zUdO-w1sNLd1f_SiI9ykFlb_Aw)

结果展示具体设置名称、说明和路径，不含开关、滑块或执行按钮。点击进入原页面并短暂高亮目标；返回保留查询。索引仅包含应用静态设置元数据，不包含密码、账号、备份地址或用户数据。

## 实现与维护

- `SettingsSearchCatalog.kt` 是静态、本地化的设置目录，包含名称、说明、路径、别名、目标路由和定位标题。只收录当前页面实际可达的设置；合并或移除旧页面时同步调整目录。
- 多词查询要求每个词都命中名称、说明、别名或路径；兼容英文大小写和全角字符，名称完全匹配优先。
- 搜索结果只有导航点击动作。主设置页内的条目也通过独立导航记录打开，原查询和返回栈保留。权限请求、开关、清空和开发者认证仍由原页面处理。
- 原页面共用 `settingsSearchAnchor`：等待布局完成后滚动定位，高亮约两秒。折叠卡片在定位时展开；关闭的功能不会因为搜索被启用。
- 新增索引时需要确认对应页面提供唯一定位节点，并将该页面加入 `SettingsSearchInstrumentedTest` 的真实页面导航测试。不可把预览卡片或弹窗标题当成实际设置行。
- 普通版和 F-Droid 共用搜索组件、导航行为及测试；F-Droid 目录排除该版本已移除的 OneDrive 入口，保留渠道能力差异。Plus 专有选项仅在已开通时索引。

## 验证记录

2026-09-28 验证完成：

| 检查 | 普通版 | F-Droid |
| --- | --- | --- |
| Debug 应用及测试包构建 | 通过 | 通过 |
| 搜索匹配单元测试 | 5/5 | 5/5 |
| 设置页安全下拉回归 | 4/4 | 4/4 |
| 真实页面设备测试 | 6/6 | 6/6 |
| 具体设置唯一定位节点 | 92/92 | 91/91 |

复用公共 Android 32 AVD `Monica_Issue136_API_32`，通过 ARM64 转译运行两版 APK。覆盖具体项搜索、点击不修改偏好或执行清空、返回保留查询、全角英文别名、深色 320dp / 1.5 倍字体、密码字段的 LazyColumn 定位、空结果以及目录中每个具体选项的真实页面定位。Plus 已开通的专属项也纳入逐项测试。验证范围为 Debug 构建和该 AVD，未做全部 Android 版本或 Release 包设备矩阵。

[M3E Canvas 编辑链接](canvas-url.txt)、[机器可读结果](validation.json)、[普通版设备日志](main-device.log)、[F-Droid 设备日志](fdroid-device.log)均随文档保存。截图只截取应用内容，不包含系统键盘、剪贴板建议或通知栏。

### 真实渲染

![具体项搜索卡片](device/settings-search-keyboard-results.png)

![进入原页并高亮](device/settings-search-keyboard-target.png)

![深色窄屏大字体](device/settings-search-dark-large.png)

![无结果状态](device/settings-search-empty.png)

F-Droid 对应截图保存在 `device/fdroid/`，已核对相同交互和布局。
