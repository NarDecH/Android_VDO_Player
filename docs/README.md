<div align="center">

<img src="assets/app-icon.svg" alt="VDO Player" width="140" />

# VDO Player — documentation

**Play the videos on your phone. When something will not play, send the log.**

</div>

This is the full manual. The short version lives on the
[download page](index.html) and in the [repository README](../README.md).

- [What it does](#what-it-does)
- [Installing](#installing)
- [Using the app](#using-the-app)
- [Permissions and privacy](#permissions-and-privacy)
- [Diagnostics and logs](#diagnostics-and-logs)
- [Troubleshooting](#troubleshooting)
- [Building from source](#building-from-source)
- [Common questions](#common-questions)

---

## What it does

VDO Player is a **local file** video player for Android. It does not stream from
the internet, it does not upload anything, and it has no account. Point it at the
videos already on the device and it plays them; the interesting engineering is in
the two places that usually break on real phones — codec coverage and
diagnosability.

![Library screen](assets/screen-library.svg)

### Feature list

**Library**

- Scans the MediaStore video collection and refreshes live when files change
- Thumbnails rendered from the video itself (no sidecar files needed)
- Search across titles and folder names, folder filter chips
- Sort by date, name, duration or size; grid (1–4 columns) or list layout
- Long press for file details: folder, resolution, duration, size, MIME type, URI

**Playback**

- Media3 ExoPlayer with the phone's hardware decoders
- FFmpeg software decoders (NextLib) for the formats hardware refuses —
  selectable priority: hardware first, FFmpeg first, or hardware only
- Playlists: next/previous within the filtered library view
- Resume where you stopped, per video, remembered across restarts
- Background audio with a media notification (play/pause/seek from the shade)
- Long press for 3× temporary speed-up; 0.25×–3× permanent speed choices

**Player screen**

- Tap to hide/show controls, double tap left/right to seek by the configured step
- Swipe vertically on the left half for brightness, right half for volume, and
  horizontally anywhere to scrub — all with an on-screen heads-up display
- Aspect ratio modes: fit, zoom (crop), stretch, match width
- Control lock so a pocketed phone cannot skip your video
- Screen rotation button plus optional auto-rotate
- Picture-in-picture (Android 8+) with the video's real aspect ratio
- Mixing: shows the exact selected video/audio/subtitle formats for the file
- Side-load `.srt`, `.vtt` or `.ass` subtitles from storage

**Diagnostics**

- Detailed in-memory log with level, tag and free-text filtering
- Rotating log files on disk, crash capture with stack traces
- Device, display, memory, storage and MediaCodec inventory report
- Export as `.txt` or `.zip`, then share through any app or save to Downloads

![Player screen](assets/screen-player.svg)

## Installing

1. Open the [download page](index.html) — it queries the GitHub API for the
   newest release — or go straight to
   [Releases](https://github.com/NarDecH/Android_VDO_Player/releases/latest).
2. Choose the APK for your device:

| File | Architectures | Size | When to pick it |
|---|---|---|---|
| `app-arm64-v8a-release.apk` | 64-bit ARM | ~24 MB | Almost every phone made since 2016 |
| `app-armeabi-v7a-release.apk` | 32-bit ARM | ~23 MB | Older or budget devices |
| `app-x86_64-release.apk` | x86_64 | ~28 MB | Emulators and x86 tablets |
| `app-universal-release.apk` | all of the above | ~51 MB | When you are not sure |

3. Android will ask you to allow installing from this source; approve it, then
   open the downloaded file.
4. On first launch the app asks for video access. Without it the library is empty
   (nothing crashes) — grant it and the list fills in.

Upgrades install over the previous version as long as the same signing key is
used. Every release published from this repository is signed with the project
key; the certificate SHA-256 is printed in each release note.

## Using the app

### Library tab

| Control | What it does |
|---|---|
| Search box | Filters by title and folder as you type |
| Sort icon | Newest, oldest, name A→Z/Z→A, longest, shortest, largest |
| Layout icon | Switch between grid and single-column list |
| Folder chips | Restrict the library to one folder ("All" clears it) |
| Tap a video | Starts playback in the current (filtered) order |
| Long press | File details dialog |

### Player screen

| Gesture / control | Action |
|---|---|
| Tap | Toggle the controls overlay |
| Double tap left / right | Seek back / forward by the step in Settings |
| Swipe horizontally | Scrub, with a preview of the target time |
| Swipe vertically, left half | Screen brightness for this player only |
| Swipe vertically, right half | Media volume |
| Long press | Play at 3× while held |
| Lock button | Hide all controls until you unlock them |
| Info button (top right) | Stream details for the current file |
| PiP button | Continue in a floating window |
| Subtitle button | Turns subtitles on, or opens a file picker if none are loaded |

### Recent tab

Shows what you have played with a progress bar per entry. **Resume** continues
from the saved position; the trash icon removes one entry, the top-right icon
clears all history (this also removes resume positions).

### Settings tab

- **Appearance** — theme (system/light/dark), Material You palette, library
  columns, default sort order
- **Playback** — decoder priority, default speed, double-tap seek step, resume,
  gesture controls, keep screen on, auto rotate, remember brightness, autoplay
  next, background playback
- **Diagnostics and logs** — file log level, export format, whether exports
  include the device report
- **About** — version and licence note

## Permissions and privacy

| Permission | Why |
|---|---|
| `READ_MEDIA_VIDEO` (Android 13+) / `READ_EXTERNAL_STORAGE` (≤ 12) | List the videos on the device |
| `POST_NOTIFICATIONS` (Android 13+) | Show the playback notification |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Keep audio playing with the screen off |
| `WAKE_LOCK` | Prevent the CPU sleeping mid-playback |
| `WRITE_EXTERNAL_STORAGE` (≤ Android 9 only) | Legacy path for saving an exported log to Downloads |

There is **no** `INTERNET` permission in the manifest. The app cannot upload
your videos or your logs: sharing only happens through the Android share sheet
you trigger yourself. Log files live in the app's private external directory
(`Android/data/com.nardech.vdoplayer/files/logs/`) and are excluded from cloud
backup.

## Diagnostics and logs

The logging pipeline has four stages:

1. **`LogBus`** — every event goes into a 2000-entry ring buffer, goes to logcat,
   and (at or above the configured file level) to the writer. Tags are
   `APP`, `UI`, `PLAYER`, `MEDIA`, `DECODER`, `STORAGE`, `EXPORT`, `CRASH`; levels
   are `VERBOSE`…`ERROR`.
2. **`LogFileWriter`** — writes off the main thread into rotating files of 2 MB,
   keeping the ten newest.
3. **`CrashGuard`** — an uncaught exception writes the stack trace plus a device
   summary and flushes the file before the process dies.
4. **`LogShare`** — builds the export you send:
   - **`.txt`** — one readable report: header, optional device + codec report,
     then the session log
   - **`.zip`** — `session.log`, `diagnostics.txt` and the raw rotated files

![Logs screen](assets/screen-logs.svg)

### Sending a good bug report

1. Reproduce the problem once, so the failure is at the end of the buffer.
2. Open **Logs**, tap **Share**, keep the default `.zip` (it carries the most
   context), and send it together with the video's container/codec if you know
   them.
3. In the player, the info button's **Copy full diagnostics** adds the active
   tracks for the file that failed.

Exports never contain the video itself — only file names, URIs, sizes, codec
identifiers, and the app's own events.

## Troubleshooting

| Symptom | What to try |
|---|---|
| Empty library | Grant video access in Settings → Apps → VDO Player → Permissions; on Android 9 and below also make sure the file is in a scanned media folder |
| "This file could not be played" | Switch decoder priority to **Prefer FFmpeg** in Settings; then export a log — the start of the error entry names the failing codec |
| Video plays but audio is silent | Usually an unsupported audio codec (DTS/TrueHD/AC-3 variants). Try **Prefer FFmpeg**, and check the Audio line in the info panel |
| Subtitles never appear | Use the subtitle button to pick the `.srt`/`.vtt`/`.ass` file; embedded tracks show up automatically when the container has them |
| Choppy software decoding | Prefer hardware decoding, or pick a lower resolution source. FFmpeg decoding is CPU work, not free |
| Battery drains while paused | Check that the playback notification is gone; if not, background playback was left enabled |
| Playback stops when leaving the app | Enable **Keep playing in the background** in Settings |

## Building from source

Requirements: JDK 17, Android SDK with **platform 37** and **build-tools 37.0.0**,
and roughly 2 GB of free disk for Gradle's caches.

```bash
export ANDROID_HOME=/path/to/Android/Sdk
./gradlew :app:assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease   # per-ABI APKs plus a universal one
```

`local.properties` needs a valid `sdk.dir`. Use forward slashes on Windows —
escaped backslashes make AGP fail with an unhelpful `IOException` (see
[AGENT.md](../AGENT.md)).

Release builds are signed from `keystore.properties` in the repository root.
The file and the keystore are never committed; without them, release builds fall
back to the debug key so the build still completes.

## Common questions

**Does it play files from other apps?** Yes — "Open with" and share-for-video
intents are handled by the same player; the file is queued and played
immediately.

**Does it support network streams?** No, and that is deliberate: the app has no
`INTERNET` permission. Use a streaming app for that.

**Why is the universal APK so large?** It carries four copies of FFmpeg. Install
the `arm64-v8a` build instead unless you need the fallback.

**Is my watch history uploaded?** No. It is a small JSON blob in the app's
private DataStore, cleared from the Recent tab.

**Why GPL-3.0?** The FFmpeg decoder library (NextLib) is GPL-3.0, so the linked
application must be as well. The reasoning and alternatives are in
[RESEARCH.md](RESEARCH.md#licensing).

---

<div align="center">

[Screenshots](index.html) · [Research](RESEARCH.md) · [Changelog](CHANGELOG.md) · [Repository](https://github.com/NarDecH/Android_VDO_Player)

</div>
