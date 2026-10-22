# Verification — 2026-10-15 (andrompv 2.3.0)

## Automated checks

- `./gradlew :app:testDebugUnitTest`: **18 passed**, no failures or skips.
- `./gradlew :app:lintDebug`: **0 errors**, 27 warnings. The warnings cover:
  - newer dependency versions;
  - KTX helper suggestions;
  - `allowBackup` deprecation;
  - the exported media session service (required for system media controllers);
  - an advisory picture-in-picture hint (auto-enter is implemented);
  - the `mipmap-anydpi-v26` qualifier (AAPT does not accept the adaptive icon without it).

  No lint baseline or suppression was added.
- `./gradlew :app:assembleDebug :app:assembleRelease`: **passed**.
  - Debug APK: about 146 MiB, including the unshrunk Compose icon set and test manifest.
  - Release APK: R8-minified, about 87 MiB, unsigned.
- `./gradlew :app:connectedDebugAndroidTest`: **3 passed**, in three consecutive runs.
  - Emulator: Pixel_10_Pro, Android 17 (API 37), ARM64, 16 KiB pages, 480×960 at 160 dpi,
    SwiftShader GL.

## Instrumented behavior

1. **Playback, controls and persistence**, driven through the Compose UI:
   - real libmpv local playback, with a nonblack rendered video pixel;
   - Pause and Forward buttons;
   - track list and metadata;
   - external subtitles, subtitle style and delay, and speed through the controller;
   - exact seek;
   - Save Screenshot written to MediaStore Pictures/andrompv and decoded as an image;
   - F / Esc full screen;
   - resume persistence and queue restore;
   - landscape: transport, queue and full-screen buttons inside the window;
   - subtitle clearance lifting `sub-pos` above the controls;
   - the queue panel floating over the video without overlapping the control bar;
   - the subtitle panel fully on screen.
2. **Seek preview**: keyframe extraction, an exact frame at the requested time, and
   cancellation of stale results.
3. **Torrent**:
   - native libtorrent file table and natural sort that keeps original indexes;
   - a loopback webseed download;
   - an HTTP 206 byte range with its payload, and the whole selected file;
   - reuse without the webseed;
   - HTTP 416.

   No public swarm is contacted.

## Manual emulator checks (adb-driven, screenshots inspected)

- Welcome view; the phone (portrait) control layout; the single-row landscape layout;
  header badges (`160 × 90 • H264 • 12 FPS`).
- The More menu, with ticked video fit and repeat, and the Settings sheet.
- The Open Network Stream dialog.
- The queue panel with Now Playing card; the subtitle panel in landscape.
- Double-tap seek ripple; volume swipe HUD; seek-bar scrub preview with thumbnail;
  sideways swipe-seek preview.
- Hardware keyboard: Space toggles on each press; → seeks; F enters full screen.
- **Picture-in-picture**: entered automatically on Home while playing, and kept playing
  in the window.
- **Media notification**: Media3 posted it as a foreground-service transport
  notification with three actions.
- **Background audio** (setting on, picture-in-picture off):
  - playback kept advancing after Home;
  - mpv switched `vid=no`;
  - the service ran in the foreground with type mediaPlayback;
  - media Pause and Play keys worked from the background;
  - video came back (`vid=auto`) on return.
- Pressing Play at the end of a file restarts it.
- Queue drag-to-reorder by the row handle: two standalone files swapped places.
- The R8 release build (signed with the local debug key, only for this test) launched
  and played video.

## Physical phone checks (2.1.0 fixes)

