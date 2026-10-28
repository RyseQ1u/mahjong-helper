package com.weile.liuzhou.mahjong.domain.mahjong

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandEvaluatorTest {

    // ------------------------------------------------------------ 和牌与向听

    @Test
    fun `十四张和牌`() {
        // 123m 456m 789s 123s 11s（4 面子 + 将）
        val hand = TileCounts.parse("123m456m789s123s11s")
        val result = HandEvaluator.evaluate(hand)
        assertTrue(result.canWin)
        assertEquals(-1, result.shanten)
        assertTrue(result.patterns.contains(HandEvaluator.HandPattern.BASIC))
    }

    @Test
    fun `十三张听牌`() {
        // 123m 456m 789m 11s 23s：将 11s + 23s 两面搭子，听 1s/4s
        val hand = TileCounts.parse("123m456m789m11s23s")
        assertEquals(0, HandEvaluator.shanten(hand))
        assertFalse(HandEvaluator.canWin(hand))
    }

    @Test
    fun `听牌有效进张`() {
        // 23s 两面听 1s / 4s
        val hand = TileCounts.parse("123m456m789m11s23s")
        val draws = HandEvaluator.effectiveDraws(hand)
        assertTrue(draws.contains(Tile.souzu(1)))
        assertTrue(draws.contains(Tile.souzu(4)))
        assertFalse(draws.contains(Tile.manzu(5)))
    }

    @Test
    fun `加一张即和`() {
        // 听牌状态下加 1s 直接和牌
        val hand = TileCounts.parse("123m456m789m11s23s")
        hand.add(Tile.souzu(1))
        assertTrue(HandEvaluator.canWin(hand))
        assertEquals(-1, HandEvaluator.shanten(hand))
    }

    @Test
    fun `三顺加将加浮张一向听`() {
        // 123m 456m 789m 11s 9p 1p：3 顺 + 将 + 两张不成搭的浮张
        val hand = TileCounts.parse("123m456m789m11s9p1p")
        assertEquals(1, HandEvaluator.shanten(hand))
    }

    @Test
    fun `搭子加浮张一向听`() {
        // 123m 456m 11s 23p 9p：2 顺 + 将 + 搭子 + 浮张
        val hand = TileCounts.parse("123m456m11s23p9p")
        assertEquals(1, HandEvaluator.shanten(hand))
    }

    @Test
    fun `七对和牌`() {
        val hand = TileCounts.parse("1122m3344p5566s7s7s")
        assertTrue(HandEvaluator.canWin(hand))
        val result = HandEvaluator.evaluate(hand)
        assertTrue(result.patterns.contains(HandEvaluator.HandPattern.SEVEN_PAIRS))
    }

    @Test
    fun `七对听牌`() {
        // 六对 + 单张 = 听最后一张
        val hand = TileCounts.parse("1122m3344p5566s7s")
        assertEquals(13, hand.total)
        assertEquals(0, HandEvaluator.shanten(hand))
    }

    @Test
    fun `碰碰胡`() {
        val hand = TileCounts.parse("111m222p333s444s55s")
        assertTrue(HandEvaluator.canWin(hand))
        assertTrue(
            HandEvaluator.patterns(hand).contains(HandEvaluator.HandPattern.ALL_TRIPLETS)
        )
    }

    @Test
    fun `清一色`() {
        val hand = TileCounts.parse("111m222m333m444m55m")
        assertTrue(
            HandEvaluator.patterns(hand).contains(HandEvaluator.HandPattern.PURE_ONE_SUIT)
        )
    }

    @Test
    fun `混色不算清一色`() {
        val hand = TileCounts.parse("111m222m333m444p55p")
        assertFalse(
            HandEvaluator.patterns(hand).contains(HandEvaluator.HandPattern.PURE_ONE_SUIT)
        )
    }

    @Test
    fun `带副露的和牌`() {
        // 手牌 11 张 + 1 副露：456m 789m 111s 55s + 副露 123p = 4 面子 + 将
        val hand = TileCounts.parse("456m789m111s55s")
        assertTrue(hand.validate(melds = 1) is TileCounts.ValidationResult.Ok)
        assertTrue(HandEvaluator.canWin(hand, melds = 1))
    }

    // ------------------------------------------------------------ 舍牌排序

    @Test
    fun `舍牌候选按向听升序`() {
        // 123m 456m 789m 11s 23s 5p：打 5p 保持听牌
        val hand = TileCounts.parse("123m456m789m11s23s5p")
        assertEquals(14, hand.total)
        val options = HandEvaluator.discardCandidates(hand)
        assertTrue(options.isNotEmpty())
        // 排序后向听非递减
        val shantens = options.map { it.shantenAfterDiscard }
        assertEquals(shantens.sorted(), shantens)
        // 最优候选应把 5p 打掉（保持 0 向听）
        assertEquals(Tile.pinzu(5), options.first().tile)
        assertEquals(0, options.first().shantenAfterDiscard)
    }

    @Test
    fun `舍牌候选数量等于不同牌种类数`() {
        val hand = TileCounts.parse("111m222p333s444s55s")
        // 5 种牌 → 5 个候选
        assertEquals(5, HandEvaluator.discardCandidates(hand).size)
    }

    // ------------------------------------------------------------ 进张过滤

    @Test
    fun `剩余可见张数过滤进张`() {
        val hand = TileCounts.parse("123m456m789m11s23s")
        // 假设 1s 已全部可见（自家 1 张 + 弃牌 3 张），则 1s 不再是进张
        val remaining: (Tile) -> Int = { t ->
            if (t == Tile.souzu(1)) 0 else 4
        }
        val draws = HandEvaluator.effectiveDraws(hand, remaining = remaining)
        assertFalse(draws.contains(Tile.souzu(1)))
        assertTrue(draws.contains(Tile.souzu(4)))
    }

    @Test
    fun `剩余可见计算`() {
        val core = DefaultMahjongCore
        // 红中：自家 0 + 弃牌 1 + 副露 0 + 其他已知 0 → 剩 3
        assertEquals(3, core.remainingVisible(Tile.dragon(0), 0, 1, 0, 0))
        // 全部可见 → 0
        assertEquals(0, core.remainingVisible(Tile.manzu(1), 1, 3, 0, 0))
        // 不超过 4
        assertEquals(4, core.remainingVisible(Tile.manzu(1), 0, 0, 0, 0))
    }

    // ------------------------------------------------------------ 校验

    @Test
    fun `张数不匹配时评估标记无效`() {
        val hand = TileCounts.parse("123m456p789s11s2s") // 13 张
        val result = HandEvaluator.evaluate(hand, melds = 1) // 与副露数矛盾
        assertFalse(result.tileCountValid)
        assertTrue(result.validationError != null)
    }
}
