package com.weile.liuzhou.mahjong.domain.settlement

import com.weile.liuzhou.mahjong.domain.game.Seat
import com.weile.liuzhou.mahjong.domain.rules.KanKind
import com.weile.liuzhou.mahjong.domain.rules.LiuzhouEvaluation
import com.weile.liuzhou.mahjong.domain.rules.LiuzhouPattern

/**
 * 计分策略（ScoringPolicy，P5）。
 *
 * 方案第 2.2 / 2.3 节。**铁律**：
 * - 只对方案中**明确写出且不与 2.3 冲突点矛盾**的规则给确定分值；
 * - 其余一律 [ScoreLine.unsupported]（points=null + 原因），**禁止脑补**；
 * - 点炮者/自摸每家的支付关系必须显式记录，不隐含。
 *
 * 已确认（2.2 表格直述且无冲突）：
 * - 基本胡：点炮 1（点炮者付）；自摸每家 1；
 * - 对对胡/碰碰胡：3 分（点炮）；
 * - 清一色：3 分（点炮）；
 * - 小七对：点炮 3 分；
 * - 暗杠：每家 1 分；明杠：1 分（**支付方待核实**，仅记额度）；
 * - 抢杠：基本胡 ×2；
 * - 海底捞月 / 杠上花：×2 倍率（需可靠事件判定；支付人差异见下）。
 *
 * 暂不支持（2.3 冲突，待用户算例核实）：
 * - 门清分值（2 分 vs 额外 1 番，及与「每家 2 分上限」的先后）；
 * - 门清对对胡 4 / 门清清一色 4；
 * - 小七对自摸每家 4（与每家 2 分上限冲突）；
 * - 杠上炮 +3（覆盖还是叠加）；
 * - 吃三比的支付对象与倍率（次数可由账本可靠统计）；
 * - 钓鱼（规则未给出）。
 */

/** 结算顺序（方案 2.2）：胡牌分 -> 吃三比分 -> 杠牌分 -> 钓鱼分。 */
enum class ScoreComponent {
    HU_POINTS,
    CHI_SAN_BI,
    KAN_POINTS,
    DIAO_YU,
}

/** 一条结算行。points 为 null 表示「暂不支持」。 */
data class ScoreLine(
    val component: ScoreComponent,
    val label: String,
    val points: Int?,
    /** 支付方：点炮者座位；null = 自摸每家均付。 */
    val payer: Seat? = null,
    val unsupportedReason: String? = null,
) {
    companion object {
        fun unsupported(
            component: ScoreComponent,
            label: String,
            reason: String,
            payer: Seat? = null,
        ) = ScoreLine(component, label, null, payer, reason)

        fun confirmed(
            component: ScoreComponent,
            label: String,
            points: Int,
            payer: Seat? = null,
        ) = ScoreLine(component, label, points, payer, null)
    }
}

/** 结算报告。 */
data class SettlementReport(
    val lines: List<ScoreLine>,
) {
    /** 是否存在「暂不支持」项。 */
    val hasUnsupported: Boolean get() = lines.any { it.points == null }

    /** 全部确认时的合计；存在暂不支持项时为 null（不展示虚假精确总分）。 */
    val confirmedTotal: Int?
        get() = if (hasUnsupported) null else lines.sumOf { it.points ?: 0 }
}

/** 计分策略接口。 */
interface ScoringPolicy {
    /** 胡牌分（含牌型与倍率）。 */
    fun scoreWin(evaluation: LiuzhouEvaluation, winner: Seat, discarder: Seat?): List<ScoreLine>

    /** 吃三比分（次数统计 + 支付/倍率，后者未确认）。 */
    fun scoreChiSanBi(ledger: ActionLedger, winner: Seat): ScoreLine

    /** 杠牌分（独立即时台账）。 */
    fun scoreKan(ledger: ActionLedger): List<ScoreLine>

    /** 钓鱼分（规则未给出，保留扩展接口）。 */
    fun scoreDiaoYu(): ScoreLine
}

/**
 * 柳州计分策略（默认实现）。
 *
 * 胡牌分优先级：七对 > 清一色 > 对对胡 > 基本胡（其他胡型不叠加基本胡，方案 2.2）。
 */
