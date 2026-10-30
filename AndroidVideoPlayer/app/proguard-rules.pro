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

# ---- Coil（相册缩略图 / 视频首帧）----
-keep class coil.** { *; }
-dontwarn coil.**
# VideoFrameDecoder 走 MediaMetadataRetriever，反射相关的解码器实现不能被裁
-keep class coil.decode.** { *; }
-keep class coil.map.** { *; }

# ---- Kotlin ----
-keepclassmembers class **$WhenMappings { <fields>; }
-dontwarn kotlin.**
