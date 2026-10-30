package com.weile.liuzhou.mahjong.domain.mahjong

/**
 * 手牌判定与向听计算引擎（mahjong-core，方案第 4 节 / P2）。
 *
 * 只做确定性计算：胡牌判定、牌型识别、向听数、有效进张、候选舍牌排序。
 * 与 ScoringPolicy（计分）和 ActionLedger（事件账本）彻底隔离——
 * 规则 2.3 节的歧义未核实前，本模块不输出任何确定性收益数字。
 *
 * 向听数采用日麻定义：-1 = 已和牌，0 = 听牌，1 = 一向听，依此类推。
 */
object HandEvaluator {

    /** 胡型识别结果。 */
    enum class HandPattern {
        /** 基本胡（4 面子 + 1 将）。 */
        BASIC,

        /** 七对。 */
        SEVEN_PAIRS,

        /** 对对胡 / 碰碰胡（全部刻子）。 */
        ALL_TRIPLETS,

        /** 清一色（同一数牌花色；字牌混入则不算）。 */
        PURE_ONE_SUIT,
    }

    /** 一次完整评估的结果。 */
    data class HandResult(
        val canWin: Boolean,
        val patterns: Set<HandPattern>,
        /** 向听数：-1 已和 / 0 听牌 / n 一向听… */
        val shanten: Int,
        /** 当前状态下的有效进张（使向听降低或直接和牌的牌）。 */
        val effectiveDraws: List<Tile>,
        /** 手牌张数与副露数是否匹配（守恒校验）。 */
        val tileCountValid: Boolean,
        /** 校验失败原因（tileCountValid 为 false 时非空）。 */
        val validationError: String?,
    )

    /** 舍牌候选：打掉某张牌后的向听与有效进张数。 */
    data class DiscardOption(
        val tile: Tile,
        val shantenAfterDiscard: Int,
        val effectiveDrawsAfterDiscard: Int,
    )

    // ------------------------------------------------------------------ 向听

    /**
     * 计算向听数（-1 已和 / 0 听牌 / n n 向听）。
     * @param counts 手牌计数
     * @param melds 已形成的公开副露组数（吃/碰/杠）
     */
    fun shanten(counts: TileCounts, melds: Int = 0): Int {
        val c = counts.counts.copyOf()
        val standard = shantenStandard(c, melds)
        val chiitoi = shantenChiitoi(c)
        return minOf(standard, chiitoi)
    }

    /** 标准形（4 面子 + 1 将）向听。 */
    private fun shantenStandard(c: IntArray, melds: Int): Int {
        val slots = (4 - melds).coerceAtLeast(0)
        var best = MAX_SHANTEN
        // 枚举将（对子）：将不占面子槽，补齐后缺口为 0。
        for (i in 0..Tile.MAX_CODE) {
            if (c[i] >= 2) {
                c[i] -= 2
                best = minOf(best, mentsuDraws(c, slots) - 1)
                c[i] += 2
            }
        }
        // 无将（单钓 tanki）：取一张孤张作「将位」，需再补 1 张成对。
        // 单钓的 1 张缺口与向听归一化 (-1) 相抵，故直接取 mentsuDraws。
        for (i in 0..Tile.MAX_CODE) {
            if (c[i] >= 1) {
                c[i] -= 1
                best = minOf(best, mentsuDraws(c, slots))
                c[i] += 1
            }
        }
        return best
    }