class LiuzhouScoringPolicy : ScoringPolicy {

    override fun scoreWin(
        evaluation: LiuzhouEvaluation,
        winner: Seat,
        discarder: Seat?,
    ): List<ScoreLine> {
        if (!evaluation.canWin) return emptyList()
        val ctx = evaluation.context
        val lines = mutableListOf<ScoreLine>()

        // ---- 基础牌型分（仅点炮情形的确定值；自摸涉及每家上限冲突）----
        val basePoints = basePointsFor(evaluation)
        if (basePoints == null) {
            lines += ScoreLine.unsupported(
                ScoreComponent.HU_POINTS,
                "胡型基础分（${patternName(evaluation)}）",
                unsupportedBaseReason(evaluation),
                payer = discarder,
            )
        } else if (ctx.selfDraw) {
            // 自摸：仅基本胡每家 1 为确认值；其他牌型自摸涉及「每家 2 分上限」冲突
            if (basePoints == 1 && evaluation.patterns.none {
                    it == LiuzhouPattern.ALL_TRIPLETS ||
                        it == LiuzhouPattern.PURE_ONE_SUIT ||
                        it == LiuzhouPattern.SEVEN_PAIRS
                }
            ) {
                lines += ScoreLine.confirmed(ScoreComponent.HU_POINTS, "基本胡自摸每家 1", 1)
            } else {
                lines += ScoreLine.unsupported(
                    ScoreComponent.HU_POINTS,
                    "${patternName(evaluation)}自摸每家付",
                    "与 2.3「自摸霹雳每家最多 2 分」上限冲突，需房间算例核实",
                )
            }
        } else {
            lines += ScoreLine.confirmed(
                ScoreComponent.HU_POINTS,
                "${patternName(evaluation)}点炮",
                basePoints,
                payer = discarder,
            )
        }

        // ---- 门清（仅自摸可计；分值/番数关系未确认）----
        if (ctx.concealed && ctx.selfDraw) {
            lines += ScoreLine.unsupported(
                ScoreComponent.HU_POINTS,
                "门清",
                "2.3：门清 2 分与额外 1 番的口径、及与每家 2 分上限的先后关系未确认",
            )
        }

        // ---- 抢杠：基本胡 ×2（确认）；与其他番型叠加未确认 ----
        if (ctx.robbingKan) {
            val simple = evaluation.patterns.none {
                it == LiuzhouPattern.ALL_TRIPLETS ||
                    it == LiuzhouPattern.PURE_ONE_SUIT ||
                    it == LiuzhouPattern.SEVEN_PAIRS ||
                    it == LiuzhouPattern.QUANQIUREN
            }
            if (simple && !ctx.selfDraw) {
                lines += ScoreLine.confirmed(ScoreComponent.HU_POINTS, "抢杠 基本胡×2", 2, payer = discarder)
            } else {
                lines += ScoreLine.unsupported(
                    ScoreComponent.HU_POINTS,
                    "抢杠与其他番型叠加",
                    "2.2 仅确认「基本胡 ×2」，叠加条件待验证",
                    payer = discarder,
                )
            }
        }

        // ---- 杠上花 ×2（倍率确认；直杠补杠的支付人差异未确认）----
        ctx.afterKan?.let { kan ->
            if (kan == KanKind.SHOUMINKAN) {
                lines += ScoreLine.unsupported(
                    ScoreComponent.HU_POINTS,
                    "杠上花（补杠）支付人",
                    "2.3：补杠后和牌的支付人与抢杠关系待核实（×2 倍率本身已确认）",
                )
            }
            lines += ScoreLine.confirmed(ScoreComponent.HU_POINTS, "杠上花 ×2（倍率）", 2)
        }

        // ---- 海底捞月 ×2（需可靠最后一张判定）----
        when (ctx.isLastTile) {
            true -> lines += ScoreLine.confirmed(ScoreComponent.HU_POINTS, "海底捞月 ×2（倍率）", 2)
            false -> Unit
            null -> lines += ScoreLine.unsupported(
                ScoreComponent.HU_POINTS,
                "海底捞月",
                "无法可靠判定最后一张摸牌（方案 2.2 要求可靠事件判定）",
            )
        }

        // ---- 杠上炮（+3 覆盖/叠加歧义，一律暂不支持）----
        if (ctx.afterKan != null && !ctx.selfDraw) {
            lines += ScoreLine.unsupported(
                ScoreComponent.HU_POINTS,
                "杠上炮",
                "2.3：「+3」是覆盖基本胡分还是叠加，需算例核实",
                payer = discarder,
            )
        }

        return lines
    }

