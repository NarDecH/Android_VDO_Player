# Changelog

All notable changes to VDO Player are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[semantic versioning](https://semver.org/spec/v2.0.0.html).

## [1.3.0] — 2026-10-23

### Added

- **No-sound hint** — a file whose audio track nothing decodes used to play in
  silence with no explanation: the picture runs, nothing throws, and the track is
  simply never selected. Once the player is ready it now notices `Tracks` reporting
  audio groups with none of them selected, names the codec in a compact card, and
  offers **Use FFmpeg**, which switches the audio decoder live and remembers the
  codec. `AudioSilence` holds the
  rules — a silent film, or any selected track, is not a failure — so the hint stays
  away while the player is still preparing, when FFmpeg already is the choice, and
  after it is dismissed for that video. 11 JVM tests pin those cases (83 in total).
- **Remembered audio fixes** — the no-sound card's FFmpeg button now records the
  codec it rescued (`AC3`, `DTS`, …). The next file with that codec switches to FFmpeg
  by itself when nothing decodes it: no card, no tap, and the saved decoder priority is
  left alone, so one AC-3 file does not turn the whole app into software decoding.
  Settings → Playback lists the remembered codecs and can forget them, and the
  automatic switch's own message carries a **Forget** action, so a codec that turns
  out to be a one-off can stop switching without leaving the player.
- **Audio-only rescue** — the no-sound card and the automatic switch now move just
  the audio decoder, through a session command of their own
  (`PlaybackCommands.setAudioDecoder`). A file whose sound needs FFmpeg keeps hardware
  video decoding, and the saved decoder priority is untouched; the stream panel's chips
  still change both halves, because there the viewer is choosing on purpose.
- **Remembered codecs switch before the first frame** — a codec you already rescued
  used to cost a silent moment per video: the player had to be ready and the audio
  track already missing before the switch could be applied. The player now reads the
  file's own audio track before playback starts and, when that codec is remembered,
  picks the FFmpeg audio renderer at prepare time, so the sound starts with the picture.
  The read goes through Media3's own extractors — the parser that will play the file —
  because the platform `MediaExtractor` does not list every track: handed the AC-3 clip,
  it reported one video track and no audio, which would have made the pre-switch give
  up on exactly the codec it exists for. The early and the live path share one decision and one
  spelling of the codec name (`audio/ac3` → `AC3`), which `AudioCodecProbeTest` pins;
  a file the probe cannot read and a track remembered under its label keep the live
  switch, which is still there. 5 more JVM tests (92 in total).
- **The probe is remembered per file** — the early switch read the header again on every
  play, including files this session had already answered for. The codec a probe finds is
  now kept in `AudioCodecCache` under the file's own identity (its size and the moment
  MediaStore added it, both already on the item), so a file opened a second time costs no
  header read at all. A re-downloaded or edited file lands on a new key and is probed
  again rather than inheriting an old codec, an item whose size is still unknown gets no
  key, and only findings are stored — a file that could not be read gets another chance.
  The map is bounded and least-recently-used, so a long session cannot grow it. 5 more
  JVM tests (97 in total).
- **The cache hit is measured, not argued** — the promise is that a file already
  probed is never opened again, and a container read gives no way to see an open from
  outside. `AudioCodecProbe.probe` now takes the track read as a parameter and the
  cache rule lives in `AudioCodecProbe.resolve`, so the JVM tests pass a read that
  counts: the second play must not open the file, a changed key and a failed read
  must, and a file with no audio is never remembered. Production still opens the file
  with Media3's extractors, and `AudioCodecProbeDeviceTest` repeats the same counts on
  the emulator against the packaged `ac3.mkv`, where the real read also has to name its
  track `AC3`. 5 more JVM tests (102 in total).
- **Probe results survive a restart** — the cache lived in the heap, so the first play
  after every launch paid for the header read and started the picture a moment before
  the sound. What a probe finds is now written down (`PrefsAudioCodecStore`, one
  tab-separated line per entry in `SharedPreferences`) and read back when the next run
  opens its first file. The key already carries the size and the timestamp that change
  when the bytes do, so a re-downloaded or edited file is still probed again; the
  written map is the same bounded LRU map the memory keeps, so the file cannot grow
  past eight entries, and a hit — the common case — writes nothing at all. A line that
  does not name both a key and a MIME type is dropped rather than guessed at. 8 more
  JVM tests (110 in total).
