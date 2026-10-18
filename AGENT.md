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
./gradlew :app:testDebugUnitTest  # JVM tests: wire protocol, file names, translations
./gradlew :app:assembleDebugAndroidTest  # compiles the instrumented playback test
./gradlew :app:connectedDebugAndroidTest  # that test on a device/emulator (CI does this)
./gradlew :app:assembleRelease    # per-ABI + universal signed APKs
./gradlew :app:lintDebug          # lint is configured with abortOnError = false
```

Verification rules that apply to this repository:

* A change is not done until `:app:assembleDebug` (or the test task, which
  compiles the same sources) succeeds. `:app:lintDebug` does **not** fail the
  build on purpose (`abortOnError = false`): its report holds ~66
  `UnsafeOptInUsageError` entries for Media3/NextLib unstable APIs (app code opts
  in with `-opt-in=androidx.media3.common.util.UnstableApi`, the lint rule does not
  see that per file) plus a handful of known platform warnings, so judge a lint run
  by whether *new* findings appear, not by the total. Two lint findings have been
  real bugs in the past — a `WrongConstant` on `SessionResult` (the error codes come
  from `SessionError`, not `SessionResult`) and an `InlinedApi` for a `MediaStore`
  volume constant — so read the non-opt-in lines.
* `testOptions.unitTests.isReturnDefaultValues = true` is set in
  `app/build.gradle.kts` because Media3's `TrackGroup` calls `TextUtils.isEmpty()`
  while it is constructed, and `TrackOptionsTest` builds real `TrackGroup`/`Tracks`
  objects. It is there for platform calls inside dependencies, **not** as a way to
  fake Android behaviour in a test: keep new tests about our own code. Kotlin errors here are usually about
  Media3 APIs; see the gotchas below before assuming a typo.
* The unit tests run on **both** GitHub platforms
  (`.github/workflows/ci.yml`: Ubuntu and Windows) for every push to `main` and every
  pull request. That is not box-ticking: the transfer tests start real loopback sockets, and
  the platforms genuinely differ — on Windows `close()` wakes a thread parked in
  `accept()`, on Linux it does not, so a listening socket that has been closed keeps
  answering on one platform and not the other. Found on a commit, that costs a CI run;
  found by a release tag, it costs a version. The release workflow runs the same test
  task on the machine that builds the APKs, so tag a commit whose CI run has passed.
* `:app:testDebugUnitTest` is plain JUnit 4 on the JVM — no Android SDK jar, no
  Robolectric, no device. Anything that can be tested without Android belongs
  there, so keep new protocol or formatting logic out of the UI layer:
  `transfer/TransferServerTest` and `TransferClientTest` start a real server on a
  loopback socket, `TransferProtocolTest` covers names/URLs/JSON, `DirectGroupPlannerTest`
  covers the Wi-Fi Direct address rules, `PlaybackPreferencesTest` the repeat cycle,
  `SeekPreviewPolicyTest` the frame bucketing, `SubtitleMatchingTest` the subtitle
  name rules, `DecoderPreferenceTest` the decoder mode table,
  `TrackOptionsTest` the audio track options built from real Media3 objects, and
  `LocalizationTest` parses both
  `strings.xml` files and fails on a missing key, a lost `%1$s` placeholder or an
  untranslated value.
* `:app:connectedDebugAndroidTest` is the other half: the instrumented
  `playback/PlaybackSmokeTest` builds the real player through `PlayerFactory`, plays
  the clips in `app/src/androidTest/assets` (both generated once with ffmpeg and
  committed, so no runner needs an encoder) and insists on frames arriving at a real
  `Surface` — once with the platform decoder, once with the bundled FFmpeg decoder,
  once while switching between the two, and once on `ac3.mkv`, whose H.264 picture the
  platform decodes and whose AC-3 audio it cannot. That last test is the one that
  shows what the extension is *for*: a track only counts as playing when a renderer
  reports it supports the format, and on an image with no AC-3 decoder the only
  renderer that can report that is NextLib's. Its premise is asked of `MediaCodecList`
  rather than assumed, and it skips itself if an image ever ships a platform AC-3
  decoder — in which case there is no gap left to demonstrate there. This is the only
  automated proof that a picture is produced, that the FFmpeg `.so` files load on a
  device, and that the live decoder switch works. Run it on an emulator through CI
  (`api-level 34`, `google_apis`, `x86_64`) rather than locally.
* The frames are counted by `playback/CountingVideoSurface`, and that class is load
  bearing: a plain `SurfaceTexture` consumes nothing, so its queue fills after the
  first frame and the decoder blocks forever waiting for a free slot. That is how the
  first CI run failed — every test that needed only one frame passed, and the switch
  test timed out on the second (`expected at least 2 rendered frame(s), saw 1`). The
  counting surface is a small EGL context on its own thread that calls
  `updateTexImage()` for every frame, the way a `TextureView` does internally, so
  "the picture keeps running" is something the test can actually observe. Keep the
  assertions on frames *streaming* (`STREAM_FRAMES`), not on the first frame arriving.
* A credential must never reach a commit, and `.gitignore` alone cannot promise
  that: it does not cover `git add -f`, a file that was added before the rule
  existed, or a key pasted into an ordinary source file. The `secret-scan` job in
  `.github/workflows/ci.yml` checks both layers in seconds, before the slow jobs
  start. `.github/scripts/tracked-secret-files.sh` fails on a tracked file whose
  name matches the "never commit these" list in `.gitignore` (keystores, `*.pem` and
  `*.key`, `.env` but not `.env.example`, `google-services.json`,
  `.git-credentials*`); gitleaks (pinned 8.30.1, downloaded from its release and
  verified against the release's SHA-256 list) then scans the commits the change
  adds — `origin/<base>..HEAD` on a pull request, the pushed range on `main` — with
  `--redact`, so a failure names the file, line and rule without repeating the
  value. Both layers ignore placeholders deliberately: a check that fires on
  `.env.example` teaches people to ignore the check. Run the file gate locally with
  `bash .github/scripts/tracked-secret-files.sh` (staged files) or
  `bash .github/scripts/tracked-secret-files.sh HEAD` (a revision).
* The emulator job drives the test through
  `.github/scripts/run-emulator-playback-test.sh`, which must be called from a single
  line. `reactivecircus/android-emulator-runner` runs each line of its `script:` input
  as its own shell invocation, so an `if`/`then`/`fi` block written in the workflow
  YAML reaches `/bin/sh` a line at a time and dies with `Syntax error: end of file
  unexpected (expecting "fi")`; the gradle status and the diagnostics are lost with
  it. `adb logcat` has to run inside the script too, while the emulator is still
  alive — in a later step `adb` waits forever for a device that is gone. On failure it
  writes logcat (whole buffer plus the filtered view it prints), `dmesg`,
  `dumpsys media.codec` and `getprop` into `emulator-diagnostics/`, and the workflow
  uploads that together with the report and the JUnit XML as the
  `emulator-playback-failure` artifact: the console keeps an excerpt, so diagnosing a
  decoding failure should not mean re-running the job to read its output. A green run
  uploads nothing. The workflow prints the failing XML from its own step as well, since
  that survives the emulator.
* The emulator's system image is cached (`actions/cache`, keyed on the image rather
  than the commit) because it is around a gigabyte of download that has nothing to do
  with the change under test; the AVD *snapshot* is deliberately not cached, because a
  snapshot carries the guest's CPU state and this job does not always land on a host
  with the same CPU, which turns a saved boot into a boot failure. Judge a change like
  that one by the step timings in the run, not by the shape of the diff.
* There is still **no physical device in the loop**, and the development machine
  cannot start an emulator (no hypervisor driver), so the only place the app really
  runs is the `playback-on-an-emulator` job in `.github/workflows/ci.yml`. A
  successful build proves the code compiles and packages — including that FFmpeg
  `.so` files and the Thai strings are inside the APK — but not that playback works
  on a phone. Say so explicitly when reporting, and prove what you can statically:
  `aapt2 dump resources` shows the `(th)` values that were packaged.
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
├── playback/              PlayerFactory (engine + DecoderManager), PlaybackService
│                          (MediaSessionService + session commands), PlaybackViewModel
│                          (MediaController + UI state), PlaybackCommands,
│                          SubtitleMatching + SubtitleDiscovery, TrackOptions
├── transfer/              Wi-Fi send/receive: TransferProtocol (wire contract),
│                          TransferServer, TransferClient, TransferDiscovery (mDNS),
│                          MediaStoreReceivedFileSink, TransferManager (state for the UI)
├── ui/                    AppRoot + library/player/recent/transfer/logs/settings screens
└── util/                  Formatters, IntentHelpers
app/src/test/java/         JVM unit tests (JUnit 4) — see “Build and verify”
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
5. **Only the UI is translated.** Every user-visible string lives in
   `res/values/strings.xml` with `res/values-th/strings.xml` as the Thai mirror;
   enums map to resources in `ui/Labels.kt` so `data/` and `logging/` stay free of
   Android resources. Log lines, diagnostics reports and the clipboard labels used
   when sharing them stay English on purpose: they are pasted into bug reports.
6. **The transfer is a hand-written HTTP server on the LAN, not a service.**
   `TransferProtocol` holds the whole wire contract (paths, the `X-VDO-Code`
   header, name sanitising, JSON); `TransferServer` is a tiny HTTP/1.1 server that
   streams one file per request into a `ReceivedFileSink`, and `TransferClient`
   posts one file per request. Both are deliberately free of Android APIs so the
   protocol is testable on the JVM. Keep them that way: put anything platform
   specific behind the sink or the event callback.
7. **Nothing is published before it is complete.** The Android sink inserts into
   MediaStore with `IS_PENDING = 1` and only clears it on `commit()`; a cancelled
   or interrupted transfer calls `discard()`, so a half file never appears in the
   gallery. The test sink does the same with a temp directory.
8. **The four digit code is the only authorisation.** A phone on a shared network
   cannot tell a friendly sender from a hostile one, so a request without the
   current code gets `403` (and the code rotates by itself after repeated wrong
   attempts). Do not relax that for convenience.
9. **Local network access is a runtime permission on Android 17.** Since the app
   targets SDK 37, Android 17 blocks the receiving socket, mDNS and outgoing
   connections until `ACCESS_LOCAL_NETWORK` is granted — and a blocked TCP
   connection surfaces as a timeout, not a permission error. `TransferManager`
   checks `hasLocalNetworkPermission()` and the Transfer screen requests it before
   starting a receive session or a scan. Anything new that touches the local
   network must go through the same gate.
10. **Repeat lives in the player, the choice lives in a `SharedPreferences`.**
    `PlaybackPreferences` stores the repeat mode in the same `vdo_player_runtime` file
    as the decoder preference, because `PlaybackService` has to apply it while it
    builds the player — long before a coroutine or a DataStore read is available.
    The UI cycles the mode with `PlaybackPreferences.nextRepeatMode`, never with its
    own arithmetic.
11. **Seek previews are decoded on demand and bucketed.** `SeekPreviewPolicy` decides
    when a frame is worth taking (two-second buckets, so a slow drag reuses the frame
    on screen), and `SeekPreviewSource` serialises the decodes behind one
    `MediaMetadataRetriever` per media item with an LRU in front. Do not call
    `getFrameAtTime` from composition or from the main thread: it is a full container
    seek plus a decode.
12. **Wi-Fi Direct is a fallback, never the default path.** `DirectTransfer` only
    answers "what address do I dial" — a group owner receives, the joiner sends, and
    the joiner's peer list gains the owner so the normal HTTP flow is unchanged. Every
    framework call is guarded, and a refusal leaves `DirectState.issue` set while the
    app keeps working over ordinary Wi-Fi.
13. **The live decoder switch goes through a session command, not a shared
    singleton.** `PlaybackService` owns the one `PlaybackEngine` (player +
    `DecoderManager`) because NextLib's manager has to be driven on the player's
    application thread, so the UI sends `PlaybackCommands.setDecoderPreference` and
    the session's `Callback.onConnect` grants that custom command to every
    controller. A refusal is not fatal: the preference is stored anyway and applies
    the next time the player is built.
14. **The renderer set is fixed at player build time; the decoder mode is not.**
    `DecoderPreference` therefore carries both halves — `extensionMode` for the
    renderers and `DecoderMode` for the selection — and the extension renderers stay
    enabled even for "hardware only". Without them the manager could never switch to
    FFmpeg mid-video. Adding a fourth setting means editing that table *and*
    `DecoderPreferenceTest`, which pins every entry on purpose.
15. **Subtitle discovery is bounded and honest.** `SubtitleResolver` only reads
    MediaStore (a folder query per video start, off the main thread), only attaches
    files it can actually open, and logs every search under `SUBTITLE`, including the
    empty ones. It cannot see third-party `.srt` files on Android 13+, where the media
    permissions do not cover them; do not "fix" that with `MANAGE_EXTERNAL_STORAGE`.
16. **Audio track options are built from the player's own track list.**
    `TrackOptions.audioTracks` keeps the group and track indices Media3 reported, so
    the picker's indices stay valid; the label formatter is injected so the file stays
    free of `Diagnostics` and testable on the JVM.
17. **`TransferServer.stop()` waits for the accept loop before it returns.** On Linux
    a thread parked in `ServerSocket.accept()` keeps the listening socket alive — the
    kernel goes on queueing connections for the closed port until that call returns —
    so "close the socket and return" left the receiver answering for a moment after
    the UI said it had stopped. The accept loop therefore runs with `soTimeout` (250 ms)
    so it notices `running == false`, and `stop()` joins it before it closes the client
    sockets and the pool. Windows hides this: there `close()` wakes the parked
    `accept()`. Do not shorten it back; `TransferServerTest` opens the port immediately
    after a stop, and the release workflow runs on Ubuntu.
18. **`local.properties` uses forward slashes.** A `sdk.dir` written with escaped
   Windows backslashes makes AGP fail with a bare
   `java.io.IOException: The filename, directory name, or volume label syntax is incorrect`
   from `SdkLocator.validateSdkPath`. Use `cygpath -m` if you generate it.

## Localisation

* A language only counts as complete when its key set and its `%1$s` placeholders
  match `values/strings.xml`. `LocalizationTest` enforces that on every test run
  (same keys, no duplicates, placeholders intact, and a Thai value that really
  contains Thai text), so the usual failure is a red test rather than a manual
  `comm`:

  ```bash
  ./gradlew :app:testDebugUnitTest --tests '*LocalizationTest'
  ```

  The old manual check, for when you want to see the difference while editing:

  ```bash
  # keys that exist in English but not in Thai (and vice versa)
  comm -3 <(grep -o 'name="[a-z_0-9]*"' app/src/main/res/values/strings.xml | sort) \
          <(grep -o 'name="[a-z_0-9]*"' app/src/main/res/values-th/strings.xml | sort)
  ```

  Symbol-only or brand values (`app_name`, `details_uri`, `transfer_progress`, …)
  legitimately read the same in both files and are listed in `LANGUAGE_NEUTRAL` in
  the test.

* `res/xml/locales_config.xml` plus `android:localeConfig` on `<application>` is
  what makes the Android 13 “App languages” picker offer the language. Adding a
  `values-xx/` folder alone does not add it to that list.
* Countable strings are `<plurals>`, read with `pluralStringResource(id, count, …)`.
  The quantity argument is the count the wording depends on, not always the first
  placeholder: `library_count` pluralises on the total, not the visible number.
  Each language must define exactly the quantities its plural rules require —
  English needs `one` and `other`, Thai only `other`.
* Compose resolves strings at composition time, so anything a gesture callback or a
  non-composable helper needs (HUD labels, snackbar text) must be resolved in the
  composable first and passed down, or read from `Context`.

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
2. Update `docs/CHANGELOG.md` **and** `docs/CHANGELOG.html`, and add the new
   `[X.Y.Z]: …/releases/tag/vX.Y.Z` link at the bottom of the Markdown file.
3. Verify locally: `./gradlew :app:testDebugUnitTest :app:assembleRelease`, then
   `apksigner verify --print-certs` on the APKs and `aapt2 dump badging` for the
   version that was packaged.
4. Push the commit to `main` and wait for the CI run to pass the unit tests on both
   Ubuntu and Windows, then push the tag: `git tag -a vX.Y.Z -m …` and
   `git push origin main vX.Y.Z`. The tag push runs
   `.github/workflows/release.yml`, which tests, builds, signs (when the
   `KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`
   repository secrets exist) and opens a **draft** release with the four APKs.
5. Publish the draft on GitHub once it looks right, and copy the `SHA-256` list
   from the workflow run summary into the release notes. The APKs that the
   workflow builds are *not* byte-identical to a local build, so never mix the two
   checksum sets. There is no `gh` CLI on the development machine — Workflows and
   the draft release are handled in the browser or through the GitHub tools.
   Open the draft **from the Releases page** and edit it there
   (`/releases/edit/untagged-…`). `/releases/edit/<tag>` is the *new release* form:
   it is prefilled with the tag message, and submitting it while a draft for that tag
   already exists creates a **second** release for the same tag — that duplicate
   carries only the source archives, so it takes over the download page and the site
   lists no APKs until it is deleted from its own release page. Publishing from the
   draft's form keeps the four APKs and the checksums together.
6. The download page reads the GitHub release API at runtime, so no page edit is
   needed — it picks up the new tag on its own.
7. `docs/` is the Pages source and redeploys from `main`, so the documentation
   should already be in the commit that the tag points at.

## Style

* Kotlin, four-space indent, trailing commas, explicit `import`s, no wildcard imports.
* Comments explain *why* (especially around threading, throttling and Media3
  workarounds); avoid restating what the code does.
* User-facing strings live in `res/values/strings.xml` / `res/values-th/strings.xml`;
  never inline UI text in a composable.
* Log meaningful state transitions once (`LogBus.i`) rather than in a loop, and
  attach the `Throwable` to `LogBus.e` so the stack trace lands in the export.

## Useful one-liners

```bash
# what is inside the APK
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep '\.so$'

# the Thai strings that actually got packaged, next to the English ones
"$ANDROID_HOME/build-tools/37.0.0/aapt2" dump resources \
  app/build/outputs/apk/release/app-arm64-v8a-release.apk | grep -A 2 'string/tab_transfer'

# permissions in the built manifest
"$ANDROID_HOME/build-tools/37.0.0/aapt2" dump permissions \
  app/build/outputs/apk/release/app-arm64-v8a-release.apk

# release certificate fingerprint
"$ANDROID_HOME/build-tools/37.0.0/apksigner" verify --print-certs \
  app/build/outputs/apk/release/app-universal-release.apk

# library versions actually in use
./gradlew :app:dependencies --configuration debugRuntimeClasspath | grep -E 'media3|nextlib|coil'
```