    /**
     * 返回「凑齐 [slots] 个面子所需的最少摸牌数」：
     * 完整面子 0、搭子 1、孤张 2。已无牌但仍有空槽时按空槽数 ×2 兜底。
     */
    private fun mentsuDraws(c: IntArray, slots: Int): Int {
        if (slots <= 0) return 0
        var i = 0
        while (i <= Tile.MAX_CODE && c[i] == 0) i++
        if (i > Tile.MAX_CODE) return slots * 2
        val isNumber = i < Tile.HONOR_START
        val rank = i % 9
        var best = Int.MAX_VALUE

        // 刻子（完整面子，0 张）
        if (c[i] >= 3) {
            c[i] -= 3
            best = minOf(best, mentsuDraws(c, slots - 1))
            c[i] += 3
        }
        // 顺子（完整面子，0 张）
        if (isNumber && rank <= 6 && c[i + 1] > 0 && c[i + 2] > 0) {
            c[i]--; c[i + 1]--; c[i + 2]--
            best = minOf(best, mentsuDraws(c, slots - 1))
            c[i]++; c[i + 1]++; c[i + 2]++
        }
        // 对子做搭子（差一张成刻，1 张）
        if (c[i] >= 2) {
            c[i] -= 2
            best = minOf(best, 1 + mentsuDraws(c, slots - 1))
            c[i] += 2
        }
        // 两面搭子（差一张成顺，1 张）
        if (isNumber && rank <= 7 && c[i + 1] > 0) {
            c[i]--; c[i + 1]--
            best = minOf(best, 1 + mentsuDraws(c, slots - 1))
            c[i]++; c[i + 1]++
        }
        // 嵌张搭子（差一张成顺，1 张）
        if (isNumber && rank <= 6 && c[i + 2] > 0) {
            c[i]--; c[i + 2]--
            best = minOf(best, 1 + mentsuDraws(c, slots - 1))
            c[i]++; c[i + 2]++
        }
        // 孤张（差两张成面子，2 张）
        c[i]--
        best = minOf(best, 2 + mentsuDraws(c, slots - 1))
        c[i]++

        return best
    }

    /** 七对形向听（6 - 对子数；每种牌最多计 1 对）。 */
    private fun shantenChiitoi(c: IntArray): Int {
        var pairs = 0
        for (i in 0..Tile.MAX_CODE) {
            if (c[i] >= 2) pairs++
        }
        return 6 - pairs
    }

    private const val MAX_SHANTEN = 8

    // ------------------------------------------------------------------ 胡牌与牌型

    /**
     * 是否能和牌（标准形或七对）。
     * 七对采用宽松口径（任意 7 个对子和牌）；柳州房间是否禁止四张同牌凑对，规则待核实。
     */
    fun canWin(counts: TileCounts, melds: Int = 0): Boolean {
        val c = counts.counts.copyOf()
        // 七对（14 张，无副露）
        if (counts.total == 14 && shantenChiitoi(c) == -1) return true
        // 标准形：枚举将，剩余牌须恰好拆成 (4-melds) 个完整面子
        val slots = (4 - melds).coerceAtLeast(0)
        for (i in 0..Tile.MAX_CODE) {
            if (c[i] >= 2) {
                c[i] -= 2
                if (canFormAllSets(c, slots)) {
                    c[i] += 2
                    return true
                }
                c[i] += 2
            }
        }
        return false
    }

    /** 剩余牌能否恰好拆成 [slots] 个完整面子（每张牌都用尽）。 */
    private fun canFormAllSets(c: IntArray, slots: Int): Boolean {
        if (slots == 0) {
            for (i in 0..Tile.MAX_CODE) if (c[i] != 0) return false
            return true
        }
        var i = 0
        while (i <= Tile.MAX_CODE && c[i] == 0) i++
        if (i > Tile.MAX_CODE) return false
        val isNumber = i < Tile.HONOR_START
        val rank = i % 9
        if (c[i] >= 3) {
            c[i] -= 3
            if (canFormAllSets(c, slots - 1)) { c[i] += 3; return true }
            c[i] += 3
        }
        if (isNumber && rank <= 6 && c[i + 1] > 0 && c[i + 2] > 0) {
            c[i]--; c[i + 1]--; c[i + 2]--
            if (canFormAllSets(c, slots - 1)) { c[i]++; c[i + 1]++; c[i + 2]++; return true }
            c[i]++; c[i + 1]++; c[i + 2]++
        }
        return false
    }

    /** 识别胡型（可在和牌或听牌时调用；未和牌时 patterns 反映潜在倾向）。 */
    fun patterns(counts: TileCounts, melds: Int = 0): Set<HandPattern> {
        val result = mutableSetOf<HandPattern>()
        val c = counts.counts.copyOf()

        // 七对
        if (counts.total == 14 && shantenChiitoi(c) == -1) {
            result += HandPattern.SEVEN_PAIRS
        }

        // 基本胡（标准形可和）
        if (canWin(counts, melds)) {
            result += HandPattern.BASIC
        }

        // 碰碰胡：全部由刻子+将构成
        if (counts.total in setOf(2, 5, 8, 11, 14) && allTriplets(c)) {
            result += HandPattern.ALL_TRIPLETS
        }

        // 清一色：所有牌属于同一数牌花色
        if (counts.total > 0 && pureOneSuit(c)) {
            result += HandPattern.PURE_ONE_SUIT
        }
        return result
    }

