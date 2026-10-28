package com.weile.liuzhou.mahjong.vision

import android.graphics.Bitmap
import com.weile.liuzhou.mahjong.domain.vision.PixelAccessor
import com.weile.liuzhou.mahjong.domain.vision.PixelViewportDetector
import com.weile.liuzhou.mahjong.domain.vision.ViewportDetector
import com.weile.liuzhou.mahjong.domain.vision.ViewportResult

/**
 * 基于黑边检测的视口识别（vision-core 的 P0 实现）。
 *
 * 思路（方案第 1 节 / 第 3.2 节）：
 * 1) 对一行/一列取平均亮度，判断是否“接近全黑”（横屏时上下常见黑边）。
 * 2) 自四个方向向内扫描，找到内容起始边界。
 * 3) 输出游戏有效区域（绝对像素 + 归一化坐标），使后续牌区定位不依赖固定黑边高度。
 *
 * 仅做离线可行性验证，P0 不做任何麻将推理。
 */
class BlackBarViewportDetector(
    private val blackThreshold: Int = 24,   // 平均亮度 ≤ 此值视为黑
    private val sampleStep: Int = 4,        // 采样步长（降采样提速）
) : ViewportDetector, PixelViewportDetector {

    override fun detect(bitmap: Bitmap): ViewportResult {
        return detect(BitmapPixelAccessor(bitmap))
    }

    /** 纯算法入口，便于 JVM 单元测试。 */
    override fun detect(accessor: PixelAccessor): ViewportResult {
        val w = accessor.width
        val h = accessor.height
        if (w <= 0 || h <= 0) return ViewportResult(0, 0, w, h, w, h)

        var top = 0
        var bottom = h - 1
        var left = 0
        var right = w - 1

        for (y in 0 until h step sampleStep) {
            if (!isLineBlack(accessor, horizontal = true, index = y, len = w)) { top = y; break }
        }
        for (y in h - 1 downTo 0 step sampleStep) {
            if (!isLineBlack(accessor, horizontal = true, index = y, len = w)) { bottom = y; break }
        }
        for (x in 0 until w step sampleStep) {
            if (!isLineBlack(accessor, horizontal = false, index = x, len = h)) { left = x; break }
        }
        for (x in w - 1 downTo 0 step sampleStep) {
            if (!isLineBlack(accessor, horizontal = false, index = x, len = h)) { right = x; break }
        }

        top = top.coerceAtMost(bottom).coerceAtLeast(0)
        bottom = bottom.coerceAtLeast(top)
        left = left.coerceAtMost(right).coerceAtLeast(0)
        right = right.coerceAtLeast(left)
        return ViewportResult(left, top, right, bottom, w, h)
    }

    /** 判断一条横线(y=index)或竖线(x=index)是否整体接近全黑。 */
    private fun isLineBlack(accessor: PixelAccessor, horizontal: Boolean, index: Int, len: Int): Boolean {
        var sum = 0
        for (i in 0 until len) {
            val lum = if (horizontal) accessor.luminance(i, index) else accessor.luminance(index, i)
            sum += lum
        }
        return (sum / len) <= blackThreshold
    }
}

/** [Bitmap] 适配 [PixelAccessor]。 */
class BitmapPixelAccessor(private val bitmap: Bitmap) : PixelAccessor {
    override val width: Int get() = bitmap.width
    override val height: Int get() = bitmap.height

    override fun luminance(x: Int, y: Int): Int {
        val p = bitmap.getPixel(x, y)
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }
}
