package com.example.videoplayer.ui.gallery

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.videoplayer.data.GalleryRepository
import com.example.videoplayer.data.GalleryVideo
import com.example.videoplayer.data.Playability
import com.example.videoplayer.data.PlayabilityCache
import com.example.videoplayer.data.PlayabilityChecker
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 相册的四种可见状态：系统正在检查 / 完整授权 / 仅部分授权 / 被拒 / 被永久拒绝。 */
enum class GalleryPermission { CHECKING, GRANTED, PARTIAL, DENIED, PERMANENTLY_DENIED }

/** 列表项 = 视频元数据 + 校验结论（null 表示还没验完）。 */
data class GalleryItem(
    val video: GalleryVideo,
    val verdict: Playability? = null,
    val checking: Boolean = false
) {
    val isUnsupported: Boolean get() = verdict is Playability.Unsupported
}

data class GalleryUiState(
    val permission: GalleryPermission = GalleryPermission.CHECKING,
    val items: List<GalleryItem> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false
) {
    val verifiedCount: Int get() = items.count { it.verdict != null }
}

/**
 * 相册页的 ViewModel。
 *
 * 权限状态由 [refreshPermissionStatus] 主动复查（进入页面、从设置返回时都调一次），
 * 不依赖 Activity 的结果回调，避免用户在系统设置里撤销权限后还拿着过期的授权状态去查库。
 */
class GalleryViewModel(application: Application) : AndroidViewModel(application) {

    private val app: Application = application
    private val repository = GalleryRepository(application)

    private val _uiState = MutableStateFlow(GalleryUiState())
    val uiState: StateFlow<GalleryUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null
    private var verifyJob: Job? = null

    init {
        refreshPermissionStatus()
    }

    /** 每次进入相册页 / 回到前台都重新查一次权限，避免用过期状态查询 MediaStore。 */
    fun refreshPermissionStatus() {
        val permission = detectPermission()
        _uiState.update { it.copy(permission = permission) }
        if (permission == GalleryPermission.GRANTED || permission == GalleryPermission.PARTIAL) {
            // 已经授权但列表还是空的（首次进入 / 从设置授权返回）时自动加载
            if (_uiState.value.items.isEmpty() && !_uiState.value.isLoading) {
                refresh(showSpinner = false)
            }
        } else {
            _uiState.update { it.copy(items = emptyList()) }
        }
    }

    private fun detectPermission(): GalleryPermission {
        val mediaVideo = ContextCompat.checkSelfPermission(
            app,
            Manifest.permission.READ_MEDIA_VIDEO
        ) == PackageManager.PERMISSION_GRANTED
        val visualUserSelected = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ContextCompat.checkSelfPermission(
                app,
                PERMISSION_READ_MEDIA_VISUAL_USER_SELECTED
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            false
        }
        val legacyStorage = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            ContextCompat.checkSelfPermission(
                app,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            false
        }
        return when {
            // Android 14：用户选了「仅允许部分照片」，只有被选中的视频可见
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                visualUserSelected && !mediaVideo -> GalleryPermission.PARTIAL

            mediaVideo -> GalleryPermission.GRANTED
            legacyStorage -> GalleryPermission.GRANTED
            else -> GalleryPermission.DENIED
        }
    }

    /**
     * 权限请求结果回调。
     *
     * [permanentlyDenied] 由 UI 通过 shouldShowRequestPermissionRationale 判断：
     * 请求完仍然没授权、且系统不再允许弹窗，才认定为永久拒绝。
     */
    fun onPermissionResult(granted: Boolean, partiallyGranted: Boolean, permanentlyDenied: Boolean) {
        val next = when {
            granted -> GalleryPermission.GRANTED
            partiallyGranted -> GalleryPermission.PARTIAL
            permanentlyDenied -> GalleryPermission.PERMANENTLY_DENIED
            else -> GalleryPermission.DENIED
        }
        _uiState.update { it.copy(permission = next) }
        if (next == GalleryPermission.GRANTED || next == GalleryPermission.PARTIAL) {
            refresh(showSpinner = false)
        }
    }

    fun refresh(showSpinner: Boolean = true) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update {
                if (showSpinner) it.copy(isLoading = true) else it.copy(isRefreshing = true)
            }
            val videos = repository.loadVideos()
            _uiState.update {
                it.copy(
                    items = videos.map { video -> GalleryItem(video = video) },
                    isLoading = false,
                    isRefreshing = false
                )
            }
            verifyAll(videos)
        }
    }

    /** 点开某个视频时按需校验；已经有结论就直接用缓存。 */
    fun verdictFor(video: GalleryVideo): Playability? {
        val cached = PlayabilityCache.get(video.cacheKey)
        if (cached != null) return cached
        return _uiState.value.items.firstOrNull { it.video.id == video.id }?.verdict
    }

    /**
     * 列表加载完就后台批量跑第二级校验，结果逐条回填。
     * 并发上限在 [PlayabilityChecker] 内部用 Semaphore(4) 控制，这里直接全量 launch 即可。
     */
    private fun verifyAll(videos: List<GalleryVideo>) {
        verifyJob?.cancel()
        if (videos.isEmpty()) return
        verifyJob = viewModelScope.launch {
            videos.forEach { video ->
                launch {
                    val cached = PlayabilityCache.get(video.cacheKey)
                    val verdict = cached ?: PlayabilityChecker.deepCheck(app, video)
                    updateVerdict(video.id, verdict)
                }
            }
        }
    }

    private fun updateVerdict(id: Long, verdict: Playability) {
        _uiState.update { state ->
            state.copy(
                items = state.items.map { item ->
                    if (item.video.id == id) item.copy(verdict = verdict, checking = false) else item
                }
            )
        }
    }

    /** 重新发起一次权限请求（「管理可访问的相册」/ 重新授权按钮）。 */
    fun resetPermissionState() {
        _uiState.update { it.copy(permission = GalleryPermission.CHECKING) }
    }

    companion object {

        /** Android 14 新增，编译期常量在 Manifest.permission 里没有，只能写字符串。 */
        const val PERMISSION_READ_MEDIA_VISUAL_USER_SELECTED =
            "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"

        /** 按系统版本给出真正需要申请的那一组权限。 */
        fun requiredPermissions(): Array<String> = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
                Manifest.permission.READ_MEDIA_VIDEO,
                PERMISSION_READ_MEDIA_VISUAL_USER_SELECTED
            )

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
                Manifest.permission.READ_MEDIA_VIDEO
            )

            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }
}
