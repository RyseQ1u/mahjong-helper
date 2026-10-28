package com.weile.liuzhou.mahjong.vision

import com.weile.liuzhou.mahjong.domain.vision.PixelAccessor
import com.weile.liuzhou.mahjong.domain.vision.TileRegion

/**
 * 从父画面中裁剪子区域的 [PixelAccessor]（零拷贝）。
 * 用于把牌区格子喂给模板匹配器，无需为每格创建 Bitmap。
 */
class SubRegionPixelAccessor(
    private val parent: PixelAccessor,
    private val bounds: TileRegion,
) : PixelAccessor {

    override val width: Int = (bounds.right - bounds.left).coerceAtLeast(0)
    override val height: Int = (bounds.bottom - bounds.top).coerceAtLeast(0)

    override fun luminance(x: Int, y: Int): Int {
        if (width <= 0 || height <= 0) return 0
        val px = (bounds.left + x).coerceIn(0, parent.width - 1)
        val py = (bounds.top + y).coerceIn(0, parent.height - 1)
        return parent.luminance(px, py)
    }
}
