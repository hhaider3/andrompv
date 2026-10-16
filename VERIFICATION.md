# Verification — 2026-10-15 (andrompv 2.0.0)

## Automated checks

- `./gradlew :app:testDebugUnitTest`: **16 passed**, no failures or skips.
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
   - resume and marker persistence and queue restore;
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

- Anything on a physical phone or tablet:
  - hardware decoding;
  - high-quality scaling on real GPUs;
  - picture-in-picture and notification behavior across OEM skins.
- Every codec family.
- Public magnet and swarm behavior; WebTorrent-only peers; RTSP/RTMP servers.
- Provider-specific permanent deletion.
- Interrupted-download reuse after the OS kills the process.
- Screenshot saving on Android 9 (save picker path).
- Store-release signing and license compliance.

