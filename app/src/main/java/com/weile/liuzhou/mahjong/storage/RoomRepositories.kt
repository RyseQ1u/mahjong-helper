package com.weile.liuzhou.mahjong.storage

import android.content.Context
import androidx.room.Room
import com.weile.liuzhou.mahjong.analysis.AnalysisSettings
import com.weile.liuzhou.mahjong.domain.game.GameEvent
import com.weile.liuzhou.mahjong.domain.storage.DebugSampleRecord
import com.weile.liuzhou.mahjong.domain.storage.DebugSampleRepository
import com.weile.liuzhou.mahjong.domain.storage.RoundHistoryRepository
import com.weile.liuzhou.mahjong.domain.storage.RoundRecord
import com.weile.liuzhou.mahjong.domain.storage.SettingsRepository
import com.weile.liuzhou.mahjong.domain.storage.toGameEvents
import com.weile.liuzhou.mahjong.domain.storage.toStoredList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 规则偏好仓库（Room 实现）。 */
class RoomSettingsRepository(private val db: AppDatabase) : SettingsRepository {

    override suspend fun load(): AnalysisSettings = withContext(Dispatchers.IO) {
        db.settingsDao().get()?.toSettings() ?: AnalysisSettings()
    }

    override suspend fun save(settings: AnalysisSettings) = withContext(Dispatchers.IO) {
        db.settingsDao().upsert(settings.toEntity())
    }
}

/** 牌局历史仓库（Room 实现）。 */
class RoomRoundHistoryRepository(private val db: AppDatabase) : RoundHistoryRepository {

    override suspend fun saveRound(record: RoundRecord, events: List<GameEvent>) =
        withContext(Dispatchers.IO) {
            val roundId = db.roundDao().insertRound(record.toEntity())
            if (events.isNotEmpty()) {
                val entities = events.toStoredList().map { it.toEntity(roundId) }
                db.roundDao().insertEvents(entities)
            }
        }

    override suspend fun recentRounds(limit: Int): List<RoundRecord> =
        withContext(Dispatchers.IO) {
            db.roundDao().recent(limit).map { it.toRecord() }
        }

    override suspend fun eventsOf(roundId: Long): List<GameEvent> =
        withContext(Dispatchers.IO) {
            db.roundDao().eventsOf(roundId).map { it.toStored() }.toGameEvents()
        }
}

/** 调试样本元数据仓库（Room 实现）。 */
class RoomDebugSampleRepository(private val db: AppDatabase) : DebugSampleRepository {

    override suspend fun record(sample: DebugSampleRecord) = withContext(Dispatchers.IO) {
        db.debugSampleDao().insert(sample.toEntity())
    }

    override suspend fun recent(limit: Int): List<DebugSampleRecord> =
        withContext(Dispatchers.IO) {
            db.debugSampleDao().recent(limit).map { it.toRecord() }
        }
}

/**
 * 存储模块单例（P5 之后的增强项）。
 *
 * 单 Activity + 单 Service 形态下用 object 持有数据库与仓库；
 * 所有读写都在 IO 协程，UI 层经 lifecycleScope 调用。
 */
object StorageModule {

    @Volatile
    private var db: AppDatabase? = null

    fun database(context: Context): AppDatabase =
        db ?: synchronized(this) {
            db ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                AppDatabase.NAME,
            ).fallbackToDestructiveMigration().build().also { db = it }
        }

    fun settings(context: Context): SettingsRepository =
        RoomSettingsRepository(database(context))

    fun rounds(context: Context): RoundHistoryRepository =
        RoomRoundHistoryRepository(database(context))

    fun debugSamples(context: Context): DebugSampleRepository =
        RoomDebugSampleRepository(database(context))
}
