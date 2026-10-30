package com.weile.liuzhou.mahjong.domain.mahjong

/**
 * 手牌计数模型（mahjong-core，方案第 2.1 / 3.1 节）。
 *
 * 以 34 种牌的计数数组表示手牌，支持守恒校验：
 * - 每种牌物理张数不超过 4；
 * - `hasDragons = false` 时不允许出现箭牌（31 种 / 124 张）；
 * - 手牌总张数需与副露数匹配（13 + 3*melds 或 14 + 3*melds）。
 */
class TileCounts(val hasDragons: Boolean = true) {

    /** 每种牌的计数，下标即 [Tile.code]。 */
    val counts = IntArray(Tile.MAX_CODE + 1)

    /** 手牌总张数。 */
    var total: Int = 0
        private set

    /** 有效牌型种类数（随牌集开关变化）。 */
    val kindCount: Int get() = if (hasDragons) 34 else 31

    fun add(tile: Tile) {
        require(tile.code < kindCount) { "牌 ${tile.displayName} 不在当前牌集内（hasDragons=$hasDragons）" }
        counts[tile.code]++
        total++
    }

    fun remove(tile: Tile) {
        require(counts[tile.code] > 0) { "手牌中没有 ${tile.displayName}，无法移除" }
        counts[tile.code]--
        total--
    }

    fun countOf(tile: Tile): Int = counts[tile.code]

    fun countOf(code: Int): Int = counts[code]

    operator fun contains(tile: Tile): Boolean = counts[tile.code] > 0

    /** 复制一份独立计数的副本。 */
    fun copy(): TileCounts = TileCounts(hasDragons).also { dst ->
        counts.copyInto(dst.counts)
        dst.total = total
    }

    /** 按编码顺序展开为牌列表。 */
    fun toSortedTiles(): List<Tile> = buildList {
        for (code in 0 until kindCount) {
            repeat(counts[code]) { add(Tile(code)) }
        }
    }

    /**
     * 守恒校验（方案 3.1：校验每种牌物理张数不超过 4）。
     * @param melds 公开副露组数（吃/碰/杠）
     */
    fun validate(melds: Int = 0): ValidationResult {
        if (!hasDragons) {
            for (code in Tile.DRAGON_START..Tile.MAX_CODE) {
                if (counts[code] > 0) return ValidationResult.Invalid("牌集设置为无箭牌，但出现了箭牌 ${Tile(code).displayName}")
            }
        }
        for (code in 0..Tile.MAX_CODE) {
            if (counts[code] > 4) {
                return ValidationResult.Invalid(
                    "牌 ${Tile(code).displayName} 出现 ${counts[code]} 张，超过每种 4 张的物理上限"
                )
            }
        }
        // 副露会从手牌中拿走 3*melds 张，故手牌张数应为 13-3*melds（未摸牌）或 14-3*melds（摸牌后）。
        val expected = when (total) {
            13 - 3 * melds, 14 - 3 * melds -> true
            else -> false
        }
        if (!expected) {
            return ValidationResult.Invalid(
                "手牌张数 $total 与副露数 $melds 不匹配（应为 13-3*melds 或 14-3*melds，即 ${13 - 3 * melds} 或 ${14 - 3 * melds}）"
            )
        }
        return ValidationResult.Ok
    }

    sealed interface ValidationResult {
        data object Ok : ValidationResult
        data class Invalid(val reason: String) : ValidationResult
    }

    companion object {
        /**
         * 从牌列表构造。
         * 牌谱字符串格式示例：`123m456p789s1234pEE`、`19m19p19s1234567z`。
         * 单字母风箭（E/S/W/N/C/F/B）与数字后缀（m/p/s/z）均可。
         */
        fun from(tiles: Iterable<Tile>, hasDragons: Boolean = true): TileCounts =
            TileCounts(hasDragons).apply { tiles.forEach { add(it) } }

        /** 解析紧凑牌谱字符串，如 "123m456p789sEE"；任一标记无法识别时抛 IllegalArgumentException。 */
        fun parse(spec: String, hasDragons: Boolean = true): TileCounts {
            val result = TileCounts(hasDragons)
            var i = 0
            while (i < spec.length) {
                val ch = spec[i]
                when (ch) {
                    'E', 'S', 'W', 'N', 'C', 'F', 'B' -> {
                        val tile = Tile.parse(ch.toString())
                            ?: throw IllegalArgumentException("无法解析牌谱字符: $ch")
                        result.add(tile)
                        i++
                    }
                    else -> {
                        // 数字 + 花色后缀，如 123m
                        var j = i
                        while (j < spec.length && spec[j].isDigit()) j++
                        if (j == i) throw IllegalArgumentException("无法解析牌谱位置 $i: ${spec.substring(i)}")
                        if (j >= spec.length) throw IllegalArgumentException("牌谱缺少花色后缀: ${spec.substring(i)}")
                        val suit = spec[j]
                        for (k in i until j) {
                            val tile = Tile.parse("${spec[k]}$suit")
                                ?: throw IllegalArgumentException("无法解析牌: ${spec[k]}$suit")
                            result.add(tile)
                        }
                        i = j + 1
                    }
                }
            }
            return result
        }
    }
}
