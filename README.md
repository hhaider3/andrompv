# andrompv

A standalone **native Kotlin Android port of macmpv**. Android Views render the
interface; a `SurfaceView` renders the actual **mpv 0.41 / FFmpeg** engine via JNI.
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
The original local workspace also contains `dist/andrompv-debug.apk`; generated
APKs, screenshots and build reports are not tracked in this source repository.
Final results and unverified scenarios: [VERIFICATION.md](VERIFICATION.md).

```sh
"$ANDROID_HOME/platform-tools/adb" install -r app/build/outputs/apk/debug/app-debug.apk
"$ANDROID_HOME/platform-tools/adb" shell am start -n dev.andrompv/.MainActivity
```

Release compilation: `./gradlew :app:assembleRelease`. Release APKs are unsigned
until you configure **your own** signing key. No signing secrets are included.
Do not distribute this debug build as a store release; read the license notice.

## Features

- Real embedded mpv/FFmpeg, OpenGL ES rendering, MediaCodec copy-back on physical
  devices, and software decode on Android emulators (their copy-back codecs can
  stall). Hardware decoding is a device capability, not guaranteed for every codec.
- Multi-select local files through Android's Storage Access Framework; grants are
  retained when the provider permits them. Open-with and share intents supported.
- HTTP/HTTPS, RTSP, RTMP and HLS streams. Cleartext HTTP is deliberately permitted
  for the source project's HTTP/RTMP features and loopback torrent server.
- Persistent queue, current item, and sidebar visibility. Collapsible collections,
  next/previous, long-press drag, move up/down, remove, clear and repeat off/all/one.
  Manual next does not repeat one.
- Play/pause, exact seeking, throttled scrub seeks, mute, volume, 0.25–4× speed,
  fullscreen and measured subtitle clearance above controls.
- Embedded audio/subtitle selection; external SRT/ASS/SSA/VTT files copied to app
  cache so document URIs work with libass. Subtitle scale, outline, bold, color,
  background opacity and sync delay; appearance settings persist.
- Per-source playback resume (including original torrent file index), saved
  intro/outro markers, and skip intro/outro actions.
- Local thumbnail scrubbing with an independent silent decoder, coalesced work,
  stale-result rejection, an 8 MiB cache and five-second idle decoder release.
- PNG video screenshots through Android's save-document picker, no broad storage
  permission. Screenshots contain the video surface, not the app's controls.
- Metadata from mpv (duration, resolution, frame rate, codecs, channels, format,
  size), without spawning ffprobe.
- `.torrent`, `.magnet` and magnet URI resolution; multi-file media sorted in
  natural order while preserving original file indexes. A random-token loopback
  HTTP endpoint supports GET/HEAD and single byte ranges, verifies pieces before
  reading sparse files, and uses piece deadlines around seeks. Metadata timeout:
  60 seconds; piece buffering timeout: 30 seconds; at most four HTTP workers.
- Torrent files stay in app-private `files/Torrents/<info-hash>/` and are rechecked
  and reused on reopen. The selected file receives download priority; after it
  completes, remaining media download one at a time in natural filename order.
- Confirmed deletion of selected Android documents or app-owned downloads.
  Clearing/removing queue entries alone never deletes files.
- Audio focus, pause/download suspension on backgrounding, screen-awake only
  during foreground playback, and native predictive-back handling.

## Android adaptations and remaining differences

This ports the player, not the macOS packaging/release scripts or marketing site.
It is **not yet exact feature parity** with the desktop UI:

- Queue reordering uses long-press drag onto another row, plus move up/down.
- Android has no application-wide Trash: explicit deletion is permanent and
  provider-dependent. Torrent downloads are app-private and removed on uninstall;
  the downloads menu lists them rather than opening Finder.
- Local document M3U playlists support absolute network URLs. Relative sibling
  paths in a `content://` document cannot be accessed with a single-document grant;
  select those files together instead. HLS manifests are passed intact to mpv.
- Remote/torrent seek previews show timestamps only. Android's metadata retriever
  cannot reliably bound/cancel network extraction, so it never receives network
  sources. Local thumbnails depend on Android's decoder support, while playback
  itself uses mpv's broader format support.
