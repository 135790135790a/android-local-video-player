package com.example.videoplayer.player

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 错误码 → 中文提示的映射表。
 *
 * media3 的 ERROR_CODE_* 都是编译期常量，会被内联，所以不需要 Android stub 也能在 JVM 里跑。
 */
class PlaybackErrorMapperTest {

    @Test
    fun `decoding failure mentions codec support`() {
        val msg = PlaybackErrorMapper.messageForCode(PlaybackException.ERROR_CODE_DECODING_FAILED)
        assertTrue(msg, msg.contains("编码"))
    }

    @Test
    fun `decoder init failure mentions codec support`() {
        val msg = PlaybackErrorMapper.messageForCode(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED)
        assertTrue(msg, msg.contains("编码"))
    }

    @Test
    fun `file not found explains missing file`() {
        val msg = PlaybackErrorMapper.messageForCode(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        assertTrue(msg, msg.contains("文件"))
    }

    @Test
    fun `malformed container explains corruption`() {
        val msg = PlaybackErrorMapper.messageForCode(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED)
        assertTrue(msg, msg.contains("损坏"))
    }

    @Test
    fun `unknown code is echoed back and offers retry`() {
        val msg = PlaybackErrorMapper.messageForCode(12345)
        assertTrue(msg, msg.contains("12345"))
        assertTrue(msg, msg.contains("重试"))
    }
}
