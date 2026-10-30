package com.weile.liuzhou.mahjong.domain.analysis

import com.weile.liuzhou.mahjong.domain.game.DiscardRecord
import com.weile.liuzhou.mahjong.domain.game.GamePhase
import com.weile.liuzhou.mahjong.domain.game.GameState
import com.weile.liuzhou.mahjong.domain.game.MeldType
import com.weile.liuzhou.mahjong.domain.game.OpenMeld
import com.weile.liuzhou.mahjong.domain.game.Seat
import com.weile.liuzhou.mahjong.domain.mahjong.Tile
import com.weile.liuzhou.mahjong.domain.mahjong.TileCounts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DefaultRecommendationEngine 测试（纯 JVM）。
 * 铁律验证：识别不确定 → 「等待确认」；置信度不称为胜率；模式排序差异。
 */
class RecommendationEngineTest {

    private val engine = DefaultRecommendationEngine()

    private fun hand(spec: String) = TileCounts.parse(spec).toSortedTiles()

    @Test
    fun `守恒冲突时等待确认`() {
        val state = GameState(
            phase = GamePhase.OWN_TURN,
            ownHand = hand("123m456p789s11s22s9m"),
            needConfirmation = true,
            confirmationReason = "牌 一万 已见 5 张",
        )
        val advice = engine.advise(state, AnalysisMode.BALANCED)
        assertTrue(advice.waitingConfirmation)
        assertTrue(advice.confirmationHint!!.contains("一万"))
        assertTrue(advice.candidates.isEmpty())
        assertFalse(advice.canWin)
    }

    @Test
    fun `手牌有未识别牌时等待确认`() {
        val state = GameState(
            phase = GamePhase.OWN_TURN,
            ownHand = listOf(Tile.manzu(1), null, Tile.manzu(3)),
        )
        val advice = engine.advise(state, AnalysisMode.BALANCED)
        assertTrue(advice.waitingConfirmation)
        assertTrue(advice.confirmationHint!!.contains("1 张未识别"))
        // 置信度 = 2/3
        assertEquals(2f / 3f, advice.recognitionConfidence, 1e-4f)
    }

    @Test
    fun `非自己回合不出建议`() {
        val state = GameState(phase = GamePhase.PLAYING, ownHand = hand("123m456p789s11s22s"))
        val advice = engine.advise(state, AnalysisMode.BALANCED)
        assertFalse(advice.waitingConfirmation)
        assertTrue(advice.candidates.isEmpty())
        assertFalse(advice.canWin)
    }

    @Test
    fun `已和牌提示自摸`() {
        val state = GameState(
            phase = GamePhase.OWN_TURN,
            ownHand = hand("123m456m789s123s11s"),
        )
        val advice = engine.advise(state, AnalysisMode.BALANCED)
        assertTrue(advice.canWin)
        assertTrue(advice.candidates.isEmpty())
        assertEquals(1f, advice.recognitionConfidence, 1e-6f)
    }

    @Test
    fun `候选最多三个且按向听升序`() {
        val state = GameState(
            phase = GamePhase.OWN_TURN,
            ownHand = hand("123m456p789s11s22s9m9p"), // 14 张
        )
        val advice = engine.advise(state, AnalysisMode.BALANCED)
        assertTrue(advice.candidates.isNotEmpty())
        assertTrue(advice.candidates.size <= 3)
        val shantens = advice.candidates.map { it.shantenAfterDiscard }
        assertEquals(shantens.sorted(), shantens)
        // 每条候选都带识别置信度（非胜率）
        advice.candidates.forEach { assertEquals(1f, it.recognitionConfidence, 1e-6f) }
    }

    @Test
    fun `速度模式与安全模式给出不同首选`() {
        // 手牌 123m456s11s + 2p3p + 5p + 789p（14 张，未和）：
        // 打 5p 保留 23p 搭子（进张 2 种，向听 0）；打 2p 保留 35p 嵌张（进张 1 种，向听 0）。
        // 弃牌区已有 3 张二筒 → 安全模式倾向打二筒，速度模式倾向进张更广的五筒。
        val state = GameState(
            phase = GamePhase.OWN_TURN,
            ownHand = hand("123m456s11s2p3p5p7p8p9p"),
            discards = listOf(
                DiscardRecord(Tile.pinzu(2), Seat.TOP, 1),
                DiscardRecord(Tile.pinzu(2), Seat.LEFT, 2),
                DiscardRecord(Tile.pinzu(2), Seat.RIGHT, 3),
            ),
        )
        val speed = engine.advise(state, AnalysisMode.SPEED).candidates.first().tile
        val balanced = engine.advise(state, AnalysisMode.BALANCED).candidates.first().tile
        val safety = engine.advise(state, AnalysisMode.SAFETY).candidates.first().tile
        assertEquals(Tile.pinzu(5), speed)   // 进张最广
        assertEquals(Tile.pinzu(5), balanced) // 综合仍看进张权重
        assertEquals(Tile.pinzu(2), safety)  // 已见 3 张更安全
    }

    @Test
    fun `无箭牌模式下箭牌手牌等待确认`() {
        val state = GameState(
            phase = GamePhase.OWN_TURN,
            ownHand = hand("123m456p789s11s22s1z"), // 含红中
        )
        val advice = engine.advise(state, AnalysisMode.BALANCED, hasDragons = false)
        assertTrue(advice.waitingConfirmation)
        assertTrue(advice.confirmationHint!!.contains("箭牌开关"))
    }

    @Test
    fun `决策只按自家副露计算`() {
        // 自家 1 副露 + 手牌 3 面子 + 将 = 和牌；若误用全场副露总数（3）则无法和牌。
        val state = GameState(
            phase = GamePhase.OWN_TURN,
            ownHand = hand("456m789m111s55s"),
            melds = mapOf(
                Seat.SELF to listOf(OpenMeld(MeldType.PON, List(3) { Tile.manzu(1) }, Seat.TOP)),
                Seat.TOP to listOf(OpenMeld(MeldType.PON, List(3) { Tile.pinzu(2) }, Seat.LEFT)),
                Seat.LEFT to listOf(OpenMeld(MeldType.PON, List(3) { Tile.pinzu(3) }, Seat.TOP)),
            ),
        )
        val advice = engine.advise(state, AnalysisMode.BALANCED)
        assertTrue(advice.canWin)
    }

    @Test
    fun `摘要文本不声称胜率`() {
        val state = GameState(
            phase = GamePhase.OWN_TURN,
            ownHand = hand("123m456p789s11s22s9m9p"),
        )
        val advice = engine.advise(state, AnalysisMode.BALANCED)
        val summary = advice.summary()
        assertFalse(summary.contains("胜率"))
    }

    @Test
    fun `空状态安全返回`() {
        val advice = engine.advise(GameState.initial(), AnalysisMode.BALANCED)
        assertNull(advice.candidates.firstOrNull())
        assertFalse(advice.canWin)
        assertFalse(advice.waitingConfirmation)
    }
}
