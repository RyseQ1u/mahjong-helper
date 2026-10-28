package com.weile.liuzhou.mahjong.domain.rules

import com.weile.liuzhou.mahjong.domain.game.OpenMeld
import com.weile.liuzhou.mahjong.domain.mahjong.HandEvaluator
import com.weile.liuzhou.mahjong.domain.mahjong.TileCounts

/**
 * 柳州麻将规则（rules-liuzhou，P5）：胡型判定层。
 *
 * 方案第 2.2 节：基本胡、门清、对对胡、清一色、小七对、全求人。
 * 本层只回答「和了什么牌型」——**不计算分数**（分数在 settlement 模块的
 * ScoringPolicy，且 2.3 节冲突点一律标注「暂不支持」）。
 */

/** 柳州胡型。 */
enum class LiuzhouPattern {
    /** 基本胡（4 面子 + 1 将）。 */
    BASIC,

    /** 对对胡 / 碰碰胡（全部刻子）。 */
    ALL_TRIPLETS,

    /** 清一色（同一数牌花色）。 */
    PURE_ONE_SUIT,

    /** 小七对。 */
    SEVEN_PAIRS,

    /** 全求人（4 副露 + 手牌仅剩 1 张）。 */
    QUANQIUREN,
}

/** 杠事件类型（杠上花/抢杠判定用）。 */
enum class KanKind {
    /** 明杠（直杠）。 */
    MINKAN,

    /** 暗杠。 */
    ANKAN,

    /** 补杠（加杠）。 */
    SHOUMINKAN,
}

/**
 * 和牌上下文。
 *
 * @param selfDraw 自摸（否则为点炮）
 * @param concealed 门清（无任何吃碰杠）
 * @param isLastTile 是否最后一张（海底捞月）；**null = 无法可靠判定**
 * @param afterKan 杠上花（和牌前一步的杠事件）；null = 非杠上花
 * @param robbingKan 抢杠和牌
 */
data class WinContext(
    val selfDraw: Boolean,
    val concealed: Boolean,
    val isLastTile: Boolean? = null,
    val afterKan: KanKind? = null,
    val robbingKan: Boolean = false,
)

/** 判型结果。 */
data class LiuzhouEvaluation(
    val canWin: Boolean,
    val patterns: Set<LiuzhouPattern>,
    /** 全求人的基础胡型（按副露推导；非全求人为 null）。 */
    val quanqiurenBase: LiuzhouPattern? = null,
    val context: WinContext,
    val meldCount: Int,
)

/**
 * 柳州胡型判定器（纯函数，可 JVM 测试）。
 *
 * 说明：
 * - 全求人 = 4 副露 + 手牌 1 张（该张即所听之牌）；其基础胡型按**副露**推导
 *   （全刻 → 对对胡；同花色 → 清一色；否则基本胡），对应方案 2.2
 *   「按基本胡/对对胡/清一色及点炮或自摸分情形计算」。
 * - 门清不作为胡型，而是上下文标志（仅自摸可计，见 ScoringPolicy）。
 */
object LiuzhouHandEvaluator {

    fun evaluate(
        counts: TileCounts,
        melds: List<OpenMeld>,
        context: WinContext,
    ): LiuzhouEvaluation {
        val meldCount = melds.size

        // 全求人：4 副露 + 手牌仅 1 张
        if (meldCount == 4 && counts.total == 1) {
            val base = quanqiurenBase(melds)
            return LiuzhouEvaluation(
                canWin = true,
                patterns = setOf(LiuzhouPattern.QUANQIUREN),
                quanqiurenBase = base,
                context = context,
                meldCount = meldCount,
            )
        }

        if (!HandEvaluator.canWin(counts, meldCount)) {
            return LiuzhouEvaluation(
                canWin = false,
                patterns = emptySet(),
                context = context,
                meldCount = meldCount,
            )
        }

        val core = HandEvaluator.patterns(counts, meldCount)
        val patterns = buildSet {
            if (core.contains(HandEvaluator.HandPattern.ALL_TRIPLETS)) {
                add(LiuzhouPattern.ALL_TRIPLETS)
            }
            if (core.contains(HandEvaluator.HandPattern.PURE_ONE_SUIT)) {
                add(LiuzhouPattern.PURE_ONE_SUIT)
            }
            if (core.contains(HandEvaluator.HandPattern.SEVEN_PAIRS)) {
                add(LiuzhouPattern.SEVEN_PAIRS)
            }
            // 基本胡：能胡即含基本胡（取优先级时见 ScoringPolicy）
            add(LiuzhouPattern.BASIC)
        }
        return LiuzhouEvaluation(
            canWin = true,
            patterns = patterns,
            context = context,
            meldCount = meldCount,
        )
    }

    /** 全求人的基础胡型：按副露推导（全刻→对对胡；同花色→清一色；否则基本胡）。 */
    private fun quanqiurenBase(melds: List<OpenMeld>): LiuzhouPattern {
        val meldTiles = melds.flatMap { it.tiles }
        val allTriplets = meldTiles.isNotEmpty() &&
            melds.all { it.type == com.weile.liuzhou.mahjong.domain.game.MeldType.PON || it.type == com.weile.liuzhou.mahjong.domain.game.MeldType.MINKAN || it.type == com.weile.liuzhou.mahjong.domain.game.MeldType.ANKAN }
        if (allTriplets) return LiuzhouPattern.ALL_TRIPLETS
        val pureOneSuit = meldTiles.isNotEmpty() &&
            meldTiles.none { it.isHonor } &&
            meldTiles.map { it.suit }.distinct().size == 1
        if (pureOneSuit) return LiuzhouPattern.PURE_ONE_SUIT
        return LiuzhouPattern.BASIC
    }
}

/**
 * 兼容旧占位接口的门面（P0 接口保留，签名改为强类型）。
 */
interface RulesLiuzhou {
    val hasDragons: Boolean

    /** 是否能胡（仅判型，不做最终计分）。 */
    fun canWin(counts: TileCounts, melds: List<OpenMeld>, context: WinContext): Boolean =
        LiuzhouHandEvaluator.evaluate(counts, melds, context).canWin
}
