# Continuous editor menus and adaptive DPI (2026-10-21)

## Change

- One shared M3 menu surface with 24dp outer corners and 6dp shadow replaces separate rounded item cards. Items have no individual background, clipping or gap; destructive actions retain the error color.
- Replaced the fixed `224.dp–320.dp` width range with `screenWidthDp × 0.76`, clamped to `224.dp–320.dp`, so display size and DPI changes recalculate the menu width from the available screen.
- URL matching, move, delete, and save behavior are unchanged. The ordinary and F-Droid editions share the same implementation.

## Verification

- Both editions passed `:app:assembleDebug :app:assembleDebugAndroidTest -PincludeX86TestAbi`; the shared menu source SHA-256 matches.
- Public AVD `Monica_Issue136_API_32`, Android 32, isolated user 10: ordinary edition at DPI 420 and F-Droid at DPI 280 each passed `ProjectCredentialEditorTest#urlRulesMoveWithRowsAndReopenAfterSaving`: open menu, select match rules, reorder, save and reopen.
- Native screenshots show one continuous surface with a red delete action. Previous large-font checks predate this final style and are not counted as a separate verification of it.
- Initial attempts were blocked by the test user's lockscreen and had no Compose hierarchy. Reruns passed after temporarily disabling that test lockscreen. Restore density, lockscreen and test settings after testing; reuse the existing public AVD.
- Local M3E Canvas project `editor-menu-continuous-321-20261021` was rendered and inspected. Editable design and screenshots remain in the ordinary edition's ignored design directory.
