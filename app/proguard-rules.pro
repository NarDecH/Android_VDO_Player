# Keep Media3 / ExoPlayer extension renderers that R8 cannot see are referenced
# reflectively by their native libraries. Minification is disabled for release
# builds today, so these rules exist for anyone who turns it back on.
-keep class androidx.media3.** { *; }
-keep class io.github.anilbeesetti.nextlib.** { *; }
-keep class com.nardech.vdoplayer.** { *; }

# FFmpeg JNI entry points
-keepclassmembers class * {
    native <methods>;
}

# Kotlin serialization / reflection friendly behaviour
-keepattributes *Annotation*, InnerClasses, Signature, SourceFile, LineNumberTable
-dontwarn org.jetbrains.annotations.**
