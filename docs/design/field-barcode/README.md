# 详情字段条形码（1.0.315）

[编辑 M3E Canvas](https://lnkiai.github.io/m3e-canvas/#docz=3VZbTxtHGP0r0TzwtIB3fcGshKr2IVVU5a1vFarW67G9Zb2z3R0SKEICgrkoJk0RDpgUgSlqIBQobdKAsUHqX8HjsZ_4C83srO9ctpRKtH6wvTM7M-ec75vvfGMAa1iHQAaPkaGpyoM_PzyoHPxcfpYie8vl_ffltRwpbtKNSSCAiKXBGJBB8zxdWi_PvaSr03Rjkrw-LR29oM9_obvP6-uquWP6ep-kDunSNjnM0K08PZkunfxxPjFVOlujmSzZekUOp8hCjs7Pkh9nKm8WLwppMpMih1M0W6T5I7Yn3_xsrZyepOs7pLh5UUjTbLFyekz20-XMu4tCuryWo_nj0tECOV2ubO6SudnKm8Xy3Cta2KFvp88npoAAYpaSZFzNBDIgEICpKziGrCSQgWJELaRF2aCiQ4zhF3AUyABDRQcCwAnIFo6BqGINARlbw1AAEYQTj1EU2rUBO6GYbHsLDRtRyPaKIQM7AxGEERBAEmENGUAGcMS0oG1rTyAYd3HZQP5qDGhRIIOYBnW23OBwOwUHAhgBsk8Ao863gTB7j2wtMJVfpktHE6Wjt1y1ZtEvCqtcbprJ0mxRDEdNJqSjKBebzKSqz7bdaYlP820cncsrp3Qrfz4xxdTemKyczZL9IpnLkkKGHBRKxTO6tF3Kp-nJO7oxeVFIs3AU8pVcmhZ2yK_fsyiMCy5JaGBrtEHy0rzhPAP90n-S6aAA4hYaNtsj2x33tQVQGdFsIIMRIAANw2THAk1kM6qTOqqObJa8TxRLU5z0imm67qTbkGawNRiZn5rmZ4oFBKArEah_fIfzKR3l66B7OTMwPlgPiYtO5OiksANP9EseAEp1gMawrl-LDo7gJmDkYIaXF1v7DgJZ8nXgYTdUwbYLimsmiYGbQX1rNU6NDGOMjKZz6zpcCRwNY10zHOgcmxgOtEFj88p1pzTXz1r8ElAdaokfRoZTZRqnDDYy_cU6XVqv5t5X1zZ51pGFTHVinszNsPKcnq8u7rdcKzeGUqtc_V5i6PccQ1Wxok0s69j9fRL_K7EQhRmLR0klDmsVUkWWAS2bVVKsA1kMCQBb_DfiPkec5_HOrPRzRn6elX7Ry7UJ_HNG_lCdkRiWbmLk44R8nI-P0_FdwiZwCzZBz2w0B6IXOt7wC8C21I9OlMDYtOXe3shTzfzG7lZMrScJsRLTEbJ6VJTs_SSiatEBFUWhKIW72FUf-PzRwy-7RckfCIb6wv2-LltVdDjg70pALZ7AA1KgJ9gVUdShuGObKtKRNfDQ-XSZSjSqGfEBMXRJlQrWqlTAkTDIpLxRwtCVErpl6aqLTFK_V5f3GpfU11mn4qGWO9fnC3kA1HfbutnW_tT6r4XKT5O8jWpgDTVBdVy3O97X4axXI-VLtHCjhCmWhZ5-zYL2t32oVFglP6yQhRzoBBXmoIKugDcYjwur_7YC1pGwnpd8-K10tkb2VujqdHl-l6k5kyL7x5f7Ej-6xZeCouTFmPjKf9OY-An30JjcIPe36XW9M7lBFn3305pcTqLbzwX9Pi_lvEZKvH_uVCMk3oqQ9ybwf21QNRGl1lR3xLxZxLvtwkQpdLep7m_NjGDgWperkfLeiLXbXEclvszOxEAbKm9VxXtD1YaqLWWaSuQl2IKtaXBDX1DDdnWncreNweD4Xw)

参考现有独立条形码项目：外层卡片留白 18dp，内层矩形白纸留白 12dp。详情字段与独立项目共用同一展示组件。条纹不裁圆角、不挤压，保持二维码切换和错误提示。

## 实现

- 将独立条形码项目的预览提取为 `BarcodePreviewCard`，详情字段生成页和独立条形码详情页共用。
- 外层 16dp 圆角卡片、18dp 留白；内层矩形白纸、12dp 白边。码图不参与圆角裁切，使用最近邻采样，二维码保持正方形。
- Code 128 根据卡片实际测得的宽度生成，逐项扣除留白的像素取整值；高度为原始矩阵高度加两侧白边，避免取整后少一个像素而触发整体缩放。过宽时继续提示使用二维码，保留不支持字符的提示和状态恢复。
- 独立条形码页的固定高度、内容复制和编辑入口保留。

## 验证

- 普通版、F-Droid 均成功构建 Debug 应用与测试包，各通过 6 项定向 JVM 测试、8 项设备测试。未执行本轮 Release/R8 构建。
- 公共 API 32 / x86_64 AVD，覆盖 320dp 与约 411dp 宽度、1.5 倍系统字体、浅色与深色。
- 从真实字段菜单打开码图后，以 ZXing 解码页面截图并核对原始内容；验证中文/表情二维码、Code 128 不支持内容、超宽提示与切回二维码、状态恢复。
- 截图逐像素检查四边及四角为白色，Code 128 条纹完整且保留 360px 原始高度。首次检查捕获 359px 取整缩放，修正后两版通过。
- 保存条形码项目所使用的预览组件按原尺寸独立渲染并验证扫码；共享组件的生产调用已在独立详情页接入。
- 运行前后核对应用和测试 APK 的 SHA-256；测试结束恢复公共 AVD 原来的 840×2100 覆盖尺寸，保留原本已启动的虚拟机。
- 两版本地 main 与对应候选源码一致，1.0.315 发行说明和 F-Droid changelog 22 已同步；本次仅修改四个源码/测试文件，保留此前所有改动。未提交、推送或发布。

## 实际渲染

截图为真实 Compose 页面，使用合成测试内容 GIFT-1234567890：

- [深色条形码，320dp](device/barcode-field-dark-code128-320dp.png)
- [浅色条形码，320dp](device/barcode-field-light-code128-320dp.png)
- [深色二维码，320dp](device/barcode-field-dark-qr-320dp.png)
- [浅色二维码，320dp](device/barcode-field-light-qr-320dp.png)
- [深色条形码，411dp](device/barcode-field-dark-code128-411dp.png)
- [浅色条形码，411dp](device/barcode-field-light-code128-411dp.png)

构建日志、设备测试记录和源码核对结果保存在工作区 `.codex-tasks/field-barcode-315/`。
