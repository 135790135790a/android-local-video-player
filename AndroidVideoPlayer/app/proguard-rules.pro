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

# ---- 启动动画资源（v1.0.7）----
# 防御性 keep：SplashActivity 用 R.raw.splash_intro 拼 "android.resource://" URI。
# 如果以后改成字符串常量、或 R8 内联导致字段引用不可见，资源压缩器可能把它当未使用资源删掉；
# 这里保留 R$raw 里的这个字段，让资源引用对压缩器始终可见。
-keep class com.example.videoplayer.R$raw {
    public static int splash_intro;
}