- Playback and downloads pause when the app backgrounds. Background media
  service, lock-screen notification, picture-in-picture, casting, and TV UI
  are not implemented. Returning to the app requires tapping Play. For a
  torrent, that tap reopens the local stream and resumes from the saved position.
- Torrent metadata is cached and files survive restarts, but no serialized
  libtorrent resume-state file is stored: existing pieces are rehashed on reopen.
- BitTorrent/libtorrent is a different peer stack than desktop WebTorrent;
  WebTorrent-only browser swarms are not promised to work.
- Mac defaults/queues do not automatically migrate to Android. URI grants and
  app-private persistence are Android-specific.
- The native wrapper does not expose an END_FILE reason. Only the observed,
  single-consumed `eof-reached` advances the queue; failed loads time out instead
  of being mistaken for successful completion.
- Share targets are video, audio, playlists, torrents, and text or magnet links.
- Public magnet swarms, RTSP/RTMP cameras, every codec, physical-device hardware
  decode and provider-specific deletion need real-device testing.

## Controls

- **Open**: choose files (including playlist/torrent files).
- **URL**: enter a stream or magnet.
- **Queue**: toggle the collection list; each `⋮` offers queue/file actions.
- Player `⋮`: tracks, subtitles, speed, volume, repeat, markers, screenshots,
  information, fullscreen and torrent downloads.
- Tap video: play/pause; in fullscreen, show/hide controls.
- Double-tap the left or right third of the video to seek ±10 seconds.
  Double-tap the center to toggle fullscreen. Back exits fullscreen before closing the app.
- On a phone-width screen the play controls sit on their own row. Subtitle, audio,
  volume, and the other tools scroll on the row beneath them.
- Hardware keyboard: Space/play-pause, arrows for ±10s, media next/previous,
  `M` mute, `[`/`]` speed, `\` reset speed, `F` fullscreen, Esc exit fullscreen.

## Verification

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
# Start an ARM64 or x86-64 emulator or connect a device first.
./gradlew :app:connectedDebugAndroidTest
```

Ten JVM regressions cover completion, repeats, queue identity/reordering/removal,
resume, natural sorting, stream validation, HLS handling, byte-range validation
and torrent path traversal. Instrumented tests use bundled tiny fixtures and
local loopback webseeds, not public peers. They exercise real libmpv playback,
controls, subtitles, metadata, persistence, local preview extraction, native
torrent resolution, exact HTTP ranges and offline downloaded-file reuse.

Test assets are included under `app/src/androidTest/assets/`, not shipped in the
main APK. Regenerate with `python3 scripts/generate-test-fixtures.py` (requires
ffmpeg with libx264). The torrent fixture's loopback port is 18765. Only the test
session opts out of SSRF filtering for its controlled loopback webseed and disables
DHT/LSD; production retains libtorrent's default SSRF protection.

## Implementation map

| macmpv concept | Android implementation |
| --- | --- |
| SwiftUI views / AppKit commands | `MainActivity.kt`, native Android widgets/pickers |
| MPVEngine / OpenGL surface | `MPVEngine.kt`, libmpv JNI / SurfaceView |
| MediaModels / PlaybackCompletion | `MediaModels.kt` |
| PlayerModel queue / persistence | `PlaybackQueue`, `PlayerStore.kt` |
| ffprobe metadata | observed mpv metadata properties |
| SeekPreview | `SeekPreview.kt`, local MediaMetadataRetriever |
| MagnetStream / torrent runtime/storage | `TorrentStream.kt`, native libtorrent / loopback server |
| NSSavePanel screenshots | PixelCopy / Android create-document picker |

Native operations are serialized off the main looper. mpv callbacks post
snapshots to the UI rather than synchronously querying native state during layout.

## Dependencies and licensing

Pinned build versions are in `app/build.gradle.kts`; no arbitrary local `.so`
files are required. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) before
publishing. The original project license is retained in [LICENSE](LICENSE).
The published mpv AAR contains a GPL-enabled native media stack; the wrapper's
MIT declaration does **not** make the entire APK MIT/Apache-only. Distribution
needs corresponding-source and notice compliance for those native components.
