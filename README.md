# 本地视频播放器（Local Video Player）

单 Activity 的本地视频播放器：**导入进媒体库 → 播放 → Compose Material 3 控制界面**。
从相册或 SAF 选择的视频会复制到应用私有目录，之后播放只认本地 File，不再依赖 `content://` 授权；
导入前做两级可播放性校验（容器/编码探测），不支持的格式在列表里直接标注。
深色主题、手机/平板横竖屏自适应；相册权限按 Android 12 / 13 / 14 三档适配，SAF 作为「不申请任何存储权限」的降级入口。

| 项目 | 值 |
| --- | --- |
| 包名 / applicationId | `com.example.videoplayer` |
| versionCode / versionName | `11` / `1.1.0` |
| minSdk / targetSdk / compileSdk | `24` / `34` / `34` |
| 语言 / UI | Kotlin + Jetpack Compose + Material 3 |
| 架构 | MVVM（`AndroidViewModel` + `StateFlow`）+ Room + WorkManager |
| 播放内核 | `androidx.media3:media3-exoplayer` + `media3-ui` 1.4.1 |
| 数据层 | Room 2.6.1（KSP）、WorkManager 2.9.1、Coil 2.7.0（coil-compose + coil-video） |
| Gradle | AGP 8.5.2 / Gradle 8.7 / Kotlin 2.0.21 / JVM 17 |
| release APK | `VideoPlayer-1.1.0-release.apk`，约 7.3 MB（含 3.6 MB 开场动画），APK Signature Scheme v2 |

---

## 一、功能清单

