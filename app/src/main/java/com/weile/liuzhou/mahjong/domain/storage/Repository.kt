package com.weile.liuzhou.mahjong.domain.storage

import com.weile.liuzhou.mahjong.analysis.AnalysisSettings
import com.weile.liuzhou.mahjong.domain.analysis.AnalysisMode
import com.weile.liuzhou.mahjong.domain.game.GameEvent
import com.weile.liuzhou.mahjong.domain.game.Seat
import com.weile.liuzhou.mahjong.domain.mahjong.Tile

/**
 * 本地存储（storage 模块，P5 之后的增强项）。
 *
 * 方案第 3 节：Room 持久化规则偏好、事件记录、识别样本与调试日志。
 * 隐私铁律（方案第 5 节）：默认本地处理，不上传屏幕/昵称/头像/牌局截图；
 * 调试样本**只记元数据**（路径/标签/时间），不把图像内容写进数据库。
 */

/** 规则偏好仓库（对局配置持久化）。 */
interface SettingsRepository {
    /** 读取偏好；无记录时返回默认 [AnalysisSettings]。 */
    suspend fun load(): AnalysisSettings

    /** 保存偏好。 */
    suspend fun save(settings: AnalysisSettings)
}

/** 一局历史记录（P3 回溯用）。 */
data class RoundRecord(
    val id: Long = 0,
    val roundIndex: Int,
    val startedAt: Long,
    val endedAt: Long,
    val eventCount: Int,
    val note: String? = null,
)

/** 调试样本元数据（不存图像内容）。 */
data class DebugSampleRecord(
    val id: Long = 0,
    val filePath: String,
    val tag: String,
    val savedAt: Long,
)

/** 牌局历史仓库（局 + 事件流）。 */
interface RoundHistoryRepository {
    /** 保存一局（含完整事件流）。 */
    suspend fun saveRound(record: RoundRecord, events: List<GameEvent>)

    /** 最近若干局（新的在前）。 */
    suspend fun recentRounds(limit: Int = 20): List<RoundRecord>

    /** 某局的完整事件流（按 seq 有序）。 */
    suspend fun eventsOf(roundId: Long): List<GameEvent>
}

/** 调试样本元数据仓库。 */
interface DebugSampleRepository {
    suspend fun record(sample: DebugSampleRecord)
    suspend fun recent(limit: Int = 50): List<DebugSampleRecord>
}

// -------------------------------------------------- 映射（纯函数，可 JVM 测试）

/** 牌局事件 ↔ 扁平存储结构的映射（Room 实体不存密封类，展平成字段）。 */
data class StoredEvent(
    val seq: Int,
    val type: String,
    val seat: String,
    /** 牌编码；无牌事件（阶段变化/重置）为 null。 */
    val tileCode: Int?,
    /** 副露来源座位；无来源为 null。 */
    val fromSeat: String?,
    /** 附加信息（如重置原因、副露类型）。 */
    val extra: String? = null,
)

/** [GameEvent] → [StoredEvent]。 */
fun GameEvent.toStored(seq: Int): StoredEvent = when (this) {
    is GameEvent.Drew -> StoredEvent(seq, "DREW", seatName(Seat.SELF), tile.code, null)
    is GameEvent.Discarded -> StoredEvent(seq, "DISCARD", seatName(seat), tile.code, null)
    is GameEvent.MeldDeclared -> StoredEvent(
        seq, "MELD", seatName(seat), meld.tiles.firstOrNull()?.code,
        sourceSeat?.let { seatName(it) }, meld.type.name,
    )
    is GameEvent.PhaseChanged -> StoredEvent(
        seq, "PHASE", seatName(Seat.SELF), null, null,
        "${from.name}->${to.name}",
    )
    is GameEvent.RoundReset -> StoredEvent(seq, "RESET", seatName(Seat.SELF), null, null, reason)
}

/** [StoredEvent] → [GameEvent]；无法识别（或牌码越界）时返回 null。 */
fun StoredEvent.toGameEvent(): GameEvent? {
    val seatEnum = seatFromName(seat) ?: return null
    val tile = tileCode?.let { if (it in 0..Tile.MAX_CODE) Tile(it) else null }
    return when (type) {
        "DREW" -> tile?.let { GameEvent.Drew(it) }
        "DISCARD" -> tile?.let { GameEvent.Discarded(seatEnum, it) }
        "MELD" -> tile?.let {
            GameEvent.MeldDeclared(
                seatEnum,
                com.weile.liuzhou.mahjong.domain.game.OpenMeld(
                    runCatching {
                        com.weile.liuzhou.mahjong.domain.game.MeldType.valueOf(extra ?: "PON")
                    }.getOrDefault(com.weile.liuzhou.mahjong.domain.game.MeldType.PON),
                    listOf(it),
                    fromSeat?.let { n -> seatFromName(n) },
                ),
                fromSeat?.let { n -> seatFromName(n) },
            )
        }
        "PHASE" -> {
            // extra 形如 "FROM->TO"；解析失败时用 UNKNOWN
            val parts = (extra ?: "").split("->")
            val from = parts.getOrNull(0)?.let { name ->
                runCatching { com.weile.liuzhou.mahjong.domain.game.GamePhase.valueOf(name) }
                    .getOrDefault(com.weile.liuzhou.mahjong.domain.game.GamePhase.INIT)
            } ?: com.weile.liuzhou.mahjong.domain.game.GamePhase.INIT
            val to = parts.getOrNull(1)?.let { name ->
                runCatching { com.weile.liuzhou.mahjong.domain.game.GamePhase.valueOf(name) }
                    .getOrDefault(com.weile.liuzhou.mahjong.domain.game.GamePhase.UNKNOWN)
            } ?: com.weile.liuzhou.mahjong.domain.game.GamePhase.UNKNOWN
            GameEvent.PhaseChanged(from, to)
        }
        "RESET" -> GameEvent.RoundReset(extra ?: "")
        else -> null
    }
}

/** 座位名（存储用）。 */
fun seatName(seat: Seat): String = seat.name

/** 座位名 → 座位。 */
fun seatFromName(name: String): Seat? = Seat.entries.firstOrNull { it.name == name }

/** 事件流 → 展平结构（保序，seq 从 0 递增）。 */
fun List<GameEvent>.toStoredList(): List<StoredEvent> =
    mapIndexed { index, event -> event.toStored(index) }

/** 展平结构 → 事件流（无法识别的条目被过滤）。 */
fun List<StoredEvent>.toGameEvents(): List<GameEvent> =
    mapNotNull { it.toGameEvent() }

/** 模式名（存储用）。 */
fun modeName(mode: AnalysisMode): String = mode.name

/** 模式名 → 模式；无法识别时回退 BALANCED。 */
fun modeFromName(name: String?): AnalysisMode =
    AnalysisMode.entries.firstOrNull { it.name == name } ?: AnalysisMode.BALANCED
