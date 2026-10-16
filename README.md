# andrompv

A standalone **native Kotlin Android port of macmpv**. Jetpack Compose renders macmpv's
glass interface; a `SurfaceView` renders the actual **mpv 0.41 / FFmpeg** engine via JNI.
This is not a website, WebView, Electron app, or a renamed platform player.
Native libtorrent replaces the macOS Node/WebTorrent CLI.

The sibling `macmpv/` Swift project is unchanged. Open this `andrompv/` folder
itself in Android Studio.

## Requirements and build

- Android 9 (API 28) or newer; ARM64 devices or x86-64 emulators.
- JDK 17 or 21 and Android SDK platform 36; Gradle wrapper included.
- Internet access on the first build to resolve pinned Maven dependencies.
- No Homebrew mpv, ffprobe, Node, or torrent CLI on the Android device.

```sh
cd andrompv
export ANDROID_HOME="$HOME/Library/Android/sdk"  # macOS example
# Use your installed JDK, e.g. Android Studio's bundled JDK on macOS:
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:assembleDebug
```

Build the installable development APK at `app/build/outputs/apk/debug/app-debug.apk`.
The local workspace also keeps built APKs and screenshots in `dist/`; generated APKs,
screenshots and build reports are not tracked in this source repository.
Final results and unverified scenarios: [VERIFICATION.md](VERIFICATION.md).

```sh
"$ANDROID_HOME/platform-tools/adb" install -r app/build/outputs/apk/debug/app-debug.apk
"$ANDROID_HOME/platform-tools/adb" shell am start -n dev.andrompv/.MainActivity
```

Release compilation: `./gradlew :app:assembleRelease`. Release builds are shrunk
with R8 (`app/proguard-rules.pro` keeps the libmpv and libtorrent4j JNI bindings) and
are unsigned until you configure **your own** signing key. No signing secrets are included.
Do not distribute this debug build as a store release; read the license notice.

## Features

Everything macmpv does, rebuilt for touch, plus what Android players are expected to do.

**From macmpv**

- Real embedded mpv/FFmpeg rendering into a `SurfaceView` over OpenGL ES. MediaCodec
  copy-back decoding on physical devices; software decoding on emulators, whose
  copy-back codecs can stall. Hardware decoding is a device capability, not guaranteed
  for every codec.
- macmpv's dark glass interface, in Jetpack Compose with Material icons:
  - a header with title and metadata badges (resolution • codec • FPS);
  - a floating control bar;
  - the "Play something beautiful" welcome view.
  - Tablets and landscape get macmpv's single control row. Phones stack the transport
    above a compact tool row.
- Menus that tick the active choice:
  - **Subtitles**: Off, tracks with language names, Open Subtitle File…, Subtitle Settings…;
  - **Audio**: tracks with codec and channel layout;
  - **Speed**: 0.25–3×;
  - **Intro/outro markers**: set, skip and clear.
- Live subtitle appearance panel: size, outline, background, bold, text color and sync
  delay. Settings persist, and subtitles move clear of the controls by the measured overlap.
- **Up Next** queue sidebar:
  - collapsible playlist and torrent collections;
  - drag-handle reordering;
  - long-press menus (Play, Move Up/Down, Remove, Delete);
  - watch-progress bars on rows, and a Now Playing card with metadata pills.
  - Repeat off/all/one. Manual Next never repeats one item.
- Seek previews like macmpv's: a silent decoder shows the nearest keyframe while you
  drag, then sharpens to the exact frame once your finger rests (local files only).
  The seek bar also shows the buffered range and marker ticks.
- Per-file resume (500 most recent files, as in macmpv), with a "Resumed from 12:34 ·
  Start over" prompt. Contextual **Skip Intro** / **Skip Outro** chips.
- Local files through the Storage Access Framework (grants kept where the provider
  allows), open-with and share intents, HTTP/HTTPS/RTSP/RTMP/HLS, and M3U playlists.
- `.torrent`, `.magnet` and magnet links through native libtorrent:
  - streamed over a random-token loopback HTTP endpoint, with natural-order episodes
    keeping their original file indexes;
  - the playing file gets download priority, then the rest download one at a time;
  - downloads are reused on reopen.
  - A **Torrent Downloads** sheet lists cached torrents, with sizes, and deletes them.
- Screenshots of the decoded video saved to Pictures/andrompv; Android 9 uses the save
  picker. The bundled FFmpeg has no PNG encoder for mpv, so the frame is copied from
  the video surface.
- macmpv's keyboard shortcuts on hardware keyboards (see Controls).

**Android additions**

- **Picture-in-picture**: enters automatically when you go Home during playback, with
  back / play-pause / forward buttons. Closing the window pauses.
- **Media session**: system media notification, lock-screen controls, and Bluetooth or
  headset buttons. Built on Media3; the mpv controller stays the source of truth.
- **Background audio** (optional): keeps the sound playing with a foreground media
  notification. Video decoding is switched off until you return.
