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
diagnosability. It can also move a file from one phone to another over your own
Wi-Fi, so you never need a cable or a cloud account to get a video onto the
device that will play it.

![Library screen](assets/screen-library.svg)

### Feature list

**Library**

- Scans the MediaStore video collection and refreshes live when files change
- Thumbnails rendered from the video itself (no sidecar files needed)
- Search across titles and folder names, folder filter chips
- Sort by date, name, duration or size; grid (1–4 columns) or list layout
- Folder sections: the toolbar's folder button (or the switch in Settings) draws the
  library grouped by folder, with the chosen sort inside each one and a count per
  heading; tapping a heading narrows the list to that folder, and its play button queues
  that folder alone — the heading stays pinned at the top while its files scroll
- Long press for file details: folder, resolution, duration, size, MIME type, URI
- A muted-speaker badge marks a file whose audio this device has already proved it
  cannot decode — drawing the grid opens no file at all, because every decision is kept
  in a per-file index, and a bounded background sweep fills in the files nobody has
  played yet

**Playback**

- Media3 ExoPlayer with the phone's hardware decoders
- FFmpeg software decoders (NextLib) for the formats hardware refuses —
  selectable priority: hardware first, FFmpeg first, or hardware only
- **Switch the decoder while a video plays** (info panel → decoder chips): a file the
  hardware refuses can be rescued without stopping it, and the choice is remembered
  for the next time. Hardware first, FFmpeg and hardware-only are the same three
  settings the Settings screen offers
- **Audio track picker**: the control row lists every audio track of the current
  file with its codec, channels and language, and switching keeps the position
- **No-sound hint**: when the file has an audio track that nothing decodes, a card
  in the player names the codec and switches the **audio decoder** to FFmpeg in one tap,
  leaving the picture on hardware decoding — and remembers that codec, so the next file
  that needs it switches by itself (the switch's message has a **Forget** action, and
  Settings → Playback lists the remembered codecs too)
- **The silence is announced before it starts**: a file whose audio no decoder on the
  device names gets the warning as it opens rather than after a stretch of picture, and
  the card's **Next video** skips past a file that can only ever be silent
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
- Mixing: shows the exact selected video/audio/subtitle formats for the file, plus the
  decoder chips for switching hardware ⇄ FFmpeg live

**Wi-Fi transfer** (the **Transfer** tab)

- **Receive**: one tap starts a small HTTP server on the phone and shows an
  address plus a four digit code. Nothing is accepted without that code, so a
  stranger on the same Wi-Fi cannot drop files onto the device.
- **Send**: the other phone finds this one over mDNS — no setup, no pairing — or
  you type the address shown on the receiving phone (`192.168.1.20` is enough).
- **No router at all?** The receiving phone can open a **Wi-Fi Direct** group and
  the sending phone joins it directly; the group owner then shows up in the same
  receiver list, so the code and the progress behave exactly as over normal Wi-Fi.
  Android asks for the Nearby devices permission the first time it is used.
- Pick several files at once: they are sent one after another with progress bars
  and a cancel button. A cancelled or interrupted transfer leaves no half file
  behind on either side.
- Videos land in `Movies/VDO Player` and appear in the library immediately;
  everything else lands in `Download/VDO Player`.
- Nothing leaves the local network, and the tab spells out what the four digit
  code protects against. A sender and receiver can be any two Android phones on
  the same Wi-Fi, including a phone hotspot.

**Languages**

- English (default) and Thai translations of the entire interface
- Android 13+ lists both in **Settings → System → App languages**, so the app can
  be switched to Thai without changing the phone's system language
- Log lines, the exported diagnostics report and clipboard labels stay English;
  they are meant to be pasted into a bug report
- Subtitles: embedded text tracks, a side-loaded `.srt` / `.vtt` / `.ass` picker, and
  automatic loading of a subtitle file next to the video under the same name (or with a
  language tag, `Movie.th.srt`). Turn it off in Settings → **Find subtitles beside the
  video**

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
| Repeat button | Cycles off → the whole queue → this video, and remembers the choice. The icon lights up while repeating |
| Audio button | Lists the audio tracks of the current file (codec, channels, language) and switches between them without losing the position |
| Seek preview | While you scrub (swipe or progress bar) a frame of the target position is decoded into the heads-up display; turn it off in Settings if you prefer the plain time label |
| Lock button | Hide all controls until you unlock them |
| Info button (top right) | Stream details for the current file, plus the decoder chips (hardware first / FFmpeg / hardware only) that switch decoding while the video keeps playing |
| PiP button | Continue in a floating window |
| Subtitle button | Turns subtitles on, or opens a file picker if none are loaded |

### Transfer tab

Two modes, chosen with the chips at the top.

**Receive**

