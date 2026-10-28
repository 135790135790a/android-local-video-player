# 本地视频播放器（Local Video Player）

单 Activity 的本地视频播放器：SAF 选片 → Media3 ExoPlayer 播放 → Compose Material 3 控制界面。
深色主题、手机/平板横竖屏自适应、全程不申请存储权限。

| 项目 | 值 |
| --- | --- |
| 包名 / applicationId | `com.example.videoplayer` |
| versionCode / versionName | `1` / `1.0.0` |
| minSdk / targetSdk / compileSdk | `24` / `34` / `34` |
| 语言 / UI | Kotlin + Jetpack Compose + Material 3 |
| 架构 | MVVM（`AndroidViewModel` + `StateFlow`） |
| 播放内核 | `androidx.media3:media3-exoplayer` + `media3-ui` 1.4.1 |
| Gradle | AGP 8.5.2 / Gradle 8.7 / Kotlin 2.0.21 / JVM 17 |
| release APK | `VideoPlayer-1.0.0-release.apk`，2.6 MB，APK Signature Scheme v2 |

---

## 一、功能清单

| 需求 | 实现位置 | 说明 |
| --- | --- | --- |
| SAF 选视频（`video/*`） | `ui/home/HomeScreen.kt` | `ActivityResultContracts.OpenDocument`，MIME 只传 `video/*` |
| 持久化 URI 授权 | `ui/home/HomeScreen.kt` | `takePersistableUriPermission(uri, FLAG_GRANT_READ_URI_PERMISSION)`，重启后仍可续播 |
| 读取文件名/大小/时长 | `data/VideoMetaReader.kt` | `OpenableColumns.DISPLAY_NAME/SIZE` + `MediaStore.MediaColumns.DURATION`，size 为 0 时用 `openAssetFileDescriptor` 兜底 |
| 最近 5 个视频 | `data/RecentVideoStore.kt` | SharedPreferences + `org.json`，按 `lastPlayedAt` 倒序去重，`MAX_ITEMS = 5` |
| 无视频引导页 | `ui/home/HomeScreen.kt` | `EmptyState`：图标 + 文案 + “选择视频”按钮 |
| 播放/暂停、点画面切换 | `PlayerViewModel.togglePlayPause()` / `PlayerScreen` | 单击＝控件可见时切换、控件隐藏时先唤出；双击＝直接切换 |
| 暂停时中央大播放按钮 | `PlayerControls.CenterPlayButton` | 76dp `FilledIconButton`，仅在暂停且控件可见时显示 |
| 进度条（拖动只改文字、松手才 seek） | `PlayerControls.ProgressRow` | 拖动期间只更新本地 `dragValue`，`onValueChangeFinished` 才 `onSeek` |
| 缓冲进度 | `PlayerControls.ProgressRow` | Slider 下方 `Canvas` 画 2dp 缓冲线，宽度 = `bufferedFraction` |
| 时间格式 mm:ss / hh:mm:ss | `util/TimeFormat.kt` | `Long.toPlaybackTime()` |
| 崩溃兜底 | `util/CrashGuard.kt` | 堆栈落 `files/crash/`，下次启动提示；详见 `防闪退报告.md` |
| 音量走 AudioManager STREAM_MUSIC | `PlayerViewModel.applyVolume()` | `audioManager.setStreamVolume(STREAM_MUSIC, level, 0)`，**没有使用 `player.volume`** |
| 静音/低/高三档图标 | `PlayerControls.VolumeButton` | `ic_volume_off/down/up` 三个矢量图，点击在 静音→max/3→max 之间循环 |
| 音量值持久化 | `PlayerViewModel.restoreSavedVolume()` | `player_prefs` 里的 `volume_percent`，启动时恢复；并用 `ContentObserver` 跟随系统音量键 |
| 倍速 0.5~2.0 | `PlayerViewModel.setSpeed()` | `player.setPlaybackSpeed(speed)`，只换速度不重建 item，播放不中断 |
| 沉浸式全屏 | `PlayerScreen` | `WindowInsetsControllerCompat.hide(systemBars())` + `setDecorFitsSystemWindows(false)` |
| 横屏播放 | `PlayerScreen` | 全屏按钮切 `SENSOR_LANDSCAPE ↔ PORTRAIT`，退出恢复 `UNSPECIFIED` |
| 3 秒无操作隐藏控件 | `PlayerScreen` | `LaunchedEffect(controlsVisible, isPlaying, interactionKey, isDragging)` + `delay(3000)` |
| 屏幕常亮 | `PlayerScreen` | `DisposableEffect(isPlaying)` 里 add/clear `FLAG_KEEP_SCREEN_ON` |
| 双击播放/暂停、左右滑 10s、上下滑音量 | `ui/player/PlayerGestures.kt` | `detectTapGestures` + `detectDragGestures` 主轴判断 |
| 旋转不重建播放器 | `PlayerViewModel` + `MainActivity` | ViewModel 持有 ExoPlayer；`onDestroy` 里 `if (!isChangingConfigurations) releasePlayer()` |
| onStop 暂停 / onDestroy release | `MainActivity` + `onHostStop/onHostStart` | onStop 暂停并记录 `resumeAfterStop`，onStart 回来续播 |
| 音频焦点 | `PlayerViewModel.requestAudioFocus()` | `AudioFocusRequest`（API 26+）/ `requestAudioFocus`（旧 API）；LOSS 暂停、LOSS_TRANSIENT 记录、GAIN 恢复 |
| 错误中文映射 | `player/PlaybackErrorMapper.kt` | `ERROR_CODE_DECODING_FAILED` → “该视频的编码格式不被支持…”；`IO_*`、`PARSING_*`、`DRM_*` 等逐条映射 |
| BUFFERING 显示 loading | `PlayerScreen` | `state.isBuffering` → 中央 `CircularProgressIndicator` |

