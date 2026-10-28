package com.weile.liuzhou.mahjong.domain.mahjong

/**
 * 麻将牌编码（mahjong-core，方案第 2.1 节）。
 *
 * 编码 0..33：
 * ```
 *  0..8   一万 .. 九万   (MANZU)
 *  9..17  一筒 .. 九筒   (PINZU)
 *  18..26 一条 .. 九条   (SOUZU)
 *  27..30 东南西北       (风牌)
 *  31..33 中发白         (箭牌)
 * ```
 * - `hasDragons = true`：34 种牌，总 136 张。
 * - `hasDragons = false`：仅使用 0..30（31 种），总 124 张；切换牌集必须清空本局状态。
 * - 每种牌物理张数恒为 4，守恒校验见 [TileCounts]。
 *
 * P2 阶段只做牌型/向听/进张等确定性计算；计分策略（ScoringPolicy）与事件账本
 * （ActionLedger）在 settlement 模块独立实现，规则未核实前不输出确定性收益数字。
 */
@JvmInline
value class Tile(val code: Int) : Comparable<Tile> {

    init {
        require(code in 0..MAX_CODE) { "tile code out of range: $code" }
    }

    /** 所属花色：0=万 1=筒 2=条 3=风 4=箭。 */
    val suit: Int
        get() = when {
            code < 27 -> code / 9
            code < DRAGON_START -> 3
            else -> 4
        }

    /** 数牌点数 1..9；风/箭牌无意义（返回 code-HONOR_START+1）。 */
    val rank: Int get() = code % 9 + 1

    /** 是否字牌（风牌或箭牌）。 */
    val isHonor: Boolean get() = code >= HONOR_START

    /** 是否风牌（东南西北）。 */
    val isWind: Boolean get() = code in HONOR_START until DRAGON_START

    /** 是否箭牌（中发白）。 */
    val isDragon: Boolean get() = code >= DRAGON_START

    /** 是否幺九数牌（1 或 9）。 */
    val isTerminal: Boolean get() = !isHonor && (rank == 1 || rank == 9)

    /** 是否中张数牌（2..8）。 */
    val isSimple: Boolean get() = !isHonor && rank in 2..8

    /** 中文牌名，如"五万""东风""红中"。 */
    val displayName: String
        get() = when {
            code < 9 -> NUM_NAMES[rank - 1] + "万"
            code < 18 -> NUM_NAMES[rank - 1] + "筒"
            code < 27 -> NUM_NAMES[rank - 1] + "条"
            code < DRAGON_START -> WIND_NAMES[code - HONOR_START] + "风"
            else -> DRAGON_NAMES[code - DRAGON_START]
        }

    override fun compareTo(other: Tile): Int = code.compareTo(other.code)

    override fun toString(): String = displayName

    companion object {
        const val MAX_CODE = 33

        /** 字牌起始编码（风牌）。 */
        const val HONOR_START = 27

        /** 箭牌起始编码（中）。 */
        const val DRAGON_START = 31

        private val NUM_NAMES =
            arrayOf("一", "二", "三", "四", "五", "六", "七", "八", "九")
        private val WIND_NAMES = arrayOf("东", "南", "西", "北")
        private val DRAGON_NAMES = arrayOf("红中", "发财", "白板")

        /** 万子 1..9。 */
        fun manzu(rank: Int): Tile = Tile(rank - 1)

        /** 筒子 1..9。 */
        fun pinzu(rank: Int): Tile = Tile(8 + rank)

        /** 条子 1..9。 */
        fun souzu(rank: Int): Tile = Tile(17 + rank)

        /** 风牌，index 0..3 = 东南西北。 */
        fun wind(index: Int): Tile = Tile(HONOR_START + index)

        /** 箭牌，index 0..2 = 中发白。 */
        fun dragon(index: Int): Tile = Tile(DRAGON_START + index)

        /**
         * 解析牌谱字符串中的单牌：
         * `1m 5p 9s`（数牌）、`E S W N`（风）、`C F B`（箭，中/发/白）。
         * 单字母风/箭优先匹配，避免 'S'（南风/条子后缀）歧义。
         * 无法识别时返回 null。
         */
        fun parse(token: String): Tile? {
            val t = token.trim()
            if (t.isEmpty()) return null
            // 单字母风/箭优先（'S' 同时是南风缩写和条子后缀）
            when (t) {
                "E" -> return wind(0)
                "S" -> return wind(1)
                "W" -> return wind(2)
                "N" -> return wind(3)
                "C" -> return dragon(0)
                "F" -> return dragon(1)
                "B" -> return dragon(2)
            }
            val suitChar = t.last()
            val rankPart = t.dropLast(1)
            return when (suitChar) {
                'm', 'M' -> rankPart.toIntOrNull()?.takeIf { it in 1..9 }?.let { manzu(it) }
                'p', 'P' -> rankPart.toIntOrNull()?.takeIf { it in 1..9 }?.let { pinzu(it) }
                's', 'S' -> rankPart.toIntOrNull()?.takeIf { it in 1..9 }?.let { souzu(it) }
                'z', 'Z' -> when (rankPart) {
                    "1" -> dragon(0)
                    "2" -> dragon(1)
                    "3" -> dragon(2)
                    "4" -> wind(0)
                    "5" -> wind(1)
                    "6" -> wind(2)
                    "7" -> wind(3)
                    else -> null
                }
                else -> null
            }
        }
    }
}