| Control | What it does |
|---|---|
| Device name | What other phones see while this one advertises; empty means the device model |
| Start receiving / Stop receiving | Opens the port and advertises on the network |
| Code | The four digits a sender must repeat; **New code** invalidates the old one at any time |
| Address | `http://<ip>:<port>` — copyable, and the one thing to type on the other phone if discovery is blocked |
| Received files | Name and folder of everything that arrived in this session; **Clear list** empties it |

**Send**

| Control | What it does |
|---|---|
| Find phones on this Wi-Fi | Scans with mDNS and lists the receivers that are advertising; tap one to select it |
| Code | The digits shown on the receiving phone |
| Or type the address | `192.168.1.20` or `192.168.1.20:45871`; **Check** says whether a receiver answered |
| Pick files and send | Chooses one or many files; they are sent one after another |
| This session | Progress, cancel, and the outcome of every upload |

A cancelled upload stops sending and the receiving phone deletes the partial file:
there is never a half-written video in the library. Received videos appear in the
**Library** tab as they land.

### Recent tab

Shows what you have played with a progress bar per entry. **Resume** continues
from the saved position; the trash icon removes one entry, the top-right icon
clears all history (this also removes resume positions).

### Settings tab

- **Appearance** — theme (system/light/dark), Material You palette, library
  columns, default sort order, folder grouping
- **Playback** — decoder priority, default speed, double-tap seek step, resume,
  gesture controls, keep screen on, auto rotate, remember brightness, seek preview,
  autoplay next, background playback
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
| `INTERNET` | Open the local sockets the Wi-Fi transfer uses — the receiving server on one phone and the sending client on the other. It is a normal permission, granted at install time, so no dialog appears. |
| `ACCESS_NETWORK_STATE` | Read whether Wi-Fi is up, so the transfer screen can explain itself instead of silently failing |
| `ACCESS_LOCAL_NETWORK` (Android 17+) | Android 17 blocks local network traffic — including the receiving socket and mDNS — for apps that target it. Requested the first time you use the transfer tab; changing Wi-Fi networks does not ask again. |

**Nothing is uploaded anywhere.** `INTERNET` is declared because a socket is how
two phones on the same Wi-Fi talk to each other: the receiving phone listens on a
port and the sending phone connects to the address you pick or discover. The app
has no server to talk to, no account and no analytics, and it never resolves a
name on the public internet. Files only move when you press **Start receiving** on
one phone and **Pick files and send** on the other, and `ACCESS_LOCAL_NETWORK`
makes that stay true on Android 17, where the network stack would otherwise block
the traffic. Log files live in the app's private external directory
(`Android/data/com.nardech.vdoplayer/files/logs/`), are excluded from cloud
backup, and are only shared through the Android share sheet you trigger yourself.

If you deny `ACCESS_LOCAL_NETWORK`, nothing breaks: the transfer tab shows a
notice with a button to grant it, and the rest of the app keeps working.

## Diagnostics and logs

The logging pipeline has four stages:

1. **`LogBus`** — every event goes into a 2000-entry ring buffer, goes to logcat,
   and (at or above the configured file level) to the writer. Tags are
   `APP`, `UI`, `PLAYER`, `MEDIA`, `DECODER`, `STORAGE`, `EXPORT`, `TRANSFER`,
   `CRASH`; levels are `VERBOSE`…`ERROR`. Transfer entries name the file, its size
   and the peer address, which is usually enough to tell a wrong code apart from a
   phone that never reached the network.
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
| "This file could not be played" | Tap the info button and switch the decoder to **FFmpeg** right there — no need to leave the video. If it still fails, export a log: the start of the error entry names the failing codec |
| Video plays but audio is silent | The player offers **Use FFmpeg** in a card as soon as it sees an audio track nothing decodes — accept it, or set **Prefer FFmpeg** yourself if you dismissed it. Accepting also remembers the codec, so later files with it switch by themselves; the Audio line in the info panel names the codec being decoded. If nothing on the device can decode the track at all, a card says so **before** the video starts, with no FFmpeg button — switching decoders cannot rescue a codec nothing implements |
| A silent file has no muted-speaker badge | Badges are decided in the background, a batch at a time, so a file is marked once its audio has actually been checked — reopen the library and give it a moment. The badge only marks a file **nothing** on the device can decode; a file that is quiet because the decoder is set to hardware only gets the player's **Use FFmpeg** card instead |
| Subtitles never appear | Use the subtitle button to pick the `.srt`/`.vtt`/`.ass` file; embedded tracks show up automatically when the container has them. Automatic loading looks for a file with the video's own name in the same folder, and on Android 13+ it can only see files this app owns — check the `SUBTITLE` lines in the log to see what it searched |
| Decoder switch does something unexpected | The chips apply immediately and are stored, so the next video starts with them. **Hardware only** refuses FFmpeg and software decoders; **FFmpeg** decodes with FFmpeg even when the chip could do it |
| Choppy software decoding | Prefer hardware decoding, or pick a lower resolution source. FFmpeg decoding is CPU work, not free |
| Battery drains while paused | Check that the playback notification is gone; if not, background playback was left enabled |
| Playback stops when leaving the app | Enable **Keep playing in the background** in Settings |
| The video repeats, or never moves to the next one | Check the repeat button in the player controls: lit green for “whole queue”, a “1” badge for “this video”. Tap it until it is dim for off |
| Scrubbing feels slow on a big 4K file | The seek preview decodes a frame per two seconds of movement, which costs CPU on large files. Turn **Seek preview** off in Settings and only the time label appears |
| Wi-Fi Direct says the request was refused | Both phones need Wi-Fi Direct enabled and no other group open; the receiving phone has to open the group first. Guest networks and some vendors disable it — fall back to a hotspot plus the typed address |
| The other phone never appears in the send list | Both phones must be on the same Wi-Fi network (**not** one on Wi-Fi and one on mobile data), and the receiving phone must have the transfer tab open with receiving started. Guest networks that isolate clients from each other block it by design — type the receiving phone's address instead, and if that also fails, use one phone as a hotspot. |
| "Nothing answered there" after typing an address | Check the address shown on the receiving phone; the port matters (`192.168.1.20:45871`, and typing just `192.168.1.20` assumes the default port). |
| Transfers fail on Android 17 with a timeout | Allow the **Nearby devices** permission for the app: **Settings → Apps → VDO Player → Permissions → Nearby devices**. Android 17 blocks local network traffic without it, and a blocked TCP connection looks like a timeout. |
| Wrong code, or the code keeps being rejected | The code changes every time receiving is restarted, and after too many wrong attempts it rotates by itself. Read it off the receiving phone again. |
| A file arrived with the wrong name | Only characters a file system cannot take (`/ \ : * ? " < > |`) are replaced with `_`; everything else, Thai included, is kept exactly as sent. |