---

## 二、目录结构

```
视频播放器/                                  ← 工作区根目录
├── README.md                               ← 本文档
├── VideoPlayer-1.0.0-release.apk           ← 已构建好的签名安装包（2.6 MB）
├── AndroidVideoPlayer/                     ← Gradle 工程根
│   ├── settings.gradle.kts                 仓库配置（阿里云/腾讯镜像）
│   ├── build.gradle.kts                    顶层插件声明
│   ├── gradle.properties                   JVM/构建开关
│   ├── gradlew / gradlew.bat               Gradle wrapper（8.7，走腾讯镜像）
│   ├── keystore.properties                 ★ 签名配置（口令，勿外传）
│   ├── keystore/
│   │   └── release.keystore                ★ 自签名证书（RSA 2048 / 10000 天）
│   ├── gradle/
│   │   ├── libs.versions.toml              版本目录
│   │   └── wrapper/
│   │       ├── gradle-wrapper.jar
│   │       └── gradle-wrapper.properties
│   └── app/
│       ├── build.gradle.kts                应用模块（签名、混淆、依赖）
│       ├── proguard-rules.pro              Media3 混淆规则
│       └── src/main/
│           ├── AndroidManifest.xml         清单（唯一权限 MODIFY_AUDIO_SETTINGS）
│           ├── java/com/example/videoplayer/
│           │   ├── MainActivity.kt         宿主 Activity、生命周期转发
│           │   ├── data/
│           │   │   ├── RecentVideo.kt      最近播放数据模型
│           │   │   ├── RecentVideoStore.kt 最近播放持久化（SP + JSON）
│           │   │   └── VideoMetaReader.kt  ContentResolver 元数据读取
│           │   ├── player/
│           │   │   ├── PlaybackErrorMapper.kt  PlaybackException → 中文提示
│           │   │   └── PlayerViewModel.kt      ★ 播放器状态机（ExoPlayer 持有者）
│           │   ├── ui/
│           │   │   ├── AppRoot.kt          首页 / 播放页切换
│           │   │   ├── home/HomeScreen.kt  选片、引导页、最近列表
│           │   │   ├── player/
│           │   │   │   ├── PlayerScreen.kt    ★ 播放页（全屏/自动隐藏/手势编排）
│           │   │   │   ├── PlayerControls.kt  顶栏、底栏、进度条、倍速、错误弹窗
│           │   │   │   ├── PlayerGestures.kt  点击/双击/滑动识别层
│           │   │   │   └── VideoSurface.kt    PlayerView 的 Compose 封装
│           │   │   └── theme/Theme.kt      深色配色 + 字体
│           │   ├── util/TimeFormat.kt      时间/体积格式化
│           │   └── util/CrashGuard.kt      未捕获异常兜底（写崩溃日志）
│           └── res/
│               ├── layout/player_view.xml  PlayerView（texture_view，关内置控制条）
│               ├── drawable/               ic_pause / ic_volume_{up,down,off} /
│               │                           ic_fullscreen{,_exit} / ic_speed /
│               │                           ic_launcher_foreground
│               ├── mipmap-*/               ic_launcher(.round) 各密度图标
│               ├── mipmap-anydpi-v26/      adaptive-icon 定义
│               └── values/                 strings / colors / themes
└── _tools/                                 本机构建工具链（JDK17、Gradle、Android SDK，可删）
    ├── jdk17/                               OpenJDK 17.0.2
    ├── gradle-8.7/                          Gradle 发行版
    ├── sdk/                                 Android SDK（platform-tools / platforms/android-34 / build-tools/34.0.0）
    ├── gradle-home/                         GRADLE_USER_HOME（依赖缓存）
    └── downloads/                           安装时下载的 zip 原件
```

