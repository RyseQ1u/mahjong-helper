package com.weile.liuzhou.mahjong.domain.settlement

import com.weile.liuzhou.mahjong.domain.game.Seat
import com.weile.liuzhou.mahjong.domain.mahjong.TileCounts
import com.weile.liuzhou.mahjong.domain.rules.KanKind
import com.weile.liuzhou.mahjong.domain.rules.LiuzhouHandEvaluator
import com.weile.liuzhou.mahjong.domain.rules.LiuzhouPattern
import com.weile.liuzhou.mahjong.domain.rules.WinContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 计分与结算测试（纯 JVM）。
 *
 * **核心断言**：已确认规则给确定分值；2.3 冲突分支一律「暂不支持」
 * （points=null + 原因），且含暂不支持项时 confirmedTotal 为 null。
 */
class LiuzhouScoringPolicyTest {

    private val policy = LiuzhouScoringPolicy()
    private val settlement = LiuzhouSettlement(policy)

    private fun hand(spec: String) = TileCounts.parse(spec)

    private fun eval(
        spec: String,
        selfDraw: Boolean = false,
        concealed: Boolean = false,
        isLastTile: Boolean? = null,
        afterKan: KanKind? = null,
        robbingKan: Boolean = false,
    ) = LiuzhouHandEvaluator.evaluate(
        hand(spec),
        emptyList(),
        WinContext(selfDraw, concealed, isLastTile, afterKan, robbingKan),
    )

    // -------------------------------------------------- 已确认分值

    @Test
    fun `基本胡点炮一分点炮者付`() {
        val lines = policy.scoreWin(eval("123m456p789s11s234s"), Seat.SELF, discarder = Seat.TOP)
        val base = lines.first { it.component == ScoreComponent.HU_POINTS && it.points != null }
        assertEquals(1, base.points)
        assertEquals(Seat.TOP, base.payer)
    }

    @Test
    fun `基本胡自摸每家一分`() {
        val lines = policy.scoreWin(
            eval("123m456p789s11s234s", selfDraw = true), Seat.SELF, discarder = null
        )
        val base = lines.first { it.label.contains("自摸") }
        assertEquals(1, base.points)
        assertNull(base.payer) // 每家均付
    }

    @Test
    fun `对对胡点炮三分`() {
        val lines = policy.scoreWin(eval("111m222p333s444s55s"), Seat.SELF, discarder = Seat.LEFT)
        val base = lines.first { it.component == ScoreComponent.HU_POINTS && it.points != null }
        assertEquals(3, base.points)
        assertEquals(Seat.LEFT, base.payer)
    }

    @Test
    fun `清一色点炮三分`() {
        val lines = policy.scoreWin(eval("111m222m333m444m55m"), Seat.SELF, discarder = Seat.LEFT)
        val base = lines.first { it.component == ScoreComponent.HU_POINTS && it.points != null }
        assertEquals(3, base.points)
    }

    @Test
    fun `小七对点炮三分`() {
        val lines = policy.scoreWin(eval("1122m3344p5566s7s7s"), Seat.SELF, discarder = Seat.TOP)
        val base = lines.first { it.component == ScoreComponent.HU_POINTS && it.points != null }
        assertEquals(3, base.points)
    }

    @Test
    fun `抢杠基本胡点炮翻倍`() {
        val lines = policy.scoreWin(
            eval("123m456p789s11s234s", robbingKan = true), Seat.SELF, discarder = Seat.TOP
        )
        val robbing = lines.first { it.label.contains("抢杠") }
        assertEquals(2, robbing.points)
    }

    @Test
    fun `抢杠基本胡点炮单条确定行`() {
        val lines = policy.scoreWin(
            eval("123m456p789s11s234s", robbingKan = true), Seat.SELF, discarder = Seat.TOP
        )
        // 1 × 2 = 2，只有一条确定胡牌分行（不再叠加 +2 行）
        val confirmed = lines.filter { it.component == ScoreComponent.HU_POINTS && it.points != null }
        assertEquals(1, confirmed.size)
        assertEquals(2, confirmed.first().points)
        assertEquals(Seat.TOP, confirmed.first().payer)
    }

