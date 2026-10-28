package com.weile.liuzhou.mahjong.domain.settlement

import com.weile.liuzhou.mahjong.domain.game.Seat
import com.weile.liuzhou.mahjong.domain.mahjong.Tile

/**
 * 事件账本（ActionLedger，P5）。
 *
 * 方案第 2.3 节实现要求：吃碰杠及来源必须保存历史动作——吃三比「从同一玩家来源
 * 累计三次吃、碰、直接明杠」不能仅通过牌型判断；杠分是独立即时台账；
 * 海底捞月需要可靠的最后一张事件判定。
 *
 * 只记录**已确认发生**的事件（置信度不足时上层不应入账）。
 */
enum class LedgerEventType {
    /** 摸牌。 */
    DRAW,

    /** 出牌（弃牌）。 */
    DISCARD,

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

    /** 和牌。 */
    WIN,
}

/** 一条账本事件。 */
data class LedgerEvent(
    val type: LedgerEventType,
    val seat: Seat,
    val tile: Tile?,
    /** 副露来源座位（吃/碰/明杠来自哪家弃牌；暗杠/自摸事件为 null）。 */
    val fromSeat: Seat? = null,
    /** 全局单调序号。 */
    val seq: Long,
)

/** 事件账本。 */
class ActionLedger {

    private val events = mutableListOf<LedgerEvent>()
    private var seqCounter = 0L

    /** 记录一条事件（自动分配序号）。 */
    fun record(type: LedgerEventType, seat: Seat, tile: Tile?, fromSeat: Seat? = null): LedgerEvent {
        val event = LedgerEvent(type, seat, tile, fromSeat, ++seqCounter)
        events += event
        return event
    }

    /** 全部事件（只读视图，按序号有序）。 */
    fun events(): List<LedgerEvent> = events.toList()

    /** 清空（新一局）。 */
    fun reset() {
        events.clear()
        seqCounter = 0L
    }

    // -------------------------------------------------- 吃三比

    /**
     * 某座位从**同一来源**累计的吃/碰/直接明杠次数（方案 2.2 吃三比）。
     * 暗杠与补杠不计入（规则写明天「吃、碰、直接明杠」；补杠是否计入见 2.3 第 5 条，暂不计）。
     */
    fun meldCountFromSameSource(seat: Seat, source: Seat): Int =
        events.count {
            it.seat == seat && it.fromSeat == source &&
                (it.type == LedgerEventType.CHI || it.type == LedgerEventType.PON || it.type == LedgerEventType.MINKAN)
        }

    /** 某座位是否达成吃三比（同来源累计 3 次）。 */
    fun hasChiSanBi(seat: Seat, source: Seat): Boolean =
        meldCountFromSameSource(seat, source) >= 3

    // -------------------------------------------------- 杠分台账

    /** 全部杠事件（独立即时杠分台账，方案 2.2）。 */
    fun kanEvents(): List<LedgerEvent> =
        events.filter {
            it.type == LedgerEventType.MINKAN ||
                it.type == LedgerEventType.ANKAN ||
                it.type == LedgerEventType.SHOUMINKAN
        }

    /** 最后一次摸牌事件（海底捞月判定的基础；无则 null）。 */
    fun lastDraw(): LedgerEvent? = events.lastOrNull { it.type == LedgerEventType.DRAW }

    /** 最后一次和牌事件。 */
    fun lastWin(): LedgerEvent? = events.lastOrNull { it.type == LedgerEventType.WIN }

    /**
     * 最后一张判定：和牌是否为牌墙最后一张摸牌。
     * 仅在「和牌事件紧邻摸牌事件且此前无未结算弃牌」时返回 true/false，
     * 否则返回 null（无法可靠判定 —— 方案 2.2 要求可靠判定才能用海底捞月）。
     */
    fun isLastTileWinReliable(): Boolean? {
        val win = lastWin() ?: return null
        val draw = lastDraw() ?: return null
        // 和牌必须是自摸且摸牌紧邻其前
        if (draw.seq + 1 != win.seq || draw.seat != win.seat) return null
        // 摸牌之后不能还有别的事件（保险）
        if (events.any { it.seq > draw.seq && it.seq < win.seq }) return null
        return true
    }
}
