package com.weile.liuzhou.mahjong.domain.game

import com.weile.liuzhou.mahjong.domain.mahjong.Tile

/**
 * 牌局状态机（game-state 模块，P3 实现）。
 *
 * 与方案第 3.1 节一致：
 * - 在动画和 UNKNOWN 期间停止自动更新可疑数据（冻结，不改状态）；
 * - 跟踪 4 家座位、弃牌顺序、每个副露来源、杠事件；
 * - 弃牌被吃碰后从弃牌区转入公开副露时**不重复计数**（consumedByMeld 标记）；
 * - 校验每种牌物理张数不超过 4；校验失败回滚到最后可靠状态并请求人工确认；
 * - 在游戏准备、结算或新一局时自动重置；
 * - 单帧不能推断来源与先后（方案 3.2 第 3 点）——事件推导基于帧间差异与去抖。
 *
 * ## 帧差事件推导规则
 * 1. **弃牌区差异**：把各座位观察到的弃牌区与状态中「有效弃牌」按多重集合比对，
 *    多出来的部分按观察顺序记为新弃牌。持续出现的弃牌不会重复计数（去抖）。
 * 2. **副露对账**：观察到的副露若不在状态中即为新副露；从其他座位**最近的未消耗弃牌**
 *    中查找来源牌并归属（后进先出，副露通常消耗最新弃牌），该弃牌标记 consumedByMeld。
 * 3. **自家手牌差异**：新增 → 摸牌事件；减少 → 出牌事件。若减少的牌属于本帧新副露
 *    （进了副露）或与本帧弃牌区差异同一次出牌，则不重复记事件。
 *
 * ## 已知近似（文档化，不脑补）
 * - 其他三家的暗牌张数按「休息位 13 - 3*副露数」估算，不追踪其摸牌（不可观测）；
 * - 暗杠补来的替代牌、别家摸牌时机不做精确推断；
 * - 以上近似只影响 informational 的 handCounts，不影响守恒校验与进张计算
 *   （后者只依赖自家手牌 + 弃牌 + 副露）。
 */
interface GameStateMachine {
    fun currentPhase(): GamePhase

    /** 手动切换阶段（UI 覆盖用）；进入准备/结算/胡牌阶段会触发自动重置。 */
    fun transitionTo(phase: GamePhase)

    /** 手动重置（清空本局）。 */
    fun reset(reason: String = "manual")

    /** 当前状态快照。 */
    fun snapshot(): GameState

    /** 最后一个通过守恒校验的可靠状态（回溯用）。 */
    fun lastReliableSnapshot(): GameState

    /** 喂入一帧观测，返回处理结果。 */
    fun observe(frame: FrameObservation): ObserveResult

    /**
     * 人工修正自家手牌（识别不确定时由用户确认/纠错）。
     * @return 修正后是否通过守恒校验（false 表示仍矛盾，继续保持等待确认）
     */
    fun correctOwnHand(tiles: List<Tile?>): Boolean

    /** 人工确认当前状态（解除等待确认冻结）。 */
    fun confirmState(): Boolean
}

/** [GameStateMachine] 的默认实现。 */
class DefaultGameStateMachine : GameStateMachine {

    private var phase: GamePhase = GamePhase.INIT
    private var state: GameState = GameState.initial()
    private var lastReliable: GameState = state
    private var awaitingConfirmation = false
    private var seqCounter = 0L
    private var lastAutoResetPhase: GamePhase? = null

    /**
     * 基线是否已采纳。
     * 第一帧带数据的观测只作为基线（可能是牌局中途进入/刚发完牌），
     * 不产生摸牌/弃牌事件——避免把历史状态当成新事件。
     */
    private var baselineEstablished = false

    override fun currentPhase(): GamePhase = phase

    override fun transitionTo(newPhase: GamePhase) {
        if (newPhase == phase) return
        phase = newPhase
        if (newPhase == GamePhase.PREPARING || newPhase == GamePhase.SETTLEMENT || newPhase == GamePhase.WIN) {
            resetInternal("transition to $newPhase", incrementRound = false)
        } else {
            state = state.copy(phase = newPhase)
        }
    }

    override fun reset(reason: String) {
        resetInternal(reason, incrementRound = false)
    }

    override fun snapshot(): GameState = state

    override fun lastReliableSnapshot(): GameState = lastReliable