    @Test
    fun `杠上花与海底捞月倍率叠加相乘`() {
        val lines = policy.scoreWin(
            eval("123m456p789s11s234s", selfDraw = true, afterKan = KanKind.ANKAN, isLastTile = true),
            Seat.SELF, discarder = null
        )
        // 基本胡自摸 1 × 杠上花 2 × 海底 2 = 4，单条确定行，无「+2 叠加」痕迹
        val confirmed = lines.filter { it.component == ScoreComponent.HU_POINTS && it.points != null }
        assertEquals(1, confirmed.size)
        assertEquals(4, confirmed.first().points)
        assertTrue(confirmed.first().label.contains("杠上花"))
        assertTrue(confirmed.first().label.contains("海底捞月"))
    }

    @Test
    fun `杠上花自摸倍率应用`() {
        val lines = policy.scoreWin(
            eval("123m456p789s11s234s", selfDraw = true, afterKan = KanKind.ANKAN),
            Seat.SELF, discarder = null
        )
        val confirmed = lines.filter { it.component == ScoreComponent.HU_POINTS && it.points != null }
        assertEquals(1, confirmed.size)
        assertEquals(2, confirmed.first().points)
    }

    @Test
    fun `抢杠叠加大番型暂不支持`() {
        val lines = policy.scoreWin(
            eval("111m222p333s444s55s", robbingKan = true), Seat.SELF, discarder = Seat.TOP
        )
        assertTrue(lines.none { it.component == ScoreComponent.HU_POINTS && it.points != null })
        assertTrue(lines.any { it.points == null && it.label.contains("抢杠") })
    }

    // -------------------------------------------------- 暂不支持分支

    @Test
    fun `门清自摸暂不支持`() {
        val lines = policy.scoreWin(
            eval("123m456p789s11s234s", selfDraw = true, concealed = true),
            Seat.SELF, discarder = null
        )
        val menqing = lines.first { it.label == "门清" }
        assertNull(menqing.points)
        assertTrue(menqing.unsupportedReason!!.contains("2.3"))
    }

    @Test
    fun `小七对自摸每家四分与上限冲突暂不支持`() {
        val lines = policy.scoreWin(
            eval("1122m3344p5566s7s7s", selfDraw = true), Seat.SELF, discarder = null
        )
        assertTrue(lines.any { it.points == null && it.label.contains("自摸") })
        assertTrue(lines.any { it.unsupportedReason?.contains("上限") == true })
    }

    @Test
    fun `杠上炮暂不支持`() {
        val lines = policy.scoreWin(
            eval("123m456p789s11s234s", selfDraw = false, afterKan = KanKind.MINKAN),
            Seat.SELF, discarder = Seat.TOP
        )
        assertTrue(lines.any { it.label == "杠上炮" && it.points == null })
    }

    @Test
    fun `海底无法判定暂不支持`() {
        val lines = policy.scoreWin(
            eval("123m456p789s11s234s", selfDraw = true, isLastTile = null),
            Seat.SELF, discarder = null
        )
        assertTrue(lines.any { it.label == "海底捞月" && it.points == null })
    }

    @Test
    fun `全求人分值暂不支持`() {
        val melds4 = List(4) {
            com.weile.liuzhou.mahjong.domain.game.OpenMeld(
                com.weile.liuzhou.mahjong.domain.game.MeldType.PON,
                listOf(com.weile.liuzhou.mahjong.domain.mahjong.Tile.manzu(1 + it * 2)),
                Seat.TOP,
            )
        }
        val e4 = LiuzhouHandEvaluator.evaluate(hand("9m"), melds4, WinContext(false, false))
        assertTrue(e4.patterns.contains(LiuzhouPattern.QUANQIUREN))
        val lines = policy.scoreWin(e4, Seat.SELF, discarder = Seat.TOP)
        assertTrue(lines.any { it.points == null && it.unsupportedReason!!.contains("全求人") })
    }

    // -------------------------------------------------- 吃三比 / 杠分 / 钓鱼