- **Auto-advance switches too** — an episode reached by the player itself fell back to
  the live rescue: the next file started silent and the switch only arrived once the
  picture was already running. The player now reads the codec of the item *after* the
  current one while the current one plays, and on arrival applies the same audio-only
  switch to the new item, with the same message and **Forget** action. The read costs
  one header read per arrival, and none for a file this process already answered for.
  One switch covers the rest of the queue — the audio decoder belongs to the session —
  so a season does not announce the same codec every episode; choosing a decoder in
  the stream panel clears that, because the viewer has just changed what the session
  does. A queue played from the start still gets the switch before the first item is
  prepared.
- The stream panel's audio line says **not decoded** instead of "waiting for
  tracks" when there is an audio track that nothing is playing.
- **The silence is announced before it starts** — the no-sound card could only report
  a file that was already playing quietly. The header read that picks the decoder now
  also answers whether anything on this device can decode the track at all:
  `AudioPreflight` asks the platform's own codec list and NextLib's FFmpeg build, and a
  codec neither names gets a card that says so **before** the file is opened, with no
  FFmpeg button — switching would be a promise the codec cannot keep. The same check
  covers a queue from its first item and every episode the player reaches by itself,
  and it deliberately says nothing when a list cannot be read, when the file has no
  audio, or about a codec name left by an older cache. The value the probe remembers
  is now the file's audio MIME types, since that is the only form either question can be
  asked of — and every track, because one playable track is sound; entries that hold a
  bare codec name are dropped and read again. 11 more JVM tests (121 in total).
- **Device-level coverage for both** — the emulator job now asks the silent-audio
  premise of a real player: `ac3.mkv` under **hardware only** must report an
  undecoded AC-3 track through `AudioSilence`, and the same clip played by FFmpeg must
  report none — and then that the audio-only rescue takes that same clip from silent to
  decoded while the video decoder mode is exactly what it was before the switch; a
  fourth AC-3 test picks the audio decoder *before* the item is prepared, the way a
  remembered codec does, and finds it already decoded at the first ready state. A
  Compose test renders the
  player's snackbar host and checks that an announcement appears above the control bar,
  which no JVM test can see.
- **The warning is also a way out** — a file no decoder here can read used to end there:
  the card said why the picture was quiet and offered nothing, because the decoder
  switch it could offer would not keep its promise. When the queue holds another video
  the card now also carries **Next video**, which skips over a file that can only ever
  be silent; a one-item queue, or repeat-one, keeps the card alone, since skipping would
  only restart the same file.
- **The recall is measured on a device too** — `aRecallNeverOpensTheFile` asks for a
  file nobody probed and finds nothing with zero opens, then probes it once and finds
  the answer with that same single open, which is the whole claim the library badge
  makes.
- **The library says it before you open the file — for the whole library** — the
  no-sound warning only arrived with the file already open, and a library badge could
  only mark a file the player had opened, while it was still among the eight entries the
  probe cache keeps. Both halves are fixed. A file whose audio nothing here can decode
  now carries a muted-speaker badge on the thumbnail in grid view, an icon beside the
  title in list view, and an **Audio** row in the details dialog that spells it out —
  and the mark reaches files nobody has played. Drawing a row still opens nothing, and
  a decision is now its own record: `AudioVerdictIndex` keeps one bit per file under the
  probe's key (512 of them, least recently used out first) in a `SharedPreferences` file
  of its own, so a badge survives both a restart and the cache evicting the file.
  `AudioVerdictSweep` fills in the rest: on the library screen it walks the files nobody
  has decided, off the main thread, reading a header only for a file that needs one and
  stopping after 120 undecided files a visit, so a huge — or partly cloud-backed —
  library cannot become one long silent read; badges appear over a few visits instead.
  The sweep also leaves the player's probe cache alone, and nothing uncertain is written:
  a read that failed, an unreadable decoder list or an
  old codec name is left undecided and retried, while a file with no audio track is
  settled so it is not re-read every visit. The mark and the player's card stay one rule
  (`AudioVerdictSweep.decide` over `AudioPreflight.outlook`). 22 more JVM tests (145 in
  total), and the emulator job round-trips the new verdict file through a second
  instance.
