package com.weile.liuzhou.mahjong.vision

import com.weile.liuzhou.mahjong.domain.vision.PixelAccessor
import com.weile.liuzhou.mahjong.domain.vision.PixelViewportDetector
import com.weile.liuzhou.mahjong.domain.vision.RecognitionResult
import com.weile.liuzhou.mahjong.domain.vision.ScreenState
import com.weile.liuzhou.mahjong.domain.vision.ScreenStateClassifier
import com.weile.liuzhou.mahjong.domain.vision.TileLayout
import com.weile.liuzhou.mahjong.domain.vision.TileMatch
import com.weile.liuzhou.mahjong.domain.vision.TileSource
import com.weile.liuzhou.mahjong.domain.vision.TileTemplateMatcher
import com.weile.liuzhou.mahjong.domain.vision.ViewportResult

/**
 * 画面识别串联器（P1）：视口检测 → 状态分类 → 牌区分割 → 模板匹配。
 *
 * 流程对应方案第 3.2 节：
 * 1) 先检测内容有效区域（黑边）并归一化坐标；
 * 2) 分类画面状态，仅在 PLAYING / OWN_TURN 时执行牌面识别；
 * 3) 按 [TileLayout] 将各牌区等分为单牌格子，逐格模板匹配；
 * 4) 汇总未知牌率等 P1 指标。
 *
 * 注意（方案 3.2 第 3 点）：单帧不能可靠判断出牌顺序与事件先后，
 * 连续牌局追踪由 game-state 模块基于多帧完成，本类只输出单帧识别结果。
 */
class ScreenRecognizer(
    private val viewportDetector: PixelViewportDetector,
    private val stateClassifier: ScreenStateClassifier,
    private val tileMatcher: TileTemplateMatcher,
    private val layout: TileLayout = TileLayout.DEFAULT,
    private val minConfidence: Float = 0.9f,
) {

    /** 识别整帧画面（PixelAccessor 入口，便于 JVM 测试）。 */
    fun recognize(screen: PixelAccessor): RecognitionResult {
        val viewport = viewportDetector.detect(screen)
        val state = stateClassifier.classify(screen)

        if (state != ScreenState.PLAYING && state != ScreenState.OWN_TURN) {
            return RecognitionResult(
                viewport = viewport,
                screenState = state,
                tiles = emptyList(),
                unknownRatio = 0f,
                templateLibraryReady = matcherReady(),
            )
        }

        val regions = listOf(
            layout.selfHand to TileSource.SELF_HAND,
            layout.selfDrawn to TileSource.SELF_DRAWN,
            layout.discardSelf to TileSource.DISCARD_SELF,
            layout.discardLeft to TileSource.DISCARD_LEFT,
            layout.discardTop to TileSource.DISCARD_TOP,
            layout.discardRight to TileSource.DISCARD_RIGHT,
        )

        val tiles = mutableListOf<TileMatch>()
        for ((region, source) in regions) {
            for (cell in region.cells(viewport)) {
                // 左右弃牌区牌面旋转 ±90°，匹配前按 rotationDeg 旋转回正向。
                val accessor = if (region.rotationDeg % 360 == 0) {
                    SubRegionPixelAccessor(screen, cell)
                } else {
                    RotatedSubRegionPixelAccessor(screen, cell, region.rotationDeg)
                }
                val match = tileMatcher.match(accessor, cell, minConfidence, topN = 1).firstOrNull()
                    ?: TileMatch.unknown(cell)
                tiles += match.copy(source = source)
            }
        }

        val unknownCount = tiles.count { it.isUnknown }
        val unknownRatio = if (tiles.isEmpty()) 0f else unknownCount.toFloat() / tiles.size
        return RecognitionResult(
            viewport = viewport,
            screenState = state,
            tiles = tiles,
            unknownRatio = unknownRatio,
            templateLibraryReady = matcherReady(),
        )
    }

    private fun matcherReady(): Boolean =
        (tileMatcher as? NccTileTemplateMatcher)?.library?.isEmpty == false
}
