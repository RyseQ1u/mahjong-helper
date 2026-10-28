package com.weile.liuzhou.mahjong.domain.game

import com.weile.liuzhou.mahjong.domain.mahjong.Tile

/**
 * 已见牌账本（game-state，P3）。
 *
 * 方案第 4.4 节：
 * ```
 * remainingVisible(t) = max(0, 4 - ownHandCount(t) - knownDiscards(t) - visibleMelds(t) - otherKnownTiles(t))
 * ```
 * 已弃牌被副露拿走时必须去重（副露消耗的弃牌不再计入弃牌区）。
 *
 * 同时承担守恒校验（方案 3.1）：任何牌在「自家手牌 + 有效弃牌 + 副露」中的
 * 合计张数不得超过 4；超过即说明识别或事件推导出错，必须回滚并请求人工确认。
 */
class TileLedger(private val state: GameState) {

    /** 自家手牌中各牌的张数（不含未识别牌）。 */
    fun ownCount(tile: Tile): Int = state.ownHand.count { it == tile }

    /** 全场有效弃牌中各牌的张数（不含被副露消耗的）。 */
    fun discardCount(tile: Tile): Int =
        state.activeDiscards().count { it.tile == tile }

    /** 全场副露中各牌的张数。 */
    fun meldCount(tile: Tile): Int =
        state.melds.values.flatten().sumOf { meld -> meld.tiles.count { it == tile } }

    /** 已见总量（自家 + 弃牌 + 副露）。 */
    fun seenCount(tile: Tile): Int = ownCount(tile) + discardCount(tile) + meldCount(tile)

    /** 剩余可见张数（方案 4.4）。注意：这不等于真实山牌存量。 */
    fun remainingVisible(tile: Tile): Int = (4 - seenCount(tile)).coerceAtLeast(0)

    /**
     * 守恒校验：返回所有超过 4 张的牌的描述；空列表表示通过。
     * 只在已见范围内校验——未识别牌不参与，因此"通过"不等于"全场无矛盾"。
     */
    fun violations(): List<String> {
        val result = mutableListOf<String>()
        for (code in 0..Tile.MAX_CODE) {
            val tile = Tile(code)
            val seen = seenCount(tile)
            if (seen > 4) {
                result += "牌 ${tile.displayName} 已见 ${seen} 张，超过每种 4 张的物理上限"
            }
        }
        return result
    }

    /** 是否通过守恒校验。 */
    val isValid: Boolean get() = violations().isEmpty()

    companion object {
        /** 便捷入口：直接从状态构造。 */
        fun of(state: GameState): TileLedger = TileLedger(state)
    }
}