    override fun observe(frame: FrameObservation): ObserveResult {
        // 0) 冻结闸门（方案 3.1：动画/UNKNOWN/等待确认期间停止更新可疑数据）
        if (awaitingConfirmation) {
            return ObserveResult.Frozen(FreezeReason.AWAITING_CONFIRMATION, state)
        }
        if (frame.phase == GamePhase.ANIMATION) {
            return ObserveResult.Frozen(FreezeReason.ANIMATION, state)
        }
        if (frame.phase == GamePhase.UNKNOWN) {
            return ObserveResult.Frozen(FreezeReason.UNKNOWN_PHASE, state)
        }
        if (frame.unknownRatio > UNKNOWN_RATIO_LIMIT) {
            return ObserveResult.Frozen(FreezeReason.HIGH_UNKNOWN_RATIO, state)
        }

        // 1) 自动重置（准备/结算/胡牌 → 新一局）。同一阶段连续帧只重置一次。
        if (frame.phase == GamePhase.PREPARING || frame.phase == GamePhase.SETTLEMENT || frame.phase == GamePhase.WIN) {
            val alreadyReset = lastAutoResetPhase == frame.phase && state.discards.isEmpty()
            if (!alreadyReset) {
                val round = state.roundIndex + 1
                resetInternal("auto: ${frame.phase}", incrementRound = false)
                state = state.copy(roundIndex = round)
                lastAutoResetPhase = frame.phase
                lastReliable = state
                return ObserveResult.Applied(listOf(GameEvent.RoundReset("auto: ${frame.phase}")), state)
            }
            // 已重置过：本阶段无事可做
            return ObserveResult.Applied(emptyList(), state)
        }
        lastAutoResetPhase = null

        // 2) 阶段推进
        if (frame.phase != phase) {
            val from = phase
            phase = frame.phase
            state = state.copy(phase = frame.phase)
            if (from != GamePhase.INIT) {
                state = state.withEvent(GameEvent.PhaseChanged(from, frame.phase))
            }
        }

        val handAtFrameStart = state.ownHand
        val events = mutableListOf<GameEvent>()
        val selfDiscardsThisFrame = mutableListOf<Tile>()

        // 2.5) 基线采纳：首帧数据只作基线，不产生事件
        val hasData = frame.ownHand.isNotEmpty() || frame.discardPiles.isNotEmpty() ||
            frame.openMelds.isNotEmpty()
        if (!baselineEstablished && hasData) {
            baselineEstablished = true
            state = state.copy(
                phase = frame.phase,
                ownHand = frame.ownHand,
                ownDrawn = frame.ownDrawn,
                discards = buildBaselineDiscards(frame.discardPiles),
                melds = frame.openMelds,
            )
            refreshHandCounts()
            lastReliable = state
            return ObserveResult.Applied(emptyList(), state)
        }

        // 3) 弃牌区差异（先于手牌差异：自家出牌可能先体现在弃牌区）
        for ((seat, observed) in frame.discardPiles) {
            for (tile in diffDiscardPile(seat, observed)) {
                seqCounter++
                state = state.copy(
                    discards = state.discards + DiscardRecord(tile, seat, seqCounter)
                )
                events += GameEvent.Discarded(seat, tile)
                if (seat == Seat.SELF) selfDiscardsThisFrame += tile
            }
        }

        // 4) 副露对账（来源归属 + 消耗弃牌 + 自家手牌移出计算）
        val movedFromHand = reconcileMelds(frame, events)

        // 5) 自家手牌差异（摸牌/出牌事件）
        diffOwnHand(frame, handAtFrameStart, movedFromHand, selfDiscardsThisFrame, events)

        // 6) 事件写入 + 手牌张数刷新
        state = events.fold(state) { s, e -> s.withEvent(e) }
        refreshHandCounts()

        // 7) 守恒校验：失败 → 回滚到最后可靠状态并请求人工确认
        val violations = TileLedger(state).violations()
        if (violations.isNotEmpty()) {
            state = lastReliable.copy(
                needConfirmation = true,
                confirmationReason = violations.first(),
            )
            awaitingConfirmation = true
            return ObserveResult.NeedsConfirmation(violations, state)
        }

        state = state.copy(needConfirmation = false, confirmationReason = null)
        lastReliable = state
        return ObserveResult.Applied(events, state)
    }

    override fun correctOwnHand(tiles: List<Tile?>): Boolean {
        val candidate = state.copy(ownHand = tiles)
        val violations = TileLedger(candidate).violations()
        return if (violations.isEmpty()) {
            state = candidate.copy(needConfirmation = false, confirmationReason = null)
            awaitingConfirmation = false
            lastReliable = state
            true
        } else {
            state = candidate.copy(needConfirmation = true, confirmationReason = violations.first())
            false
        }
    }

