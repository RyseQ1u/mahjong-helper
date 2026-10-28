package com.weile.liuzhou.mahjong.domain.mahjong

/**
 * 麻将核心（mahjong-core 模块门面接口，方案第 3 节）。
 *
 * 底层计算见 [HandEvaluator]；本接口面向 UI / 识别 / 牌局追踪等上层模块。
 * 关键约束：hasDragons 布尔开关（136 / 124 张），切换牌集必须清空本局状态；
 * 校验每种牌物理张数不超过 4。
 */
interface MahjongCore {

    /**
     * 某张牌的剩余可见张数（方案 4.4）：
     * `4 - 自家手牌数 - 已知弃牌数 - 可见副露数 - 其他已知数`，下限 0。
     * 注意「剩余可见推算量」不等同真实山牌存量。
     */
    fun remainingVisible(
        tile: Tile,
        ownHandCount: Int,
        knownDiscards: Int,
        visibleMelds: Int,
        otherKnownTiles: Int,
    ): Int

    /** 计算手牌向听数（-1 已和 / 0 听牌 / n n 向听）。 */
    fun shanten(hand: TileCounts, melds: Int = 0): Int

    /** 枚举有效进张（使向听降低或直接和牌的牌）。 */
    fun effectiveDraws(hand: TileCounts, melds: Int = 0): List<Tile>
}

/** [MahjongCore] 的默认实现（委托给 [HandEvaluator]）。 */
object DefaultMahjongCore : MahjongCore {

    override fun remainingVisible(
        tile: Tile,
        ownHandCount: Int,
        knownDiscards: Int,
        visibleMelds: Int,
        otherKnownTiles: Int,
    ): Int = maxOf(0, 4 - ownHandCount - knownDiscards - visibleMelds - otherKnownTiles)

    override fun shanten(hand: TileCounts, melds: Int): Int =
        HandEvaluator.shanten(hand, melds)

    override fun effectiveDraws(hand: TileCounts, melds: Int): List<Tile> =
        HandEvaluator.effectiveDraws(hand, melds)
}