> 工作区根目录里的 `_setup2.py`、`_sdk2.py`、`_find_urls.py`、`_mkpkg.py`、`_mkicons.py` 是本次搭建工具链时用过的一次性脚本（下载 JDK/Gradle/SDK、生成 `package.xml`、用 Pillow 生成启动图标），与 App 本身无关，可以删除。


> `_tools/` 是本机为了从零构建而下载的工具链（JDK 17.0.2、Gradle 8.7、Android SDK 34 + build-tools 34.0.0 + platform-tools）。它不是工程的一部分，可整目录删除；换台机器按第五节重新装一遍即可。

---

## 三、逐文件说明

### 3.1 构建脚本

| 文件 | 作用 / 关键点 |
| --- | --- |
| `settings.gradle.kts` | `pluginManagement` 与 `dependencyResolutionManagement` 都指向国内镜像（阿里云 google/central/gradle-plugin + 腾讯 maven-public），因为 `repo.maven.apache.org` 在本机 DNS 被污染；`FAIL_ON_PROJECT_REPOS`；`include(":app")` |
| `build.gradle.kts`（根） | 三个插件 `alias(...) apply false`：android application、kotlin android、kotlin compose（Kotlin 2.0 起 Compose 编译器独立成插件） |
| `gradle.properties` | `-Xmx2048m`、`parallel`、`caching`、`useAndroidX`、`nonTransitiveRClass`；另加 `org.gradle.vfs.watch=false`（沙箱环境文件监听会报 error 5）与 `android.overridePathCheck=true`（工程路径含中文） |
| `gradle/libs.versions.toml` | 版本目录：agp 8.5.2、kotlin 2.0.21、composeBom 2024.09.03、media3 **1.4.1**（1.5+ 要求 compileSdk 35，不能升） |
| `app/build.gradle.kts` | `versionCode 1` / `versionName "1.0.0"`；`signingConfigs.release` 从 `keystore.properties` 读四要素；release 开 `isMinifyEnabled + isShrinkResources` + `proguard-android-optimize.txt`；**没有 keystore.properties 时自动回退 debug 签名**，保证任何人 clone 都能 `assembleRelease`；`compileOptions`/`jvmTarget` 都 17 |
| `app/proguard-rules.pro` | keep `androidx.media3.exoplayer.**`、`common.**`、`extractor.**`、`decoder.**`、`datasource.**`，keep `Renderer` 实现类，`keepattributes Annotation/Signature/InnerClasses`，`-dontwarn androidx.media3.**`（可选依赖缺失告警） |
| `keystore.properties` | `storeFile=keystore/release.keystore`、`storePassword`/`keyPassword=videoplayer123`、`keyAlias=videoplayer`。**正式发布务必换成自己的密码并排除出版本库** |
| `keystore/release.keystore` | 测试用自签名证书：RSA 2048、SHA256withRSA、有效期 10000 天、`CN=Video Player Demo` |

### 3.2 清单与资源

| 文件 | 作用 / 关键点 |
| --- | --- |
| `AndroidManifest.xml` | 只声明 `MODIFY_AUDIO_SETTINGS`（普通权限，无弹窗）；用 `tools:node="remove"` 显式剔除 media3 库清单合并进来的 `ACCESS_NETWORK_STATE`；**没有 INTERNET、没有 READ_MEDIA_VIDEO、没有 FOREGROUND_SERVICE**；MainActivity 配 `configChanges="orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden"`，让旋转不重建 Activity |
| `res/values/themes.xml` | `android:Theme.Material.NoActionBar`，`windowBackground #FF0D0D0F`，状态栏/导航栏透明（配合 Compose 边到边） |
| `res/values/strings.xml` | `app_name = 本地视频播放器` |
| `res/layout/player_view.xml` | `androidx.media3.ui.PlayerView`，`surface_type="texture_view"`（**必须 TextureView**：SurfaceView 在 Compose 里会盖住控件层）、`use_controller="false"`（控件全由 Compose 画）、`show_buffering="never"`、`resize_mode="fit"` |
| `res/drawable/ic_pause.xml` 等 | material-icons-core **只含常用图标，没有 Pause / VolumeUp / VolumeDown / VolumeOff**，所以这四个用标准 Material path 自绘矢量图（24dp，fillColor 白，靠 `Icon(tint=)` 着色） |
| `res/drawable/ic_fullscreen(_exit).xml`、`ic_speed.xml` | Fullscreen / Speed 同样不在 core 集合里，自绘 |
| `res/mipmap-*`、`mipmap-anydpi-v26` | 各密度 PNG + adaptive icon（前景 `ic_launcher_foreground.xml`，背景色 `#FF1B1B1F`） |

