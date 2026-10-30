package com.weile.liuzhou.mahjong.vision

import com.weile.liuzhou.mahjong.domain.vision.PixelAccessor
import com.weile.liuzhou.mahjong.domain.vision.TileRegion

/**
 * 带旋转的父画面子区域访问器（零拷贝）。
 *
 * 左右玩家的弃牌区牌面相对正向旋转 ±90°（见 [com.weile.liuzhou.mahjong.domain.vision.LayoutRegion.rotationDeg]），
 * 模板匹配前需把子区域旋转回正向。
 *
 * 约定：rotationDeg 为「牌面相对正向顺时针旋转的角度」，此处反向旋转回正向：
 * - 90（左侧）：逆时针 90° —— dst(x,y) = src(W-1-y, x)
 * - 270（右侧）：顺时针 90° —— dst(x,y) = src(y, H-1-x)
 *
 * ⚠️ 旋转方向需在接入真实模板后，按左右弃牌区实测校准（当前按常见竖排放置约定实现）。
 */
class RotatedSubRegionPixelAccessor(
    private val parent: PixelAccessor,
    private val bounds: TileRegion,
    private val rotationDeg: Int,
) : PixelAccessor {

    private val srcW = (bounds.right - bounds.left).coerceAtLeast(0)
    private val srcH = (bounds.bottom - bounds.top).coerceAtLeast(0)

    private val rotated = rotationDeg % 180 != 0

    override val width: Int = if (rotated) srcH else srcW
    override val height: Int = if (rotated) srcW else srcH

    override fun luminance(x: Int, y: Int): Int {
        if (width <= 0 || height <= 0) return 0
        if (parent.width <= 0 || parent.height <= 0) return 0
        val sx: Int
        val sy: Int
        when (rotationDeg % 360) {
            90 -> {
                // 逆时针 90°：把顺时针放置的牌面转正
                sx = srcW - 1 - y
                sy = x
            }
            180 -> {
                sx = srcW - 1 - x
                sy = srcH - 1 - y
            }
            270 -> {
                // 顺时针 90°：把逆时针放置的牌面转正
                sx = y
                sy = srcH - 1 - x
            }
            else -> {
                sx = x
                sy = y
            }
        }
        val px = (bounds.left + sx).coerceIn(0, parent.width - 1)
        val py = (bounds.top + sy).coerceIn(0, parent.height - 1)
        return parent.luminance(px, py)
    }
}