| 需求 | 实现位置 | 说明 |
| --- | --- | --- |
| SAF 选视频（`video/*`，降级入口） | `ui/AppRoot.kt` | `ActivityResultContracts.OpenDocument`，MIME 只传 `video/*`，拿到后直接入库 |
| 持久化 URI 授权 | `ui/AppRoot.kt` | `takePersistableUriPermission(...)`；入库复制完成后即使授权失效也能播放 |
| 读取文件名/大小/时长 | `data/VideoMetaReader.kt` | `OpenableColumns.DISPLAY_NAME/SIZE` + `MediaStore.MediaColumns.DURATION`，size 为 0 时用 `openAssetFileDescriptor` 兜底 |
| **媒体入库（v1.0.2）** | `data/MediaLibrary.kt` + `work/ImportWorker.kt` | 复制到 `getExternalFilesDir(MOVIES)/library`，同名加 `(1)` 后缀，写前查空间留 32MB |
| **媒体库列表（v1.0.2）** | `ui/library/LibraryScreen.kt` | 网格封面 + 时长/分辨率角标 + 大小/编码，顶部三格占用统计 |
| **视频信息面板（v1.0.2）** | `ui/library/VideoInfoSheet.kt` | 分辨率/帧率/码率/编码/容器/时长/大小/导入时间/路径，可一键删除 |
| **后台导入进度（v1.0.2）** | `ui/library/LibraryViewModel.kt` | `workManager.getWorkInfoByIdFlow` 驱动进度卡片，支持取消 |
| **重复导入去重（v1.0.2）** | `work/ImportWorker.kt` | 文件名+大小+mtime 一重，前 1MB MD5 二重 |
| **播放进度记忆（v1.0.3）** | `data/db/VideoDao.updatePosition` + `PlayerViewModel.persistPosition` | 播放中每 5 秒、切后台、退出各落库一次；离结尾不足 3 秒归零 |
| **连播（v1.0.5）** | `ui/library/LibraryScreen` 传队列 → `PlayerViewModel.playlist` | 从媒体库点开时携带当前筛选/排序后的队列，播完自动下一条；队尾或下一条文件丢失则停在当前页 |
| **媒体库搜索 + 排序（v1.0.5）** | `ui/library/LibraryViewModel.kt`（`setQuery` / `setSort`） | 标题/路径/编码关键词 250ms 防抖过滤；最近导入/名称/大小/时长四维排序，选择存 SharedPreferences |
| **后台播放（v1.0.5）** | `player/PlaybackService.kt` + `MediaController` | 播放器归 Service 所有，UI 通过 `MediaController` 连接；通知栏/锁屏/耳机键与界面操作同一个播放器 |
| **外挂字幕（v1.0.6）** | `PlayerViewModel.attachSubtitle` + `VideoSurface` 字幕样式 | 支持 `.srt` / `.vtt` / `.ass` / `.ssa`，走 ExoPlayer 自带解析器；从当前位置重载不跳回开头，关闭只禁用文本轨不重载 |
| **启动动画（v1.0.7）** | `SplashActivity.kt` + `res/raw/splash_intro.mp4` | 冷启动全屏静音播放 15 秒 720p 开场动画；播完 / 点击任意处 / 「跳过」按钮 / 播放出错 / 20 秒超时 五种方式进入主界面 |
| **画中画（v1.1.0）** | `PlayerTopBar` 的 PiP 按钮 + `MainActivity.onPictureInPictureModeChanged` | Android 8.0+，`enterPictureInPictureMode` 固定 9:16；PiP 里通过 `LocalPipMode` 隐藏全部控件 |
| **左半屏亮度手势（v1.1.0）** | `ui/player/PlayerGestures.kt` | 上下滑动时按落点分左右：左调 `window.attributes.screenBrightness`，右调媒体音量；不动系统设置 |
| **长按 2 倍速（v1.1.0）** | `PlayerViewModel.toggleSpeedBoost()` | 长按画面在 2x / 1x 间切换，**不写偏好**，退出播放页仍用设置里的倍速 |
| 播放/暂停、点画面切换 | `PlayerViewModel.togglePlayPause()` / `PlayerScreen` | 单击＝控件可见时切换、控件隐藏时先唤出；双击＝直接切换 |
| 暂停时中央大播放按钮 | `PlayerControls.CenterPlayButton` | 76dp `FilledIconButton`，仅在暂停且控件可见时显示 |
| 进度条（拖动只改文字、松手才 seek） | `PlayerControls.ProgressRow` | 拖动期间只更新本地 `dragValue`，`onValueChangeFinished` 才 `onSeek` |
| 缓冲进度 | `PlayerControls.ProgressRow` | Slider 下方 `Canvas` 画 2dp 缓冲线，宽度 = `bufferedFraction` |
| 时间格式 mm:ss / hh:mm:ss | `util/TimeFormat.kt` | `Long.toPlaybackTime()` |
| 崩溃兜底 | `util/CrashGuard.kt` | 堆栈落 `files/crash/`，下次启动提示；详见 `防闪退报告.md` |
| **导出诊断包（v1.0.4）** | `util/DiagnosticsExporter.kt` + 媒体库右上角菜单 | 一键生成 zip（设备信息 + 媒体库清单 + 崩溃日志）并调起系统分享 |
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
| **相册浏览（v1.0.1）** | `ui/gallery/GalleryScreen.kt` | MediaStore 查全部视频，3 列（横屏 5 列）网格 + Coil 视频首帧缩略图 + 下拉刷新 |
| **可播放性预检（v1.0.1）** | `data/PlayabilityChecker.kt` | 一级静态校验 + 二级 MediaCodec 解码器探测，不通过弹窗说明并可「仍要尝试播放」 |
| **权限三档适配（v1.0.1）** | `ui/gallery/GalleryViewModel.kt` | 13+ `READ_MEDIA_VIDEO` / 14+ 部分授权 `READ_MEDIA_VISUAL_USER_SELECTED` / ≤12 `READ_EXTERNAL_STORAGE` |
| **预检结果缓存（v1.0.1）** | `data/PlayabilityChecker.kt` | key = Uri + size + mtime，`ConcurrentHashMap` 内存缓存，避免重复解封装 |
| **静音降级（v1.0.1）** | `PlayerViewModel` + `PlayabilityChecker` | 视频轨可解、音频轨无解码器时静音播放并 Toast 提示 |

---

## 二、目录结构

