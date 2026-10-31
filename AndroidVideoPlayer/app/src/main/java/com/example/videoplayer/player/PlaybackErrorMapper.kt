package com.example.videoplayer.player

import androidx.media3.common.PlaybackException

/**
 * 把 ExoPlayer 的 [PlaybackException.errorCode] 翻译成用户能看懂的中文提示。
 */
object PlaybackErrorMapper {

    fun toUserMessage(error: PlaybackException): String = messageForCode(error.errorCode)

    /**
     * 纯函数版本：只依赖错误码，方便在 JVM 单元测试里直接验证整张映射表。
     * 媒体3 的 ERROR_CODE_* 都是编译期常量，JVM 测试里不会触发 Android stub。
     */
    fun messageForCode(errorCode: Int): String = when (errorCode) {
        // ---- 解码相关：编码格式不被设备支持 ----
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ->
            "该视频的编码格式不被支持，可以换一个视频，或先用转码工具转换格式"

        // ---- 解封装相关：文件损坏或封装格式不支持 ----
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ->
            "视频文件已损坏，无法解析"

        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED ->
            "该视频的封装格式不受支持"

        // ---- IO 相关 ----
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
            "找不到这个视频文件，它可能已被移动或删除，请重新选择"

        PlaybackException.ERROR_CODE_IO_NO_PERMISSION ->
            "没有读取该文件的权限，请重新选择视频以重新授权"

        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
            "读取视频时网络连接失败"

        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
            "读取视频失败（服务器返回异常状态码）"

        PlaybackException.ERROR_CODE_IO_UNSPECIFIED ->
            "读取视频文件失败，请重新选择视频"

        // ---- 音频轨道 ----
        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED ->
            "音频轨道初始化失败，可能是音频编码不受支持"

        // ---- DRM（6000-6999） ----
        in 6_000..6_999 ->
            "该视频受 DRM 版权保护，本播放器无法播放"

        // ---- 其他 ----
        PlaybackException.ERROR_CODE_TIMEOUT ->
            "播放超时，请检查文件是否可访问"

        PlaybackException.ERROR_CODE_REMOTE_ERROR ->
            "播放器内部错误，请重试"

        else ->
            "播放失败（错误码 $errorCode），请重试或更换视频"
    }

    /** 开发者排查用的附加信息，UI 上折叠显示。 */
    fun technicalDetail(error: PlaybackException): String {
        val cause = error.cause?.message
        return buildString {
            append("errorCode=")
            append(error.errorCode)
            append(", name=")
            append(error.errorCodeName)
            if (!cause.isNullOrBlank()) {
                append("\n")
                append(cause)
            }
        }
    }
}
