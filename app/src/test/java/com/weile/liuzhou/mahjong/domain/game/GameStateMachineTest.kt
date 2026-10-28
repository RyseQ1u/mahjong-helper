package com.weile.liuzhou.mahjong.domain.game

import com.weile.liuzhou.mahjong.domain.mahjong.Tile
import com.weile.liuzhou.mahjong.domain.mahjong.TileCounts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TileLedger 守恒与剩余可见张数测试（纯 JVM）。
 */
class TileLedgerTest {

    private fun hand(spec: String): List<Tile> = TileCounts.parse(spec).toSortedTiles()

    @Test
    fun `剩余可见张数计算`() {
        val state = GameState(
            phase = GamePhase.PLAYING,
            ownHand = hand("19m19p19s1234567z"), // 无一万
            discards = listOf(
                DiscardRecord(Tile.manzu(1), Seat.TOP, 1),
                DiscardRecord(Tile.manzu(1), Seat.LEFT, 2),
            ),
            melds = mapOf(
                Seat.RIGHT to listOf(
                    OpenMeld(MeldType.PON, listOf(Tile.manzu(2), Tile.manzu(2), Tile.manzu(2)))
                )
            ),
        )
        val ledger = TileLedger(state)
        // 一万：自家 1 张 + 弃牌 2 张 → 已见 3，剩 1
        assertEquals(1, ledger.remainingVisible(Tile.manzu(1)))
        // 二万：副露 3 张 → 剩 1
        assertEquals(1, ledger.remainingVisible(Tile.manzu(2)))
        // 九条：自家 1 张 → 剩 3
        assertEquals(3, ledger.remainingVisible(Tile.souzu(9)))
        assertTrue(ledger.isValid)
    }

    @Test
    fun `被副露消耗的弃牌不重复计数`() {
        val state = GameState(
            phase = GamePhase.PLAYING,
            ownHand = emptyList(),
            discards = listOf(
                DiscardRecord(Tile.manzu(5), Seat.TOP, 1, consumedByMeld = true),
            ),
            melds = mapOf(
                Seat.RIGHT to listOf(
                    OpenMeld(MeldType.PON, List(3) { Tile.manzu(5) }, fromSeat = Seat.TOP)
                )
            ),
        )
        val ledger = TileLedger(state)
        // 五万：3 张在副露，弃牌已被消耗不计 → 已见 3，剩 1
        assertEquals(1, ledger.remainingVisible(Tile.manzu(5)))
        assertEquals(3, ledger.seenCount(Tile.manzu(5)))
        assertEquals(0, ledger.discardCount(Tile.manzu(5)))
        assertTrue(ledger.isValid)
    }

    @Test
    fun `超过四张报守恒违规`() {
        val state = GameState(
            phase = GamePhase.PLAYING,
            ownHand = hand("1111m234p567s78s9s"), // 一万 ×4
            discards = listOf(DiscardRecord(Tile.manzu(1), Seat.TOP, 1)), // 第五张
        )
        val ledger = TileLedger(state)
        assertFalse(ledger.isValid)
        val violations = ledger.violations()
        assertEquals(1, violations.size)
        assertTrue(violations.first().contains("一万"))
    }
}

/**
 * DefaultGameStateMachine 帧差事件推导测试（合成帧序列，纯 JVM）。
 * 对应 P3 验收：连续回放不重复计数；未知牌时停止并允许人工修正；可回溯到最后可靠状态。
 */
class GameStateMachineTest {

    private fun hand(spec: String): List<Tile> = TileCounts.parse(spec).toSortedTiles()

    /** 13 张标准手牌。 */
    private fun hand13(): List<Tile> = hand("123m456p789s11s22s")

    private fun obs(
        phase: GamePhase = GamePhase.PLAYING,
        ownHand: List<Tile?> = emptyList(),
        ownDrawn: Tile? = null,
        discardPiles: Map<Seat, List<Tile>> = emptyMap(),
        openMelds: Map<Seat, List<OpenMeld>> = emptyMap(),
        unknownRatio: Float = 0f,
    ) = FrameObservation(phase, ownHand, ownDrawn, discardPiles, openMelds, unknownRatio)