## Building from source

Requirements: JDK 17, Android SDK with **platform 37** and **build-tools 37.0.0**,
and roughly 2 GB of free disk for Gradle's caches.

```bash
export ANDROID_HOME=/path/to/Android/Sdk
./gradlew :app:assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease   # per-ABI APKs plus a universal one
./gradlew :app:testDebugUnitTest # wire protocol, file names, Thai translations
```

The unit tests are plain JVM tests — no device or emulator — and they cover the
parts that are easy to break silently: the HTTP contract between the two phones,
the file-name sanitiser, and the promise that both string files carry the same
keys with the same placeholders.

`local.properties` needs a valid `sdk.dir`. Use forward slashes on Windows —
escaped backslashes make AGP fail with an unhelpful `IOException` (see
[AGENT.md](../AGENT.md)).

Release builds are signed from `keystore.properties` in the repository root.
The file and the keystore are never committed; without them, release builds fall
back to the debug key so the build still completes.

## Common questions

**How do I switch the app to Thai?** On Android 13 or newer, open
**Settings → System → App languages → VDO Player** and pick ไทย. The app itself
has no language switch on purpose: that screen is where Android keeps per-app
languages, and it survives reinstalls.

**Does it play files from other apps?** Yes — "Open with" and share-for-video
intents are handled by the same player; the file is queued and played
immediately.

**Does it support network streams?** No, and that is deliberate: it plays files
that are on the device. The `INTERNET` permission exists only for the Wi-Fi
transfer tab, which talks to another phone on your own network. Use a streaming
app for HTTP(S) playback.

**Why is the universal APK so large?** It carries four copies of FFmpeg. Install
the `arm64-v8a` build instead unless you need the fallback.

**Is my watch history uploaded?** No. It is a small JSON blob in the app's
private DataStore, cleared from the Recent tab.

**Does the transfer work without internet access?** Yes. It needs Wi-Fi between the
two phones — a home router, a public hotspot, or one phone sharing its own hotspot —
but no internet connection and no account. Airplane mode plus Wi-Fi works.

**Where do received files go?** Videos go to `Movies/VDO Player`, other files to
`Download/VDO Player`, and both appear in the phone's own Files app. Videos show up
in the library straight away, so you can play a file the moment it arrives.

**Can it send to an iPhone or a PC?** Not with a nice UI, but the receiving side is
plain HTTP: `curl -H 'X-VDO-Code: 1234' --data-binary @clip.mp4 'http://192.168.1.20:45871/upload?name=clip.mp4'`
works from any machine on the same network, and the code is the one shown on the
receiving phone.

**Why GPL-3.0?** The FFmpeg decoder library (NextLib) is GPL-3.0, so the linked
application must be as well. The reasoning and alternatives are in
[RESEARCH.md](RESEARCH.md#licensing).

---

<div align="center">

[Screenshots](index.html) · [Research](RESEARCH.md) · [Changelog](CHANGELOG.md) · [Repository](https://github.com/NarDecH/Android_VDO_Player)

</div>