### 3.3 Kotlin 源码

| 文件 | 作用 / 关键点 |
| --- | --- |
| `MainActivity.kt` | `enableEdgeToEdge()` + `setContent { VideoPlayerTheme { AppRoot(viewModel) } }`；`by viewModels<PlayerViewModel>()`；`onStart→onHostStart()`、`onStop→onHostStop()`、`onDestroy` 里**只有非配置变更**才 `releasePlayer()` |
| `ui/AppRoot.kt` | 不引 navigation-compose：`var showPlayer by rememberSaveable`，`HomeScreen` ↔ `PlayerScreen`；`LaunchedEffect(showPlayer, state.hasVideo)` 在没有媒体时自动回首页 |
| `ui/home/HomeScreen.kt` | `rememberLauncherForActivityResult(OpenDocument())`；选中后 `takePersistableUriPermission` 再 `viewModel.openVideo(uri)`；`EmptyState` 引导页、`RecentVideoRow`（文件名/大小/时长/相对时间 + 删除）、清空全部；`LazyColumn` 限宽 `760.dp` 适配平板 |
| `ui/player/PlayerScreen.kt` | 组装 `VideoSurface` + `GestureLayer` + 顶/底栏；`DisposableEffect` 进出全屏时隐藏/恢复系统栏；`DisposableEffect(isPlaying)` 加/清 `FLAG_KEEP_SCREEN_ON`；3 秒自动隐藏；`BackHandler` 先暂停再返回；`Context.findActivity()` 扩展用于拿 Activity |
| `ui/player/PlayerControls.kt` | `PlayerTopBar`（返回 + 标题，标题 `maxLines=1` 省略）；`PlayerBottomBar`（进度条在上、按钮在下）；`ProgressRow`（Slider + Canvas 缓冲线 + 左右时间）；`VolumeButton`（三档图标）；`SpeedButton`（窄屏只显示图标，宽屏带 “1.5x” 文字 + DropdownMenu）；`CenterPlayButton`；`PlayerErrorDialog`（中文友好提示 + 技术详情 + 重试） |
| `ui/player/PlayerGestures.kt` | `GestureLayer`：水平拖动累计 ≥48dp 且横向为主 → 松手 `onSeekBy(±10s)`；垂直每 26dp 一格 → `onVolumeStep`；拖动中显示气泡提示（“松开快进 10 秒” / “音量 45%”），松手 800ms 后消失 |
| `ui/player/VideoSurface.kt` | `AndroidView` 里 inflate `player_view.xml`，`player` 属性在 factory/update 两处都赋值，`onRelease` 里置 null 防泄漏 |
| `player/PlayerViewModel.kt` | 核心。`val player: ExoPlayer` 单实例；`PlayerUiState`（videoName/uri/isPlaying/isBuffering/isReady/ended/durationMs/positionMs/bufferedMs/speed/volumeLevel/volumePercent/errorMessage/errorDetail）；`recentVideos: StateFlow`；`VolumeLevel{MUTE,LOW,HIGH}`；方法：`openVideo/retry/dismissError/togglePlayPause/play/pause/seekTo/seekBy/setSpeed/cycleVolume/adjustVolumeBy/setVolumePercent/onHostStart/onHostStop/releasePlayer/removeRecent/clearRecent` |
| `player/PlaybackErrorMapper.kt` | `toUserMessage(PlaybackException)` + `technicalDetail()`，把 `errorCode` 分组成：解码类、容器解析类、IO 类（找不到/无权限/网络）、音轨类、DRM 类（`in 6_000..6_999`）、超时/远端 |
| `data/RecentVideoStore.kt` | `SharedPreferences("recent_videos")` + `org.json`；`load()/upsert()/remove()/clear()`，`upsert` 按 uri 去重后按 `lastPlayedAt` 倒序 `take(5)` |
| `data/VideoMetaReader.kt` | `ContentResolver.query` 三列；`runCatching` 包裹；文件名缺失时回退 `uri.lastPathSegment`，大小缺失时用 `openAssetFileDescriptor().length` |
| `util/TimeFormat.kt` | `toPlaybackTime()`：<1h → `mm:ss`，≥1h → `h:mm:ss`；`toReadableSize()`：B/KB/MB/GB |
| `util/CrashGuard.kt` | 全局 `UncaughtExceptionHandler`：崩溃堆栈写入 `files/crash/`（最多保留 5 份），下次启动 Toast 提示；**不吞异常**，交回系统默认处理器 |
| `ui/theme/Theme.kt` | 固定 `darkColorScheme`（primary `#B9A4FF`、background `#0C0C0E`、surface `#121116`…），不做动态取色，保证任何机型都是深色 |

