# 更新日志

本项目的版本变更记录。版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

---

## [1.0.6] - 2026-10-31

versionCode: 7 · versionName: 1.0.6

播放器完整性的最后一块：外挂字幕。

### Added

- **外挂字幕**：播放页底栏新增字幕按钮，从文件管理器选 `.srt` / `.vtt` / `.ass` / `.ssa`，交给 ExoPlayer 自带的 SubRip / WebVTT / SSA 解析器，不自己解析。
  - 挂载后从**当前播放位置**重载（`setMediaItem(item, positionMs)`），不会跳回开头，原播放状态（播放中 / 暂停）保持。
  - 字幕文件同样走 `takePersistableUriPermission`，下次进 App 仍可读。
  - 已有字幕时点按钮弹菜单：「更换字幕」/「关闭字幕」。
  - 关闭字幕只禁用文本轨（`setTrackTypeDisabled(TRACK_TYPE_TEXT, true)`），**不重载媒体**，播放不中断、缓冲不重来。
- `res/drawable/ic_subtitles.xml`：自建 24dp vector（material-icons core 子集没有 subtitles 图标）。
- `VideoSurface` 配置 `PlayerView.subtitleView` 样式：白字 + 半透明黑底 + 描边，底部留 12% 空白避免被进度条压住。

### Changed

- `PlayerUiState` 新增 `subtitleName`，换视频时清空；字幕按钮在已挂字幕时用主题色高亮。

### Fixed

- 无。

### 已知限制

- ASS/SSA 的特效标签（`{\an8}`、卡拉 OK 等）会被忽略，只保留文本与基本样式。
- 字幕不随视频记忆：本次挂载只在本播放会话内有效，退出播放页后需重新选。
- 各文件管理器对 `.srt` 报的 MIME 不统一，所以选择器用 `*/*`，选错格式会 Toast 提示。

## [1.0.5] - 2026-10-31

versionCode: 6 · versionName: 1.0.5

`1.0.4 → 1.0.5` 是三块结构性升级合成的一个大包：连播 + 搜索排序（体验）、拆掉 ViewModel 的两个关注点（技术债）、后台播放改成正统 MediaController 架构（技术债，行为有变化）。

### Added

- **连播**：从媒体库打开视频时携带当前筛选/排序后的队列，一条播完自动播下一条；队尾或下一条文件已丢失时停在当前页。SAF / 相册的单文件播放不连播。
- **媒体库搜索**：标题、路径、编码关键词实时过滤（250ms 防抖），无结果有空状态提示。
- **媒体库排序**：最近导入 / 名称 / 文件大小 / 时长，四个维度切换，选择持久化在 SharedPreferences，重启后保留。
- `player/AudioFocusController.kt`：音频焦点集中管理（GAIN / LOSS / LOSS_TRANSIENT / LOSS_TRANSIENT_CAN_DUCK，含「短暂丢失后按需续播」）。
- `player/PositionPersister.kt`：播放位置独立落库（播放中每 5 秒 + 离开页面 + 播完归零），自带 scope，ViewModel 销毁后仍能写完最后一次。

### Changed

- **播放器实例归 Service 所有**：`PlaybackService` 内部创建 ExoPlayer 并配 `MediaSession`，UI 侧 `PlayerViewModel` 通过 `MediaController` 连接。
  - 旋转、切后台、锁屏不再触碰播放器实例，播放连续性的保证从「ViewModel 不重建」升级为「播放器生命周期与 UI 完全解耦」。
  - 通知栏 / 锁屏 / 耳机线控与界面操作的是同一个播放器，状态天然一致。
  - `player/PlaybackHolder.kt`（旧的静态共享播放器）已删除。
- `PlayerViewModel` ~650 行降到 ~600 行，音频焦点、进度落库两个关注点移出。
- **行为变化**：从最近任务划掉 App 时 `PlaybackService.onTaskRemoved` 会暂停并停止服务，播放随之结束。需要「划掉也不停」得改用前台服务常驻方案。

### Fixed