    override fun confirmState(): Boolean {
        if (!TileLedger(state).isValid) return false
        awaitingConfirmation = false
        state = state.copy(needConfirmation = false, confirmationReason = null)
        lastReliable = state
        return true
    }

    /** 把首帧观察到的弃牌区转成基线弃牌记录（按座位内观察顺序编号）。 */
    private fun buildBaselineDiscards(piles: Map<Seat, List<Tile>>): List<DiscardRecord> {
        val records = mutableListOf<DiscardRecord>()
        for ((seat, tiles) in piles) {
            for (tile in tiles) {
                seqCounter++
                records += DiscardRecord(tile, seat, seqCounter)
            }
        }
        return records
    }

    // -------------------------------------------------- 内部：弃牌区差异

    /**
     * 多重集合比对：观察弃牌区中「多于当前有效弃牌」的部分即新弃牌（按观察顺序）。
     * 被副露消耗的弃牌不计入有效弃牌；若画面仍显示已消耗的弃牌（识别滞后），
     * 可能产生幽灵弃牌——由守恒校验拦截并请求人工确认（安全默认）。
     */
    private fun diffDiscardPile(seat: Seat, observed: List<Tile>): List<Tile> {
        val activeCounts = state.discards
            .filter { it.seat == seat && !it.consumedByMeld }
            .groupingBy { it.tile }
            .eachCount()
            .toMutableMap()
        val seen = mutableMapOf<Tile, Int>()
        val newTiles = mutableListOf<Tile>()
        for (tile in observed) {
            val count = (seen[tile] ?: 0) + 1
            seen[tile] = count
            val active = activeCounts[tile] ?: 0
            if (count > active) {
                newTiles += tile
                activeCounts[tile] = active + 1
            }
        }
        return newTiles
    }

    // -------------------------------------------------- 内部：副露对账

    /** 对账新副露，返回「本帧因副露从自家手牌移出的牌」（供手牌差异排除）。 */
    private fun reconcileMelds(frame: FrameObservation, events: MutableList<GameEvent>): List<Tile> {
        val movedFromHand = mutableListOf<Tile>()
        val known = state.melds.values.flatten()
        for ((seat, melds) in frame.openMelds) {
            for (meld in melds) {
                if (known.any { it == meld }) continue
                // 新副露：查找来源弃牌（后进先出）
                val source = if (meld.type.fromDiscard) findSourceDiscard(seat, meld) else null
                if (source != null) {
                    state = state.copy(
                        discards = state.discards.map {
                            if (it.seq == source.seq) it.copy(consumedByMeld = true) else it
                        }
                    )
                }
                state = state.copy(melds = state.melds + (seat to (state.meldsOf(seat) + meld)))
                events += GameEvent.MeldDeclared(seat, meld, source?.seat)
                if (seat == Seat.SELF) {
                    // 自家副露：牌从手牌移入副露（立即生效，避免手牌与副露重复计数）；
                    // 返回值供手牌差异排除这些“消失”。
                    val moved = meldTilesFromHand(meld, source?.tile, state.ownHand)
                    state = state.copy(ownHand = removeTiles(state.ownHand, moved))
                    movedFromHand += moved
                }
            }
        }
        return movedFromHand
    }

    /** 从其他座位最近的未消耗弃牌中找来源（副露通常消耗最新弃牌）。 */
    private fun findSourceDiscard(seat: Seat, meld: OpenMeld): DiscardRecord? =
        state.discards
            .filter { !it.consumedByMeld && it.seat != seat && it.tile in meld.tiles }
            .maxByOrNull { it.seq }

    /** 从手牌中移除指定牌（多重集合语义，保留未识别 null）。 */
    private fun removeTiles(hand: List<Tile?>, tiles: List<Tile>): List<Tile?> {
        val result = hand.toMutableList()
        for (t in tiles) {
            val idx = result.indexOfFirst { it == t }
            if (idx >= 0) result.removeAt(idx)
        }
        return result
    }

    /** 该副露中来自手牌的牌（受手牌实际张数约束）。 */
    private fun meldTilesFromHand(meld: OpenMeld, calledTile: Tile?, hand: List<Tile?>): List<Tile> {        val handCounts = hand.filterNotNull().groupingBy { it }.eachCount()
        return when (meld.type) {
            MeldType.ANKAN -> {
                val t = meld.tiles.first()
                List(minOf(MeldType.ANKAN.tilesFromHand, handCounts[t] ?: 0)) { t }
            }
            MeldType.SHOUMINKAN -> {
                val t = meld.tiles.first()
                if ((handCounts[t] ?: 0) > 0) listOf(t) else emptyList()
            }
            else -> {
                val fromHand = meld.tiles.toMutableList()
                if (calledTile != null) fromHand.remove(calledTile)
                fromHand.filter { (handCounts[it] ?: 0) > 0 }
            }
        }
    }