    @Test
    fun `首帧基线不产生事件`() {
        val m = DefaultGameStateMachine()
        val result = m.observe(
            obs(
                ownHand = hand13(),
                discardPiles = mapOf(Seat.TOP to listOf(Tile.manzu(1), Tile.manzu(2))),
            )
        )
        assertTrue(result is ObserveResult.Applied)
        assertTrue((result as ObserveResult.Applied).events.isEmpty())
        assertEquals(13, m.snapshot().ownHand.size)
        assertEquals(2, m.snapshot().discards.size)
        assertEquals(13, m.snapshot().handCounts[Seat.SELF])
    }

    @Test
    fun `弃牌去抖不重复计数`() {
        val m = DefaultGameStateMachine()
        // 基线：对家已出 1m
        m.observe(obs(ownHand = hand13(), discardPiles = mapOf(Seat.TOP to listOf(Tile.manzu(1)))))

        // 同一弃牌持续三帧 → 只应记录一次
        repeat(3) {
            val r = m.observe(obs(discardPiles = mapOf(Seat.TOP to listOf(Tile.manzu(1)))))
            assertTrue((r as ObserveResult.Applied).events.isEmpty())
        }
        assertEquals(1, m.snapshot().discards.size)

        // 新弃牌追加
        val r = m.observe(obs(discardPiles = mapOf(Seat.TOP to listOf(Tile.manzu(1), Tile.manzu(2)))))
        val events = (r as ObserveResult.Applied).events
        assertEquals(1, events.size)
        assertTrue(events[0] is GameEvent.Discarded)
        assertEquals(Tile.manzu(2), (events[0] as GameEvent.Discarded).tile)
        assertEquals(2, m.snapshot().discards.size)
    }

    @Test
    fun `摸牌与自家出牌事件`() {
        val m = DefaultGameStateMachine()
        m.observe(obs(ownHand = hand13()))

        // 摸牌：手牌 13 → 14
        val drawn = Tile.souzu(9)
        val r1 = m.observe(obs(ownHand = hand13() + drawn))
        val e1 = (r1 as ObserveResult.Applied).events
        assertEquals(1, e1.size)
        assertTrue(e1[0] is GameEvent.Drew)
        assertEquals(drawn, (e1[0] as GameEvent.Drew).tile)

        // 出牌：手牌 14 → 13（打出摸来的那张）
        val r2 = m.observe(obs(ownHand = hand13()))
        val e2 = (r2 as ObserveResult.Applied).events
        assertEquals(1, e2.size)
        assertTrue(e2[0] is GameEvent.Discarded)
        assertEquals(Seat.SELF, (e2[0] as GameEvent.Discarded).seat)
        // 出牌已记入自家弃牌区
        assertEquals(listOf(drawn), m.snapshot().discardsOf(Seat.SELF))

        // 下一帧弃牌区显示出过的牌 → 不重复计数
        val r3 = m.observe(obs(discardPiles = mapOf(Seat.SELF to listOf(drawn))))
        assertTrue((r3 as ObserveResult.Applied).events.isEmpty())
        assertEquals(1, m.snapshot().discards.size)
    }

    @Test
    fun `副露来源归属且弃牌不重复计数`() {
        val m = DefaultGameStateMachine()
        val fiveM = Tile.manzu(5)
        // 基线：对家弃 5m；下家手牌 13
        m.observe(
            obs(
                discardPiles = mapOf(Seat.TOP to listOf(fiveM)),
                openMelds = mapOf(Seat.RIGHT to listOf(OpenMeld(MeldType.PON, List(3) { fiveM }))),
            )
        )
        // 注意：基线帧同时含弃牌与副露——副露作为基线，来源为空（中途进入不脑补）
        assertEquals(1, m.snapshot().meldsOf(Seat.RIGHT).size)
        assertTrue(m.snapshot().meldsOf(Seat.RIGHT)[0].fromSeat == null)

        // 新场景（另起状态机，按事件顺序推导来源）
        val m2 = DefaultGameStateMachine()
        m2.observe(obs(ownHand = hand13()))
        // 对家出 5m
        m2.observe(obs(discardPiles = mapOf(Seat.TOP to listOf(fiveM))))
        // 下家碰 5m
        val r = m2.observe(
            obs(openMelds = mapOf(Seat.RIGHT to listOf(OpenMeld(MeldType.PON, List(3) { fiveM }))))
        )
        val events = (r as ObserveResult.Applied).events
        assertEquals(1, events.size)
        val meldEvent = events[0] as GameEvent.MeldDeclared
        assertEquals(Seat.RIGHT, meldEvent.seat)
        assertEquals(Seat.TOP, meldEvent.sourceSeat)

        // 来源弃牌被消耗：弃牌区不再计入，账本不重复
        assertTrue(m2.snapshot().discardsOf(Seat.TOP).isEmpty())
        val ledger = TileLedger(m2.snapshot())
        assertEquals(3, ledger.seenCount(fiveM)) // 仅副露 3 张
        assertEquals(1, ledger.remainingVisible(fiveM))
        // 下家暗牌数：13 - 3*1 = 10
        assertEquals(10, m2.snapshot().handCounts[Seat.RIGHT])
    }