---

## 四、关键实现说明

### 4.1 播放器生命周期（旋转不中断）

```kotlin
// MainActivity
override fun onDestroy() {
    super.onDestroy()
    // 旋转属于配置变更，Activity 会重建但 ViewModel 还在，不能在这里 release
    if (!isChangingConfigurations) viewModel.releasePlayer()
}
```

- ExoPlayer 由 `PlayerViewModel` 持有，Activity 重建（旋转/分屏）时 ViewModel 存活 → 播放器实例不变 → 进度不丢、播放不断。
- `releasePlayer()` 幂等（内部 `released` 标志），`onCleared()` 里再兜底调一次。
- `onStop` 调 `onHostStop()`：记录 `resumeAfterStop`，暂停并 `abandonAudioFocus()`；`onStart` 的 `onHostStart()` 若原本在播则续播。
- 进度用 250ms 轮询（播放中）/ 600ms（暂停中）同步到 `StateFlow`，避免 `Player.Listener` 高频回调导致 Compose 重组过密。

### 4.2 音频焦点

```kotlin
val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
    .setAudioAttributes(
        // 这里要平台类 android.media.AudioAttributes，media3 有同名类，必须写全限定名
        android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
            .build()
    )
    .setWillPauseWhenDucked(true)
    .setOnAudioFocusChangeListener(listener)
    .build()
```

ExoPlayer 构建时 `setAudioAttributes(..., /* handleAudioFocus = */ false)` —— 焦点由我们自己管，这样“来电暂停、恢复后续播”的行为完全可控：`LOSS` → 暂停，`LOSS_TRANSIENT` → 记录 `resumeOnFocusGain` 后暂停，`GAIN` → 需要时恢复。同时 `setHandleAudioBecomingNoisy(true)` 处理拔耳机。

### 4.3 音量（必须走 AudioManager）

```kotlin
private fun applyVolume(level: Int) {
    val max = maxVolume()
    val clamped = level.coerceIn(0, max)
    runCatching {
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, clamped, 0)
    }
}

private fun levelFor(value: Int, max: Int): VolumeLevel = when {
    value <= 0 -> VolumeLevel.MUTE
    value * 2 >= max -> VolumeLevel.HIGH
    else -> VolumeLevel.LOW
}
```

- 三档语义：`MUTE`(0) → `LOW`(max/3) → `HIGH`(max)，点击循环。
- 百分比持久化到 `player_prefs/volume_percent`，启动时按 `max * percent / 100` 恢复（不同机型音量档位不同，存百分比才不会错）。
- `ContentObserver(Settings.System.CONTENT_URI)` 监听系统音量变化，用户按音量键时 UI 图标同步更新。
- `MODIFY_AUDIO_SETTINGS` 是 normal 权限，安装即授予，不会有弹窗。

### 4.4 进度条（拖动只改文字，松手才 seek）

```kotlin
var dragValue by remember { mutableFloatStateOf(Float.NaN) }
val displayPosition = if (dragValue.isNaN()) state.positionMs else (dragValue * duration).toLong()

Slider(
    value = if (dragValue.isNaN()) currentFraction else dragValue,
    onValueChange = { dragValue = it },                        // 只更新本地值 + 文字
    onValueChangeFinished = {
        if (!dragValue.isNaN()) onSeek((dragValue * duration).toLong())
        dragValue = Float.NaN
    }
)
```

缓冲进度用 Slider 下面的 `Canvas` 画一条 2dp 细线（`drawLine` 到 `size.width * bufferedFraction`），不拦截触摸。

### 4.5 全屏、常亮与自动隐藏