- **The library can be read as folder sections** — a flat grid answers "what is new"
  but not "what is in the Camera folder": the chips could narrow the list to one folder
  and nothing could show them all at once. A folder button in the toolbar (and the same
  switch in Settings → Appearance) now draws the library as sections, in folder-name
  order, with the chosen sort still ordering the files inside each one and a
  `%1$d videos` count on every heading. Each heading is also the shortcut to its folder
  filter, toggling the chip that was already there, and it stays pinned to the top of the
  list while its own files scroll underneath it — giving way only when the next folder's
  heading arrives — so a long section never leaves its tiles unnamed. A play button on the
  heading starts that folder from its first file and queues that folder alone, so the
  section ends where it ends instead of rolling on into the next one; being on the pinned
  copy, it is reachable however far into the folder you have scrolled. Grouping is a
  layout, not a filter: search and the chips still apply first, next/previous walk what
  is on screen — folder by folder — and the setting survives a restart like the grid
  columns and sort order.
  The rules live in `LibrarySections`, so the order a section promises is pinned by
  `LibrarySectionsTest` (4 more JVM tests, 149 in total) rather than argued from the
  screen, and what a section is actually drawn as — a heading that names its folder,
  carries its count, spans the row in grid mode and toggles the filter when tapped —
  is covered by `LibraryFolderSectionsTest` on the emulator, including that the heading
  is still on screen once its first files have scrolled away, that the next folder's
  heading takes its place at the top, and that the play button hands the player that
  folder — in drawn order, from its first file — rather than the whole library.

### Fixed

- **Announcements made in the player were invisible** — the player is a full-screen
  overlay outside the `Scaffold`, so the app's `SnackbarHost` was never composed
  while a video was open and every message (“Decoder: FFmpeg”, the repeat mode)
  queued forever. The player now carries its own host above the control bar — and
  above the stream panel while that is open — so those confirmations finally appear.
  The no-sound card's FFmpeg button goes through the same switch, so its result is
  announced too.

## [1.2.0] — 2026-10-16

### Added

- **Live decoder switching** — the player's stream panel now switches between
  hardware and FFmpeg decoding while the video keeps playing. NextLib's
  `DecoderManager` is attached to the one ExoPlayer, the UI asks the service over a
  custom Media3 session command, and `PlaybackService` applies the change on the
  player's thread and remembers it for the next start; a file the hardware decoder
  refuses can be rescued without leaving it. The preference table grew the modes
  behind the three settings, so both the panel and Settings drive the same choice.
- **Audio track picker** — the control row lists every audio track of the current
  file (codec, channels, language, the same description the stream panel shows) and
  switches between them through `TrackSelectionParameters`. `TrackOptions` builds
  the list, so the group and track indices the picker hands back are exactly the
  ones the player reported — the part that is easy to get wrong and is now covered
  by a test.
- **Subtitle auto-discovery** — a `.srt` / `.vtt` / `.ass` file that sits next to
  the video under the same name (or with a language tag, `Movie.th.srt`) loads by
  itself. `SubtitleMatching` holds the name rules — exact name first, then the
  device language, then the other languages, and files of other videos never match
  — and `SubtitleResolver` does the MediaStore lookup off the main thread and only
  attaches files it can actually open. Switchable in Settings, and a new `SUBTITLE`
  log tag records every search, including the ones that find nothing.
- 18 more JVM tests (72 in total): the subtitle name rules, the decoder preference
  table, the audio track options built from real Media3 `Tracks` objects, and the
  promise that stopping the receiver has really let go of the port

### Changed

- **Hardware only** now means hardware MediaCodec decoders only. The FFmpeg
  renderers stay built in every mode, because the renderer set is fixed when the
  player is created — that is exactly what makes switching to FFmpeg possible while
  a video plays.
- Subtitle auto-discovery is honest about its reach: it reads MediaStore, so on
  Android 13 and newer it can only see subtitle files this app owns (the media
  permissions cover video, audio and images, not `.srt`). Where nothing is found,
  the subtitle button still takes any file you pick.
- An unknown decoder value in the settings store now falls back to the default
  instead of being ignored silently, and the decoder switch logs the modes that are
  really active after NextLib applies it.

### Fixed

- **Stopping a receive session now really closes the port.** `TransferServer.stop()`
  closed the listening socket and returned, but on Linux a thread parked in
  `ServerSocket.accept()` keeps that socket alive, and the kernel went on handing the
  closed port connections for a moment — so the receiver could still take a file after
  the screen said it had stopped. The accept loop now waits with a short timeout and
  `stop()` waits for it to leave before it returns. Caught by the release workflow on
  Ubuntu, where the unit test that opens the port right after a stop failed; the same
  test now pins the guarantee.

