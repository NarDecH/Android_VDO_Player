# AGENT.md — working notes for AI agents

This file is the entry point for any AI agent (or new human contributor) making
changes to **VDO Player**, an Android video player. Read it before editing code:
several things here are non-obvious and cost real debugging time.

## What this project is

A local-file Android video player. Kotlin + Jetpack Compose UI, Media3/ExoPlayer
for playback, NextLib's FFmpeg renderers as a software fallback for codecs the
device hardware cannot decode, and a first-class logging/diagnostics subsystem
whose whole purpose is to make a failure on someone else's phone debuggable.

`app_id` is `com.nardech.vdoplayer` (debug builds install as
`com.nardech.vdoplayer.debug`). Minimum SDK 26, target/compile SDK 37.

## Build and verify

```bash
export ANDROID_HOME=/path/to/Android/Sdk

./gradlew :app:assembleDebug      # fast check that everything compiles
./gradlew :app:assembleRelease    # per-ABI + universal signed APKs
./gradlew :app:lintDebug          # lint is configured with abortOnError = false
```

Verification rules that apply to this repository:

* A change is not done until `:app:assembleDebug` succeeds. Kotlin errors here
  are usually about Media3 APIs; see the gotchas below before assuming a typo.
* There is **no device or emulator in the loop**. A successful build proves the
  code compiles and packages (including that FFmpeg `.so` files are inside the
  APK) — it does not prove playback works. Say so explicitly when reporting.
* `apksigner verify --verbose` must report `Verified using v2 scheme: true` for
  release artifacts. Run it from
  `$ANDROID_HOME/build-tools/37.0.0/apksigner` (`.bat` on Windows).
* The APK contents are worth a look when touching dependencies:
  `unzip -l app/build/outputs/apk/debug/app-debug.apk | grep '\.so$'` should list
  `libavcodec.so`, `libswscale.so`, `libswresample.so`, `libmedia3ext.so` per ABI.

## Repository layout

```
app/src/main/java/com/nardech/vdoplayer/
├── MainActivity.kt        one activity; edge-to-edge Compose; reads ACTION_VIEW/SEND
├── VdoPlayerApp.kt        Application; AppContainer (manual DI); Coil image loader
├── data/                  VideoLibrary (MediaStore), AppSettings (DataStore),
│                          PlayHistory (JSON blob in DataStore)
├── logging/               LogEntry/LogBus, LogFileWriter, CrashGuard, Diagnostics,
│                          LogShare (export/share/save)
├── playback/              PlayerFactory, PlaybackService (MediaSessionService),
│                          PlaybackViewModel (MediaController + UI state)
├── ui/                    AppRoot + library/player/recent/logs/settings screens
└── util/                  Formatters, IntentHelpers
docs/                      user documentation (Markdown + HTML) and the Pages site
```

## Design decisions you should not undo casually

1. **The player lives in a service.** `PlaybackService : MediaSessionService` owns
   the single `ExoPlayer`; the UI talks to it through a `MediaController`
   (`PlaybackViewModel`). This is what makes background audio and the media
   notification work. UI-only state (overlays, aspect mode) belongs in Compose,
   not in the service.
2. **Track selection goes through `TrackSelectionParameters`.** A `MediaController`
   cannot call every `Player` method; commands such as track overrides may be
   refused by the session. Every such call in `PlaybackViewModel` is wrapped in
   `runCatching` and logs the refusal instead of crashing playback.
3. **Logging is pull-based for the UI.** `LogBus` exposes a `version` counter and a
   `snapshot()` function rather than a `StateFlow<List<…>>`. Publishing a 2000 element
   list twice a second while a video plays is the kind of thing that shows up as
   dropped frames.
4. **History writes are throttled.** `PlayerScreen` checkpoints the watch position
   every 5 seconds plus once on dispose. Do not move that back to every position
   tick: each write is a DataStore transaction.
5. **`local.properties` uses forward slashes.** A `sdk.dir` written with escaped
   Windows backslashes makes AGP fail with a bare
   `java.io.IOException: The filename, directory name, or volume label syntax is incorrect`
   from `SdkLocator.validateSdkPath`. Use `cygpath -m` if you generate it.

## Build gotchas (learned the hard way)

* **AGP 9 has built-in Kotlin.** Do *not* apply `org.jetbrains.kotlin.android`; it
  fails with "the plugin is no longer required since AGP 9.0". You *do* still
  apply `org.jetbrains.kotlin.plugin.compose` (version must match the Kotlin
  version you pin in `gradle/libs.versions.toml`).
* **`kotlin { compilerOptions { … } }` replaces `android.kotlinOptions`.** The
  Media3 opt-in flag lives there:
  `freeCompilerArgs.add("-opt-in=androidx.media3.common.util.UnstableApi")`.
  Removing it produces a wall of `@UnstableApi` opt-in errors.
* **`SubtitleConfiguration` is nested**: `MediaItem.SubtitleConfiguration`, not
  `androidx.media3.common.SubtitleConfiguration`. `MediaMetadata` has no
  `subtitleConfigurations` property either — read it from
  `currentMediaItem.localConfiguration.subtitleConfigurations`.
* **keytool on Windows needs an ASCII, printable password.** A password generated
  with `openssl rand -base64 … | tr -d '\n'` keeps its `\r` and keytool aborts with
  `Password is not ASCII`. Use `openssl rand -hex 16`. PKCS12 also ignores
  `-keypass`, so store and key passwords must be identical.
* **Gradle 9 refuses non-existent module directories** (`include(":app")` before
  `app/` exists). Create the directory before running any Gradle task.
* **Installing a Media3 software decoder is a native-dependency decision.** NextLib
  ships ~55 MB of FFmpeg for four ABIs, which is why `splits { abi { … } }` exists.
  If you add another native library, measure the APK size again.

## Never commit

* `vdo-release.jks`, `keystore.properties`, `local.properties`, `*.jks`, `*.keystore`
  — all covered by `.gitignore`; keep it that way.
* Log files, exports, `build/` output, `.freebuff/` workspace state.
* Secrets of any kind. Release signing credentials stay local; if CI ever signs
  releases, they belong in repository secrets (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`,
  `KEY_ALIAS`, `KEY_PASSWORD`).

## Release process

1. Bump `versionCode` / `versionName` in `app/build.gradle.kts`.
2. Update `docs/CHANGELOG.md` **and** `docs/CHANGELOG.html`.
3. `./gradlew :app:assembleRelease` and verify with `apksigner verify`.
4. `gh release create vX.Y.Z app/build/outputs/apk/release/*.apk --title … --notes …`
5. The download page reads the GitHub release API at runtime, so no page edit is
   needed — it picks up the new tag automatically.
6. Commit and push (`docs/` is the Pages source; the site redeploys from `main`).

## Style

* Kotlin, four-space indent, trailing commas, explicit `import`s, no wildcard imports.
* Comments explain *why* (especially around threading, throttling and Media3
  workarounds); avoid restating what the code does.
* User-facing strings are English and currently live inline in composables;
  `res/values/strings.xml` holds the ones referenced from the manifest.
* Log meaningful state transitions once (`LogBus.i`) rather than in a loop, and
  attach the `Throwable` to `LogBus.e` so the stack trace lands in the export.

## Useful one-liners

```bash
# what is inside the APK
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep '\.so$'

# release certificate fingerprint
"$ANDROID_HOME/build-tools/37.0.0/apksigner" verify --print-certs \
  app/build/outputs/apk/release/app-universal-release.apk

# library versions actually in use
./gradlew :app:dependencies --configuration debugRuntimeClasspath | grep -E 'media3|nextlib|coil'
```
