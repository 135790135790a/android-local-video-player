# 更新日志

本项目的版本变更记录。版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

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