```
视频播放器/                                  ← 工作区根目录
├── README.md                               ← 本文档
├── VideoPlayer-1.1.0-release.apk           ← 已构建好的签名安装包（约 7.3 MB，含开场动画）
├── CHANGELOG.md                            ← 版本变更记录（v1.0.0 → v1.1.0）
├── .github/workflows/android.yml           ← CI：单测 + assembleRelease + 产物上传
├── scripts/test-local.ps1                  ← 本地跑单测的绕行脚本（中文路径 → %TEMP%）
├── AndroidVideoPlayer/                     ← Gradle 工程根
│   ├── settings.gradle.kts                 仓库配置（阿里云/腾讯镜像）
│   ├── build.gradle.kts                    顶层插件声明
│   ├── gradle.properties                   JVM/构建开关
│   ├── gradlew / gradlew.bat               Gradle wrapper（8.7，走腾讯镜像）
│   ├── keystore.properties                 ★ 签名配置（口令，勿外传）
│   ├── keystore/
│   │   └── release.keystore                ★ 自签名证书（RSA 2048 / 10000 天）
│   ├── mapping/                            R8 混淆映射归档（assembleRelease 后生成，不进版本库）
│   ├── gradle/
│   │   ├── libs.versions.toml              版本目录
│   │   └── wrapper/
│   │       ├── gradle-wrapper.jar
│   │       └── gradle-wrapper.properties
│   └── app/
│       ├── build.gradle.kts                应用模块（签名、混淆、依赖、KSP）
│       ├── proguard-rules.pro              Media3 混淆规则
│       └── src/main/
│           ├── AndroidManifest.xml         清单（相册权限三档 + MODIFY_AUDIO_SETTINGS）
│           ├── java/com/example/videoplayer/
│           │   ├── MainActivity.kt         宿主 Activity、生命周期转发、旧数据迁移、画中画状态
│           │   ├── SplashActivity.kt       启动画面：全屏静音播开场动画，可跳过
│           │   ├── data/
│           │   │   ├── MediaLibrary.kt     ★ 私有目录复制 / 空间检查 / 重名 / 校验和
│           │   │   ├── VideoProbe.kt       MediaExtractor + Retriever 探测分辨率与编码
│           │   │   ├── VideoMetaReader.kt  ContentResolver 元数据读取
│           │   │   ├── LegacyMigrator.kt   v1.0.1「最近播放」→ 媒体库迁移
│           │   │   ├── RecentVideo.kt      旧「最近播放」模型（仅迁移用）
│           │   │   ├── RecentVideoStore.kt 旧「最近播放」持久化（仅迁移用）
│           │   │   ├── GalleryVideo.kt     相册视频模型
│           │   │   ├── GalleryRepository.kt MediaStore 查询（IO 线程）
│           │   │   ├── Playability.kt      预检结果 sealed 类型
│           │   │   ├── PlayabilityChecker.kt 两级可播放性校验 + 缓存
│           │   │   └── db/
│           │   │       ├── VideoEntity.kt  Room 实体（含 resolutionLabel 计算属性）
│           │   │       ├── VideoDao.kt     DAO（Flow 查询 + 去重查找）
│           │   │       └── AppDatabase.kt  Room 数据库单例
│           │   ├── work/
│           │   │   └── ImportWorker.kt     ★ WorkManager 入库任务（复制/去重/探测/写库）
│           │   ├── player/
│           │   │   ├── AudioFocusController.kt v1.0.5 音频焦点集中管理
│           │   │   ├── PlaybackErrorMapper.kt  PlaybackException → 中文提示
│           │   │   ├── PlaybackService.kt      v1.0.5 MediaSessionService，持有 ExoPlayer + MediaSession
│           │   │   ├── PlayerViewModel.kt      ★ 播放器状态机（MediaController 连接服务）
│           │   │   └── PositionPersister.kt    v1.0.5 播放位置独立落库
│           │   ├── ui/
│           │   │   ├── AppRoot.kt          首页（媒体库 / 相册 两个 Tab）+ 播放页切换
│           │   │   ├── LocalPipMode.kt      v1.1.0 画中画状态（CompositionLocal）
│           │   │   ├── gallery/
│           │   │   │   ├── GalleryScreen.kt    相册页（已退化为导入来源）
│           │   │   │   └── GalleryViewModel.kt 相册权限矩阵 + MediaStore 查询
│           │   │   ├── library/
│           │   │   │   ├── LibraryScreen.kt    ★ 媒体库网格 + 占用统计 + 导入进度
│           │   │   │   ├── LibraryViewModel.kt  ★ 媒体库状态（WorkManager 进度）
│           │   │   │   └── VideoInfoSheet.kt   视频信息底部弹层
│           │   │   ├── player/
│           │   │   │   ├── PlayerScreen.kt    ★ 播放页（全屏/自动隐藏/手势编排）
│           │   │   │   ├── PlayerControls.kt  顶栏、底栏、进度条、倍速、错误弹窗
│           │   │   │   ├── PlayerGestures.kt  点击/双击/长按/滑动识别层（左亮度右音量）
│           │   │   │   └── VideoSurface.kt    PlayerView 的 Compose 封装
│           │   │   └── theme/Theme.kt      深色配色 + 字体
│           │   └── util/
│           │       ├── AppImageLoader.kt   ★ 全局 ImageLoader（注册 VideoFrameDecoder）
│           │       ├── ContextExt.kt       findActivity 扩展
│           │       ├── Formatters.kt       体积/码率/帧率/日期格式化
│           │       ├── TimeFormat.kt       播放进度格式化
│           │       ├── CrashGuard.kt       未捕获异常兜底（写崩溃日志）
│           │       └── DiagnosticsExporter.kt  v1.0.4 导出诊断包（设备信息+媒体库+崩溃日志）
│           └── res/
│               ├── layout/
│               │   ├── player_view.xml     PlayerView（texture_view，关内置控制条）
│               │   └── activity_splash.xml 启动画面（media3 PlayerView + 跳过按钮）
│               ├── raw/splash_intro.mp4    ★ 开场动画（15s / 720p / 静音，3.6 MB）
│               ├── drawable/               ic_pause / ic_volume_{up,down,off} /
│               │                           ic_fullscreen{,_exit} / ic_speed /
│               │                           ic_subtitles / ic_pip / bg_splash_skip /
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
| `app/build.gradle.kts` | `versionCode 7` / `versionName "1.0.6"`；`signingConfigs.release` 从 `keystore.properties` 读四要素；release 开 `isMinifyEnabled + isShrinkResources` + `proguard-android-optimize.txt`；**没有 keystore.properties 时自动回退 debug 签名**，保证任何人 clone 都能 `assembleRelease`；`compileOptions`/`jvmTarget` 都 17 |
| `app/proguard-rules.pro` | keep `androidx.media3.exoplayer.**`、`common.**`、`extractor.**`、`decoder.**`、`datasource.**`，keep `Renderer` 实现类，`keepattributes Annotation/Signature/InnerClasses`，`-dontwarn androidx.media3.**`（可选依赖缺失告警） |
| `keystore.properties` | `storeFile=keystore/release.keystore`、`storePassword`/`keyPassword=videoplayer123`、`keyAlias=videoplayer`。**正式发布务必换成自己的密码并排除出版本库** |
| `keystore/release.keystore` | 测试用自签名证书：RSA 2048、SHA256withRSA、有效期 10000 天、`CN=Video Player Demo` |

### 3.2 清单与资源

| 文件 | 作用 / 关键点 |
| --- | --- |
| `AndroidManifest.xml` | v1.0.3 起声明 `MODIFY_AUDIO_SETTINGS`、相册三档权限、`FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PLAYBACK` + `POST_NOTIFICATIONS`；用 `tools:node="remove"` 剔除 media3 库合并进来的 `ACCESS_NETWORK_STATE`；**没有 INTERNET**；`PlaybackService` 声明 `foregroundServiceType="mediaPlayback"`、`exported="true"` 并挂 `androidx.media3.session.MediaSessionService` 的 intent-filter；MainActivity 配 `configChanges="orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden"`，让旋转不重建 Activity |
| `res/values/themes.xml` | `android:Theme.Material.NoActionBar`，`windowBackground #FF0D0D0F`，状态栏/导航栏透明（配合 Compose 边到边） |
| `res/values/strings.xml` | `app_name = 本地视频播放器` |
| `res/layout/player_view.xml` | `androidx.media3.ui.PlayerView`，`surface_type="texture_view"`（**必须 TextureView**：SurfaceView 在 Compose 里会盖住控件层）、`use_controller="false"`（控件全由 Compose 画）、`show_buffering="never"`、`resize_mode="fit"` |
| `res/drawable/ic_pause.xml` 等 | material-icons-core **只含常用图标，没有 Pause / VolumeUp / VolumeDown / VolumeOff**，所以这四个用标准 Material path 自绘矢量图（24dp，fillColor 白，靠 `Icon(tint=)` 着色） |
| `res/drawable/ic_fullscreen(_exit).xml`、`ic_speed.xml` | Fullscreen / Speed 同样不在 core 集合里，自绘 |
| `res/mipmap-*`、`mipmap-anydpi-v26` | 各密度 PNG + adaptive icon（前景 `ic_launcher_foreground.xml`，背景色 `#FF1B1B1F`） |

