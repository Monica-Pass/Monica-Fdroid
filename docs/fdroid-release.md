# 发布 F-Droid 更新

F-Droid 官方已启用 Monica 的标签自动更新。常规版本发布在本仓库完成，无需每次向 fdroiddata 提交 MR。

2026-09-16 核对的[官方配置](https://gitlab.com/fdroid/fdroiddata/-/blob/master/metadata/takagi.ru.monica.fdroid.yml)：

```yaml
Repo: https://github.com/Monica-Pass/Monica-Fdroid
AutoUpdateMode: Version
UpdateCheckMode: Tags
CurrentVersion: 1.0.311
CurrentVersionCode: 18
```

审核员在 [MR !48681](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/48681) 合并后建议使用这条现成的自动更新流程。无需添加 GitLab Token、Webhook 或自动提交 MR 的机器人。

## 本次发布：1.0.313

源码已准备为 `versionName "1.0.313"`、`versionCode 20`。完整发行说明放在本仓库根目录的 [Monica F-Droid发行说明.md](../Monica%20F-Droid发行说明.md)，包含中文和英文的简要、详细说明；F-Droid 客户端摘要另存于 `fastlane/metadata/android/<语言>/changelogs/20.txt`。准备工作不创建标签或 Release。

1. 打开本仓库的 [Actions](https://github.com/Monica-Pass/Monica-Fdroid/actions/workflows/check-fdroid-release.yml)，确认待发布提交的 **F-Droid release readiness** 检查通过。也可点 **Run workflow**，选择 `main`，填写 `v1.0.313` 做只读检查。
2. 打开 [Releases → Draft a new release](https://github.com/Monica-Pass/Monica-Fdroid/releases/new)。仓库必须是 `Monica-Pass/Monica-Fdroid`。
3. **Choose a tag** 输入 `v1.0.313`，选择创建新标签；**Target** 选择已检查的 `main` 提交。
4. 标题填写 `Monica 1.0.313`，可从根目录发行说明复制对应语言的简要或详细内容作为发布日志，然后点 **Publish release**。无需上传 APK，F-Droid 会从标签源码自行构建、签名和分发。
5. 等待 F-Droid 定期检查、构建和更新索引。可查看[官方元数据](https://gitlab.com/fdroid/fdroiddata/-/blob/master/metadata/takagi.ru.monica.fdroid.yml)、[构建状态](https://monitor.f-droid.org/builds)和[应用页面](https://f-droid.org/packages/takagi.ru.monica.fdroid/)。发布时间由 F-Droid 队列决定，不会在 GitHub 发布后立即上架。

F-Droid 直接扫描 Git 标签；GitHub Release 的正文、是否标记为 Pre-release 或是否上传 APK，都不决定它是否发现版本。当前官方规则没有过滤测试标签，因此本仓库只给正式版打标签，测试版本使用分支。

## 以后更新

每次同步新版源码时，先在 `app/build.gradle` 中准备新的静态版本信息。例如下一版使用 `versionName "1.0.314"` 和 `versionCode 21`，并同步 `BASE_VERSION_NAME`、`FULL_VERSION_NAME`、`BUILD_DETAIL_TAG` 与 APK 文件名中的版本。`versionCode` 必须高于此前发布的所有版本，仅修改标签或 Release 标题不会更新安装包版本。

完整发行说明统一更新根目录的 `Monica F-Droid发行说明.md`，保持中文／英文、简要／详细的结构。同时准备 `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`，最多 500 字符；需要中文等语言时，可在对应语言目录下增加同名文件。F-Droid 客户端读取这些摘要，GitHub Release 正文可以写更完整的日志，二者不会自动互相复制。

源码和摘要提交后，发布时仍然只需检查通过的提交、创建对应标签、填写 Release 日志。已经发布的标签保持不变；修正已发布版本时增加版本号和 `versionCode`。

## 检查与边界

仓库的只读检查会验证版本字段一致、标签匹配、版本代码递增及摘要文件完整性。它在相关文件变动、`v*` 标签推送时自动运行，也支持手动运行：

```sh
python3 scripts/check-fdroid-release.py --tag v1.0.313
```

此命令只核对标签名称，不创建标签。工作流只有源码读取权限，不修改版本、不发布 Release、不构建 APK。标签推送后的检查无法阻止 F-Droid 已经看到该标签，所以发布前先检查待发布提交。

自动更新沿用官方配置中最新的构建配方。普通源码和版本更新无需 MR；若以后更换仓库地址、模块路径、Rust/NDK 工具链或需要额外构建步骤，仍可能需要一次 MR 调整配方。检查通过也不等于 F-Droid 官方构建已通过。

依据：[F-Droid 构建元数据参考](https://f-droid.org/docs/Build_Metadata_Reference/#AutoUpdateMode) · [标签检查规则](https://f-droid.org/docs/Build_Metadata_Reference/#UpdateCheckMode) · [更新摘要格式](https://f-droid.org/docs/All_About_Descriptions_Graphics_and_Screenshots/)。