```kotlin
// 进出播放页时
DisposableEffect(Unit) {
    val activity = context.findActivity()
    val window = activity.window
    val controller = WindowCompat.getInsetsController(window, window.decorView)
    val wasDecorFits = /* 记录原值 */
    WindowCompat.setDecorFitsSystemWindows(window, false)
    controller.hide(WindowInsetsCompat.Type.systemBars())
    controller.systemBarsBehavior = BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    onDispose {
        controller.show(WindowInsetsCompat.Type.systemBars())
        WindowCompat.setDecorFitsSystemWindows(window, wasDecorFits)
        activity.requestedOrientation = SCREEN_ORIENTATION_UNSPECIFIED
    }
}

// 播放中常亮
DisposableEffect(state.isPlaying) {
    if (state.isPlaying) window.addFlags(FLAG_KEEP_SCREEN_ON)
    else window.clearFlags(FLAG_KEEP_SCREEN_ON)
    onDispose { window.clearFlags(FLAG_KEEP_SCREEN_ON) }
}

// 3 秒自动隐藏（播放中、控件可见、没在拖动）
LaunchedEffect(controlsVisible, state.isPlaying, interactionKey, isDragging) {
    if (controlsVisible && state.isPlaying && !isDragging) {
        delay(3000)
        controlsVisible = false
    }
}
```

全屏按钮切换 `requestedOrientation`：横屏 → `SENSOR_LANDSCAPE`，再点 → `PORTRAIT`。控件栏用 `Brush.verticalGradient` 半透明黑遮罩，保证任何画面上文字都可读。

### 4.6 错误码映射（节选）

| PlaybackException.errorCode | 提示文案 |
| --- | --- |
| `ERROR_CODE_DECODING_FAILED` / `_DECODER_INIT_FAILED` / `_DECODER_QUERY_FAILED` | 该视频的编码格式不被支持（设备缺少对应的硬解/软解器），可以换个播放器或转码后再试 |
| `ERROR_CODE_PARSING_CONTAINER_MALFORMED` / `_UNSUPPORTED` | 视频文件损坏或封装格式不被支持 |
| `ERROR_CODE_IO_FILE_NOT_FOUND` | 视频文件找不到了，可能已被移动或删除 |
| `ERROR_CODE_IO_NO_PERMISSION` | 没有读取该视频的权限，请重新从首页选择一次 |
| `ERROR_CODE_IO_UNSPECIFIED` / `_BAD_HTTP_STATUS` | 读取视频数据失败 |
| `ERROR_CODE_AUDIO_TRACK_INIT_FAILED` 等 | 音频轨初始化失败（可能是音轨编码不支持） |
| `6_000..6_999`（DRM） | 该视频受 DRM 保护，本应用不支持播放 |
| 其他 | `播放失败（错误码 xxx）` |

弹窗同时给出“重试 / 关闭”，技术细节（errorCode + cause）折叠在下面，方便排查。

---

## 五、构建

### 5.1 环境要求

- JDK 17
- Android SDK：`platforms;android-34`、`build-tools;34.0.0`、`platform-tools`
- 网络能访问 Maven 仓库（工程已配好阿里云/腾讯镜像）

### 5.2 本机（已配置好，可直接用）

工作区里的 `_tools/` 已经是自包含工具链，构建时设置这几个环境变量即可（PowerShell）：

```powershell
$root = "C:\Users\86139\Desktop\deepseek\视频播放器"
$env:JAVA_HOME        = "$root\_tools\jdk17"
$env:ANDROID_HOME     = "$root\_tools\sdk"
$env:ANDROID_SDK_ROOT = "$root\_tools\sdk"
$env:GRADLE_USER_HOME = "$root\_tools\gradle-home"   # 别写用户目录，避免污染
$env:ANDROID_USER_HOME= "$root\_tools\android-home"  # 注意：只设这个，别再加 ANDROID_PREFS_ROOT
```

### 5.3 Debug 构建

```powershell
cd "$root\AndroidVideoPlayer"
.\gradlew.bat :app:assembleDebug           # 或 _tools\gradle-8.7\bin\gradle.bat -p . :app:assembleDebug
```

产物：`AndroidVideoPlayer\app\build\outputs\apk\debug\app-debug.apk`

### 5.4 Release 构建（含签名 + 混淆 + 资源压缩）

1. 生成 keystore（本仓库已生成好一份测试证书，正式发布请重新生成）：

```powershell
& "$root\_tools\jdk17\bin\keytool.exe" -genkeypair -v `
  -keystore "$root\AndroidVideoPlayer\keystore\release.keystore" `
  -alias videoplayer -keyalg RSA -keysize 2048 -validity 10000 `
  -storepass 你的密码 -keypass 你的密码 `
  -dname "CN=你的名字, OU=部门, O=公司, L=Beijing, ST=Beijing, C=CN"
```

2. 在 `AndroidVideoPlayer/keystore.properties` 写入四要素：

```properties
storeFile=keystore/release.keystore
storePassword=你的密码
keyAlias=videoplayer
keyPassword=你的密码
```