- 连播遇到「文件已丢失」的条目不再把播放器打进错误态，直接跳过并停在当前页。
- 进度落库不再依赖 `viewModelScope`，进程被回收的瞬间也能写完最后一次位置。
- 预检拦截 / 播放错误对话框打开时屏蔽播放器手势，避免误触底层画面。

### 已知限制

- 仍无真机回归，三块改动都是「编译通过 + 静态审计」级别。
- `MediaController` 连接是异步的，极少数情况下播放页会先显示一帧空白再出画面。

## [1.0.4] - 2026-10-30

versionCode: 5 · versionName: 1.0.4

本版不新增功能，只补地基：可测试、可拆分、可诊断。

### Added

- **单元测试**：新增 JVM 单元测试（`app/src/test/`），覆盖 `Formatters`
  （体积 / 码率 / 帧率 / 时长 / 时间）与 `PlaybackErrorMapper`（错误码 → 中文提示映射表），
  `./gradlew :app:testDebugUnitTest` 可离线运行，不需要设备。
- **VolumeController**：音量逻辑从 `PlayerViewModel` 抽成独立类，统一封装
  `AudioManager.STREAM_MUSIC` 的读写、三档图标判定（静音 / 低 / 高）与音量持久化，
  并监听系统音量变化同步 UI。

### Changed

- `PlayerViewModel` 移除音量相关的 `ContentObserver` 与 `AudioManager` 操作，
  改为订阅 `VolumeController` 的状态流，类体量下降。
- `PlaybackErrorMapper` 新增纯函数 `messageForCode(Int)`，
  原 `toUserMessage(PlaybackException)` 委托给它，方便单元测试直接验证映射表。
- `app/build.gradle.kts` 增加 `testImplementation(libs.junit)`（4.13.2，不打包进 APK）。

### Fixed

- 无。

## [1.0.3] - 2026-10-30

versionCode: 4 · versionName: 1.0.3

### Added

- **播放进度记忆**：媒体库里的视频记住上次看到的位置（`VideoEntity.lastPositionMs`），
  再次打开自动续播。播放中每 5 秒落一次库，切后台、退出时各再落一次，
  进程被系统杀掉也不丢进度；离结尾不足 3 秒视为「已看完」，下次从头播放。
- **封面进度条**：媒体库网格每个封面底部叠加一条细进度条，一眼看出哪些看了一半。
- **后台播放**：新增 `PlaybackService`（继承 `MediaSessionService`），播放时自动升起媒体通知，
  支持通知栏播放/暂停/进度拖动、锁屏控件、蓝牙耳机与线控按键。
  播放器实例仍由 `PlayerViewModel` 创建、通过 `PlaybackHolder` 共享给服务，
  画面由 ExoPlayer 直接渲染到 `PlayerView`，不经过跨进程 Surface。
- **通知权限**：Android 13+ 启动时请求 `POST_NOTIFICATIONS`，拒绝也不影响播放，
  只是不显示媒体通知。

### Changed

- `onStop` 不再暂停播放：切到后台继续出声，回前台时画面从当前进度接上。
  用户从最近任务划掉 App 时停止播放并退出，不做「划掉还在后台放声音」的行为。
- `AndroidManifest.xml` 恢复 `FOREGROUND_SERVICE`，并新增
  `FOREGROUND_SERVICE_MEDIA_PLAYBACK`（API 34 起前台服务必须声明类型）。
- 新增依赖 `androidx.media3:media3-session`（1.4.1）。
- `versionCode` 3 → 4，`versionName` "1.0.2" → "1.0.3"。

### Fixed

- 切后台时不再提前放弃音频焦点，来电恢复后仍能正确续播。

---

## [1.0.2] - 2026-10-30

versionCode: 3 · versionName: 1.0.2

### Added

- **媒体入库**：从相册或 SAF 选择的视频会复制到应用私有目录
  `Android/data/com.example.videoplayer/files/Movies/library/`（外部存储不可用时降级到
  `filesDir/movies/library/`）。播放路径从此只认本地 File，不再依赖 `content://` 授权是否还有效。