### 3.3 Kotlin 源码

| 文件 | 作用 / 关键点 |
| --- | --- |
| `MainActivity.kt` | `enableEdgeToEdge()` + `setContent { VideoPlayerTheme { AppRoot(playerViewModel) } }`；`by viewModels<PlayerViewModel>()`；启动时后台跑 `LegacyMigrator.migrateIfNeeded()`；`onStart→onHostStart()`、`onStop→onHostStop()`、`onDestroy` 里**只有非配置变更**才 `releasePlayer()` |
| `SplashActivity.kt` | 启动画面：把 `R.raw.splash_intro` 复制到 `cacheDir/splash_intro_<versionCode>.mp4`，用 `ExoPlayer` + `PlayerView(texture_view)` 静音播放；`goMain()` 由「播完 / 点击根布局 / 跳过按钮 / `Player.Listener.onPlayerError` / 20 秒超时」五种情况触发，`hasLeft` 保证只跳一次 |
| `ui/AppRoot.kt` | 不引 navigation-compose：`TabRow` 切「媒体库 / 相册」，`var showPlayer by rememberSaveable` 切播放页；SAF 选片走 `OpenDocument()` + `takePersistableUriPermission` 后直接 `libraryViewModel.importUris(...)`；相册 `onPlay` 也是入库，完成后 Toast 提示 |
| `ui/library/LibraryScreen.kt` | 头部标题 + 刷新/导入菜单（DropdownMenu：从相册 / 从文件）；`UsageCard` 三格统计；`ImportBanner`（Running 显示 `LinearProgressIndicator` + 取消，Finished 显示成功/失败/跳过）；空态 `EmptyLibrary`；`LazyVerticalGrid` 3 列（≥600dp 5 列）；`VideoTile` 用 `AppImageLoader` 解首帧 + 时长/分辨率角标 + Info 按钮 + `SOURCE_LOST` 黑罩 |
| `ui/library/LibraryViewModel.kt` | `videos: StateFlow<List<VideoEntity>>`（`stateIn(WhileSubscribed(5s))`）；`importUris(List<Uri>)` 建 `OneTimeWorkRequest` 并订阅 `getWorkInfoByIdFlow` 映射成 `ImportState`；`deleteVideo`（DB + 文件）、`markPlayed`、`reconcileWithDisk()`（数据库与磁盘对账） |
| `ui/library/VideoInfoSheet.kt` | `ModalBottomSheet` 展示分辨率/帧率/码率/视频编码/音频编码/容器/时长/大小/导入时间/最后播放/播放次数/本地路径，底部「播放」「删除」，并说明「本地视频是单码率文件」 |
| `work/ImportWorker.kt` | `CoroutineWorker`：读 `KEY_URIS` → 一重去重（文件名 + 大小 + mtime）→ `copyToLibrary` → MD5 二重去重 → `VideoProbe.probeFile` → `dao.insert`；逐条 `setProgress`，单条失败不阻断整批 |
| `data/MediaLibrary.kt` | `dir()`（外部私有目录降级内部）、`copyToLibrary()`（流式拷贝 + 空间预检 + 失败删半截）、`uniqueFile()`（重名加 `(1)`）、`checksum()`（前 1MB MD5）、`lastModified()`（Documents / MediaStore 两路兜底） |
| `data/VideoProbe.kt` | `MediaExtractor` 读轨道 MIME + 码率，`MediaMetadataRetriever` 读宽高/旋转/时长/帧率；`describeCodec()` 映射 H.264 / H.265 / AV1 / VP9 / AAC / Opus… |
| `data/LegacyMigrator.kt` | 一次性迁移 v1.0.1「最近播放」：可读的复制入库，不可读的插入 `SOURCE_LOST` 占位；用 `media_library` prefs 标记保证幂等 |
| `data/db/VideoEntity.kt` · `VideoDao.kt` · `AppDatabase.kt` | Room `videos` 表（fileName/localPath/size/durationMs/width/height/frameRate/bitRate/mimeType/videoCodec/audioCodec/sourceUri/importedAt/lastPlayedAt/playCount/checksum/lastModified/status）；DAO 提供 `observeAll` / `findByFingerprint` / `findByChecksum` / `insert` / `markPlayed` / `updateStatus` / `deleteById` |
| `ui/gallery/GalleryScreen.kt` + `GalleryViewModel.kt` | 相册页：权限三档 + 部分授权提示 + 永久拒绝时引导去设置；MediaStore 查询在 IO 线程；预检不通过灰显「不支持」；点击行为已改为「加入导入队列」 |
| `data/PlayabilityChecker.kt` · `Playability.kt` · `GalleryRepository.kt` · `GalleryVideo.kt` | 两级预检（静态元数据 + `MediaCodecList` 解码器探测）与结果缓存；MediaStore 投影与 `content://` Uri 生成 |
| `util/AppImageLoader.kt` | 全局单例 `ImageLoader`，注册 `VideoFrameDecoder`；媒体库与相册封面都走它（否则视频文件当图片解码必失败） |
| `ui/player/PlayerScreen.kt` | 组装 `VideoSurface` + `GestureLayer` + 顶/底栏；`DisposableEffect` 进出全屏隐藏/恢复系统栏；`DisposableEffect(isPlaying)` 加/清 `FLAG_KEEP_SCREEN_ON`；3 秒自动隐藏；`BackHandler` 先暂停再返回；预检 loading 有 200ms 阈值，错误/拦截用整页遮罩 + `PlayerErrorOverlay` |
| `ui/player/PlayerControls.kt` | `PlayerTopBar`、`PlayerBottomBar`（进度条在上、按钮在下）、`ProgressRow`（Slider + Canvas 缓冲线）、`VolumeButton`（三档图标）、`SpeedButton`（窄屏只留图标）、`CenterPlayButton`、`PlayerErrorDialog` |
| `ui/player/PlayerGestures.kt` | `GestureLayer`：水平拖动累计 ≥48dp 且横向为主 → 松手 `onSeekBy(±10s)`；垂直每 26dp 一格 → `onVolumeStep`；拖动中气泡提示，松手 800ms 消失 |
| `ui/player/VideoSurface.kt` | `AndroidView` 里 inflate `player_view.xml`，`player` 在 factory/update 两处赋值，`onRelease` 置 null 防泄漏 |
| `player/PlayerViewModel.kt` | 核心。v1.0.5 起不再持有 ExoPlayer，改为 `MediaController` 异步连接 `PlaybackService`（`val player: Player?`）；`PlayerUiState`；`VideoSource{SAF,GALLERY,RECENT,LIBRARY}`；`PreflightBlock`；`openLibraryVideo(video, queue)` 用本地 `File` 播放、跳过预检、带连播队列；方法：`openVideo/retry/playAnyway/dismissPreflightBlock/togglePlayPause/seekTo/seekBy/setSpeed/cycleVolume/onHostStart/onHostStop/releasePlayer` |
| `player/AudioFocusController.kt`（v1.0.5） | 音频焦点集中管理：`AudioFocusRequest` + `AudioManager.OnAudioFocusChangeListener`，GAIN / LOSS / LOSS_TRANSIENT / LOSS_TRANSIENT_CAN_DUCK 四分支，短暂丢失后按需续播 |
| `player/PositionPersister.kt`（v1.0.5） | 播放位置落库：`Dispatchers.IO` 专用 scope + `@Volatile boundId`，播放中每 5 秒 / 离开页面 / 播完归零；ViewModel 销毁不影响最后一次写入 |
| `player/PlaybackService.kt`（v1.0.5） | `MediaSessionService`，**自己创建 ExoPlayer** 并配 `MediaSession`（`setSessionActivity` 指向启动页）；`onGetSession` 返回会话；`onTaskRemoved` 暂停 + `stopSelf`；`onDestroy` 释放 session 与 player |
| `player/PlaybackErrorMapper.kt` | `toUserMessage(PlaybackException)` + `technicalDetail()`，把 `errorCode` 分组成：解码类、容器解析类、IO 类、音轨类、DRM 类、超时/远端 |
| `data/RecentVideoStore.kt` · `RecentVideo.kt` | v1.0.2 起仅用于迁移：`SharedPreferences("recent_videos")` + `org.json`，`load()/upsert()/remove()/clear()` |
| `data/VideoMetaReader.kt` | `ContentResolver.query` 三列；`runCatching` 包裹；文件名缺失回退 `uri.lastPathSegment`，大小缺失用 `openAssetFileDescriptor().length` |
| `util/TimeFormat.kt` · `Formatters.kt` | `toPlaybackTime()` / `toReadableSize()`；`bytes/bitRate/frameRate/dateTime/duration` |
| `util/CrashGuard.kt` | 全局 `UncaughtExceptionHandler`：堆栈写入 `files/crash/`（最多 5 份），下次启动 Toast 提示；**不吞异常** |
| `util/DiagnosticsExporter.kt`（v1.0.4） | 生成诊断包 zip（`device.txt` 设备/版本/ABI、`library.txt` 媒体库统计与前 200 条清单、`crash/` 最近 5 份崩溃日志），经 `FileProvider` 分享；**不含视频内容** |
| `ui/theme/Theme.kt` | 固定 `darkColorScheme`（primary `#B9A4FF`、background `#0C0C0E`…），不做动态取色，保证任何机型都是深色 |

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

