# Italian locale — 1.0.313

Italian is available in Settings and Quick Setup through Language.ITALIAN, using the `it` locale (including `it-IT` resource matching). It is appended to the enum; settings continue to persist enum names, and the startup cache uses the existing path.

The language option reads “超级马里奥语” in Simplified/Traditional Chinese, Classical Chinese and Nya. Other locales display the recognizable native name “Italiano”. The existing language dialog layout is unchanged. The editable M3E sketch is linked in [design/language-selection-m3e.md](design/language-selection-m3e.md).

The pack covers all 6,157 translatable resources across 33 XML files, including GPG, credential exchange, backup, database management and runtime errors. Existing non-translatable native language names/brands continue to use their defaults. Italian plurals include one, many and other.

Translation workflow: offline machine-assisted draft using Helsinki-NLP opus-mt-tc-big-en-it (CTranslate2 conversion), followed by review of common actions, every GPG message, technical tokens, abnormal length/repetition, multi-sentence omissions and examples. This is not a claim of a native-speaker review of every sentence. Models/runtime remain outside the repositories.

Static validation covers resource names/types, XML, duplicate keys, format arguments, named tokens, escaped line breaks and replacement characters. Device tests cover language/region resolution, switching, persistent settings and startup-cache reread, service-message refresh, all packaged string formatting, plural rules, and the Chinese nickname in the actual language dialog at 1.5× font scale.

Release notes now present GPG once per language as a new feature, omit its pre-release repairs and duplicate action descriptions, simplify editor-opening performance details, and include the user's Italian/Mario joke verbatim in Chinese.

## Results

- Main debug app and instrumentation build passed (build 12, 1.0.313). Five unit tests passed: LocaleResourceCoverageTest (3), StartupLanguageCacheTest (2).
- Final API 32 x86_64 device run: 19 tests passed in 26.145 s — Italian locale (7), Italian UI (4), all locales (4), language dialog (3), GPG localization (1).
- Additional 320 dp / 1.5× Chinese nickname test: passed. Main suite used 411 dp, with explicit 360 dp dock and 1.5–1.6× font scenarios. Inspected native dialog and Quick Setup screenshots and checked text bounds.
- Initial all-locale run found existing Nya leading-space trimming in sync_status_with_vault. Escaped the leading spaces in that label and its matching default-value suffix in both variants; the final all-locale run passed.
- The final text-only repack reused compiled Kotlin only after checking every production Kotlin/Java source hash, every generated R class/ID and production BuildConfig against the successful build. BUILD_TIME was frozen in a temporary Gradle init script to avoid unnecessary full recompilation; no production build script changed for this workflow.
- Device language settings were restored by test teardown. Restored the shared AVD to 840×2100, 420 dpi, font scale 1.5, then stopped the task-started emulator while retaining its data.
- Main and F-Droid integration/resources are mirrored. No separate F-Droid binary was device-tested. Existing default-resource warnings remain outside this change.

[Native screenshots and test logs](italian-locale-device/). The full compile logs, translation cache and reproducible resource checks remain in workspace `.codex-tmp/`.
