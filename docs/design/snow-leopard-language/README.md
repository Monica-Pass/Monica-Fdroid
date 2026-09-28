# 芝士雪豹语 · PR #143

[在 M3E Canvas 编辑](https://lnkiai.github.io/m3e-canvas/#docz=eNrFlltvEkEUx7-KmWdM9jK7LDzW-KS--WYaM4UBNl12ye6g1IYETbxLNRFr4jXWl6YN2tooYmtM_CqyLDz5FTzT2UIXsLvEqAkhzOyZs7_zP5dhFTGTWRRl0QXHNnPk1PfOqd7ndn_9tn_nVrC7z9eDey_9t9vD51uD3e7gfRul0JJr0gKc6X_4FrQ2g7Wd_ou78GSw2fAPusHW058HD0Inu0_8g4b_aK2_vuNvvJrwxM2-Nntf1vz1j_CBnV73Phj_aNwQB3ufm_6d2_3mhnAO9sGN7rBxN9i-7zf3_C8teDsYA1HBJWUeRaXk2BTWFYuwguOWYYvYedcx83yTWJQxeo6ucMuqW7G4KStRfnQV5Ym7jLIFYnkUQnRY6YKTpx7KMrcKGznHZi7xGBz1GPgkLnfplUiFv9d1qnae8p0C2HGbFY_RMqzLDjMdG3ZoreJSzzOvUFQPgcH5pVUEbFlkEbtYJUUOZItQQgXbj0Qm4EENZaUUWjn8Xiryl1TdAsmBv8UUKgJC5ZhDE15_WhKnZP3wmIFTiNRMMIJV6tBi1oFl0-brJacGiyvENclhRIzWGI_YvAZ0qiGJnwrK6hjcF0zLGhOdARGIaVP3vHMVzrgkb1a9i04FZRXjaLngMOaU-U59sZ46DiELaiyClTl9DLY8xg4xp7gtskQ5oSilUSAK5rm28iLNkySKIFFVRaBIaiyKMkYxoWYWqhClPQuIP4VVznI8OsLBxpjUv7k3fNpGk0xqqI4hkIx4ddQ51Om8Hj57GOztB_uvR1CyPsmAQ12ELIoqxTLgpIXF4woLSztWVxTUgp5bGVXWRFnhqbLCk9BaRDgFp2OhteTCiW6dPS6PVDyp0vQjRXEoaXyv6nNWGgwgmFuXLRhCM-ut3_o0-NgRgcCQHb7pTtVeOtKZqqTEQqYT5308UDT823kykXVpMutTSTcEsSYmoCobscRG8qQH7xq9r4-FYid0Syaqmh7fLZm_qRqOFU2WoqqlE0xgaQ7ZuteH261Y2eToPYAT5E6W_7NwSkQ4nKCLZSW5cKP_SSeppkZVS5I79Q9VixnOCXTDUd0y8YNFxnPcar8dyVMg2tEcDknSmXgSbd4rv0Rzy7Ov_M4HmLzQGVOTV9YjadWSVJb-T5tBkaaY05Gsalr84JPTybMK0wP-ycXMECNy6etqgsKa4wI4axct0ytFARbrvwColome)

沿用现有语言弹窗：中文组内依次为简体中文、繁体中文、喵喵语、芝士雪豹语、文言文。点击展开箭头不改变语言；点选子项后切换语言并收起。保留现有主题、可滚动布局与可访问性。

遵守先前 Canvas 网页访问限制，仅生成可编辑文档，实际效果通过 Android 测试截图检查。

## 发布范围

**彩蛋娱乐性语言，只保留一个版本**（1.0.314）。放在中文分类内，不作为长期维护的独立语言。

来源：[PR #143](https://github.com/Monica-Pass/Monica/pull/143)，原始提交 `a0c40a0ea5c6cf3c2b540d22c3ce5600fda92561`。语言资源和雪豹图来自该 PR。Android 使用 `zh-XB`，缺失项目继承中文；切换到其他语言时恢复常规桌面图标。

后续移除此彩蛋时，应同步处理保存的 `SNOW_LEOPARD` 选项和启动器别名，避免已选择此语言的用户丢失桌面入口。

## 验证（2026-09-28）

主版与 F-Droid 均通过 4 项 JVM 测试、21 项设备测试，以及 Debug 应用和测试包构建。使用公共 API 32 / x86_64 模拟器，验证中文分组选择、1.5 倍字体、深色主题、中文回退、危险操作提示、语言持久化、启动器图标切换与升级修复。

测试在包含此前 MDBX 和密码库排序改动的集成工作区完成。F-Droid 语言包移除了该版本不提供的“从其他应用导入”两项文本。

- [中文分组实际渲染](device/language-snow-leopard-chinese-group.png)
- [深色主题与大字体实际渲染](device/language-snow-leopard-dark-large-text.png)
- [系统加载的雪豹桌面图标](device/snow-leopard-launcher-icon.png)
- [验证记录](verification.json)