（`isMinifyEnabled=true` + `isShrinkResources=true` 均已开启，v1.0.6 的 release 产物 **约 3.7 MB**；比 v1.0.0 多出来的主要是 Room、WorkManager、coil-video、media3-session 与字幕相关资源。）

### 5.5 安装到手机

```powershell
# 手机开 USB 调试，连上后：
& "$root\_tools\sdk\platform-tools\adb.exe" devices
& "$root\_tools\sdk\platform-tools\adb.exe" install -r "$root\AndroidVideoPlayer\app\build\outputs\apk\release\app-release.apk"
# 或直接安装已导出的副本：
& "$root\_tools\sdk\platform-tools\adb.exe" install -r "$root\VideoPlayer-1.0.6-release.apk"
```

也可以把 APK 拷进手机点安装（需在系统里允许“安装未知来源应用”）。
启动命令：`adb shell am start -n com.example.videoplayer/.MainActivity`

### 5.6 校验 APK

```powershell
$bt = "$root\_tools\sdk\build-tools\34.0.0"
& "$bt\apksigner.bat" verify --print-certs "$root\VideoPlayer-1.0.6-release.apk"
& "$bt\aapt2.exe" dump badging "$root\VideoPlayer-1.0.6-release.apk" | Select-String "package:|sdkVersion|uses-permission"
```

