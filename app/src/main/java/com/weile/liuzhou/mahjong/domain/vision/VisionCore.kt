package com.weile.liuzhou.mahjong.domain.vision

import android.graphics.Bitmap

/**
 * 视口检测抽象（vision-core 模块接口）。
 *
 * 职责：在一张屏幕截图中识别“游戏有效区域”，去除上下/左右黑边，
 * 输出归一化坐标，使后续牌区定位不依赖固定黑边高度。
 * 详见方案第 1 节、第 3.2 节。
 */
interface ViewportDetector {
    fun detect(bitmap: Bitmap): ViewportResult
}

/**
 * 视口检测的像素抽象入口（vision-core）。
 *
 * 与 [ViewportDetector] 相同的能力，但输入是 [PixelAccessor] 而非 [Bitmap]，
 * 使 ScreenRecognizer 等纯算法链路可以在不依赖 Android 运行时的 JVM 测试中验证。
 */
interface PixelViewportDetector {
    fun detect(accessor: PixelAccessor): ViewportResult
}

/**
 * 视口检测结果。
 *
 * @param left/top/right/bottom 游戏有效区域（绝对像素，基于输入图尺寸）。
 * @param imageWidth/imageHeight 输入图尺寸，用于把绝对坐标换算为归一化坐标。
 */
data class ViewportResult(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val imageWidth: Int,
    val imageHeight: Int,
) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)

    /** 归一化左上角 X（0..1）。 */
    val normLeft: Float get() = if (imageWidth > 0) left.toFloat() / imageWidth else 0f
    val normTop: Float get() = if (imageHeight > 0) top.toFloat() / imageHeight else 0f
    val normRight: Float get() = if (imageWidth > 0) right.toFloat() / imageWidth else 0f
    val normBottom: Float get() = if (imageHeight > 0) bottom.toFloat() / imageHeight else 0f

    override fun toString(): String = buildString {
        append("Viewport[left=$left, top=$top, right=$right, bottom=$bottom, ")
        append("size=${width}x$height, ")
        append("norm=(${String.format("%.3f", normLeft)},${String.format("%.3f", normTop)}," +
                "${String.format("%.3f", normRight)},${String.format("%.3f", normBottom)})]")
    }
}