    @Test
    fun `吃三比达成三次支付暂不支持但次数可靠`() {
        val ledger = ActionLedger()
        ledger.record(LedgerEventType.CHI, Seat.RIGHT, TileCounts.parse("1m").toSortedTiles().first(), fromSeat = Seat.TOP)
        ledger.record(LedgerEventType.PON, Seat.RIGHT, TileCounts.parse("5m").toSortedTiles().first(), fromSeat = Seat.TOP)
        ledger.record(LedgerEventType.MINKAN, Seat.RIGHT, TileCounts.parse("9m").toSortedTiles().first(), fromSeat = Seat.TOP)
        val line = policy.scoreChiSanBi(ledger, Seat.RIGHT)
        assertNull(line.points)
        assertTrue(line.label.contains("3"))
    }

    @Test
    fun `吃三比未达成零分`() {
        val ledger = ActionLedger()
        ledger.record(LedgerEventType.CHI, Seat.RIGHT, TileCounts.parse("1m").toSortedTiles().first(), fromSeat = Seat.TOP)
        val line = policy.scoreChiSanBi(ledger, Seat.RIGHT)
        assertEquals(0, line.points)
    }

    @Test
    fun `暗杠每家一分明杠记额度支付待核实`() {
        val ledger = ActionLedger()
        ledger.record(LedgerEventType.ANKAN, Seat.SELF, TileCounts.parse("1m").toSortedTiles().first())
        ledger.record(LedgerEventType.MINKAN, Seat.SELF, TileCounts.parse("5m").toSortedTiles().first(), fromSeat = Seat.TOP)
        val lines = policy.scoreKan(ledger)
        val ankan = lines.first { it.label.contains("暗杠") }
        assertEquals(1, ankan.points)
        val minkan = lines.first { it.label.contains("明杠") }
        assertEquals(1, minkan.points)
        assertTrue(minkan.unsupportedReason!!.contains("支付"))
    }

    @Test
    fun `补杠分暂不支持`() {
        val ledger = ActionLedger()
        ledger.record(LedgerEventType.SHOUMINKAN, Seat.SELF, TileCounts.parse("1m").toSortedTiles().first())
        val line = policy.scoreKan(ledger).first()
        assertNull(line.points)
    }

    @Test
    fun `钓鱼分暂不支持`() {
        val line = policy.scoreDiaoYu()
        assertNull(line.points)
        assertTrue(line.unsupportedReason!!.contains("钓鱼"))
    }

    // -------------------------------------------------- 结算编排

    @Test
    fun `结算按顺序编排且含暂不支持时不给总分`() {
        val ledger = ActionLedger()
        ledger.record(LedgerEventType.ANKAN, Seat.SELF, TileCounts.parse("1m").toSortedTiles().first())
        ledger.record(LedgerEventType.DISCARD, Seat.TOP, TileCounts.parse("9m").toSortedTiles().first())

        val report = settlement.settle(
            eval("123m456p789s11s234s", selfDraw = true, concealed = true),
            ledger, winner = Seat.SELF, discarder = null,
        )
        // 组件顺序：胡牌分 -> 吃三比分 -> 杠牌分 -> 钓鱼分
        val order = report.lines.map { it.component }.distinct()
        assertEquals(
            listOf(ScoreComponent.HU_POINTS, ScoreComponent.CHI_SAN_BI, ScoreComponent.KAN_POINTS, ScoreComponent.DIAO_YU),
            order,
        )
        // 门清/钓鱼未确认 → 不给虚假总分
        assertTrue(report.hasUnsupported)
        assertNull(report.confirmedTotal)
    }

    @Test
    fun `钓鱼未确认故不给出合计`() {
        val ledger = ActionLedger()
        ledger.record(LedgerEventType.DISCARD, Seat.TOP, TileCounts.parse("9m").toSortedTiles().first())
        val report = settlement.settle(
            eval("123m456p789s11s234s", selfDraw = false),
            ledger, winner = Seat.SELF, discarder = Seat.TOP,
        )
        // 钓鱼规则未给出 → 永远存在暂不支持项 → 不给虚假精确总分（有意的安全默认）
        assertTrue(report.hasUnsupported)
        assertNull(report.confirmedTotal)
        // 但确认的行仍然给出确定值
        assertTrue(report.lines.any { it.points == 1 })
    }
}