OnePlus CPH2749, Android 16 (API 36), 1272×2772 at 560 dpi. Checked by hand over adb,
without the instrumented suite (it uninstalls the app, which would wipe the owner's data).

- Hardware decoding and high-quality scaling render correctly on the real GPU.
- Seek bar: dragging follows the finger. In 2.0.0 only the first touch registered,
  because the drag handler consumed each event before reading its movement.
- Magnet links: an opaque `magnet:?…` link opens and streams. In 2.0.0 it failed with
  "This isn't a hierarchical URI", because Android's `Uri.getQueryParameter` rejects
  opaque URIs.
- Reopening a magnet that is already queued plays the existing entry. In 2.0.0 it
  played an unqueued copy and Media3 rejected the resulting "loading while idle" state.
- Queue panel: swipe left to close; in phone landscape it takes the full height.
- Rotate button: flips to landscape and back. The paused frame is redrawn after rotating.
- The landscape control row fits a phone (screenshot in ⋮, no volume slider), with
  rotate at the far right.
- Hardware keyboard: Space toggles playback even after Tab moves focus into the controls.

## Update check, download and install (emulator, real GitHub release)

A build posing as 2.0.0 (`-PappVersionName=2.0.0 -PappVersionCode=3`) was pointed at the
real v2.1.0 release:

- On launch it found 2.1.0 and showed the release notes and size.
- It downloaded all 152,901,320 bytes with live progress, matched the SHA-256, and
  confirmed the file was a newer andrompv.
- **Install** first sent the app to Android's "Install unknown apps" screen. After allowing
  it, Android's installer accepted the update ("Update this app?"), and the installed
  version became 2.1.0 (code 4). Android's own check that the signing key matches passed.
- A build already at 2.1.0 shows no prompt and Settings reports "Version 2.1.0 · Check now".
  The leftover download is deleted at the next launch.
- Not tested: a checksum mismatch or a truncated download on a live connection (both throw
  and discard the file in the code), and updating on a physical phone.

## Torrent seeding, progress and direct play (ported from macmpv 1.9)

- Unit tests (6) cover the saved-progress format and its bad-input handling, the row state
  (a percentage capped at 99, or LOCAL), the Now Playing source line, the disk-usage
  estimate for older downloads, and when a file may play straight from disk.
- The instrumented torrent test now downloads only the queued episode from a loopback
  webseed. It checks the progress file reports it saved and the other file not, the torrent
  reports "Playing from local storage" (it left the swarm), the saved file is verified for
  direct play with matching bytes, and the queue state is LOCAL for it.
- Real swarm, emulator: the Creative Commons Big Buck Bunny magnet streamed, and its progress
  file grew while it downloaded (72 MB, then 276,134,947 of 276,134,947 bytes). On completion
  the log showed "All queued files saved; left the swarm", the header changed to "Playing
  from local storage", and the queue row read `MP4 • 263.3 MB • LOCAL`.
- After restarting the app, the LOCAL label came back from the progress file, and playing the
  item made mpv open the saved file on disk. The torrent never started (no libtorrent log
  lines) and playback resumed from the saved position.
- Not tested: a very large torrent, whether uploading really stops on a real phone's network
  (the code pauses the torrent, which closes its peer connections), and torrents with several
  queued videos on a real swarm.

## Emulator rendering finding

mpv's default high-quality scalers presented black, and sometimes dotted, frames on
the emulator's SwiftShader GL in about half of launches. The original andrompv 1.0 APK
showed the same black output in the launch-then-open flow (4 of 4 runs).

Each of these was ruled out:

- URI type;
- surface attach order (including mpv-android's force-window sequence);
- direct rendering;
- FBO format;
- the shader cache;
- the Compose layer (the window is transparent over the video).

mpv's `fast` scaler set rendered on 6 of 6, then 8 of 8, launches. **High-quality
scaling** therefore defaults to off on emulators and on for devices, and is a setting.

A separate cause found along the way: a Compose `Modifier.blur` (RenderEffect) anywhere
in the window also stopped the SurfaceView from showing through. The welcome glow now
uses a radial gradient.

## Not claimed verified

- Other phones and tablets: hardware decoding and scaling were checked on one OnePlus phone
  only. Picture-in-picture, the media notification and background audio were checked on
  the emulator, not yet on a phone.
- Every codec family.
- Public magnet and swarm behavior; WebTorrent-only peers; RTSP/RTMP servers.
- Provider-specific permanent deletion.
- Interrupted-download reuse after the OS kills the process.
- Screenshot saving on Android 9 (save picker path).
- Store-release signing and license compliance.

