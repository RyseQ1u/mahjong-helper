package com.weile.liuzhou.mahjong.vision

import com.weile.liuzhou.mahjong.domain.vision.PixelAccessor
import com.weile.liuzhou.mahjong.domain.vision.ScreenState
import com.weile.liuzhou.mahjong.domain.vision.ScreenStateClassifier

/**
 * 基于画面统计特征的启发式状态分类器（P1 框架实现）。
 *
 * ⚠️ 「准备/和/正在游戏」等状态的可靠判定依赖真实截图中的文字与锚点特征
 * （如顶部按钮区域、弹窗）。当前实现只覆盖可离线验证的稳健路径：
 * 整屏近黑（采集异常/黑屏保护）→ UNKNOWN，其余默认 PLAYING；
 * OWN_TURN / ANIMATION / SETTLEMENT 的精确判定需要真实样本标注后补齐规则，
 * 在补齐前调用方必须结合帧差与倒计时，且置信度不足时显示「请确认」。
 */
class HeuristicScreenStateClassifier(
    private val blackThreshold: Int = 24,
    private val blackRatioLimit: Float = 0.98f,
) : ScreenStateClassifier {

    override fun classify(screen: PixelAccessor): ScreenState {
        if (screen.width <= 0 || screen.height <= 0) return ScreenState.UNKNOWN

        val step = 8
        var total = 0
        var dark = 0
        var y = 0
        while (y < screen.height) {
            var x = 0
            while (x < screen.width) {
                total++
                if (screen.luminance(x, y) <= blackThreshold) dark++
                x += step
            }
            y += step
        }
        if (total == 0) return ScreenState.UNKNOWN
        val darkRatio = dark.toFloat() / total
        // 整屏近黑：采集未生效或游戏开启防截图保护（方案第 5 节：报告不支持，不绕过）。
        return if (darkRatio >= blackRatioLimit) ScreenState.UNKNOWN else ScreenState.PLAYING
    }
}
