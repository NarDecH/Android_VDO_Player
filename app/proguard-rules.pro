# R8 runs on release builds. Media3, Compose, DataStore, Coil and NextLib each ship
# consumer rules for the parts they reach by name, so this file only holds what no
# library can declare for this app. The previous version kept every class of this
# app and every Media3 class in full, which left R8 almost nothing to remove.

# NextLib's FFmpeg renderers are constructed and called from libmedia3ext.so. The
# library's own rules keep native method names but not the classes they belong to,
# and a renamed class is a JNI lookup that finds nothing at playback time — the
# failure is a silent file, not a crash, which is the hardest kind to notice.
-keep class io.github.anilbeesetti.nextlib.** { *; }

# Belt and braces for JNI: keep the name of every native method and of the class
# declaring it, including in a NextLib release that stops declaring this itself.
-keepclasseswithmembernames class * {
    native <methods>;
}

# The log screen and the diagnostics report print class names, and a bug report full
# of `a.b.c` is not worth having. Names only: these classes are still shrunk,
# optimised and removed when nothing reaches them, which is why the app's own
# package is kept by name rather than kept whole.
-keepnames class com.nardech.vdoplayer.**
-keepnames class androidx.media3.**

# The metadata Media3 and Compose read at runtime, and the line numbers that make a
# stack trace in the log point at a line of code.
-keepattributes *Annotation*, InnerClasses, Signature, SourceFile, LineNumberTable
-dontwarn org.jetbrains.annotations.**