    @Test
    fun `守恒失败回滚并请求人工确认`() {
        val m = DefaultGameStateMachine()
        // 自家手牌含 4 张一万
        m.observe(obs(ownHand = hand("1111m234p567s78s9s")))
        val before = m.snapshot()

        // 对家再弃一张一万 → 全场 5 张，守恒失败
        val r = m.observe(obs(discardPiles = mapOf(Seat.TOP to listOf(Tile.manzu(1)))))
        assertTrue(r is ObserveResult.NeedsConfirmation)
        // 已回滚：手牌/弃牌/副露与最后可靠状态一致，第五张一万不在弃牌中
        assertEquals(before.ownHand, m.snapshot().ownHand)
        assertEquals(before.discards, m.snapshot().discards)
        assertTrue(m.snapshot().needConfirmation)
        assertNotNull(m.snapshot().confirmationReason)
        assertTrue(m.snapshot().discards.isEmpty())

        // 等待确认期间后续帧被冻结
        val r2 = m.observe(obs(discardPiles = mapOf(Seat.TOP to listOf(Tile.manzu(2)))))
        assertTrue(r2 is ObserveResult.Frozen)
        assertEquals(FreezeReason.AWAITING_CONFIRMATION, (r2 as ObserveResult.Frozen).reason)
        assertTrue(m.snapshot().discards.isEmpty())
    }

    @Test
    fun `人工修正手牌后恢复追踪`() {
        val m = DefaultGameStateMachine()
        m.observe(obs(ownHand = hand("1111m234p567s78s9s")))
        m.observe(obs(discardPiles = mapOf(Seat.TOP to listOf(Tile.manzu(1)))))
        assertTrue(m.snapshot().needConfirmation)

        // 用户修正：一万其实只有 3 张（识别错误），换一张九条
        val fixed = hand("111m234p567s78s9s") + Tile.souzu(9)
        assertTrue(m.correctOwnHand(fixed.sorted()))
        assertFalse(m.snapshot().needConfirmation)

        // 恢复后正常推导
        val r = m.observe(obs(discardPiles = mapOf(Seat.TOP to listOf(Tile.manzu(2)))))
        assertTrue(r is ObserveResult.Applied)
        assertEquals(1, m.snapshot().discards.size)
    }

    @Test
    fun `动画与未知阶段冻结`() {
        val m = DefaultGameStateMachine()
        m.observe(obs(ownHand = hand13()))
        val before = m.snapshot()

        // 动画帧带新弃牌 → 冻结，状态不变
        val r1 = m.observe(
            obs(phase = GamePhase.ANIMATION, discardPiles = mapOf(Seat.TOP to listOf(Tile.manzu(1))))
        )
        assertTrue(r1 is ObserveResult.Frozen)
        assertEquals(FreezeReason.ANIMATION, (r1 as ObserveResult.Frozen).reason)
        assertEquals(before, m.snapshot())

        // 未知帧 → 冻结
        val r2 = m.observe(obs(phase = GamePhase.UNKNOWN))
        assertTrue(r2 is ObserveResult.Frozen)
        assertEquals(FreezeReason.UNKNOWN_PHASE, (r2 as ObserveResult.Frozen).reason)

        // 解冻后该弃牌被正常记录（只一次）
        val r3 = m.observe(obs(discardPiles = mapOf(Seat.TOP to listOf(Tile.manzu(1)))))
        assertTrue(r3 is ObserveResult.Applied)
        assertEquals(1, m.snapshot().discards.size)
    }

