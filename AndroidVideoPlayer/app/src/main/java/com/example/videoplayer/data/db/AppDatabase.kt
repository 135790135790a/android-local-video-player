package com.example.videoplayer.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [VideoEntity::class],
    version = 3,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun videoDao(): VideoDao

    companion object {
        private const val DB_NAME = "media_library.db"

        /**
         * v1 -> v2：新增「上次播放位置」列。
         *
         * 只做 ALTER TABLE 加列，不动已有数据 —— 用户导入的视频记录不能因为升级丢失。
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE videos ADD COLUMN lastPositionMs INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /**
         * v2 -> v3：新增「这部片上次用的字幕」和「上次的倍速」。
         *
         * 三列都可空/带默认值，老记录升级后等于「没挂过字幕、1 倍速」，行为不变。
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE videos ADD COLUMN subtitleUri TEXT")
                db.execSQL("ALTER TABLE videos ADD COLUMN subtitleName TEXT")
                db.execSQL(
                    "ALTER TABLE videos ADD COLUMN playbackSpeed REAL NOT NULL DEFAULT 1.0"
                )
            }
        }

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { instance = it }
            }
    }
}
