package com.weile.liuzhou.mahjong.vision

import com.weile.liuzhou.mahjong.domain.mahjong.Tile
import com.weile.liuzhou.mahjong.domain.vision.PixelAccessor
import com.weile.liuzhou.mahjong.domain.vision.TileRegion
import com.weile.liuzhou.mahjong.domain.vision.TileTemplate
import com.weile.liuzhou.mahjong.domain.vision.TileTemplateLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NccTileTemplateMatcher 纯算法测试（合成模板，不依赖真实截图）。
 * 真实牌面模板必须由用户提供的《微乐广西麻将》截图生成后另行验证。
 */
class NccTileTemplateMatcherTest {

    private class FakeAccessor(
        override val width: Int,
        override val height: Int,
        private val grid: IntArray,
    ) : PixelAccessor {
        override fun luminance(x: Int, y: Int): Int = grid[y * width + x]
    }

    /** 生成形状互不相同的 8x8 灰度图案（不同斜率 + 正弦扰动，避免仿射等价导致 NCC 全等）。 */
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

    /** 把 8x8 图案按最近邻放大 scale 倍。 */
    private fun accessorFromPattern(p: IntArray, scale: Int): FakeAccessor {
        val size = 8 * scale
        val grid = IntArray(size * size) { i ->
            val x = i % size
            val y = i / size
            p[(y / scale) * 8 + (x / scale)]
        }
        return FakeAccessor(size, size, grid)
    }

    @Test
    fun `精确匹配置信度接近1`() {
        val pattern = makePattern(1)
        val lib = TileTemplateLibrary.of(TileTemplate(Tile.manzu(1), 8, 8, pattern))
        val matcher = NccTileTemplateMatcher(lib)

        // 同图案放大 2 倍后仍应精确命中
        val result = matcher.match(
            accessorFromPattern(pattern, scale = 2),
            TileRegion(0, 0, 16, 16),
            minConfidence = 0.9f,
        )
        assertEquals(1, result.size)
        assertEquals(Tile.manzu(1), result[0].tile)
        assertTrue("confidence should be ~1, got ${result[0].confidence}", result[0].confidence > 0.99f)
    }

    @Test
    fun `topN返回多个候选`() {
        val lib = TileTemplateLibrary.of(
            TileTemplate(Tile.manzu(1), 8, 8, makePattern(1)),
            TileTemplate(Tile.manzu(2), 8, 8, makePattern(2)),
            TileTemplate(Tile.manzu(3), 8, 8, makePattern(3)),
        )
        val matcher = NccTileTemplateMatcher(lib)
        val result = matcher.match(
            accessorFromPattern(makePattern(2), scale = 1),
            TileRegion(0, 0, 8, 8),
            minConfidence = 0.5f,
            topN = 3,
        )
        assertEquals(3, result.size)
        // 最佳候选应为图案 2 对应的二万
        assertEquals(Tile.manzu(2), result.first().tile)
        // 候选按置信度降序
        assertTrue(result[0].confidence >= result[1].confidence)
        assertTrue(result[1].confidence >= result[2].confidence)
    }

    @Test
    fun `形状不同低于阈值返回unknown`() {
        val lib = TileTemplateLibrary.of(TileTemplate(Tile.manzu(1), 8, 8, makePattern(1)))
        val matcher = NccTileTemplateMatcher(lib)
        val result = matcher.match(
            accessorFromPattern(makePattern(7), scale = 1), // 完全不同的形状
            TileRegion(0, 0, 8, 8),
            minConfidence = 0.9f,
        )
        assertEquals(1, result.size)
        assertEquals(0f, result[0].confidence, 1e-6f)
    }

    @Test
    fun `平坦区域返回unknown`() {
        val lib = TileTemplateLibrary.of(TileTemplate(Tile.manzu(1), 8, 8, makePattern(1)))
        val matcher = NccTileTemplateMatcher(lib)
        val flat = FakeAccessor(16, 16, IntArray(256) { 200 })
        val result = matcher.match(flat, TileRegion(0, 0, 16, 16), minConfidence = 0.5f)
        assertEquals(0f, result[0].confidence, 1e-6f)
    }

    @Test
    fun `空模板库返回unknown`() {
        val matcher = NccTileTemplateMatcher(TileTemplateLibrary.EMPTY)
        val result = matcher.match(
            accessorFromPattern(makePattern(1), scale = 1),
            TileRegion(0, 0, 8, 8),
            minConfidence = 0.5f,
        )
        assertEquals(1, result.size)
        assertEquals(0f, result[0].confidence, 1e-6f)
    }
}
