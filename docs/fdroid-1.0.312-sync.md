# F-Droid 1.0.312 synchronization

Upstream: [Monica 1.0.312, 90995f0b](https://github.com/Monica-Pass/Monica/commit/90995f0b537c3378d2d843ad8c0f76d876370789). This edition retains application ID `takagi.ru.monica.fdroid`, uses static version name `1.0.312`, and advances its own version code from `18` to `19`.

## Included updates

- Classical Chinese (Huaxia), Polish, Nya with Chinese fallback, and the completed shared translations. F-Droid-specific HTTP, unsupported-provider and scanner messages are also localized.
- Shared M3E database management, WebDAV backup, language selection, multiple-credential editing, and import/export screens; adaptive tiles, smooth expansion and optional looping card stacks.
- ZIP/CSV imports into a selected local, KeePass, MDBX or Bitwarden database, including supported portable passkeys. Database-scoped file exports, encrypted ZIP password prompts, progress reporting, background export and bounded Rust batch writes. ZIP attachment payloads are streamed and checked for integrity; errors identify the affected item and attachment, and failed-file cleanup reports when manual removal is needed.
- KeePass conflict comparison and merging, MDBX API token fields/favorites and faster detail reads, accurate pending-sync status, database-scoped trash, and the upstream Bitwarden and navigation fixes.
- Updated Rust JNI autofill and MDBX source implementations, with matching generated Kotlin bindings.

## F-Droid adaptations

System Credential Exchange is omitted in this edition. The standard Android adapter `androidx.credentials.providerevents:providerevents-play-services:1.0.0-beta01` depends on `com.google.android.gms:play-services-identity-credentials:16.0.0-alpha12`. The AndroidX wrapper does not remove that Google Play Services dependency.

The F-Droid app has no Provider Events dependencies, registration on unlock, system application picker, exported exchange Activity, exchange intent filters or Play Services shrinker rules. Import opens the file workflow directly. The pure CXF/portable credential helpers remain shared source for file migration; they do not register a provider or call Google services. Existing Monica passkey creation/authentication and ZIP-based migration remain available through the open-source `androidx.credentials:credentials` library and existing storage paths.

Google Drive and OneDrive authentication remain disabled, with the previous GMS/MSAL exclusions retained. WebDAV's explicit HTTP opt-in stays off by default and is available in the connection form; the gateway still enforces it. Scanning continues to use CameraX/ZXing. F-Droid's universal APK, static build metadata, signing-block exclusions and optional `NonFreeNet` integrations retain their existing policies.

MDBX is built from the vendored `Mdbx-ffi` sources. The five read-session and bounded sync-delta source overlays match the standard Android sources after line-ending normalization; patch files and [source provenance](../mdbx-engine/MDBX3_SOURCE_PROVENANCE.json) are included. Both Rust libraries continue to build from source. No prebuilt native library or signing material was imported.

## Verification scope

The initial source synchronization used static checks of resource XML, duplicate keys, translation placeholders, source references, dependency/manifest exclusions and Rust source hashes. Existing version, F-Droid boundary and file-import UI checks were updated for this edition.

The attachment-export follow-up passed 17 focused JVM tests covering a generated 70 MiB payload, ZIP round-trip, integrity failures, cancellation, and legacy/chunked attachment decryption. Nine changed standard Android Kotlin files were compiled independently against cached dependencies and compile-only resource symbols. The 22 shared source, resource and test files match this edition byte for byte. No Gradle build, APK packaging, Rust build, emulator run or F-Droid runtime validation was performed.

The official fdroiddata build recipe and F-Droid publication are separate from this source update.

## References

- [Android Credential Transfer](https://developer.android.com/identity/sign-in/credential-transfer)
- [F-Droid inclusion policy](https://f-droid.org/docs/Inclusion_Policy/)
- [Upstream release notes](https://github.com/Monica-Pass/Monica/blob/90995f0b537c3378d2d843ad8c0f76d876370789/Monica%20Android%E5%8F%91%E8%A1%8C%E8%AF%B4%E6%98%8E.md)