    // -------------------------------------------------- 内部：自家手牌差异

    /**
     * 手牌多重集合差异：
     * - 新增 → 摸牌；
     * - 减少 → 出牌（除非属于本帧新副露，或已由本帧弃牌区差异记录同一次出牌）。
     */
    private fun diffOwnHand(
        frame: FrameObservation,
        handAtFrameStart: List<Tile?>,
        movedFromHand: List<Tile>,
        selfDiscardsThisFrame: List<Tile>,
        events: MutableList<GameEvent>,
    ) {
        // 本帧未报手牌（空列表）= 无数据，保持状态不变（不是“手牌清零”）。
        if (frame.ownHand.isEmpty()) {
            state = state.copy(ownDrawn = frame.ownDrawn)
            return
        }
        // 手牌存在未识别牌时不做多重集合差异（防止把“变模糊”误判为出牌），只更新展示。
        if (frame.ownHand.any { it == null }) {
            state = state.copy(ownHand = frame.ownHand, ownDrawn = frame.ownDrawn)
            refreshHandCounts()
            return
        }

        // 观察手牌 = 识别手牌 + 单独摆放的摸牌（若未包含在名单中）
        val observed = frame.ownHand.toMutableList()
        if (frame.ownDrawn != null && observed.none { it == frame.ownDrawn }) {
            observed += frame.ownDrawn
        }

        val prevKnown = handAtFrameStart.filterNotNull()
        val currKnown = observed.filterNotNull()

        // 移除集合 = (prev - curr) - movedFromHand
        val removals = mutableListOf<Tile>()
        val currCounts = currKnown.groupingBy { it }.eachCount().toMutableMap()
        val movedCounts = movedFromHand.groupingBy { it }.eachCount().toMutableMap()
        for (t in prevKnown) {
            val c = currCounts[t] ?: 0
            if (c > 0) {
                currCounts[t] = c - 1
            } else {
                val m = movedCounts[t] ?: 0
                if (m > 0) movedCounts[t] = m - 1 else removals += t
            }
        }

        // 新增集合 = curr - prev
        val additions = mutableListOf<Tile>()
        val prevCounts = prevKnown.groupingBy { it }.eachCount().toMutableMap()
        for (t in currKnown) {
            val c = prevCounts[t] ?: 0
            if (c > 0) prevCounts[t] = c - 1 else additions += t
        }

        for (t in additions) events += GameEvent.Drew(t)

        // 减少的牌：先匹配本帧弃牌区差异记录的自家出牌（同一次事件，不重复计数）
        val pendingSelfDiscards = selfDiscardsThisFrame.toMutableList()
        for (t in removals) {
            val idx = pendingSelfDiscards.indexOf(t)
            if (idx >= 0) {
                pendingSelfDiscards.removeAt(idx)
            } else {
                seqCounter++
                state = state.copy(
                    discards = state.discards + DiscardRecord(t, Seat.SELF, seqCounter)
                )
                events += GameEvent.Discarded(Seat.SELF, t)
            }
        }

        // 手牌展示更新为观察值（保留 null = 未识别，供 UI 显示「请确认」）
        state = state.copy(ownHand = observed, ownDrawn = frame.ownDrawn)
    }

    // -------------------------------------------------- 内部：张数刷新与重置

    /** 自家按实际手牌张数；其他家按休息位近似 13 - 3*副露数。 */
    private fun refreshHandCounts() {
        val counts = state.handCounts.toMutableMap()
        counts[Seat.SELF] = state.ownHand.size
        for (seat in Seat.entries) {
            if (seat == Seat.SELF) continue
            counts[seat] = 13 - 3 * state.meldsOf(seat).size
        }
        state = state.copy(handCounts = counts)
    }

    private fun resetInternal(reason: String, incrementRound: Boolean) {
        val round = if (incrementRound) state.roundIndex + 1 else 1
        phase = GamePhase.INIT
        state = GameState.initial(roundIndex = round)
        lastReliable = state
        awaitingConfirmation = false
        seqCounter = 0L
        lastAutoResetPhase = null
        baselineEstablished = false
    }

    private fun GameState.withEvent(event: GameEvent): GameState =
        copy(events = events + event)

    private companion object {
        /** 未知牌率超过此值即冻结（等识别稳定或人工确认）。 */
        const val UNKNOWN_RATIO_LIMIT = 0.5f
    }
}
