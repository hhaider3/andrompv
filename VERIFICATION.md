# Verification — 2026-10-12

## Delivered artifact

- `dist/andrompv-debug.apk` (97,307,308 bytes; about 93 MiB).
- SHA-256: `b6a18a2e1d71494c39f3e6a496737720e8f1c6044cab018839243a48c649f7dd`.
- Byte-identical to `app/build/outputs/apk/debug/app-debug.apk`.
- `apksigner verify --print-certs`: passed (standard Android development/debug certificate).
- `zipalign -c -P 16 4`: passed.
- ELF program-header check: all 22 ARM64/x86-64 native libraries have LOAD
  alignment of at least 16 KiB.
- Application ID: `dev.andrompv`; minimum API 28, target API 36.
- `dist/andrompv-emulator.png`: screen capture after opening the generated video
  through the actual Android document picker.

## Final checks

- Kotlin debug and release compilation / APK assembly: **passed**.
- JVM tests: **10 passed**, no failures/errors/skips.
- Android lint: **0 errors, 32 warnings**. Remaining warnings concern English-only
  programmatic UI text, newer dependency versions, backup configuration and
  transitive manifest declarations. Compiler also reports legacy activity-result
  and API-28-compatible system-bar APIs as deprecated. No lint baseline,
  disabled check, assertion weakening or type suppression was added.
- Connected instrumentation: **3 passed**, no failures/errors/skips.
  Report: `app/build/reports/androidTests/connected/debug/index.html`.
- Emulator: Pixel_10_Pro, Android 17/API 37, ARM64, **16,384-byte pages**.
  The final run used 480×960 / 160 dpi to reduce software-renderer load on this
  8 GB Mac. Earlier native runs at the AVD's 1280×2856 resolution intermittently
  timed out loading video; these were not counted as successful runs.
- The final full device test included a PixelCopy assertion that a rendered
  video pixel was nonblack and a valid PNG was written, not merely a metadata
  or initialization assertion.
- Actual user interface smoke test: launch, Open → Downloads → video document,
  `content://` fd playback, queue title, 24-second duration, playback position
  progression, queue visibility, keyboard pause and screen capture checked.
- macmpv Git status was compared to the initial state: no existing macOS files
  were edited, staged, reverted or committed by this port.

## Instrumented behavior

1. Local silent thumbnail extraction, bounded size and stale callback cancellation.
2. Real libmpv local video/audio playback; observed dimensions/audio tracks;
   rendered-video capture/PNG; UI pause/seek controls; external subtitles;
   subtitle delay/speed; fullscreen controls; queue/resume/marker persistence.
3. Native libtorrent file-table resolution and natural sort retaining original
   indexes; controlled loopback webseed downloading; verified HTTP 206 byte range
   and payload; entire selected-file payload; reuse without the webseed; HTTP 416.

The tests contact no public torrent swarm. Only the isolated test session permits
its loopback webseed by disabling SSRF filtering and DHT/LSD. Production retains
libtorrent's default SSRF protection. Torrent initialization uses libtorrent's
full add_torrent_params loader, preserving trackers and webseed URLs outside the
info dictionary.

## Not claimed verified

Physical phone hardware decode, all supported codec families, public magnet
metadata/swarm behavior, WebTorrent-only browser peers, RTSP/RTMP servers,
provider-specific permanent deletion, interrupted-download reuse across OS
process death, screenshot save-picker writes to every provider, and store-release
license/signing compliance require further testing. Reuse within a recreated
stream selection is tested; the torrent offline check does not kill/relaunch the
Android process. Release APK compiled but is **unsigned**.

See `README.md` for known desktop-parity differences. This is a working native
player port, not a claim of exact parity with all macOS UI/packaging features.
