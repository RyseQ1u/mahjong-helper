package com.weile.liuzhou.mahjong.domain.settlement

import com.weile.liuzhou.mahjong.domain.game.Seat
import com.weile.liuzhou.mahjong.domain.rules.LiuzhouEvaluation

/**
 * 结算（settlement 模块编排层，P5）。
 *
 * 方案第 2.3 节实现要求的三层隔离在此汇合：
 * - **HandEvaluation**（rules-liuzhou）：和了什么牌型；
 * - **ScoringPolicy**（本包）：支付/倍率/封顶；
 * - **ActionLedger**（本包）：吃碰杠及来源。
 *
 * 结算顺序（方案 2.2）：胡牌分 -> 吃三比分 -> 杠牌分 -> 钓鱼分。
 *
 * **铁律**：在规则未确认的分支里必须标注「暂不支持」，不展示虚假的精确分数——
 * 报告中任一行为 null 即 [SettlementReport.confirmedTotal] 为 null。
 */
interface Settlement {
    /**
     * 结算一局。
     * @param evaluation 胡型判定（HandEvaluation 层输出）
     * @param ledger 事件账本（ActionLedger）
     * @param winner 和牌座位
     * @param discarder 点炮者（自摸为 null）
     */
    fun settle(
        evaluation: LiuzhouEvaluation,
        ledger: ActionLedger,
        winner: Seat,
        discarder: Seat?,
    ): SettlementReport
}

/** [Settlement] 默认实现：按结算顺序编排 [ScoringPolicy] 的各组件。 */
class LiuzhouSettlement(
    private val policy: ScoringPolicy = LiuzhouScoringPolicy(),
) : Settlement {

    override fun settle(
        evaluation: LiuzhouEvaluation,
        ledger: ActionLedger,
        winner: Seat,
        discarder: Seat?,
    ): SettlementReport {
        val lines = mutableListOf<ScoreLine>()
        // 1) 胡牌分（牌型 + 倍率）
        lines += policy.scoreWin(evaluation, winner, discarder)
        // 2) 吃三比分
        lines += policy.scoreChiSanBi(ledger, winner)
        // 3) 杠牌分（独立即时台账）
        lines += policy.scoreKan(ledger)
        // 4) 钓鱼分（扩展接口，第一版不估算）
        lines += policy.scoreDiaoYu()
        return SettlementReport(lines)
    }
}
