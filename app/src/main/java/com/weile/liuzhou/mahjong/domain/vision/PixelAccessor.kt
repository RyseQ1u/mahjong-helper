package com.weile.liuzhou.mahjong.domain.vision

/**
 * 像素亮度访问抽象。
 *
 * 目的：把“黑边检测”的纯算法与 Android 的 [android.graphics.Bitmap] 解耦，
 * 使其可以在不依赖 Android 运行时的 JVM 单元测试中被验证（见 BlackBarViewportDetectorTest）。
 */
interface PixelAccessor {
    val width: Int
    val height: Int

    /** 返回坐标 (x, y) 处像素的近似亮度，范围 0..255。 */
    fun luminance(x: Int, y: Int): Int
}
