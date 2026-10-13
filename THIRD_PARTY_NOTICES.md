# Third-party dependencies and distribution notice

The application source is a Kotlin port of the sibling macmpv project, whose
Apache-2.0 license is copied into `LICENSE`. That license does not override any
third-party library's terms.

## Bundled runtime dependencies

- **libmpv Android wrapper 1.0.0** — `dev.jdtech.mpv:libmpv:1.0.0`.
  Wrapper declared MIT. Source: <https://github.com/jarnedemeulemeester/libmpv-android>.
  Artifact: <https://central.sonatype.com/artifact/dev.jdtech.mpv/libmpv/1.0.0>.
  This AAR actually includes native mpv, FFmpeg, libass/libplacebo and other
  linked components. Runtime identifies mpv 0.41.0 and FFmpeg n8.1, with **GPL
  enabled**. The MIT wrapper license is NOT a license for all of these binaries.
- **mpv** — <https://github.com/mpv-player/mpv>.
  See <https://github.com/mpv-player/mpv/blob/master/Copyright>,
  <https://github.com/mpv-player/mpv/blob/master/LICENSE.GPL> and
  <https://github.com/mpv-player/mpv/blob/master/LICENSE.LGPL>.
- **FFmpeg** — <https://ffmpeg.org/legal.html>.
  The distributed native configuration is GPL-enabled. Source:
  <https://github.com/FFmpeg/FFmpeg/tree/n8.1>.
- **libtorrent4j 2.1.0-39** — MIT Java/JNI wrapper and native Android artifacts.
  <https://github.com/aldenml/libtorrent4j>.
  Underlying libtorrent: <https://github.com/arvidn/libtorrent> (BSD-3-Clause).
  Review the native release's dependency notices as well.
- **AndroidX Activity and transitive AndroidX libraries** — Apache-2.0.
  <https://android.googlesource.com/platform/frameworks/support>.
- **Kotlin standard library** — Apache-2.0.
  <https://github.com/JetBrains/kotlin>.
- **Gradle wrapper** — Apache-2.0.
  <https://github.com/gradle/gradle>.

JUnit and AndroidX Test are test-only and are not included in the application APK.
The subtitle font is read/copied from the device's own Android system fonts at
runtime; no external font binary is included in the source checkout.

## Before distributing

This checkout includes a locally installable **debug APK**, not a legal/compliance
certification or store-ready release. Do not describe the aggregate APK as
Apache-only or MIT-only. Audit the exact published AAR and native artifact build
recipes; retain each component's license/notices; provide the exact corresponding
source and build information required by the GPL-enabled stack, including wrapper
changes as applicable. The links above alone are not a substitute for fulfilling
corresponding-source obligations. Alternatively rebuild an audited, compatible
native stack with appropriate licensing choices and repeat device tests.

The app contains no DRM bypass, hosted services or account integration. Use
torrents only for content you are authorized to download and share; BitTorrent
sessions may upload pieces while running.
