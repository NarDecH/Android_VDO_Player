# Changelog

All notable changes to VDO Player are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[semantic versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.1] — 2026-10-15

### Added

- Thai (`th`) translation of the whole interface: `res/values-th/strings.xml`
  mirrors `values/strings.xml` key for key, including the heads-up display, the
  track info panel and the export snackbars
- `res/xml/locales_config.xml` plus `android:localeConfig`, so Android 13 and
  newer list English and Thai in **Settings → System → App languages**
- Every user-visible string moved out of the composables into resources; enum
  display names (sort order, theme, decoder priority) resolve through
  `ui/Labels.kt` instead of being baked into the data layer
- The media notification channel is named by the app now, so it follows the
  selected language instead of Media3's default wording
- The three count strings became `<plurals>`, so the English copy reads “1 video”
  and “1 log line” instead of “1 videos”

## [1.0.0] — 2026-10-15

First public release. Everything below is new.

### Added

**Library**

- MediaStore video scan with a `ContentObserver` so the list updates when files
  are added or removed while the app is open
- Thumbnails decoded from the video frames themselves (Coil 3 + `coil-video`)
- Search by title or folder, folder filter chips, seven sort orders
- Grid layout with 1–4 columns and a single-column list layout
- Long-press details dialog: folder, resolution, duration, size, MIME type,
  relative path and content URI

**Playback**

- Media3 ExoPlayer hosted in a `MediaSessionService`, driven from the UI through a
  `MediaController`, so playback survives the activity
- FFmpeg software decoders through
  `io.github.anilbeesetti:nextlib-media3ext` with three priority modes
  (hardware first, FFmpeg first, hardware only)
- Playlists built from the current filtered library view with previous/next
- Per-video resume positions, throttled to one DataStore write every 5 seconds
- Background audio with a media notification and session activity that returns to
  the player
- Speed control from 0.25× to 3×, plus a 3× long-press boost
- Aspect ratio modes (fit, zoom, stretch, match width), control lock, rotation
  button, picture-in-picture with the real video aspect ratio
- Side-loading of `.srt`, `.vtt` and `.ass` subtitles, and a subtitle on/off
  toggle through `TrackSelectionParameters`
- Gesture layer: vertical brightness (left) and volume (right) with heads-up
  display, horizontal scrubbing with a time preview, double-tap seek

**Diagnostics**

- `LogBus`: 2000-entry ring buffer, versioned snapshots, logcat mirroring,
  eight tags and six levels
- `LogFileWriter`: asynchronous rotating files (2 MB × 10) in the app external
  files directory
- `CrashGuard`: uncaught exceptions are logged with a device summary and flushed
  to disk before the process exits
- `Diagnostics`: device, OS, ABI, display, memory, storage, player state, active
  tracks and a full `MediaCodecList` inventory
- `LogShare`: `.txt` and `.zip` exports, share sheet delivery through
  `FileProvider`, and saving into the shared Downloads collection
- Logs screen with level, tag and text filters, freeze/resume of the live tail,
  copy, clear, share and save

**App**

- Material 3 UI with dynamic colour, light/dark/system themes
- Settings for appearance, playback, diagnostics and logging
- Recent tab with per-entry progress, remove and clear-all
- Handles `ACTION_VIEW` and `ACTION_SEND` intents for `video/*` and `audio/*`
- Adaptive launcher icon with a monochrome variant for themed icons

**Distribution**

- Per-ABI release APKs (`arm64-v8a`, `armeabi-v7a`, `x86_64`) plus a universal
  APK, signed with the project release key
- GitHub Pages download site that resolves the newest release through the GitHub
  API and offers the right asset per architecture
- Markdown and HTML documentation for users, plus this changelog

### Known limitations

- No device-level smoke test ran during development: correctness of playback on
  real hardware is asserted from the API contracts and the build artefacts, not
  from a recorded test run. Screen-level behaviour is unverified on device.
- Screenshots in the documentation are vector mockups of the shipped layouts, not
  device captures.
- Minification is disabled for release builds on purpose; APKs are larger than
  they need to be.
- Media3 does not expose every `Player` command to a `MediaController`; where the
  session refuses, the app logs the refusal and keeps the automatic selection.
- Downloads export uses the app's private external directory before Android 10
  instead of the public Downloads collection, because the legacy path needs a
  runtime storage permission.

### Security

- The release keystore and `keystore.properties` are excluded from version
  control; releases are signed locally.
- The app requests no `INTERNET` permission.

---

## Unreleased

### Planned

- CI workflow that builds and signs a draft release from a version tag
  (`gh release` driven, needs the `workflow` scope on the publishing token)
- Runtime decoder switching (hardware ⇄ FFmpeg) without restarting playback,
  using NextLib's `DecoderManager`
- Subtitle auto-discovery next to the video file
- Audio track picker UI (the engine already exposes the tracks)

[1.0.1]: https://github.com/NarDecH/Android_VDO_Player/releases/tag/v1.0.1
[1.0.0]: https://github.com/NarDecH/Android_VDO_Player/releases/tag/v1.0.0