- **媒体库首页**：网格展示全部已入库视频，每项显示首帧封面、时长角标、分辨率角标、
  文件大小与编码；顶部统计卡显示「视频数 / 已占用空间 / 可用空间」。
- **视频信息面板**：分辨率（附 4K / 1080P / 720P 等标签）、帧率、码率、视频编码、音频编码、
  容器格式、时长、文件大小、导入时间、最后播放时间、播放次数、本地路径。
- **后台导入 + 进度**：导入走 WorkManager（`ImportWorker`），卡片实时显示「第 i / N 个」、
  当前文件名、成功/失败计数，支持取消；退到后台或进程被杀后任务可恢复。
- **删除视频**：信息面板内一键删除，同时清掉数据库记录和本地文件。
- **重复导入去重**：一重按「文件名 + 大小 + 修改时间」，二重按「文件前 1MB 的 MD5」，
  命中则丢弃新副本并计入「跳过」。
- **旧版数据迁移**：首次启动把 v1.0.1「最近播放」里仍可读取的记录复制入库（幂等，只跑一次），
  读不到的标记为「源文件已丢失」保留在列表里可见可删。

### Changed

- 首页由「最近播放 / 相册」改为「媒体库 / 相册」，**媒体库成为主入口**。
- **相册页退化为导入来源**：点击相册里的视频即加入导入队列（Toast 提示），播放统一走媒体库。
- SAF「选择视频」保留为降级入口（相册权限被永久拒绝时使用），行为从「直接播放」改为「入库」。
- 新增依赖 `androidx.room:room-runtime` / `room-ktx`（2.6.1，KSP 编译器）、
  `androidx.work:work-runtime-ktx`（2.9.1），并启用 `ksp` 插件。
- `AndroidManifest.xml` 剔除 WorkManager 带进来的 `FOREGROUND_SERVICE`
  （导入是普通后台任务，不用前台服务）；`RECEIVE_BOOT_COMPLETED` 与 `WAKE_LOCK` 保留，
  分别用于重启后恢复导入队列和 JobScheduler 调度。
- `versionCode` 2 → 3，`versionName` "1.0.1" → "1.0.2"。

### Fixed

- 修复 v1.0.1 点击历史记录时 `takePersistableUriPermission` 授权已失效导致的无声失败。
- 修复同名视频互相覆盖：入库时自动加 `(1)` `(2)` 后缀（并过滤文件名里的非法字符）。
- 修复导入中途失败留下半截文件：复制失败或结果为 0 字节时删除临时文件。
- 修复数据库与磁盘不一致：每次进入媒体库核对一遍，库里有记录但文件已消失标「源文件已丢失」，
  目录里有文件但库里没记录直接清理。
- 修复导入前空间不足导致写坏文件：写入前检查可用空间并预留 32MB 余量。
- 修复媒体库缩略图不显示：`VideoFrameDecoder` 没注册到全局 `ImageLoader`，
  现在统一走 `AppImageLoader` 单例解码视频首帧。

---

## [1.0.1] - 2026-10-29

versionCode: 2 · versionName: 1.0.1

### Added

- **相册页**（首页 Tab）：用 `MediaStore.Video.Media.EXTERNAL_CONTENT_URI` 读取手机里的全部视频，
  `LazyVerticalGrid` 3 列（横屏 5 列）展示缩略图、时长角标、文件名（单行省略），支持下拉刷新，空相册有空状态。
- **视频缩略图**：用 Coil 的 `VideoFrameDecoder`（`io.coil-kt:coil-video`）从视频首帧抽封面，带占位图和失败占位图。
- **两级可播放性校验**：
  - 第一级（列表加载时批量做）：MIME 必须是 `video/*`；`DURATION` / `SIZE` / 宽高任一为 0 或 -1 时标记「可能无法播放」；并发上限 4，不阻塞 UI。
  - 第二级（点击播放时做）：`MediaExtractor` 遍历轨道读 `MediaFormat` 的 mime，再用
    `MediaCodecList(REGULAR_CODECS).findDecoderForFormat()` 判断有没有解码器。
