package com.weile.liuzhou.mahjong.domain.analysis

import com.weile.liuzhou.mahjong.domain.game.GamePhase
import com.weile.liuzhou.mahjong.domain.game.GameState
import com.weile.liuzhou.mahjong.domain.game.Seat
import com.weile.liuzhou.mahjong.domain.game.TileLedger
import com.weile.liuzhou.mahjong.domain.mahjong.HandEvaluator
import com.weile.liuzhou.mahjong.domain.mahjong.Tile
import com.weile.liuzhou.mahjong.domain.mahjong.TileCounts

/**
 * 出牌决策（analysis-engine，P4）。
 *
 * 方案第 4 节：先确定性（向听、有效进张），后概率（预期得分、攻防权重、放炮风险）。
 * 允许配置模式：SPEED / BALANCED / SAFETY。
 *
 * **铁律**：
 * - `recognitionConfidence` 仅表示牌面识别置信度，**严禁**称作“真实胜率”；
 * - 风险评估只能作为有限信息下的估计，不能读取对手暗牌；
 * - 规则 2.3 歧义未核实前不输出任何确定性收益数字；
 * - 识别不确定（未识别牌 / 守恒冲突）时必须输出「等待确认」，不带着错误状态继续推算。
 */
enum class AnalysisMode {
    /** 听牌速度优先：向听最低 + 进张最广。 */
    SPEED,

    /** 综合：向听优先，速度与安全加权平衡。 */
    BALANCED,

    /** 安全优先：同等向听下优先打出“已见张数多”的相对安全牌。 */
    SAFETY,
}

/** 单个舍牌候选。 */
data class Candidate(
    val tile: Tile,
    val shantenAfterDiscard: Int,
    val effectiveDrawsAfterDiscard: Int,
    val reason: String,
    /** 仅表示牌面识别置信度，严禁称作“真实胜率”。 */
    val recognitionConfidence: Float,
)

/** 一次建议输出。 */
data class Advice(
    /** 按优先级排序的候选（最多 3 个）。 */
    val candidates: List<Candidate> = emptyList(),
    /** 是否已和牌（自摸）。 */
    val canWin: Boolean = false,
    /** 是否需要人工确认（识别不确定 / 守恒冲突）。 */
    val waitingConfirmation: Boolean = false,
    /** 等待确认的原因说明。 */
    val confirmationHint: String? = null,
    /** 手牌识别置信度（已知牌 / 总牌数）。 */
    val recognitionConfidence: Float = 0f,
    /** 当前牌局阶段。 */
    val phase: GamePhase = GamePhase.UNKNOWN,
) {
    /** 供 UI 展示的一行摘要。 */
    fun summary(): String = when {
        waitingConfirmation -> "⚠ 等待确认：${confirmationHint ?: "识别不确定"}"
        canWin -> "🎉 可以和牌（自摸）"
        candidates.isEmpty() -> when (phase) {
            GamePhase.OWN_TURN -> "轮到自家，分析中…"
            else -> "等待回合…"
        }
        else -> candidates.first().let {
            "建议打 ${it.tile.displayName}｜向听 ${it.shantenAfterDiscard}｜进张 ${it.effectiveDrawsAfterDiscard} 种"
        }
    }
}

/** 出牌决策引擎接口。 */
interface RecommendationEngine {
    /**
     * 基于当前牌局状态给出建议。
     * @param state 牌局状态（来自 GameStateMachine）
     * @param mode 配置模式
     * @param hasDragons 牌集开关（true=136 张含箭牌；false=124 张，切换时必须已重置牌局）
     */
    fun advise(
        state: GameState,
        mode: AnalysisMode = AnalysisMode.BALANCED,
        hasDragons: Boolean = true,
    ): Advice
}

/**
 * [RecommendationEngine] 默认实现（确定性部分）。
 *
 * 排序策略（方案 4.5）：
 * - 首先按向听数升序；
 * - 然后按模式权重：
 *   - SPEED：有效进张数降序；
 *   - BALANCED：有效进张与安全分加权；
 *   - SAFETY：安全分（已见张数，越多越不易放炮）优先。
 * 有效进张按剩余可见张数过滤（[TileLedger.remainingVisible]）。
 */
class DefaultRecommendationEngine : RecommendationEngine {