- **Gestures**:
  - double-tap the sides to seek (taps add up);
  - double-tap the middle for full screen (macmpv's double-click);
  - swipe sideways to seek, with a preview;
  - swipe up and down on the right for volume, on the left for brightness;
  - touch and hold for 2× speed;
  - pinch to zoom to fill.
- **Full screen** hides the system bars and turns the screen to match the video.
  Fit / Zoom to fill / Stretch modes.
- **Settings**:
  - hardware decoding;
  - high-quality scaling;
  - resume;
  - skip interval (5/10/15/30 s);
  - picture-in-picture;
  - background audio;
  - swipe gestures;
  - hold-for-speed;
  - rotate in full screen.
- Audio focus, ducking, and screen-on only while playing.

## Android adaptations and remaining differences

- Android has no app-wide Trash: deleting is permanent and depends on the document
  provider. Torrent downloads are app-private and removed on uninstall. The downloads
  sheet replaces Show in Finder.
- Local document M3U playlists support absolute network URLs only. Relative sibling paths
  in a `content://` document cannot be opened with a single-document grant; select those
  files together instead. HLS manifests are passed to mpv intact.
- Remote and torrent seek previews show timestamps only: Android's metadata retriever
  cannot reliably bound or cancel network reads.
- Without background audio, leaving the app pauses playback and torrent downloads, as
  macmpv does when it quits. A torrent then reopens its stream when you press Play.
- **High-quality scaling** is on by default on devices and off on emulators. mpv's
  default scalers render black or garbled frames on the emulator's SwiftShader GL. Turn
  it off on a device that shows the same symptom, or to save battery.
- Torrent metadata is cached and files survive restarts, but no libtorrent resume file
  is stored: existing pieces are rehashed on reopen. BitTorrent/libtorrent is a
  different peer stack from desktop WebTorrent, so WebTorrent-only browser swarms are
  not expected to work.
- macmpv's queues and settings do not migrate to Android.
- Casting and an Android TV interface are not implemented.

## Controls

- **Open** (header): choose files, including playlists and torrents. **Link** button:
  open a stream or magnet link. **⋮**: picture-in-picture, video fit, repeat, media
  info, torrent downloads, settings. On phones it also holds Mute and Save Screenshot.
- Control bar:
  - queue, subtitles, subtitle style, audio and markers;
  - previous, back, play/pause, forward and next;
  - mute and volume, speed, screenshot, full screen.
- Tap the video to show or hide the controls. They hide after 3.5 s of playback and
  stay up while paused or while a menu is open.
- Hardware keyboard (as in macmpv):
  - Space or K: play/pause;
  - ← / →: seek by the skip interval;
  - Ctrl/⌘ + ← / →: previous / next item;
  - ↑ / ↓: volume;
  - M: mute;
  - `[` / `]` / `\`: speed down / up / reset;
  - F: full screen;
  - Esc: close panel or leave full screen;
  - Ctrl+O: open media;
  - Ctrl+Shift+O: open subtitles;
  - Ctrl+Shift+S: screenshot;
  - Ctrl+Alt+S: queue;
  - Ctrl+L: open URL.
  - Media keys work in the app and through the media session.

## Verification

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
# Start an ARM64 or x86-64 emulator or connect a device first.
./gradlew :app:connectedDebugAndroidTest
```

Sixteen JVM tests cover:

- completion and repeat modes;
- queue identity, reordering (including drag), removal and torrent placeholder replacement;
- resume pruning;
- natural sorting;
- stream validation and HLS handling;
- byte ranges and torrent path traversal;
- labels.

Instrumented tests use bundled tiny fixtures and local loopback webseeds, not public
peers. They drive the Compose UI (pause and seek buttons, keyboard full screen,
landscape layout, the queue panel staying clear of the controls, and the subtitle
panel) and exercise:

- real libmpv playback and a nonblack rendered frame;
- subtitles and speed;
- a screenshot saved to MediaStore;
- persistence;
- local keyframe and exact preview extraction;
- native torrent resolution, exact HTTP ranges and offline reuse of downloaded files.

Test assets are under `app/src/androidTest/assets/` and are not shipped in the main APK.
Regenerate them with `python3 scripts/generate-test-fixtures.py` (needs ffmpeg with
libx264). The torrent fixture's loopback port is 18765. Only the test session opts out
of SSRF filtering, for its controlled loopback webseed, and disables DHT/LSD.
Production keeps libtorrent's default SSRF protection.

## Implementation map

| macmpv concept | Android implementation |
| --- | --- |
| PlayerModel (+Queue, +Persistence) | `PlayerController.kt` (app-scoped, Compose state), `PlaybackQueue`, `PlayerStore.kt` |
| ContentView / PlayerHeader / WelcomeView | `ui/PlayerScreen.kt` |
| PlayerControls / SubtitleSettingsView | `ui/ControlsBar.kt`, `ui/Sheets.kt` |
| QueueSidebar | `ui/QueuePanel.kt` |
| MPVEngine / VideoSurface | `MPVEngine.kt` (libmpv JNI), `SurfaceView` in `MainActivity.kt` |
| SeekPreview / SeekPreviewPopover | `SeekPreview.kt`, `PreviewCard` |
| MagnetStream / torrent runtime and storage | `TorrentStream.kt`, native libtorrent and loopback server |
| CinewaveApp menu commands | `MainActivity.kt` keyboard shortcuts |
| (no macOS equivalent) | `PlaybackService.kt` (Media3 session), picture-in-picture in `MainActivity.kt` |

Native operations are serialized off the main looper. mpv callbacks update snapshot
state on the main thread, with position updates coalesced to four a second (as in
macmpv), so the UI never queries native state during composition. The controller
outlives the activity, so picture-in-picture, the notification and background audio
all drive the same player.

## Dependencies and licensing

Pinned build versions are in `app/build.gradle.kts`; no arbitrary local `.so`
files are required. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) before
publishing. The original project license is retained in [LICENSE](LICENSE).
The published mpv AAR contains a GPL-enabled native media stack; the wrapper's
MIT declaration does **not** make the entire APK MIT/Apache-only. Distribution
needs corresponding-source and notice compliance for those native components.
