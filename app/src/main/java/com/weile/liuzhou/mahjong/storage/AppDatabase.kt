package com.weile.liuzhou.mahjong.storage

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import com.weile.liuzhou.mahjong.domain.game.GameEvent
import com.weile.liuzhou.mahjong.domain.storage.DebugSampleRecord
import com.weile.liuzhou.mahjong.domain.storage.RoundRecord
import com.weile.liuzhou.mahjong.domain.storage.StoredEvent
import com.weile.liuzhou.mahjong.domain.storage.toGameEvent
import com.weile.liuzhou.mahjong.domain.storage.toStored

/** 规则偏好（单行表）。 */
@Entity(tableName = "settings")
data class SettingsEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val hasDragons: Boolean,
    val mode: String,
    val analysisIntervalMs: Long,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}

/** 一局历史。 */
@Entity(tableName = "rounds")
data class RoundEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val roundIndex: Int,
    val startedAt: Long,
    val endedAt: Long,
    val eventCount: Int,
    val note: String?,
)

/** 局内事件（展平存储；副露只存首牌，回溯时足够定位，完整副露见事件流注释）。 */
@Entity(
    tableName = "round_events",
    foreignKeys = [
        ForeignKey(
            entity = RoundEntity::class,
            parentColumns = ["id"],
            childColumns = ["roundId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("roundId")],
)
data class RoundEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val roundId: Long,
    val seq: Int,
    val type: String,
    val seat: String,
    val tileCode: Int?,
    val fromSeat: String?,
    val extra: String?,
)

/** 调试样本元数据（不存图像内容）。 */
@Entity(tableName = "debug_samples")
data class DebugSampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val filePath: String,
    val tag: String,
    val savedAt: Long,
)

@Dao
interface SettingsDao {
    @Query("SELECT * FROM settings WHERE id = ${SettingsEntity.SINGLETON_ID}")
    suspend fun get(): SettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SettingsEntity)
}

@Dao
interface RoundDao {
    @Insert
    suspend fun insertRound(round: RoundEntity): Long

    @Insert
    suspend fun insertEvents(events: List<RoundEventEntity>)

    @Query("SELECT * FROM rounds ORDER BY id DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<RoundEntity>

    @Query("SELECT * FROM round_events WHERE roundId = :roundId ORDER BY seq ASC")
    suspend fun eventsOf(roundId: Long): List<RoundEventEntity>
}

@Dao
interface DebugSampleDao {
    @Insert
    suspend fun insert(sample: DebugSampleEntity)

    @Query("SELECT * FROM debug_samples ORDER BY id DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<DebugSampleEntity>
}

/** 应用数据库（storage 模块）。 */
@Database(
    entities = [SettingsEntity::class, RoundEntity::class, RoundEventEntity::class, DebugSampleEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun settingsDao(): SettingsDao
    abstract fun roundDao(): RoundDao
    abstract fun debugSampleDao(): DebugSampleDao

    companion object {
        const val NAME = "weile_liuzhou.db"
    }
}

// -------------------------------------------------- 实体 ↔ 领域映射（纯函数）

internal fun SettingsEntity.toSettings(): com.weile.liuzhou.mahjong.analysis.AnalysisSettings =
    com.weile.liuzhou.mahjong.analysis.AnalysisSettings(
        hasDragons = hasDragons,
        mode = com.weile.liuzhou.mahjong.domain.storage.modeFromName(mode),
        analysisIntervalMs = analysisIntervalMs,
    )

internal fun com.weile.liuzhou.mahjong.analysis.AnalysisSettings.toEntity(): SettingsEntity =
    SettingsEntity(
        hasDragons = hasDragons,
        mode = com.weile.liuzhou.mahjong.domain.storage.modeName(mode),
        analysisIntervalMs = analysisIntervalMs,
    )

internal fun RoundEntity.toRecord(): RoundRecord =
    RoundRecord(id, roundIndex, startedAt, endedAt, eventCount, note)

internal fun RoundRecord.toEntity(): RoundEntity =
    RoundEntity(id, roundIndex, startedAt, endedAt, eventCount, note)

internal fun DebugSampleEntity.toRecord(): DebugSampleRecord =
    DebugSampleRecord(id, filePath, tag, savedAt)

internal fun DebugSampleRecord.toEntity(): DebugSampleEntity =
    DebugSampleEntity(id, filePath, tag, savedAt)

internal fun StoredEvent.toEntity(roundId: Long): RoundEventEntity =
    RoundEventEntity(0, roundId, seq, type, seat, tileCode, fromSeat, extra)

internal fun RoundEventEntity.toStored(): StoredEvent =
    StoredEvent(seq, type, seat, tileCode, fromSeat, extra)