- 播放前加载态有 **200ms 阈值**：校验很快时不闪一下，慢才显示「正在检查视频是否可播放…」。
- 校验不通过时列表项灰显 + 「不支持」角标，点击弹出说明原因，并提供「仍要尝试播放」强制入口。
- 视频轨可解码但**音频轨无解码器**时允许播放并静音，Toast 提示「音频格式不支持，将静音播放」。
- 权限被永久拒绝时显示说明卡片 + 「去设置」按钮（`Settings.ACTION_APPLICATION_DETAILS_SETTINGS`）。
- Android 14+ 选「仅允许部分照片和视频」时，列表顶部显示「你已授权部分视频 · 管理可访问的相册」入口。
- 播放页新增「返回相册」按钮，预检失败、播放入口失败时都能一键回相册。
- 本文件 `CHANGELOG.md`。

### Changed

- `versionCode` 1 → 2，`versionName` "1.0.0" → "1.0.1"。
- 首页改为两个 Tab：「最近播放」/「相册」。原来的 SAF 单文件选择（`ActivityResultContracts.OpenDocument`）
  保留在「最近播放」Tab 内，作为相册权限被拒时的降级方案。
- 新增依赖 `io.coil-kt:coil-compose` 与 `io.coil-kt:coil-video`（`2.7.0`），`proguard-rules.pro` 补 Coil keep 规则。
- `AndroidManifest.xml` 新增 `READ_MEDIA_VIDEO`、`READ_MEDIA_VISUAL_USER_SELECTED`、
  `READ_EXTERNAL_STORAGE`（`android:maxSdkVersion="32"`）；仍然不申请 `MANAGE_EXTERNAL_STORAGE`。
- 相册页与最近播放页共用同一个 `PlayerViewModel`（挂在 Activity 上），切换 Tab 不重建播放器。

### Fixed

- 修掉了 v1.0.0 遗留的隐患：拖动进度条时若 `player.duration` 仍是 `C.TIME_UNSET`，
  `seekTo` 的上界会被压成 0，把播放位置强制归零。现在 duration 未知时用 `Long.MAX_VALUE` 作上界。
- ExoPlayer 错误提示补齐中文映射：
  解码不支持（`ERROR_CODE_DECODING_FAILED` / `ERROR_CODE_DECODER_INIT_FAILED`）、
  文件丢失（`ERROR_CODE_IO_FILE_NOT_FOUND`）、容器损坏（`ERROR_CODE_PARSING_CONTAINER_MALFORMED`）、
  音频轨道异常（`ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED`）、DRM、超时等。
- 相册页每次进入都用 `ContextCompat.checkSelfPermission` 复查权限，用户在系统设置里撤销权限后不会崩溃。
- 所有 `MediaExtractor` / `MediaMetadataRetriever` 都走 `try-catch-finally` + `release()`，避免资源泄漏与 ANR。
- MediaStore 查询全部在 `Dispatchers.IO`，主线程不查询。

---

## [1.0.0] - 2026-10-28

首次发布。

- SAF（`ActivityResultContracts.OpenDocument`）选择视频，不申请存储运行时权限，`takePersistableUriPermission` 持久化授权。
- Media3 ExoPlayer 播放内核，MVVM（ViewModel + StateFlow）持有播放器，旋转不中断播放。
- 播放/暂停、点击画面切换、暂停态中央大播放按钮、进度条（拖动只更新文字，松手才 seek，显示缓冲进度）。
- AudioManager 控制 `STREAM_MUSIC`，静音三档图标，音量值持久化。
- 倍速 0.5 / 0.75 / 1 / 1.25 / 1.5 / 2。
- 沉浸式全屏、3 秒自动隐藏控件、屏幕常亮、音频焦点处理。
- 最近播放 5 个视频（SharedPreferences 持久化），一键续播。
- 深色主题、Material 3、每个控件都有 `contentDescription`。
