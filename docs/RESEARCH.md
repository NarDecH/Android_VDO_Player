# Research notes

Everything in this document was gathered while building 1.0.0, and each claim
that came from an external source carries the URL it came from. Where a number is
a measurement from this machine, it says so.

- [1. The problem](#1-the-problem)
- [2. Decoder strategy](#2-decoder-strategy)
- [3. Version matrix](#3-version-matrix)
- [4. Build system: AGP 9 and built-in Kotlin](#4-build-system-agp-9-and-built-in-kotlin)
- [5. Playback architecture](#5-playback-architecture)
- [6. Packaging and APK size](#6-packaging-and-apk-size)
- [7. Licensing](#7-licensing)
- [8. Screenshots](#8-screenshots)
- [9. Rejected alternatives](#9-rejected-alternatives)
- [10. References](#10-references)

---

## 1. The problem

Android phones ship hardware video decoders that cover the mainstream formats and
nothing else. The practical consequences users hit:

* an MKV whose video track is fine but whose audio is AC-3 or DTS — silent video
* VP9 or AV1 on a chip that predates those codecs — "cannot play this file"
* HEVC 10-bit or unusual profiles on older SoCs — refuses to start
* containers with multiple audio tracks where only one decodes

A player that only wraps `MediaPlayer`/ExoPlayer's platform renderers can do
nothing about these. A player that adds a software decoder can, at the cost of CPU
and battery. That trade-off is the entire design axis of VDO Player, and it is
exposed to the user as three decoder priority modes instead of a hidden default.

## 2. Decoder strategy

Three candidate approaches were evaluated for a Media3-based app.

### Option A — AndroidX Media3 FFmpeg extension (official)

The Media3 repository contains a `decoder_ffmpeg` module, but **it is not
published to Google Maven**. Its README instructs you to check out the repository,
build FFmpeg native libraries yourself with the NDK, and depend on the module
locally. It also provides an **audio** renderer (`FfmpegAudioRenderer`); video
software decoding requires additional work.

* Verdict: rejected. It means vendoring a native build toolchain into this
  repository and maintaining NDK builds for every release.
* Source: [androidx/media decoder_ffmpeg README](https://github.com/androidx/media/blob/release/libraries/decoder_ffmpeg/README.md)

A third-party AAR exists on Maven Central, published by Jellyfin:
`org.jellyfin.media3:media3-ffmpeg-decoder:1.9.0+1`. It is tied to Media3 1.9.0,
not the current 1.11.1 line, and is not an AndroidX artefact.

* Source: [Jellyfin AAR on Maven Central](https://central.sonatype.com/artifact/org.jellyfin.media3/media3-ffmpeg-decoder)

### Option B — NextLib (chosen)

`io.github.anilbeesetti:nextlib-media3ext` packages FFmpeg (plus dav1d for AV1)
as a drop-in `RenderersFactory` for Media3. Integration is two lines:

```kotlin
val renderersFactory = NextRenderersFactory(context)
    .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
ExoPlayer.Builder(context).setRenderersFactory(renderersFactory).build()
```

Decoder coverage reported by the project:

| Kind | Decoders |
|---|---|
| Video | H.264, HEVC, VP8, VP9, AV1 (dav1d) |
| Audio | Vorbis, Opus, FLAC, ALAC, µ-law/A-law PCM, MP3, AMR-NB/WB, AAC, AC-3, E-AC-3, DCA, MLP, TrueHD |

* ABIs: `armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`
* Pin used here: `1.11.1-0.16.0`, whose POM depends on `androidx.media3:media3-exoplayer:1.11.1`
* Licence: GPL-3.0 (see [7. Licensing](#7-licensing))
* Sources: [NextLib README](https://github.com/anilbeesetti/nextlib), [Maven Central](https://central.sonatype.com/artifact/io.github.anilbeesetti/nextlib-media3ext)

NextLib also exposes a `DecoderManager` for switching between hardware and FFmpeg
decoders at runtime on a live `ExoPlayer`. That is deliberately **not** wired up in
1.0.0: the API surface for it is newer than the renderer factory, so it is listed
as planned work in the changelog rather than guessed at.

### Option C — LibVLC

`org.videolan.android:libvlc-all:3.7.7` is a complete alternative engine with
broader format and protocol coverage, HDR support and audio passthrough. It is not
a renderer you can plug into ExoPlayer — adopting it means rewriting playback, the
UI bindings, and the track/subtitle plumbing around a different API, and shipping
another large native payload.

* Verdict: rejected for 1.0.0. Kept as the fallback plan if a class of files turns
  out to defeat FFmpeg too.
* Sources: [libvlc-all 3.7.7](https://central.sonatype.com/artifact/org.videolan.android/libvlc-all/3.7.7), [vlc-android](https://github.com/videolan/vlc-android)

### Decision

Media3 + NextLib, with the extension renderer priority exposed to the user as
*Hardware first* (`EXTENSION_RENDERER_MODE_ON`), *Prefer FFmpeg*
(`…_PREFER`) and *Hardware only* (`…_OFF`). `ON` is the default because it keeps
hardware decoding — with its lower power draw — in front, and only falls back when
the platform cannot handle the format.

## 3. Version matrix

Resolved against Google Maven and Maven Central metadata during this build, then
pinned in `gradle/libs.versions.toml`.

| Component | Version | Why this one |
|---|---|---|
| Android Gradle Plugin | 9.4.1 | Latest 9.4 patch; `9.5.0` is still alpha at the time of writing |
| Gradle | 9.8.1 | Current stable; 9.4.x requires ≥ 9.6 |
| Kotlin | 2.4.21 | Latest stable in the 2.4 line (2.5.0 was still Beta) |
| Compose BOM | 2026.09.00 | Latest stable BOM; resolves Compose UI/foundation 1.12.1 |
| Media3 | 1.11.1 | Latest stable, and the exact version NextLib 1.11.1-0.16.0 builds against |
| NextLib | 1.11.1-0.16.0 | Newest published, matched to Media3 1.11.1 |
| Activity Compose | 1.13.0 | Latest stable |
| Core KTX | 1.19.1 | Latest stable |
| Lifecycle | 2.11.0 | Latest stable |
| DataStore Preferences | 1.2.1 | Latest stable in the 1.2 line |
| Coil | 3.6.3 | Latest stable 3.x; `coil-video` provides the frame decoder |
| JDK | 17 | AGP 9.4 minimum and default |
| compileSdk / targetSdk | 37 (Android 17) | Current platform; Google Play requires ≥ 36 for new apps from 31 Aug 2026 |
| minSdk | 26 (Android 8.0) | Needed for adaptive icons without bitmap fallbacks, PiP, and `java.time` without desugaring |

Sources: [AGP 9.4 release notes](https://developer.android.com/build/releases/agp-9-4-0-release-notes),
[AGP/Gradle compatibility](https://developer.android.com/build/releases/about-agp),
[Compose BOM](https://developer.android.com/develop/ui/compose/bom),
[Media3 releases](https://developer.android.com/jetpack/androidx/releases/media3),
[Android 17 SDK setup](https://developer.android.com/about/versions/17/setup-sdk),
[Play target API requirements](https://developer.android.com/google/play/requirements/target-sdk).

## 4. Build system: AGP 9 and built-in Kotlin

AGP 9 ships **built-in Kotlin**: you no longer apply `org.jetbrains.kotlin.android`,
and applying it fails outright ("the plugin is no longer required since AGP 9.0").
Two consequences shaped this project:

1. `android { kotlinOptions { … } }` is gone; compiler settings move to
   `kotlin { compilerOptions { … } }`, which is where the Media3
   `-opt-in=androidx.media3.common.util.UnstableApi` flag lives.
2. The Compose compiler **is** still a separate plugin. The Kotlin documentation is
   explicit: with AGP 9 you drop `org.jetbrains.kotlin.android` but keep
   `org.jetbrains.kotlin.plugin.compose`, whose version must match the Kotlin
   version (2.4.21 here).

This was verified end to end: the first `:app:assembleDebug` after removing the
obsolete plugin configuration succeeded, while the Compose plugin remained
required for `@Composable` compilation.

* Sources: [Migrate to built-in Kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin),
  [Compose compiler migration guide](https://kotlinlang.org/docs/compose-compiler-migration-guide.html),
  [Set up the Compose Compiler Gradle plugin](https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler)

### Windows-specific traps found while building

| Symptom | Cause | Fix |
|---|---|---|
| `IOException: The filename, directory name, or volume label syntax is incorrect` from `SdkLocator.validateSdkPath` | `sdk.dir=C\:\Users\…` in `local.properties` | write the path with forward slashes (`cygpath -m`) |
| `keytool: Password is not ASCII` | base64 password still carried a `\r` from the pipe | generate with `openssl rand -hex 16` |
| `Configuring project ':app' without an existing directory is not allowed` | `include(":app")` evaluated before `app/` existed | create the module directory first (Gradle 9 rule) |

## 5. Playback architecture

* `PlaybackService : MediaSessionService` owns the single `ExoPlayer`. Media3's
  `MediaSessionService` gives background playback, a media notification and
  automatic media-button handling without hand-writing a foreground service.
* The UI connects with `MediaController.Builder(context, SessionToken(...)).buildAsync()`
  and treats the controller as the player. All UI state is pulled from it on a
  500 ms ticker plus on player events, which avoids callback races with a released
  player.
* `TrackSelectionParameters` is the only supported way to steer track selection
  from the controller (subtitle on/off, forced overrides). When the session refuses
  a command, the call is wrapped in `runCatching` and logged — the alternative,
  crashing playback because a session command was unavailable, is worse.
* The decoder preference cannot change on a live player, so it is persisted in a
  plain `SharedPreferences` file that the service reads in `onCreate`; DataStore is
  asynchronous and the service cannot block on it.

## 6. Packaging and APK size

Measured on the build machine (`assembleRelease`, x86_64 host, Gradle 9.8.1):

| Artefact | Size |
|---|---|
| `app-arm64-v8a-release.apk` | 24 MB |
| `app-armeabi-v7a-release.apk` | 23 MB |
| `app-x86_64-release.apk` | 28 MB |
| `app-universal-release.apk` | 51 MB |

Inside a debug APK, `unzip -l` reports per-ABI native libraries including
`libavcodec.so` (5.6 MB for arm64), `libavutil.so`, `libswscale.so`,
`libswresample.so` and `libmedia3ext.so`. That is the cost of the software decoder
path, and the reason `splits { abi { … isUniversalApk = true } }` is enabled: a
phone downloads roughly half of the universal size, while the universal APK remains
available for anything unusual.

Minification is deliberately **off** for 1.0.0. R8 with Media3 plus a native
library is usually fine, but there is no device in the verification loop here, so
the predictable build wins over a smaller one, and `proguard-rules.pro` documents
what would be needed to turn it back on.

## 7. Licensing

| Dependency | Licence | Consequence |
|---|---|---|
| `io.github.anilbeesetti:nextlib-media3ext` | **GPL-3.0** | Linking it makes the combined application GPL-3.0 |
| AndroidX Media3 / ExoPlayer | Apache-2.0 | Compatible with GPL-3.0 |
| Jetpack Compose, Activity, Lifecycle, DataStore | Apache-2.0 | Compatible |
| Coil 3 | Apache-2.0 | Compatible |

Because the FFmpeg decoder path is GPL-3.0, **VDO Player as a whole is released
under GPL-3.0-or-later** and ships the licence text in `LICENSE`. There is no way
around this while bundling NextLib: the only Apache-2.0-friendly alternatives are
the official Media3 FFmpeg extension (which you must build yourself) or moving to a
different decoder stack entirely.

## 8. Screenshots

The documentation uses vector mockups (`docs/assets/screen-*.svg`) rather than
device captures. No Android device or emulator participated in building this
release, so presenting renders as screenshots would be dishonest. The mockups
reproduce the shipped layouts — component order, iconography and the information
each screen shows — and are labelled as mockups wherever they appear.

## 9. Rejected alternatives

| Considered | Why not |
|---|---|
| Official Media3 FFmpeg extension | Requires vendoring FFmpeg and NDK builds into this repository |
| Jellyfin FFmpeg AAR | Pinned to Media3 1.9.0, not the 1.11 line, and third-party |
| LibVLC engine | Different playback stack, more rewriting, second native payload |
| `MediaPlayer`/`VideoView` | No playlist, weak track handling, no session/background story |
| Exposing only a "software decoding" toggle | Hides a real CPU/battery trade-off from the user; three explicit modes are clearer |
| Grid/list as two screens | Unnecessary duplication; layout is a property of the library screen |
| Room for playback history | A `DataStore` JSON blob is a few hundred entries of cheap writes; a database is overhead at this scale |
| R8 minification in 1.0.0 | Unverifiable without a device in the loop; documented as a future switch |
| `INTERNET` permission | Not needed: the app only plays local files. Omitting it makes "no uploads" a structural fact |

## 10. References

1. AGP 9.4 release notes — https://developer.android.com/build/releases/agp-9-4-0-release-notes
2. AGP and Gradle compatibility — https://developer.android.com/build/releases/about-agp
3. Migrate to built-in Kotlin — https://developer.android.com/build/migrate-to-built-in-kotlin
4. Compose compiler migration guide — https://kotlinlang.org/docs/compose-compiler-migration-guide.html
5. Set up the Compose Compiler Gradle plugin — https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler
6. Compose BOM — https://developer.android.com/develop/ui/compose/bom
7. Media3 release notes — https://developer.android.com/jetpack/androidx/releases/media3
8. Android 17 SDK setup — https://developer.android.com/about/versions/17/setup-sdk
9. Google Play target API requirements — https://developer.android.com/google/play/requirements/target-sdk
10. kotlinx.coroutines releases — https://kotlinlang.org/docs/releases.html
11. Gradle releases — https://gradle.org/releases/
12. NextLib — https://github.com/anilbeesetti/nextlib
13. NextLib on Maven Central — https://central.sonatype.com/artifact/io.github.anilbeesetti/nextlib-media3ext
14. AndroidX Media `decoder_ffmpeg` README — https://github.com/androidx/media/blob/release/libraries/decoder_ffmpeg/README.md
15. Jellyfin Media3 FFmpeg AAR — https://central.sonatype.com/artifact/org.jellyfin.media3/media3-ffmpeg-decoder
16. libVLC for Android 3.7.7 — https://central.sonatype.com/artifact/org.videolan.android/libvlc-all/3.7.7
17. vlc-android source — https://github.com/videolan/vlc-android
18. Android NDK ABI guidance — https://developer.android.com/ndk/guides/abis
19. GNU GPL-3.0 text — https://www.gnu.org/licenses/gpl-3.0.txt
