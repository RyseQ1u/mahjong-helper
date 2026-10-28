package com.weile.liuzhou.mahjong.domain.vision

import com.weile.liuzhou.mahjong.domain.game.FrameObservation
import com.weile.liuzhou.mahjong.domain.game.GamePhase
import com.weile.liuzhou.mahjong.domain.game.Seat

/**
 * 识别结果 → 帧观测适配器（P4 整合层，纯函数）。
 *
 * 把 [RecognitionResult]（vision 输出）转换为 [FrameObservation]（game-state 输入）：
 * - 画面阶段按 [TileSource] 无关的 ScreenState 映射；
 * - 自家手牌/摸牌按来源标签取用，tile 为 null 表示未识别；
 * - 各弃牌区按座位归位（SELF=底部、LEFT=左侧、TOP=顶部、RIGHT=右侧）；
 * - 副露：P1 尚未识别副露（无真实模板），传空 Map，待 P1 模板到位后补充。
 */
fun RecognitionResult.toFrameObservation(): FrameObservation {
    val phase = when (screenState) {
        ScreenState.PLAYING -> GamePhase.PLAYING
        ScreenState.OWN_TURN -> GamePhase.OWN_TURN
        ScreenState.ANIMATION -> GamePhase.ANIMATION
        ScreenState.SETTLEMENT -> GamePhase.SETTLEMENT
        ScreenState.PREPARING -> GamePhase.PREPARING
        ScreenState.UNKNOWN -> GamePhase.UNKNOWN
    }

    val hand = tiles.filter { it.source == TileSource.SELF_HAND }.map { it.tile }
    val drawn = tiles.filter { it.source == TileSource.SELF_DRAWN }.map { it.tile }.firstOrNull()

    val discardPiles = buildMap {
        put(Seat.SELF, tiles.discardTilesOf(TileSource.DISCARD_SELF))
        put(Seat.LEFT, tiles.discardTilesOf(TileSource.DISCARD_LEFT))
        put(Seat.TOP, tiles.discardTilesOf(TileSource.DISCARD_TOP))
        put(Seat.RIGHT, tiles.discardTilesOf(TileSource.DISCARD_RIGHT))
    }.filterValues { it.isNotEmpty() }

    return FrameObservation(
        phase = phase,
        ownHand = hand,
        ownDrawn = drawn,
        discardPiles = discardPiles,
        // P1 无副露识别（模板库为空），显式传空
        openMelds = emptyMap(),
        unknownRatio = unknownRatio,
    )
}

private fun List<TileMatch>.discardTilesOf(source: TileSource): List<com.weile.liuzhou.mahjong.domain.mahjong.Tile> =
    filter { it.source == source && it.tile != null }.map { it.tile!! }
