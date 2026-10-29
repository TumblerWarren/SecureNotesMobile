# SecureNote Native Android

Native Kotlin/Compose rewrite of SecureNotesMobile. Vault data stays in app-private storage until the user chooses an export or backup destination. The app has no network permission and provides its own Android-backed viewers. Android document providers can receive explicit exports and encrypted backups.

## Features

- Password-only sessions, independent offline recovery key, password rotation, immediate lock on pause/screen lock and five-minute foreground inactivity lock. Playback stops on lock.
- Native libsodium Argon2id (64 MiB, three passes), random 256-bit master/recovery keys, authenticated key wrappers, SQLCipher metadata and encrypted full-text search.
- Tink authenticated streaming files with independent keys, bounded import buffers, atomic commits, orphan cleanup and revocable 64-bit random-access readers.
- Paged collections; encrypted thumbnails; native tiled image fit/zoom/rotate; paged UTF-8 text; isolated native PDF rendering; internal Media3 audio/video controls and same-folder encrypted playback queues for supported Android codecs.
- Encrypted folder hierarchies, a unified folder/file list, breadcrumbs, folder import, colors, moving, renaming, and arranging selected files by type.
- Internal EPUB 2/3 reading for DRM-free reflowable books, with chapter contents, section navigation, inline raster images, internal links, font-size controls and encrypted reading position. See [EPUB support and limits](docs/EPUB.md).
- Native rich-text blocks, lists, links, tables, colored drawings, attachments, search and pinning. Drafts survive block recycling; formatting preserves the native selection; debounced saves serialize only changed text blocks. See [notes optimization research and implementation](docs/NOTES-PERFORMANCE.md), [native notes implementation](docs/NOTES.md) and the [drawing canvas review and upgrade proposal](docs/DRAWING-RESEARCH.md).
- Read-only legacy importer: decrypts the old database in an isolated process into memory, converts notes and embedded media, retains original HTML inside encrypted storage, and leaves the source untouched.
- JVM security tests, Android functional/security/performance tests, a >4 GiB bounded-heap test, Macrobenchmark and profile generation tooling.

Arbitrary files can be stored. Office document previews, password-protected PDFs and unsupported media codecs may not render internally. After a verified import, the app can request deletion of selected originals with user confirmation; provider deletion is not secure erasure. Exported copies are plaintext. Recovery unlocks existing data; it cannot restore missing data without a backup. See [vault transfers](docs/VAULT-TRANSFERS.md). See the [security contract](docs/SECURITY.md).

## Build

GitHub Actions builds and verifies pull requests, Android-related pushes/merges to `master`, and manual workflow dispatches. The [approved Paper redesign implementation](docs/design/APPROVED-UI-IMPLEMENTATION.md) records validation results and links the preview APK, recordings and actual screen captures. Local Gradle startup is blocked by the execution environment’s socket restrictions.

Requires JDK 21, Android SDK platform 36, build tools 35, NDK 27.2.12479018, CMake 3.22.1, Python 3.12+ and make. Set `ANDROID_HOME` or an untracked `local.properties` with `sdk.dir=/absolute/sdk/path`.

```sh
python3 tools/build_sodium.py --ndk "$ANDROID_HOME/ndk/27.2.12479018"
./gradlew :core:test :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease :app:lintDebug :benchmark:assembleBenchmark
```

The sodium script builds checksummed upstream source for ARM64 and x86-64. Generated source/binaries stay ignored. Native libraries use 16 KiB-compatible linker alignment.

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`, with application ID `com.anonymous.SecureNotesMobile.nativepreview`; it installs alongside the existing application. Signed production builds use `com.anonymous.SecureNotesMobile.vault` so they install beside the original app (`com.anonymous.SecureNotesMobile`) with its different signing certificate. The new app has separate storage; original vault data stays in the original app and is not automatically migrated. Do not uninstall the original app to resolve a package conflict. Master builds publish only APKs signed with the configured release key after tests and signature verification pass; missing signing secrets stop publication. Unsigned compilation outputs are excluded from artifacts. See [release signing](docs/RELEASE-SIGNING.md) for setup and upgrade compatibility.

## Build versions

The production release package ID is `com.anonymous.SecureNotesMobile.vault`. Install signed releases from [GitHub Releases](https://github.com/TumblerWarren/SecureNotesMobile/releases/latest); the original app uses a different package and separate storage.

CI passes its monotonically increasing workflow run number as `ANDROID_BUILD_NUMBER` to both jobs. Each new run uses `versionCode = 1000 + run number` and preview version `2.0.1-build.<run number>-preview`. Thus a post-merge build has a higher version than its preceding PR build. Re-running an existing run retains its version; starting a new manual run advances it. The workflow checks the actual generated APK metadata and publishes the version in the job summary.

Local builds without that environment variable use development code 4 and `2.0.1-dev-preview`; they are not newer than CI builds. Keep the same workflow counter, and raise the offset if that counter is ever reset. Future native production upgrades must keep the `.vault` application ID and the same native release signing key.

## Verification

```sh
./gradlew :core:test
# Creates/removes a synthetic 4 GiB + 65,537-byte file; requires >5 GiB free disk.
./gradlew :core:largeFileTest
# Requires an Android device/emulator; test vaults use separate random directories.
./gradlew :app:connectedDebugAndroidTest
```

Compiling device tests does not execute them. The host large-file test uses a 128 MiB JVM heap and checks seeks beyond 2 GiB and 4 GiB; its results do not establish Android performance. See [performance procedures](docs/PERFORMANCE.md) for device benchmarks and profile generation.

See the [other-feature source review](docs/FEATURE-REVIEW.md) for viewer, library, layout and performance fixes and remaining device checks.

## Release gates

Before a production release, the implementation still needs generated device profiles, physical 4 GB device measurements with the 10,000-file/100 GB workload and 20 GB videos, broader codec/device coverage, and independent security review. Executed emulator tests and sampled visual evidence are recorded in the implementation report. Keep another copy of important data during validation. No claim of independent audit or measured physical-device performance is made.
