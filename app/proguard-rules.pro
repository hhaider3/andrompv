# libmpv calls back into MPVLib (event/property/log observers) through JNI.
-keep class dev.jdtech.mpv.** { *; }

# libtorrent4j is a SWIG binding: native code resolves these classes and
# methods by name, and director callbacks are invoked from C++.
-keep class org.libtorrent4j.** { *; }
-dontwarn org.libtorrent4j.**