3. 打包：

```powershell
cd "$root\AndroidVideoPlayer"
.\gradlew.bat :app:assembleRelease
```

产物路径：

```
AndroidVideoPlayer\app\build\outputs\apk\release\app-release.apk
```

（`isMinifyEnabled=true` + `isShrinkResources=true` 均已开启，本工程 release 产物 **2.6 MB**。）

### 5.5 安装到手机

```powershell
# 手机开 USB 调试，连上后：
& "$root\_tools\sdk\platform-tools\adb.exe" devices
& "$root\_tools\sdk\platform-tools\adb.exe" install -r "$root\AndroidVideoPlayer\app\build\outputs\apk\release\app-release.apk"
# 或直接安装已导出的副本：
& "$root\_tools\sdk\platform-tools\adb.exe" install -r "$root\VideoPlayer-1.0.0-release.apk"
```

也可以把 APK 拷进手机点安装（需在系统里允许“安装未知来源应用”）。
启动命令：`adb shell am start -n com.example.videoplayer/.MainActivity`

### 5.6 校验 APK

```powershell
$bt = "$root\_tools\sdk\build-tools\34.0.0"
& "$bt\apksigner.bat" verify --print-certs "$root\VideoPlayer-1.0.0-release.apk"
& "$bt\aapt2.exe" dump badging "$root\VideoPlayer-1.0.0-release.apk" | Select-String "package:|sdkVersion|uses-permission"
```

实测结果：

```
Verifies
Verified using v2 scheme (APK Signature Scheme v2): true
Signer #1 certificate DN: CN=Video Player Demo, OU=Demo, O=Demo, L=Beijing, ST=Beijing, C=CN
package: name='com.example.videoplayer' versionCode='1' versionName='1.0.0' compileSdkVersion='34'
sdkVersion:'24'  targetSdkVersion:'34'
uses-permission: name='android.permission.MODIFY_AUDIO_SETTINGS'
uses-permission: name='com.example.videoplayer.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'   ← androidx 自动生成，无需关注
SHA-256: 5B09C0C0BFB98ACA49B4E057FE37136D5427D526B2625062723C5217799E01D7
```

---

## 六、权限说明

| 权限 | 来源 | 是否必要 |
| --- | --- | --- |
| `MODIFY_AUDIO_SETTINGS` | 工程清单 | 需要。调系统媒体音量（normal 权限，安装即授予，无运行时弹窗） |
| ~~`READ_MEDIA_VIDEO` / `READ_EXTERNAL_STORAGE`~~ | — | **不需要**：走 SAF，权限跟随 URI 授权 |
| ~~`INTERNET`~~ | — | **不需要**：纯本地播放 |
| ~~`ACCESS_NETWORK_STATE`~~ | media3 库清单合并 | **已用 `tools:node="remove"` 剔除**；将来加网络流播放再删掉那两行 |
| ~~`FOREGROUND_SERVICE` / `WAKE_LOCK`~~ | — | **不需要**：没有后台播放 |

---

## 七、已知限制

1. **编码支持取决于系统解码器**：ExoPlayer 用平台 `MediaCodec`。H.264/HEVC/H.265 一般没问题；AV1、部分 10bit HEVC、ProRes、DNxHD 等在多数手机上无硬解，会走 `ERROR_CODE_DECODING_FAILED`（已映射为中文提示）。真要全格式支持需引入 `media3-decoder-ffmpeg` 扩展（会显著增大包体，且超出“不引第三方库”的约束）。
2. **不支持 DRM 加密视频**：`Common.DataScheme` / Widevine 相关需要 `media3-exoplayer-dash` 等模块与授权服务，本工程未包含，`DRM` 类错误码统一提示不支持。
3. **不支持网络流**：只处理 `content://`；且已剔除 `ACCESS_NETWORK_STATE`。
4. **不支持字幕外挂、音轨/字幕轨切换**：`PlayerView` 的 `use_controller=false`，没有内建轨道选择 UI。
5. **倍速下音调不变**：ExoPlayer 默认用 `SonicAudioProcessor` 保持音调，但极高倍速（2x）在低端机上可能有轻微卡顿。
6. **画中画、后台播放、锁屏控制**：均未实现（见第八节）。
7. **进度不记忆**：同一视频重新打开从头播放（需求只要求“一键续播”，未要求记住播放位置；要加的话在 `RecentVideo` 里存 `positionMs` 即可）。
8. **texture_view 的代价**：为让 Compose 控件盖在画面上用了 TextureView，比 SurfaceView 多一次纹理拷贝，4K 高码率视频在极低端机上可能略掉帧；若把控件层改成 Dialog/PopupWindow 可换回 SurfaceView。
9. **自签名证书**：`keystore/release.keystore` 是测试证书，密码写在 `keystore.properties` 里，**仅用于本地安装验证，不能上应用商店**；上架要换成自己的证书并妥善保管（丢了就无法覆盖升级）。