实测结果：

```
Verifies
Verified using v2 scheme (APK Signature Scheme v2): true
Signer #1 certificate DN: CN=Video Player Demo, OU=Demo, O=Demo, L=Beijing, ST=Beijing, C=CN
package: name='com.example.videoplayer' versionCode='7' versionName='1.0.6' compileSdkVersion='34'
sdkVersion:'24'  targetSdkVersion:'34'
uses-permission: name='android.permission.MODIFY_AUDIO_SETTINGS'
uses-permission: name='android.permission.READ_MEDIA_VIDEO'
uses-permission: name='android.permission.READ_MEDIA_VISUAL_USER_SELECTED'
uses-permission: name='android.permission.READ_EXTERNAL_STORAGE' maxSdkVersion='32'
uses-permission: name='android.permission.FOREGROUND_SERVICE'
uses-permission: name='android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK'
uses-permission: name='android.permission.POST_NOTIFICATIONS'
uses-permission: name='android.permission.WAKE_LOCK'
uses-permission: name='android.permission.RECEIVE_BOOT_COMPLETED'
uses-permission: name='com.example.videoplayer.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'   ← androidx 自动生成，无需关注
文件大小: 3,191,191 字节（约 3.04 MB）
SHA-256: C9AF62B1137DE102BCBF2C3369E44FBB0B9981C248DA1E921A742857EF1EDEE4
```

