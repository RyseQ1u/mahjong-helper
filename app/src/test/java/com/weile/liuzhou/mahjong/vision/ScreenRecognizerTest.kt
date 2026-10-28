package com.weile.liuzhou.mahjong.vision

import com.weile.liuzhou.mahjong.domain.mahjong.Tile
import com.weile.liuzhou.mahjong.domain.vision.PixelAccessor
import com.weile.liuzhou.mahjong.domain.vision.ScreenState
import com.weile.liuzhou.mahjong.domain.vision.TileLayout
import com.weile.liuzhou.mahjong.domain.vision.TileTemplate
import com.weile.liuzhou.mahjong.domain.vision.TileTemplateLibrary
import com.weile.liuzhou.mahjong.domain.vision.ViewportResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ScreenRecognizer 串联测试（合成画面 + 合成模板，纯 JVM）。
 *
 * 合成画面结构（400x300，四周黑边，内容区 200 灰底，自家手牌区填入 13 个已知图案）
 * 对应方案第 1 节画面描述的简化版。真实截图接入后需重新校准布局与阈值。
 */
class ScreenRecognizerTest {

    private class FakeAccessor(
        override val width: Int,
        override val height: Int,
        private val grid: IntArray,
    ) : PixelAccessor {
        override fun luminance(x: Int, y: Int): Int = grid[y * width + x]
    }

    private val screenW = 400
    private val screenH = 300
    private val contentLeft = 40
    private val contentTop = 30
    private val contentRight = 360
    private val contentBottom = 270

    /** 13 张互不相同的合成牌（一万..九万 + 一筒..四筒）。 */
    private val handTiles: List<Tile> =
        (1..9).map { Tile.manzu(it) } + (1..4).map { Tile.pinzu(it) }

    private val viewportDetector = BlackBarViewportDetector()
    private val stateClassifier = HeuristicScreenStateClassifier()

    private fun makePattern(seed: Int): IntArray {
        val ax = 12 + (seed % 5) * 9
        val ay = 10 + (seed % 4) * 11
        val freq = 1 + seed % 3
        return IntArray(64) { i ->
            val x = i % 8
            val y = i / 8
            val v = 40 + (ax * x + ay * y + 30 * Math.sin((x + y) * freq * 0.7)).toInt()
            v.coerceIn(20, 235)
        }
    }

    private val patterns: Map<Tile, IntArray> =
        handTiles.associateWith { makePattern(handTiles.indexOf(it)) }

    /** 构造合成画面：黑边 + 内容灰底 + 手牌区填入已知图案。 */
    private fun buildScreen(): FakeAccessor {
        val grid = IntArray(screenW * screenH) { i ->
            val x = i % screenW
            val y = i / screenW
            val inContent = x in contentLeft until contentRight && y in contentTop until contentBottom
            if (inContent) 200 else 0
        }
        // 先探测视口，再按布局把手牌图案填入对应格子
        val screen = FakeAccessor(screenW, screenH, grid)
        val viewport = viewportDetector.detect(screen)
        val cells = TileLayout.DEFAULT.selfHand.cells(viewport)
        assertEquals(13, cells.size)
        handTiles.forEachIndexed { index, tile ->
            val cell = cells[index]
            val p = patterns.getValue(tile)
            for (y in cell.top until cell.bottom) {
                for (x in cell.left until cell.right) {
                    val tx = (x - cell.left) * 8 / (cell.right - cell.left)
                    val ty = (y - cell.top) * 8 / (cell.bottom - cell.top)
                    grid[y * screenW + x] = p[ty * 8 + tx]
                }
            }
        }
        return FakeAccessor(screenW, screenH, grid)
    }

    private fun templateLibrary(): TileTemplateLibrary = TileTemplateLibrary.of(
        *patterns.map { (tile, p) -> TileTemplate(tile, 8, 8, p) }.toTypedArray()
    )

    @Test
    fun `视口检测忽略黑边`() {
        val screen = buildScreen()
        val viewport = viewportDetector.detect(screen)
        // sampleStep=4，允许 ±4 误差
        assertTrue("left=${viewport.left}", viewport.left in 36..44)
        assertTrue("top=${viewport.top}", viewport.top in 26..34)
        assertTrue("right=${viewport.right}", viewport.right in 356..364)
        assertTrue("bottom=${viewport.bottom}", viewport.bottom in 264..272)
    }

    @Test
    fun `合成画面整链路识别`() {
        val screen = buildScreen()
        val recognizer = ScreenRecognizer(
            viewportDetector = viewportDetector,
            stateClassifier = stateClassifier,
            tileMatcher = NccTileTemplateMatcher(templateLibrary()),
        )
        val result = recognizer.recognize(screen)

        assertEquals(ScreenState.PLAYING, result.screenState)
        assertTrue(result.templateLibraryReady)
        // 13 手牌 + 1 摸牌 + 4 处弃牌区 × 8 = 46 个格子
        assertEquals(46, result.tiles.size)
        // 前 13 个为自家手牌，应与合成牌一一对应且置信度接近 1
        handTiles.forEachIndexed { index, tile ->
            val match = result.tiles[index]
            assertEquals("tile[$index]", tile, match.tile)
            assertTrue("tile[$index] confidence=${match.confidence}", match.confidence > 0.99f)
        }
        // 其余 33 格为均匀灰底 → unknown
        val unknownCount = result.tiles.count { it.confidence <= 0f }
        assertEquals(33, unknownCount)
        assertEquals(33f / 46f, result.unknownRatio, 1e-4f)
    }

    @Test
    fun `空模板库全部unknown`() {
        val screen = buildScreen()
        val recognizer = ScreenRecognizer(
            viewportDetector = viewportDetector,
            stateClassifier = stateClassifier,
            tileMatcher = NccTileTemplateMatcher(TileTemplateLibrary.EMPTY),
        )
        val result = recognizer.recognize(screen)
        assertTrue(!result.templateLibraryReady)
        assertEquals(46, result.tiles.size)
        assertEquals(1f, result.unknownRatio, 1e-4f)
    }

    @Test
    fun `整屏近黑判定UNKNOWN且不识别`() {
        val grid = IntArray(screenW * screenH) { 0 }
        val screen = FakeAccessor(screenW, screenH, grid)
        val recognizer = ScreenRecognizer(
            viewportDetector = viewportDetector,
            stateClassifier = stateClassifier,
            tileMatcher = NccTileTemplateMatcher(templateLibrary()),
        )
        val result = recognizer.recognize(screen)
        assertEquals(ScreenState.UNKNOWN, result.screenState)
        assertTrue(result.tiles.isEmpty())
    }

    @Test
    fun `牌区等分格子连续且有序`() {
        val viewport = ViewportResult(40, 30, 360, 270, screenW, screenH)
        val cells = TileLayout.DEFAULT.selfHand.cells(viewport)
        assertEquals(13, cells.size)
        for (i in 1 until cells.size) {
            assertEquals(cells[i - 1].right, cells[i].left)
        }
        // 竖排区域（左侧弃牌区）
        val verticalCells = TileLayout.DEFAULT.discardLeft.cells(viewport)
        assertEquals(8, verticalCells.size)
        for (i in 1 until verticalCells.size) {
            assertEquals(verticalCells[i - 1].bottom, verticalCells[i].top)
        }
    }
}