    /** 仅用刻子 + 将能否分解全部手牌。 */
    private fun allTriplets(c: IntArray): Boolean {
        var i = 0
        while (i <= Tile.MAX_CODE && c[i] == 0) i++
        if (i > Tile.MAX_CODE) return true
        if (c[i] >= 3) {
            c[i] -= 3
            val ok = allTriplets(c)
            c[i] += 3
            return ok
        }
        if (c[i] == 2) {
            c[i] -= 2
            val ok = allTriplets(c)
            c[i] += 2
            return ok
        }
        return false
    }

    /** 是否所有牌都属于同一数牌花色（万/筒/条之一；含字牌则不算清一色）。 */
    private fun pureOneSuit(c: IntArray): Boolean {
        var suit = -1
        for (code in 0..Tile.MAX_CODE) {
            if (c[code] == 0) continue
            if (code >= Tile.HONOR_START) return false
            if (suit == -1) suit = code / 9
            if (code / 9 != suit) return false
        }
        return suit != -1
    }

    // ------------------------------------------------------------------ 进张与舍牌

    /**
     * 有效进张：加入每张牌后向听降低、或直接和牌的牌集合。
     * @param remaining 每种牌的剩余可见张数（方案 4.4：4 - 自家 - 弃牌 - 副露 - 其他已知）
     */
    fun effectiveDraws(
        counts: TileCounts,
        melds: Int = 0,
        remaining: (Tile) -> Int = { _ -> 4 },
    ): List<Tile> {
        val base = shanten(counts, melds)
        val c = counts.counts.copyOf()
        val result = mutableListOf<Tile>()
        for (code in 0 until counts.kindCount) {
            if (remaining(Tile(code)) <= 0) continue
            c[code]++
            val s = shantenStandardOrChiitoi(c, melds)
            c[code]--
            if (s < base || s == -1) result += Tile(code)
        }
        return result
    }

    private fun shantenStandardOrChiitoi(c: IntArray, melds: Int): Int =
        minOf(shantenStandard(c, melds), shantenChiitoi(c))

    /**
     * 候选舍牌排序（方案第 4.2 / 4.5 节）：
     * 对每个不同的可打手牌，计算打掉后的向听数与有效进张数，
     * 按「向听升序 → 有效进张数降序」排序。
     */
    fun discardCandidates(
        counts: TileCounts,
        melds: Int = 0,
        remaining: (Tile) -> Int = { _ -> 4 },
    ): List<DiscardOption> {
        val c = counts.counts.copyOf()
        val options = mutableListOf<DiscardOption>()
        for (code in 0 until counts.kindCount) {
            if (c[code] == 0) continue
            val tile = Tile(code)
            c[code]--
            val shantenAfter = shantenStandardOrChiitoi(c, melds)
            val effCount = countEffectiveDraws(c, melds, remaining, shantenAfter)
            c[code]++
            options += DiscardOption(tile, shantenAfter, effCount)
        }
        return options.sortedWith(
            compareBy({ it.shantenAfterDiscard }, { -it.effectiveDrawsAfterDiscard })
        )
    }

    /** 统计使向听低于 [baseline]（或直接和牌）的进张种数。 */
    private fun countEffectiveDraws(
        c: IntArray,
        melds: Int,
        remaining: (Tile) -> Int,
        baseline: Int,
    ): Int {
        var n = 0
        for (code in 0..Tile.MAX_CODE) {
            if (remaining(Tile(code)) <= 0) continue
            c[code]++
            val s = shantenStandardOrChiitoi(c, melds)
            c[code]--
            if (s == -1 || s < baseline) n++
        }
        return n
    }

    /**
     * 完整评估入口。
     */
    fun evaluate(counts: TileCounts, melds: Int = 0): HandResult {
        val validation = counts.validate(melds)
        val shantenValue = shanten(counts, melds)
        val win = canWin(counts, melds)
        return HandResult(
            canWin = win,
            patterns = if (win) patterns(counts, melds) else emptySet(),
            shanten = shantenValue,
            effectiveDraws = effectiveDraws(counts, melds),
            tileCountValid = validation is TileCounts.ValidationResult.Ok,
            validationError = (validation as? TileCounts.ValidationResult.Invalid)?.reason,
        )
    }
}
