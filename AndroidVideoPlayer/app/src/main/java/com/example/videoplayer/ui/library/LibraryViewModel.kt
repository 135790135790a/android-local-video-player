package com.example.videoplayer.ui.library

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.videoplayer.data.MediaLibrary
import com.example.videoplayer.data.db.AppDatabase
import com.example.videoplayer.data.db.VideoEntity
import com.example.videoplayer.util.DiagnosticsExporter
import com.example.videoplayer.work.ImportWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** 媒体库排序方式 */
enum class LibrarySort(val label: String) {
    IMPORTED_DESC("导入时间"),
    NAME_ASC("名称"),
    SIZE_DESC("文件大小"),
    DURATION_DESC("时长"),
    LAST_PLAYED_DESC("最近播放")
}

data class LibraryUsage(
    val count: Int = 0,
    val totalBytes: Long = 0L,
    val freeBytes: Long = 0L
)

sealed interface ImportState {
    data object Idle : ImportState
    data class Running(
        val index: Int,
        val total: Int,
        val done: Int,
        val failed: Int,
        val fileName: String
    ) : ImportState

    data class Finished(val done: Int, val failed: Int, val skipped: Int) : ImportState
    data class Failed(val message: String) : ImportState
}

/**
 * 媒体库的状态源：Room 列表 + 存储占用 + 导入任务进度。
 *
 * 导入交给 WorkManager（进程被杀也能续），这里只负责派发与观察，
 * 不自己拿线程做 IO —— 切后台不影响导入，回到前台自动接着显示进度。
 */
class LibraryViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    private val dao = AppDatabase.get(context).videoDao()
    private val workManager = WorkManager.getInstance(context)

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _sort = MutableStateFlow(LibrarySort.IMPORTED_DESC)
    val sort: StateFlow<LibrarySort> = _sort.asStateFlow()

    /** 搜索 + 排序都在内存里做：视频量级是几百，够用且不必每改一个字就查一次库 */
    val videos: StateFlow<List<VideoEntity>> =
        combine(dao.observeAll(), _query, _sort) { list, keyword, sort ->
            val filtered = if (keyword.isBlank()) {
                list
            } else {
                val q = keyword.trim()
                list.filter {
                    it.fileName.contains(q, ignoreCase = true) ||
                        it.videoCodec.contains(q, ignoreCase = true) ||
                        it.audioCodec.contains(q, ignoreCase = true) ||
                        it.resolutionLabel.contains(q, ignoreCase = true)
                }
            }
            when (sort) {
                LibrarySort.IMPORTED_DESC -> filtered
                LibrarySort.NAME_ASC -> filtered.sortedBy { it.fileName.lowercase() }
                LibrarySort.SIZE_DESC -> filtered.sortedByDescending { it.size }
                LibrarySort.DURATION_DESC -> filtered.sortedByDescending { it.durationMs }
                LibrarySort.LAST_PLAYED_DESC -> filtered.sortedByDescending { it.lastPlayedAt }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setQuery(text: String) {
        _query.value = text
    }

    fun setSort(sort: LibrarySort) {
        _sort.value = sort
    }

    private val _usage = MutableStateFlow(LibraryUsage())
    val usage: StateFlow<LibraryUsage> = _usage.asStateFlow()

    private val _importState = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = _importState.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private var activeWorkId: UUID? = null

    init {
        viewModelScope.launch {
            refreshUsage()
            reconcileWithDisk()
        }
    }

    /** 把相册 / SAF 选中的 Uri 丢给后台任务入库 */
    fun importUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (_importState.value is ImportState.Running) {
            _message.value = "正在导入中，请稍候"
            return
        }
        val request = OneTimeWorkRequestBuilder<ImportWorker>()
            .setInputData(
                workDataOf(ImportWorker.KEY_URIS to uris.map { it.toString() }.toTypedArray())
            )
            .addTag(ImportWorker.TAG)
            .build()
        activeWorkId = request.id
        _importState.value = ImportState.Running(0, uris.size, 0, 0, "准备中…")
        workManager.enqueue(request)

        viewModelScope.launch {
            workManager.getWorkInfoByIdFlow(request.id).collect { info ->
                _importState.value = info.toImportState()
                if (info != null && info.state.isFinished) {
                    refreshUsage()
                    reconcileWithDisk()
                }
            }
        }
    }

    fun cancelImport() {
        workManager.cancelAllWorkByTag(ImportWorker.TAG)
        _importState.value = ImportState.Idle
        _message.value = "已取消导入，已完成的部分已保留"
    }

    fun dismissImportResult() {
        if (_importState.value !is ImportState.Running) {
            _importState.value = ImportState.Idle
        }
    }

    fun deleteVideo(video: VideoEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.deleteById(video.id)
            MediaLibrary.deleteFile(video.localPath)
            refreshUsage()
            _message.value = "已删除《${video.fileName}》"
        }
    }

    fun markPlayed(video: VideoEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            dao.markPlayed(video.id, System.currentTimeMillis())
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    /** 生成诊断包（设备信息 + 媒体库概览 + 崩溃日志），供用户反馈问题时附上。 */
    suspend fun exportDiagnostics(): File? = DiagnosticsExporter.export(getApplication())

    fun refresh() {
        viewModelScope.launch {
            refreshUsage()
            reconcileWithDisk()
        }
    }

    private suspend fun refreshUsage() = withContext(Dispatchers.IO) {
        val count = dao.count()
        val total = dao.totalSize()
        _usage.value = LibraryUsage(
            count = count,
            totalBytes = total,
            freeBytes = MediaLibrary.freeBytes(context)
        )
    }

    /**
     * 启动自检：
     * 1. DB 里有记录但文件被系统清掉了 → 标成 SOURCE_LOST（条目留着让用户删）
     * 2. 目录里有文件但 DB 里没记录（上次复制完没来得及入库）→ 直接删掉，避免占用空间
     */
    private suspend fun reconcileWithDisk() = withContext(Dispatchers.IO) {
        val all = runCatching { dao.loadAll() }.getOrDefault(emptyList())
        all.forEach { item ->
            if (item.status == VideoEntity.Status.READY &&
                item.localPath.isNotBlank() &&
                !File(item.localPath).exists()
            ) {
                dao.updateStatus(item.id, VideoEntity.Status.SOURCE_LOST)
            }
        }
        val referenced = all.mapNotNull { it.localPath.takeIf(String::isNotBlank) }
            .map { File(it).absolutePath }
            .toSet()
        MediaLibrary.dir(context).listFiles()?.forEach { file ->
            if (file.isFile && file.absolutePath !in referenced) {
                runCatching { file.delete() }
            }
        }
    }

    private fun WorkInfo?.toImportState(): ImportState {
        if (this == null) return ImportState.Idle
        return when (state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED -> {
                val total = progress.getInt(ImportWorker.PROGRESS_TOTAL, 0)
                ImportState.Running(
                    index = progress.getInt(ImportWorker.PROGRESS_CURRENT, 0) + 1,
                    total = total,
                    done = progress.getInt(ImportWorker.PROGRESS_DONE, 0),
                    failed = progress.getInt(ImportWorker.PROGRESS_FAILED, 0),
                    fileName = progress.getString(ImportWorker.PROGRESS_NAME).orEmpty()
                )
            }

            WorkInfo.State.SUCCEEDED -> ImportState.Finished(
                done = outputData.getInt(ImportWorker.RESULT_DONE, 0),
                failed = outputData.getInt(ImportWorker.RESULT_FAILED, 0),
                skipped = outputData.getInt(ImportWorker.RESULT_SKIPPED, 0)
            )

            WorkInfo.State.FAILED -> ImportState.Failed("导入中断，已完成的部分已保存")
            WorkInfo.State.CANCELLED -> ImportState.Idle
        }
    }
}