    @Test
    fun `高未知率冻结`() {
        val m = DefaultGameStateMachine()
        m.observe(obs(ownHand = hand13()))
        val r = m.observe(obs(discardPiles = mapOf(Seat.TOP to listOf(Tile.manzu(1))), unknownRatio = 0.8f))
        assertTrue(r is ObserveResult.Frozen)
        assertEquals(FreezeReason.HIGH_UNKNOWN_RATIO, (r as ObserveResult.Frozen).reason)
        assertTrue(m.snapshot().discards.isEmpty())
    }

    @Test
    fun `结算自动重置且不重复重置`() {
        val m = DefaultGameStateMachine()
        m.observe(obs(ownHand = hand13()))
        m.observe(obs(discardPiles = mapOf(Seat.TOP to listOf(Tile.manzu(1)))))
        assertEquals(1, m.snapshot().discards.size)

        // 结算 → 自动重置，局数 +1
        val r1 = m.observe(obs(phase = GamePhase.SETTLEMENT))
        assertTrue(r1 is ObserveResult.Applied)
        val e1 = (r1 as ObserveResult.Applied).events
        assertEquals(1, e1.size)
        assertTrue(e1[0] is GameEvent.RoundReset)
        assertEquals(2, m.snapshot().roundIndex)
        assertTrue(m.snapshot().discards.isEmpty())

        // 连续结算帧不重复重置（无事可做，无新事件）
        val r2 = m.observe(obs(phase = GamePhase.SETTLEMENT))
        assertTrue(r2 is ObserveResult.Applied)
        assertTrue((r2 as ObserveResult.Applied).events.isEmpty())
        assertEquals(2, m.snapshot().roundIndex)

        // 准备阶段再次重置，局数 +1
        val r3 = m.observe(obs(phase = GamePhase.PREPARING))
        assertTrue(r3 is ObserveResult.Applied)
        assertEquals(3, m.snapshot().roundIndex)
    }

    @Test
    fun `手牌含未知牌时跳出手牌差异但弃牌仍更新`() {
        val m = DefaultGameStateMachine()
        m.observe(obs(ownHand = hand13()))

        // 一张牌变模糊（null），同时对家出新弃牌
        val blurred: MutableList<Tile?> = ArrayList<Tile?>(hand13())
        blurred[0] = null
        val r = m.observe(
            obs(ownHand = blurred, discardPiles = mapOf(Seat.TOP to listOf(Tile.manzu(1))))
        )
        val events = (r as ObserveResult.Applied).events
        // 只有弃牌事件，没有摸牌/出牌事件（防止把模糊误判为出牌）
        assertEquals(1, events.size)
        assertTrue(events[0] is GameEvent.Discarded)
        assertEquals(1, m.snapshot().discards.size)
        // 手牌展示保留 null（UI 显示「请确认」）
        assertTrue(m.snapshot().ownHand.contains(null))
        assertEquals(13, m.snapshot().ownHand.size)
    }

    @Test
    fun `最后可靠状态可回溯`() {
        val m = DefaultGameStateMachine()
        m.observe(obs(ownHand = hand13()))
        m.observe(obs(discardPiles = mapOf(Seat.TOP to listOf(Tile.manzu(1)))))
        val reliable = m.lastReliableSnapshot()
        assertEquals(m.snapshot(), reliable)
        assertEquals(1, reliable.discards.size)
    }

    @Test
    fun `暗杠从手牌移除四张`() {
        val m = DefaultGameStateMachine()
        // 手牌 3 张一万 + 其他
        m.observe(obs(ownHand = hand("111m234p567s78s9s1p")))
        // 暗杠一万（摸到第四张后杠）
        val r = m.observe(
            obs(openMelds = mapOf(Seat.SELF to listOf(OpenMeld(MeldType.ANKAN, List(4) { Tile.manzu(1) }))))
        )
        val events = (r as ObserveResult.Applied).events
        assertEquals(1, events.size)
        assertTrue(events[0] is GameEvent.MeldDeclared)
        // 手牌中的 3 张一万被移入副露，不产生出牌事件
        assertTrue(events.none { it is GameEvent.Discarded })
        assertTrue(m.snapshot().ownHand.none { it == Tile.manzu(1) })
        assertEquals(1, m.snapshot().meldsOf(Seat.SELF).size)
    }
}
