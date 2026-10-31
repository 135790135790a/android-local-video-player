package com.example.videoplayer.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface VideoDao {

    /** 媒体库列表：连 SOURCE_LOST 一起返回，由 UI 灰显并允许删除，不让用户的数据凭空消失 */
    @Query("SELECT * FROM videos ORDER BY importedAt DESC")
    fun observeAll(): Flow<List<VideoEntity>>

    /** 启动自检用：连 SOURCE_LOST 一起取，检查文件是否被系统清理 */
    @Query("SELECT * FROM videos ORDER BY importedAt DESC")
    suspend fun loadAll(): List<VideoEntity>

    @Query("SELECT * FROM videos WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): VideoEntity?

    /** 用内容指纹判重：同一段视频换了文件名也能识别出来 */
    @Query("SELECT * FROM videos WHERE checksum = :checksum LIMIT 1")
    suspend fun findByChecksum(checksum: String): VideoEntity?

    @Query("SELECT * FROM videos WHERE fileName = :fileName AND size = :size AND lastModified = :lastModified LIMIT 1")
    suspend fun findByFingerprint(fileName: String, size: Long, lastModified: Long): VideoEntity?

    /** (fileName, size, lastModified) 唯一索引冲突时直接忽略，天然完成去重 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(video: VideoEntity): Long

    @Query("UPDATE videos SET lastPlayedAt = :timestamp, playCount = playCount + 1 WHERE id = :id")
    suspend fun markPlayed(id: Long, timestamp: Long)

    /** 续播用：记下当前播放位置（毫秒），播到结尾时传 0 */
    @Query("UPDATE videos SET lastPositionMs = :positionMs WHERE id = :id")
    suspend fun updatePosition(id: Long, positionMs: Long)

    @Query("UPDATE videos SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String)

    @Query("DELETE FROM videos WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM videos")
    suspend fun deleteAll()

    @Query("SELECT COALESCE(SUM(size), 0) FROM videos")
    suspend fun totalSize(): Long

    @Query("SELECT COUNT(*) FROM videos")
    suspend fun count(): Int
}