## [1.1.0] — 2026-10-16

### Added

- **Wi-Fi transfer (the Transfer tab)** — send videos and other files from one phone
  to another on the same network, with no cable, no cloud and no account:
  - **Receive** starts a small HTTP server, shows the phone's address and a four
    digit code, and advertises the phone over mDNS so the other one can find it
    without typing anything
  - **Send** lists the receivers it discovers, or takes a typed address
    (`192.168.1.20`, `192.168.1.20:45871`, `http://…`); **Check** confirms that
    something answered before you pick files
  - Several files per transfer, one after another, with progress, speed-independent
    cancel, and per-file outcomes; a cancelled or interrupted transfer leaves no
    partial file behind on either side, and the receiver stays usable
  - Received videos are published to `Movies/VDO Player` through MediaStore
    `IS_PENDING` and appear in the library immediately; other files go to
    `Download/VDO Player`
  - Files can be streamed from a provider that does not report a size (chunked
    request bodies), so the sender never needs the whole file in memory
  - A new `TRANSFER` log tag records every incoming and outgoing file with its size
    and the peer address
- **Wi-Fi Direct**, for the two phones with no router between them: the receiving
  phone opens a group, the sending phone finds it and joins, and the group owner
  then appears in the ordinary receiver list — same code, same progress, same
  `Movies/VDO Player` destination. `NEARBY_WIFI_DEVICES` (Android 13+) or
  `ACCESS_FINE_LOCATION` (≤ 12) is requested the first time it is used.
- `ACCESS_LOCAL_NETWORK` (Android 17) plus the runtime prompt: Android 17 blocks
  local network traffic — the receiving socket and mDNS included — for apps that
  target SDK 37, and a blocked connection only shows up as a timeout
- Optional custom device name for the receiving phone, stored in settings
- **Repeat**, from the player's control row: one tap walks off → the whole queue →
  this video, announce the new mode, and remember it for the next session
- **Seek preview**: dragging the progress bar or scrubbing with a swipe decodes a
  frame of the target position and shows it in the heads-up display, cached per
  two-second bucket so a slow drag does not re-decode; switchable in Settings
- 54 JVM unit tests (`./gradlew :app:testDebugUnitTest`) covering the wire
  protocol both ways over loopback sockets, the file-name sanitiser, the chunked
  body path, cancellation, the repeat cycle, the seek-preview policy, the Wi-Fi
  Direct address rules, and the promise that both string files carry the same keys
  and placeholders; CI runs them before it builds a release
- A GitHub Actions release workflow (`.github/workflows/release.yml`): pushing a
  `v*` tag runs the unit tests, builds the four APKs, signs them from the
  repository secrets when they are configured, verifies every signature and opens
  a **draft** release with the APKs attached; the `SHA-256` checksums are printed
  in the workflow run summary

### Changed

- The manifest declares `INTERNET` and `ACCESS_NETWORK_STATE`. 1.0.0 did not, and
  the manual made a point of it; the transfer sockets need them. Nothing is sent
  to the internet — the app has no backend, and files only move between two phones
  on your own network.
- Unit testing is now part of the build (JUnit 4 in the version catalog)

### Fixed

- File names are sanitised by removing only what a file system cannot take
  (directory separators, control characters and `/ \ : * ? " < > |`), so Thai
  names with vowel marks and ordinary punctuation arrive unchanged instead of
  turning into `_`
- Long file names are shortened without losing the extension, so a long video
  name still lands in `Movies` instead of `Downloads`
- Cancelling an upload reports “Cancelled” instead of an internal
  “insufficient data written” error, and a sender walking away is no longer logged
  as an app error
- The transfer screen resolves its probe wording at composition time, so a language
  change while the screen is open cannot leave a stale message behind

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

[1.3.0]: https://github.com/NarDecH/Android_VDO_Player/releases/tag/v1.3.0
[1.2.0]: https://github.com/NarDecH/Android_VDO_Player/releases/tag/v1.2.0
[1.1.0]: https://github.com/NarDecH/Android_VDO_Player/releases/tag/v1.1.0
[1.0.1]: https://github.com/NarDecH/Android_VDO_Player/releases/tag/v1.0.1
[1.0.0]: https://github.com/NarDecH/Android_VDO_Player/releases/tag/v1.0.0