---

### 5.7 单元测试

纯逻辑单测（格式化、错误码映射），跑在 JVM 上，不需要设备：

```powershell
& "$root\_tools\gradle-8.7\bin\gradle.bat" -p "$root\AndroidVideoPlayer" :app:testDebugUnitTest --no-daemon
```

测试代码在 `app/src/test/java/`，只依赖 `testImplementation(libs.junit)`，不会打进 APK。

> ⚠️ **工程路径含中文时跑不了单测**：JVM 无法从含中文的 classpath 目录加载测试类，报
> `java.lang.ClassNotFoundException: com.example.videoplayer.xxxTest`。
> 只把 `GRADLE_USER_HOME` 指到 ASCII 路径**不够**——argfile 能打开了，但测试类仍然加载不到，
> 必须让「工程所在的绝对路径」是纯 ASCII。
>
> 本仓库提供一键绕行脚本（自动同步到 `%TEMP%\vp_test` 再跑）：
>
> ```powershell
> powershell -ExecutionPolicy Bypass -File scripts\test-local.ps1
> ```
>
> **编译和打 Release 包不受影响**，只有跑测试受影响；GitHub Actions 的 runner 路径本身是 ASCII，
> CI 里直接 `./gradlew :app:testDebugUnitTest` 即可。

### 5.8 CI（GitHub Actions）

仓库根 `.github/workflows/android.yml`，push 到 `main`、开 PR、手动触发都会跑：

1. JDK 17（temurin）+ `android-actions/setup-android`
2. `./gradlew :app:testDebugUnitTest` —— 10 个用例，失败即红
3. `./gradlew :app:assembleRelease` —— 没有 `keystore.properties` 时自动回退 debug 签名，保证任何人 clone 下来都能出包
4. 上传产物 `app-release`（APK）；失败时额外上传 `test-report`

`~/.gradle/caches` 有缓存（key 含 `libs.versions.toml` 哈希），首次约 6~8 分钟，命中缓存后 2~3 分钟。

> 首次在 GitHub 上跑会顺带验证两件事：`settings.gradle.kts` 里的国内镜像在海外 runner 上是否可达，
> 以及 Gradle wrapper 的腾讯镜像 `distributionUrl` 能否下载。任一条不通就把对应 URL 换回官方源。

## 六、权限说明

