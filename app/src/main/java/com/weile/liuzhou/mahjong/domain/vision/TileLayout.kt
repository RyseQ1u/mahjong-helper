package com.weile.liuzhou.mahjong.domain.vision

/**
 * 牌区布局（vision-core，P1）。
 *
 * 方案第 1 节画面标注：玩家自己在屏幕底部；其他玩家在左侧、顶部、右侧；
 * 中央是弃牌区。所有坐标相对 [ViewportResult] 的有效区域归一化（0..1），
 * 不依赖 2048×1280 等绝对像素。
 *
 * ⚠️ 以下默认布局基于用户提供的一张截图描述估算，**尚未在真实多设备样本上校准**；
 * P1 接入真实截图后必须逐区域核对并调整，且不同手机分辨率需分别测试。
 */
data class TileLayout(
    val selfHand: LayoutRegion,
    val selfDrawn: LayoutRegion,
    val discardSelf: LayoutRegion,
    val discardLeft: LayoutRegion,
    val discardTop: LayoutRegion,
    val discardRight: LayoutRegion,
) {
    companion object {
        /** 默认布局（横屏，底部为自家手牌）。 */
        val DEFAULT = TileLayout(
            selfHand = LayoutRegion(0.10f, 0.82f, 0.62f, 0.15f, expectedTiles = 13, horizontal = true),
            selfDrawn = LayoutRegion(0.76f, 0.84f, 0.08f, 0.12f, expectedTiles = 1, horizontal = true),
            discardSelf = LayoutRegion(0.20f, 0.60f, 0.24f, 0.16f, expectedTiles = 8, horizontal = true),
            discardLeft = LayoutRegion(0.02f, 0.18f, 0.10f, 0.40f, expectedTiles = 8, horizontal = false, rotationDeg = 90),
            discardTop = LayoutRegion(0.25f, 0.06f, 0.30f, 0.12f, expectedTiles = 8, horizontal = true),
            discardRight = LayoutRegion(0.88f, 0.18f, 0.10f, 0.40f, expectedTiles = 8, horizontal = false, rotationDeg = 270),
        )
    }
}

/**
 * 一个牌区（归一化坐标 + 期望牌数 + 方向）。
 * @param rotationDeg 该区域牌面相对正向的旋转角度（左右玩家约 ±90°）
 */
data class LayoutRegion(
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val expectedTiles: Int,
    val horizontal: Boolean = true,
    val rotationDeg: Int = 0,
) {
    /** 将归一化区域转换为视口内的绝对像素矩形。 */
    fun toAbsolute(viewport: ViewportResult): TileRegion {
        val left = (viewport.left + x * viewport.width).toInt()
        val top = (viewport.top + y * viewport.height).toInt()
        val right = (viewport.left + (x + w) * viewport.width).toInt()
        val bottom = (viewport.top + (y + h) * viewport.height).toInt()
        return TileRegion(left, top, right, bottom)
    }

    /** 将区域等分为 expectedTiles 个单牌格子（按阅读顺序）。 */
    fun cells(viewport: ViewportResult): List<TileRegion> {
        val abs = toAbsolute(viewport)
        val n = expectedTiles.coerceAtLeast(1)
        return if (horizontal) {
            val cw = abs.width / n
            (0 until n).map { i ->
                TileRegion(abs.left + i * cw, abs.top, abs.left + (i + 1) * cw, abs.bottom)
            }
        } else {
            val ch = abs.height / n
            (0 until n).map { i ->
                TileRegion(abs.left, abs.top + i * ch, abs.right, abs.top + (i + 1) * ch)
            }
        }
    }
}