---

## 八、三个可扩展方向

### 1）画中画（Picture-in-Picture）

- `AndroidManifest.xml` 的 Activity 加 `android:supportsPictureInPicture="true"`、`android:configChanges` 补 `smallestScreenSize|screenSize|orientation|layoutDirection`。
- `MainActivity` 里 `enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())`，API 26+ 才有，用 `Build.VERSION.SDK_INT >= 26` 包一层（minSdk 24）。
- 用户按 Home 时（`onUserLeaveHint()`）自动进入 PIP；PIP 模式下隐藏除画面外的所有 Compose 控件（`isInPictureInPictureMode` 判断），`PlayerView` 的 `useController` 保持 false，交给系统 PIP 窗口的播放/暂停。
- 注意与现有沉浸式逻辑的冲突：进 PIP 前要 `show(systemBars())`，否则 PIP 窗口里没有系统 UI。

### 2）后台播放 + MediaSession

- 加依赖 `androidx.media3:media3-session`，把 `ExoPlayer` 包进 `MediaSession`，用 `MediaSessionService` 承载（清单加 `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PLAYBACK`，Android 14 还要声明 `foregroundServiceType="mediaPlayback"`）。
- `PlayerViewModel` 改为绑定 Service（或用 `MediaController` 连接），播放状态从 `MediaController` 的 `Player.Listener` 拿。
- 通知栏/锁屏/蓝牙耳机控制由 `MediaSession` 自动生成 `MediaStyle` 通知；音频焦点、`onTaskRemoved` 停止逻辑也要迁到 Service。
- 好处：切后台不断播、支持车机/手表/耳机按键。

### 3）字幕外挂

- 加 `media3-extractor`（已在传递依赖里）之后，用 `MediaItem.Builder().setSubtitleConfigurations(listOf(SubtitleConfiguration.Builder(subtitleUri).setMimeType(MimeTypes.TEXT_VTT).setLanguage("zh").setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()))`。
- 字幕文件也用 SAF 选（`OpenDocument`，MIME `application/x-subrip` / `text/vtt`），同样 `takePersistableUriPermission`。
- 用 `Player.Listener.onCues(CueGroup)` 把 `Cue.text` 渲染成 Compose 文本层（bottom padding 抬到控制栏之上），或用 `PlayerView.setSubtitleView()` + `SubtitleView.setStyle(CaptionStyleCompat)`。
- 支持 `.srt` 需要转成 WebVTT 或让 ExoPlayer 用 `SubripDecoder`（`SubtitleConfiguration` 的 MIME 传 `MimeTypes.APPLICATION_SUBRIP` 即可，media3 1.4 已内置 SRT 解析）。

---

## 九、构建踩坑记录（换机器时可能遇到）

1. **`repo.maven.apache.org` 解析失败**（本机 DNS 污染）→ `settings.gradle.kts` 已全部改国内镜像。
2. **工程路径含中文** → AGP 报 `Your project path contains non-ASCII characters`，已加 `android.overridePathCheck=true`；另有 aapt2 打印 `Failed to stat ... android.jar` 的**无害警告**（路径编码问题，不影响产物）。
3. **Gradle 文件监听报 `Couldn't open current thread, error = 5`** → 加 `org.gradle.vfs.watch=false`。
4. **`ANDROID_PREFS_ROOT` 与 `ANDROID_USER_HOME` 同时存在** → AGP 直接报错，只留 `ANDROID_USER_HOME`。
5. **`Gradle wrapper` 生成时 `--validate-url` 会联网** → 加 `--no-validate-url`；`distributionUrl` 已改成腾讯镜像。
6. **`sdkmanager` 拉不到远程清单** → 改为手动下载 `platform-34-ext7_r03.zip`、`build-tools_r34-windows.zip`、`platform-tools-latest-windows.zip` 并解压（解压后还需补一个 `package.xml`，见 `_tools/sdk/**/package.xml`）。
7. **material-icons-core 图标不全** → Pause/Volume*/Fullscreen/Speed 全部自绘 vector，不要再 import `Icons.Filled.Pause` 之类不存在的项。
8. **`AudioAttributes` 同名冲突** → `requestAudioFocus()` 里用 `android.media.AudioAttributes` 全限定名。