    override fun scoreChiSanBi(ledger: ActionLedger, winner: Seat): ScoreLine {
        // 从每个可能的来源统计最大同来源次数
        val maxCount = Seat.entries
            .filter { it != winner }
            .maxOfOrNull { ledger.meldCountFromSameSource(winner, it) } ?: 0
        return if (maxCount >= 3) {
            ScoreLine.unsupported(
                ScoreComponent.CHI_SAN_BI,
                "吃三比（同来源 $maxCount 次）",
                "2.3：支付对象与倍率未确认（次数已由账本可靠统计）",
            )
        } else {
            ScoreLine.confirmed(ScoreComponent.CHI_SAN_BI, "吃三比（未达成）", 0)
        }
    }

    override fun scoreKan(ledger: ActionLedger): List<ScoreLine> =
        ledger.kanEvents().map { event ->
            when (event.type) {
                LedgerEventType.ANKAN -> ScoreLine.confirmed(
                    ScoreComponent.KAN_POINTS, "暗杠（每家 1）", 1
                )
                LedgerEventType.MINKAN -> ScoreLine(
                    component = ScoreComponent.KAN_POINTS,
                    label = "明杠 1 分",
                    points = 1,
                    unsupportedReason = "2.2 注：明杠由谁支付仍需房间规则验证（仅记额度）",
                )
                LedgerEventType.SHOUMINKAN -> ScoreLine.unsupported(
                    ScoreComponent.KAN_POINTS,
                    "补杠分",
                    "2.2 未给出补杠分值（暂按不计，待核实）",
                )
                else -> ScoreLine.confirmed(ScoreComponent.KAN_POINTS, "杠", 0)
            }
        }

    override fun scoreDiaoYu(): ScoreLine = ScoreLine.unsupported(
        ScoreComponent.DIAO_YU,
        "钓鱼分",
        "2.3：钓鱼规则未给出，第一版不估算（保留扩展接口）",
    )

    // -------------------------------------------------- 内部

    /** 基础牌型点矾分值（已确认部分）；null = 该组合未确认。 */
    private fun basePointsFor(e: LiuzhouEvaluation): Int? {
        // 全求人：按基础胡型 + 点炮/自摸情形计算——但 2.2 未给全求人具体分值 → 未确认
        if (e.patterns.contains(LiuzhouPattern.QUANQIUREN)) return null
        return when {
            e.patterns.contains(LiuzhouPattern.SEVEN_PAIRS) -> 3          // 小七对点炮 3
            e.patterns.contains(LiuzhouPattern.PURE_ONE_SUIT) -> 3        // 清一色 3
            e.patterns.contains(LiuzhouPattern.ALL_TRIPLETS) -> 3         // 对对胡 3
            else -> 1                                                      // 基本胡 1
        }
    }

    private fun unsupportedBaseReason(e: LiuzhouEvaluation): String =
        if (e.patterns.contains(LiuzhouPattern.QUANQIUREN)) {
            "2.2 未给出全求人具体分值（需按基本胡/对对胡/清一色情形分别确认）"
        } else {
            "2.3：牌型分与「每家 2 分上限」关系未确认"
        }

    private fun patternName(e: LiuzhouEvaluation): String = when {
        e.patterns.contains(LiuzhouPattern.QUANQIUREN) -> "全求人"
        e.patterns.contains(LiuzhouPattern.SEVEN_PAIRS) -> "小七对"
        e.patterns.contains(LiuzhouPattern.PURE_ONE_SUIT) -> "清一色"
        e.patterns.contains(LiuzhouPattern.ALL_TRIPLETS) -> "对对胡"
        else -> "基本胡"
    }
}