    override fun advise(state: GameState, mode: AnalysisMode, hasDragons: Boolean): Advice {
        val confidence = handConfidence(state)

        // 1) 守恒冲突 → 等待确认
        if (state.needConfirmation) {
            return Advice(
                waitingConfirmation = true,
                confirmationHint = state.confirmationReason ?: "牌张守恒校验未通过",
                recognitionConfidence = confidence,
                phase = state.phase,
            )
        }

        // 2) 手牌有未识别牌 → 等待确认（不带着错误状态继续推算）
        val unknownCount = state.ownHand.count { it == null }
        if (unknownCount > 0) {
            return Advice(
                waitingConfirmation = true,
                confirmationHint = "手牌有 $unknownCount 张未识别，请确认",
                recognitionConfidence = confidence,
                phase = state.phase,
            )
        }

        // 3) 非自己回合不出建议
        if (state.phase != GamePhase.OWN_TURN || state.ownHand.isEmpty()) {
            return Advice(recognitionConfidence = confidence, phase = state.phase)
        }

        val known = state.ownHand.filterNotNull()
        // 决策只应参考「自家」已形成的副露组数，而非全场四家副露总数。
        val melds = state.meldsOf(Seat.SELF).size

        // 4) 牌集校验 + 已和牌（自摸）
        val counts = buildCounts(known, hasDragons)
            ?: return Advice(
                waitingConfirmation = true,
                confirmationHint = "手牌不在当前牌集内（检查箭牌开关）",
                recognitionConfidence = confidence,
                phase = state.phase,
            )
        if (HandEvaluator.canWin(counts, melds)) {
            return Advice(canWin = true, recognitionConfidence = confidence, phase = state.phase)
        }

        // 5) 候选舍牌（向听升序 + 模式权重）
        val ledger = TileLedger(state)
        val remaining: (Tile) -> Int = { ledger.remainingVisible(it) }
        val scored = HandEvaluator.discardCandidates(counts, melds, remaining)
            .map { option ->
                val seen = 4 - remaining(option.tile) // 已见张数（安全分）
                val score = when (mode) {
                    AnalysisMode.SPEED ->
                        -option.shantenAfterDiscard * 1000 + option.effectiveDrawsAfterDiscard * 10
                    AnalysisMode.BALANCED ->
                        -option.shantenAfterDiscard * 1000 + option.effectiveDrawsAfterDiscard * 10 + seen
                    AnalysisMode.SAFETY ->
                        -option.shantenAfterDiscard * 1000 + seen * 10 + option.effectiveDrawsAfterDiscard
                }
                Scored(option, seen, score)
            }
            .sortedWith(compareByDescending<Scored> { it.score }.thenBy { it.option.tile.code })

        val top = scored.take(3).map { s ->
            Candidate(
                tile = s.option.tile,
                shantenAfterDiscard = s.option.shantenAfterDiscard,
                effectiveDrawsAfterDiscard = s.option.effectiveDrawsAfterDiscard,
                reason = buildReason(mode, s.option.shantenAfterDiscard, s.option.effectiveDrawsAfterDiscard, s.seen),
                recognitionConfidence = confidence,
            )
        }
        return Advice(candidates = top, recognitionConfidence = confidence, phase = state.phase)
    }

    private fun buildReason(mode: AnalysisMode, shanten: Int, draws: Int, seen: Int): String {
        val base = "向听 $shanten，进张 $draws 种"
        return when (mode) {
            AnalysisMode.SPEED -> "$base（速度优先）"
            AnalysisMode.BALANCED -> "$base（综合）"
            AnalysisMode.SAFETY -> "$base；已见 $seen 张，相对安全"
        }
    }

    /** 手牌识别置信度 = 已知牌 / 总牌数。 */
    private fun handConfidence(state: GameState): Float {
        if (state.ownHand.isEmpty()) return 0f
        val known = state.ownHand.count { it != null }
        return known.toFloat() / state.ownHand.size
    }

    /**
     * 由已知手牌构造计数。牌不在当前牌集内（如无箭牌模式下出现箭牌）时返回 null
     * ——由调用方转为「等待确认」，不静默丢弃。
     */
    private fun buildCounts(tiles: List<Tile>, hasDragons: Boolean): TileCounts? = try {
        TileCounts.from(tiles, hasDragons)
    } catch (e: IllegalArgumentException) {
        null
    }

    /** 排序中间结构。 */
    private data class Scored(
        val option: HandEvaluator.DiscardOption,
        val seen: Int,
        val score: Int,
    )
}
