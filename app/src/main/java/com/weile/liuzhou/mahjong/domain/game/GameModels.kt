package com.weile.liuzhou.mahjong.domain.game

import com.weile.liuzhou.mahjong.domain.mahjong.Tile

/**
 * 牌局连续追踪（game-state 模块，P3）的数据模型。
 *
 * 方案第 3.1 节要求：在动画与 UNKNOWN 期间停止自动更新可疑数据；跟踪 4 家座位、
 * 弃牌顺序、每个副露来源、杠事件；校验每种牌物理张数不超过 4；置信度不足时显示
 * 「请确认」，不能带着错误状态继续推算；数据可回溯到最后一个可靠状态。
 *
 * 座位约定（方案第 1 节画面描述：自家在底部，其他玩家在左/上/右）：
 * ```
 *          TOP（对家）
 *   LEFT（上家）   RIGHT（下家）
 *          SELF（自家）
 * ```
 * 出牌顺序（逆时针）：SELF → RIGHT → TOP → LEFT → SELF。
 */

/**
 * 牌局阶段（方案第 3.1 节）。
 *
 * - INIT：初始/未知开局
 * - PREPARING：准备（出现「准备」提示，应自动重置）
 * - DEALING：发牌
 * - PLAYING：对局进行中
 * - OWN_TURN：轮到自己出牌（触发完整识别与舍牌建议）
 * - ANIMATION：动画/特效中（冻结可疑数据更新）
 * - WIN：胡牌
 * - SETTLEMENT：结算/过渡画面（应自动重置）
 * - UNKNOWN：无法判定（冻结，显示「请确认」）
 */
enum class GamePhase {
    INIT,
    PREPARING,
    DEALING,
    PLAYING,
    OWN_TURN,
    ANIMATION,
    WIN,
    SETTLEMENT,
    UNKNOWN,
}

/** 四家座位。 */
enum class Seat {
    /** 自家（屏幕底部）。 */
    SELF,

    /** 下家（屏幕右侧，自家下一位出牌）。 */
    RIGHT,

    /** 对家（屏幕顶部）。 */
    TOP,

    /** 上家（屏幕左侧，自家上一位出牌）。 */
    LEFT,
    ;

    /** 下一家（逆时针）。 */
    fun next(): Seat = when (this) {
        SELF -> RIGHT
        RIGHT -> TOP
        TOP -> LEFT
        LEFT -> SELF
    }
}

/** 副露类型。 */
enum class MeldType {
    /** 吃。 */
    CHI,

    /** 碰。 */
    PON,

    /** 明杠（直杠）。 */
    MINKAN,

    /** 暗杠。 */
    ANKAN,

    /** 补杠（加杠）。 */
    SHOUMINKAN,
    ;

    /** 该副露从手牌中取走的张数（补杠只加 1 张，其余取 2/3/4 张中的手牌部分）。 */
    val tilesFromHand: Int
        get() = when (this) {
            CHI, PON -> 2
            MINKAN -> 3
            ANKAN -> 4
            SHOUMINKAN -> 1
        }

    /** 是否从他人弃牌触发（暗杠否）。 */
    val fromDiscard: Boolean
        get() = this != ANKAN
}

/** 公开副露。 */
data class OpenMeld(
    val type: MeldType,
    /** 副露的牌（按牌面排序便于比较）。 */
    val tiles: List<Tile>,
    /** 来源座位（吃/碰/明杠来自哪家弃牌；暗杠为 null）。 */
    val fromSeat: Seat? = null,
)

/** 一条弃牌记录。 */
data class DiscardRecord(
    val tile: Tile,
    val seat: Seat,
    /** 全局单调递增序号，用于排序与来源查找。 */
    val seq: Long,
    /** 是否已被副露消耗（从弃牌区移入副露，避免重复计数）。 */
    val consumedByMeld: Boolean = false,
)

/**
 * 牌局状态快照（不可变）。
 *
 * @param ownHand 自家手牌，null 元素表示该张未被识别（置信度不足）
 * @param discards 全局弃牌流（含被副露消耗的记录，按 seq 有序）
 * @param handCounts 各家暗牌张数（自家取 ownHand.size；其他家按 13-3*melds 近似，见实现注释）
 */
