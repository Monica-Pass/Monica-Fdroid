# Password icons and field barcodes

Shared changes for Monica 1.0.314 / F-Droid versionCode 21:

- Password icons support Emoji, launchable application icons, and compatible ADW/Nova/GO icon packs. Selected artwork is copied into Monica's existing uploaded-image storage and remains available after the source is uninstalled.
- Picker searches and icon drafts survive rotation. Leaving an unsaved editor removes only its temporary image. Compact landscape grids keep icon names visible.
- Password field menus display Unicode QR codes or Code 128 for up to 80 printable ASCII characters, with quiet zones, integer module scaling, error recovery, and rotation guidance for wide barcodes.
- Shared translations, tests, Unicode data/license, editable M3E Canvas documents, and the existing Steam card spacing changes are synchronized.

The F-Droid manifests retain their edition-specific exclusions and add only the icon-pack queries and test fixture. Dependencies, application ID, static versions, native source builds, and unrelated working changes are preserved.

Updated release text: [full bilingual notes](../Monica%20F-Droid发行说明.md), [English client summary](../fastlane/metadata/android/en-US/changelogs/21.txt), and [Chinese client summary](../fastlane/metadata/android/zh-CN/changelogs/21.txt).

Editable designs: [installed icons](design/password-installed-icons-m3e.md), [Emoji](design/password-emoji-icon-m3e.md), and [field barcodes](design/password-field-barcode-m3e.md). Each document contains its M3E Canvas share link.

## Verification deferred

The user requested that F-Droid verification wait for a later combined review of all changes. Main-edition tests and device results do not establish F-Droid compatibility.

An already-started F-Droid build reached JVM tests and failed in `FossBuildBoundaryGuardTest`, `LocaleResourceCoverageTest`, `FrozenVersionCodeGuardTest`, and `VersionMetadataRegressionGuardTest`. The locale report listed four missing Italian resources: `webdav_allow_insecure_http`, `webdav_allow_insecure_http_description`, `fdroid_cloud_provider_unavailable`, and `qr_image_read_failed`. The final compact-grid update was synchronized afterward. F-Droid packaging and device verification remain pending; no further F-Droid validation was run after the user's instruction.
