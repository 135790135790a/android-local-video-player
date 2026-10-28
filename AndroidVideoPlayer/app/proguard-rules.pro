# ---- Media3 / ExoPlayer ----
-dontwarn androidx.media3.**
-keep class androidx.media3.exoplayer.** { *; }
-keep class androidx.media3.common.** { *; }
-keep class androidx.media3.extractor.** { *; }
-keep class androidx.media3.decoder.** { *; }
-keep class androidx.media3.datasource.** { *; }

# 反射创建的解码器 / 渲染器
-keep class * extends androidx.media3.exoplayer.Renderer { *; }
-keep class * implements androidx.media3.exoplayer.Renderer { *; }

# 保留注解，避免播放器内部反射读取失败
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes InnerClasses

# ---- Kotlin ----
-keepclassmembers class **$WhenMappings { <fields>; }
-dontwarn kotlin.**
