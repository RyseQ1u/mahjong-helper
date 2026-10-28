package com.weile.liuzhou.mahjong.vision

import com.weile.liuzhou.mahjong.domain.vision.PixelAccessor
import com.weile.liuzhou.mahjong.domain.vision.ViewportResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BlackBarViewportDetector 纯算法测试（不依赖 Android 运行时）。
 * 通过 FakeAccessor 注入亮度网格，验证黑边检测与归一化坐标。
 */
class BlackBarViewportDetectorTest {

    private val detector = BlackBarViewportDetector()

    /** 内存中的亮度网格，实现 [PixelAccessor]。 */
    private class FakeAccessor(
        override val width: Int,
        override val height: Int,
        private val grid: IntArray, // 行优先，长度 = width*height
    ) : PixelAccessor {
        override fun luminance(x: Int, y: Int): Int = grid[y * width + x]
    }

    private fun makeGrid(w: Int, h: Int, fill: (x: Int, y: Int) -> Int): FakeAccessor {
        val grid = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            fill(x, y)
        }
        return FakeAccessor(w, h, grid)
    }

    @Test
    fun detectsTopAndBottomBlackBars() {
        val w = 200
        val h = 300
        val accessor = makeGrid(w, h) { _, y ->
            if (y < 50 || y >= 250) 0 else 255   // 上下黑边，中间白
        }
        val result: ViewportResult = detector.detect(accessor)

        // sampleStep=4，允许 ±(step-1) 误差。
        assertTrue("top should be near 50, got ${result.top}", result.top in 48..54)
        assertTrue("bottom should be near 249, got ${result.bottom}", result.bottom in 246..250)
        assertEquals(w, result.imageWidth)
        assertEquals(h, result.imageHeight)
        assertTrue(result.width > 0)
        assertTrue(result.height > 0)
        // 归一化坐标在 0..1
        assertTrue(result.normTop in 0f..1f)
        assertTrue(result.normBottom in 0f..1f)
    }

    @Test
    fun detectsSideBlackBars() {
        val w = 400
        val h = 200
        val accessor = makeGrid(w, h) { x, _ ->
            if (x < 80 || x >= 320) 0 else 255   // 左右黑边
        }
        val result = detector.detect(accessor)
        assertTrue("left should be near 80, got ${result.left}", result.left in 78..82)
        assertTrue("right should be near 319, got ${result.right}", result.right in 318..322)
    }

    @Test
    fun allBlackReturnsFullImage() {
        val w = 100
        val h = 100
        val accessor = makeGrid(w, h) { _, _ -> 0 }
        val result = detector.detect(accessor)
        assertEquals(0, result.left)
        assertEquals(0, result.top)
        assertEquals(w - 1, result.right)
        assertEquals(h - 1, result.bottom)
    }
}