data class GameState(
    val phase: GamePhase,
    val ownHand: List<Tile?> = emptyList(),
    val ownDrawn: Tile? = null,
    val discards: List<DiscardRecord> = emptyList(),
    val melds: Map<Seat, List<OpenMeld>> = emptyMap(),
    val handCounts: Map<Seat, Int> = Seat.entries.associateWith { 13 },
    /** 本局有序事件流（回溯用）。 */
    val events: List<GameEvent> = emptyList(),
    val needConfirmation: Boolean = false,
    val confirmationReason: String? = null,
    val roundIndex: Int = 1,
) {
    /** 当前有效（未被副露消耗）的弃牌，按顺序。 */
    fun activeDiscards(): List<DiscardRecord> = discards.filter { !it.consumedByMeld }

    /** 某座位当前的弃牌区（有效）。 */
    fun discardsOf(seat: Seat): List<Tile> =
        activeDiscards().filter { it.seat == seat }.map { it.tile }

    /** 某座位的副露列表。 */
    fun meldsOf(seat: Seat): List<OpenMeld> = melds[seat].orEmpty()

    /** 全场副露组数。 */
    val totalMelds: Int get() = melds.values.sumOf { it.size }

    companion object {
        fun initial(roundIndex: Int = 1): GameState = GameState(
            phase = GamePhase.INIT,
            roundIndex = roundIndex,
        )
    }
}

/** 牌局事件（有序事件流，供 UI 展示与回溯）。 */
sealed interface GameEvent {
    /** 自家摸牌。 */
    data class Drew(val tile: Tile) : GameEvent

    /** 某家出牌（弃牌）。 */
    data class Discarded(val seat: Seat, val tile: Tile) : GameEvent

    /** 副露声明（含来源座位；暗杠来源为 null）。 */
    data class MeldDeclared(
        val seat: Seat,
        val meld: OpenMeld,
        val sourceSeat: Seat?,
    ) : GameEvent

    /** 阶段变化。 */
    data class PhaseChanged(val from: GamePhase, val to: GamePhase) : GameEvent

    /** 新一局重置。 */
    data class RoundReset(val reason: String) : GameEvent
}

/** 单帧观测输入（由 vision 层适配 [com.weile.liuzhou.mahjong.domain.vision.RecognitionResult] 得到）。 */
data class FrameObservation(
    /** 画面阶段（由 ScreenState 映射）。 */
    val phase: GamePhase,
    /** 自家手牌（有序；null = 未识别）。 */
    val ownHand: List<Tile?> = emptyList(),
    /** 自家单独摆放的摸牌（若有）。 */
    val ownDrawn: Tile? = null,
    /** 各座位弃牌区（按出牌顺序）。 */
    val discardPiles: Map<Seat, List<Tile>> = emptyMap(),
    /** 各座位可见副露。 */
    val openMelds: Map<Seat, List<OpenMeld>> = emptyMap(),
    /** 本帧未知牌率（P1 指标）。 */
    val unknownRatio: Float = 0f,
    /** 帧时间戳（ms）。 */
    val timestampMs: Long = 0L,
)

/** 观测处理结果。 */
sealed interface ObserveResult {
    /** 已应用：本帧推导出的事件与新状态。 */
    data class Applied(
        val events: List<GameEvent>,
        val state: GameState,
    ) : ObserveResult

    /** 被冻结：状态未变（方案 3.1：动画/UNKNOWN 期间停止更新可疑数据）。 */
    data class Frozen(
        val reason: FreezeReason,
        val state: GameState,
    ) : ObserveResult

    /** 需要人工确认：守恒校验失败，已回滚到最后可靠状态。 */
    data class NeedsConfirmation(
        val violations: List<String>,
        val state: GameState,
    ) : ObserveResult
}

/** 冻结原因。 */
enum class FreezeReason {
    ANIMATION,
    UNKNOWN_PHASE,
    HIGH_UNKNOWN_RATIO,
    AWAITING_CONFIRMATION,
}
