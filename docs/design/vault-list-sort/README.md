# 密码库列表排序 · #144

[在 M3E Canvas 编辑](https://lnkiai.github.io/m3e-canvas/#docz=eNrFl01vGkcYx78K2l6JxMy-sHBLemxy68mVZS0whJWXXbQsLq5liaRt4qaNHTkuRH5pGiuR3UNaK0EuYEuW-lEadjGnfIU-s0NrBmxmsVVysZjxMzP__f_meWZmRfJMzyJSWrrn2GbWiP31Z8z_41Hv1wd-53mwvul3NmKfIUWR4lLGNUkeAv21xvmrQ3_jfbf15PxgM9hp-q-3Y38_2oyx-KDe9k83PtQeBu_OeluH_vrL3tbLbuukv_-i21r3Oz_3vz3s7-0He2cfT3f6tR-CH3_zn773O1s0uH3cPdvz376A4f73hxDJxHyoPQjqj7snx_7rtr_5U_Bst9fc7508D37Z7bZq54-bEA8S865RpN9SKjg2gXbJMry84xahy7BzrmPmaKdhEc8jX5BlGllxSxYN9QqEDl2Rcoa7KKXzhlUm8M2OV7jn5EhZSntuBTqyju25RtmDoWUP5jRcOmW5YJTouq5TsXOE9uQhjsYslz1ShHbR8UzHhh5SLbmkXDaXiLQ6EAyTf7UigTaId1wPom32GcN-Qm9VSifi0nL4N3OfRlfcvJGFiebj0n1YuzQ0kwnr3kqwUVgJhyl6XDKqJgRBKx5GXDZg0bRp2yNVKmXJcE0j_JZBh2VkiEW3wRAeaoH5DWErZRwrx-xanV-ND8-OmByZCgE9sibUgy70mGD9nYrngYuXqKL_hVbRccnCEglNZIroWv9KZlv14-k2Z-yoSsyZhpAqVImju4YTWPPbzVSwu4b1oPHmP51IGdUhMx0IMx0R6MkXOjJO9TIZbC1Z19lPLKU1-Jk3LetiO30OW9cwbeLedb6GIa6RMyvlL50Ss4Q17zgAokh7RlUrA_cYYqSlhKqV6O7NkYwblqigfuSfdHrb343sP6SN6lGHEwdHMFGNaKJCwQxMVJNXuzhioT5qYWJUscbtP6yL958W3cGRkhIla5OcHhknhXqS0fX0aw-h7Hc766yc9F-1ezu_T6j2E_JF5_JFTmKhTv0a-aLeKF_GRKcG5iYGosXmpqYsiVSAs5AJwxYqdrZAsovhETVeH9lhPFYQ0eAYSWpMpC7OaTTNQfLsae_gCKry7fASMTchmRHiGCuyIlaC_mfIYsYIc5AVOYJ_ePaUZY6yoiKxSvkalOdCyrcnUVZ4yqkI9wTlhpQJ2AiXueUrynYEyCoHWU1EsE-9CWQB4m7r7ThijUOsIlmscYqzxV_bgUM5aBz3G016yaofsVdB42AS7CQHW1XFJzRKfvqU1nnaWgQn9dmndIrnnRRfJVDq-rwbB4x3_WgCb5zgeGvUQdH1OvHJeWPE8dZwhEcBmjlvjDnemiy-TeAp3i7wfAK2U-Y35p8xmi6-lmF51rzHnzFY4YHrEaxUZg9c5YGnxDcLrF4f-FUJPr_6D7V1Qqs)

从列表右上角「更多 → 排序方式」打开。默认名称 A → Z；选择立即应用并保存，无额外确认。六种排序对全部项目、文件夹、类型筛选及搜索结果一致。文件夹入口顺序保持原有规则。日期排序按本地日期分组，名称排序保留首字母分组与中文拼音。

面板可滚动，整行可点选，12dp 侧边距，外侧 24dp、相邻 4dp 圆角。遵守先前 Canvas 网页访问限制，仅生成可编辑文档，真实渲染通过 Android 测试检查。

## 排序约定

- 名称默认 A → Z，继续使用现有中文拼音归一化；数字与符号归入末尾的 `#` 组。
- 创建与更新时间直接读取现有条目元数据，选择排序不写入条目，也不触发数据库内容同步。
- 相同时间按名称、类型、稳定条目 ID 排列，避免刷新跳动；日期未知的条目始终位于末尾。
- 通行密钥没有独立修改时间，更新排序使用创建时间，不把最近使用时间误当成修改时间。原生 API Key 摘要未暴露创建时间时显示在日期未知组。
- 排序存入本机设置，并随页面设置导出／恢复；未知的未来排序值回退为名称 A → Z。
- 选择是密码库列表的统一偏好；概览的最近项目、常用项目与收藏预览保留其自身含义。

## 验证范围

JVM 测试覆盖六种顺序、首字母／日期分组、稳定排序、未知日期、源数据不变、保留快照键以及本地／MDBX／KeePass／Bitwarden 文件夹和搜索。设备测试操作真实密码库页面、底部排序面板、文件夹搜索、概览返回、设置重新读取和导出恢复；检查深色主题与 1.5 倍字体。

## 验证结果 · 2026-09-28

主版本与 F-Droid 均通过 88 项 JVM 测试、15 项设备测试及 Debug / AndroidTest 打包。另对文件夹搜索补充键盘搜索键收起后的顺序检查，两版各通过一次定向复测。测试复用已运行的公共 API 32 / x86_64 虚拟机，未新建或停止它。未构建本次 Release 包，未复制安装包到交付目录。

设置导出读取同一份已提交的 DataStore 快照，避免刚修改排序就导出时读到 UI 通知缓存中的旧值。原生排序不会修改条目时间。两版发行说明已同步；两个 F-Droid changelog 均未超过 500 字符。

以下均为合成数据；1970 年日期用于固定时间排序测试，不是用户密码。

- [排序面板](device/vault-sort-sheet.png)
- [创建时间排序](device/vault-sort-created.png)
- [文件夹内搜索](device/vault-sort-folder-search.png)
- [深色主题与大字体](device/vault-sort-dark-large-text.png)
- [机器可读验证记录](verification.json)
