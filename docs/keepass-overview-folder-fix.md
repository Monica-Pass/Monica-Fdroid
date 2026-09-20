# KeePass overview duplicate folders

Root cause: catalogue/password folders use UUID identities, while passkeys only carry encoded group paths. Overview fallback created another path-keyed folder and displayed the encoded path literally.

Fix: index paths by database before aggregation, retain UUID priority, resolve UUID-less items using the catalogue or other items, decode only display labels. Navigation paths and persistent data remain unchanged. The shared prepared metadata applies to Kotlin and Rust aggregation.

Validation: VaultOverviewKeePassFolderTest 6/6 and VaultOverviewProjectionTest 8/8 passed. Includes combined counts, passkey-only catalogue lookup, unloaded catalogue and reversed item order, encoded slash/plus/percent, separate databases and distinct UUIDs. Robolectric API 29 cached runtime; no device UI test in this run.

The application sources compiled successfully with normal Gradle Kotlin tasks. The final test-only correction and packaging reused those unchanged compiled classes via a temporary Gradle init script.