| 权限 | 来源 | 是否必要 |
| --- | --- | --- |
| `MODIFY_AUDIO_SETTINGS` | 工程清单 | 需要。调系统媒体音量（normal 权限，安装即授予，无运行时弹窗） |
| `READ_MEDIA_VIDEO` | 工程清单 | 需要（Android 13+）。相册页读视频列表；用户拒绝时降级到 SAF 入口 |
| `READ_MEDIA_VISUAL_USER_SELECTED` | 工程清单 | 需要（Android 14+）。用户选「仅允许部分照片和视频」时只拿到它，列表只能看到被选中的视频 |
| `READ_EXTERNAL_STORAGE`（`maxSdkVersion="32"`） | 工程清单 | 需要（Android 12 及以下） |
| `WAKE_LOCK` / `RECEIVE_BOOT_COMPLETED` | WorkManager 库清单合并 | 需要。前者供 JobScheduler 持锁跑导入，后者用于重启后继续未完成的导入队列 |
| `com.example.videoplayer.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | androidx 自动生成 | 自用权限，无需关注 |
| ~~`INTERNET`~~ | — | **不需要**：纯本地播放 |
| ~~`ACCESS_NETWORK_STATE`~~ | media3 库清单合并 | **已用 `tools:node="remove"` 剔除**；将来加网络流播放再删掉那行 |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | **v1.0.3 起需要**：后台播放用 `MediaSessionService`，播放时以 `mediaPlayback` 类型进入前台 | Android 14（targetSdk 34）起前台服务必须声明具体类型 |
| `POST_NOTIFICATIONS` | **v1.0.3 起需要**：媒体通知在 Android 13+ 是运行时权限 | 拒绝后仍可后台播放，只是不显示通知 |

> 不申请 `MANAGE_EXTERNAL_STORAGE`（全盘文件权限会被应用商店拒审）；
> 也不申请 `POST_NOTIFICATIONS`——导入进度只在应用内展示，不发通知。

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
10. **媒体库会占用双倍空间（v1.0.2）**：视频被复制进应用私有目录，同一部片子既有原文件又有一份副本。删除媒体库条目只删副本；卸载应用会连副本一起删掉（需要时重新导入）。可用空间不足时会拒绝导入并提示。
11. **导入耗时与视频大小成正比（v1.0.2）**：复制 + 探测在 WorkManager 后台线程串行执行，一部 2GB 的电影在入门机上可能要几十秒。导入期间可以退出应用，任务会继续；但系统内存极度紧张时可能被推迟到下次充电/充电时执行。
12. **媒体库视频不支持「移动到 SD 卡」**：私有目录固定在主存储；外置存储的 `getExternalFilesDirs` 未使用，换卡后需要重新导入。

---

## 八、三个可扩展方向

### 1）画中画（Picture-in-Picture）

- `AndroidManifest.xml` 的 Activity 加 `android:supportsPictureInPicture="true"`、`android:configChanges` 补 `smallestScreenSize|screenSize|orientation|layoutDirection`。
- `MainActivity` 里 `enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())`，API 26+ 才有，用 `Build.VERSION.SDK_INT >= 26` 包一层（minSdk 24）。
- 用户按 Home 时（`onUserLeaveHint()`）自动进入 PIP；PIP 模式下隐藏除画面外的所有 Compose 控件（`isInPictureInPictureMode` 判断），`PlayerView` 的 `useController` 保持 false，交给系统 PIP 窗口的播放/暂停。
- 注意与现有沉浸式逻辑的冲突：进 PIP 前要 `show(systemBars())`，否则 PIP 窗口里没有系统 UI。

### 2）后台播放 + MediaSession（✅ v1.0.5 已升级为 MediaController 架构）

- 依赖 `androidx.media3:media3-session`（1.4.1）。
- v1.0.5 起播放器归 `PlaybackService` 所有：Service 内部 `ExoPlayer.Builder(...).build()`，
  配 `MediaSession` 对外暴露会话；UI 侧 `PlayerViewModel` 用
  `MediaController.Builder(app, SessionToken(...)).buildAsync()` 异步连接，
  此后播放/暂停/seek/倍速全部通过控制器转发给 Service 的同一个播放器实例，
  `PlayerView` 也渲染这个实例 —— 画面不跨进程，不需要 `PlayerSurface`。
- 换来的是：旋转、切后台、锁屏、划走再回来，播放器都不受影响；通知栏 / 锁屏 / 耳机线控
  与界面操作的是同一个播放器，状态天然一致（v1.0.3 的 `PlaybackHolder` 单例方案已删除）。
- 清单：`FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_MEDIA_PLAYBACK`，
  service 声明 `foregroundServiceType="mediaPlayback"`；`POST_NOTIFICATIONS` 启动时请求。
- 已知取舍：`onTaskRemoved` 里暂停 + `stopSelf`，所以「划掉最近任务」会结束播放。
  想保住播放需改成常驻前台服务（`startForeground` + 不 stopSelf），代价是通知栏常驻。

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
