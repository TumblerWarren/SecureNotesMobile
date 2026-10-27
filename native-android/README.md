# SecureNote Native Android

Native Kotlin/Compose rewrite of SecureNotesMobile. All vault data stays local. The app has no network permission and provides its own Android-backed viewers, without external-view intents, plaintext export, sharing or printing.

## Features

- Password-only sessions, independent offline recovery key, password rotation, immediate lock on pause/screen lock and five-minute foreground inactivity lock. Playback stops on lock.
- Native libsodium Argon2id (64 MiB, three passes), random 256-bit master/recovery keys, authenticated key wrappers, SQLCipher metadata and encrypted full-text search.
- Tink authenticated streaming files with independent keys, bounded import buffers, atomic commits, orphan cleanup and revocable 64-bit random-access readers.
- Paged collections; encrypted thumbnails; native tiled image zoom; paged UTF-8 text; isolated native PDF rendering; Media3 audio/video playback for supported Android codecs.
- Native rich-text blocks, lists, links, tables, colored drawings, attachments, search and pinning. Saves update individual blocks rather than serializing the database.
- Read-only legacy importer: decrypts the old database in an isolated process into memory, converts notes and embedded media, retains original HTML inside encrypted storage, and leaves the source untouched.
- JVM security tests, Android functional/security/performance tests, a >4 GiB bounded-heap test, Macrobenchmark and profile generation tooling.

Arbitrary files can be stored. Office document previews, password-protected PDFs and unsupported media codecs may not render internally. The app does not delete original plaintext files outside its vault. Recovery unlocks existing data; it cannot restore deleted files. See the [security contract](docs/SECURITY.md).

## Build

Requires JDK 21, Android SDK platform 36, build tools 35, NDK 27.2.12479018, CMake 3.22.1, Python 3.12+ and make. Set `ANDROID_HOME` or an untracked `local.properties` with `sdk.dir=/absolute/sdk/path`.

```sh
python3 tools/build_sodium.py --ndk "$ANDROID_HOME/ndk/27.2.12479018"
./gradlew :core:test :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease :app:lintDebug :benchmark:assembleBenchmark
```

The sodium script builds checksummed upstream source for ARM64 and x86-64. Generated source/binaries stay ignored. Native libraries use 16 KiB-compatible linker alignment.

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`, with application ID `com.anonymous.SecureNotesMobile.nativepreview`; it installs alongside the existing application. Production builds retain the original application ID and require its original signing key. Release builds are unsigned; no deployment is performed by building them.

## Verification

```sh
./gradlew :core:test
# Creates/removes a synthetic 4 GiB + 65,537-byte file; requires >5 GiB free disk.
./gradlew :core:largeFileTest
# Requires an Android device/emulator; test vaults use separate random directories.
./gradlew :app:connectedDebugAndroidTest
```

Compiling device tests does not execute them. The host large-file test uses a 128 MiB JVM heap and checks seeks beyond 2 GiB and 4 GiB; its results do not establish Android performance. See [performance procedures](docs/PERFORMANCE.md) for device benchmarks and profile generation.

## Release gates

The implementation needs executed Android security, viewer and lifecycle tests; generated device profiles; physical 4 GB device measurements with the 10,000-file/100 GB workload and 20 GB videos; and independent security review before a production release. Keep another copy of important data during validation. No claim of independent audit or measured physical-device performance is made.
