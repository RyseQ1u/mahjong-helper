package com.weile.liuzhou.mahjong.analysis

import com.weile.liuzhou.mahjong.domain.analysis.AnalysisMode

/**
 * 分析配置（P4 设置项，方案第 4 节 / P4 需求）。
 *
 * - [hasDragons]：牌集开关（136 / 124）。**切换时必须清空本局状态**
 *   （方案 2.1），由 UI 在切换时调用 `resetRound()`。
 * - [mode]：出牌模式（速度 / 综合 / 安全）。
 * - [analysisIntervalMs]：分析间隔（性能档位）。越小越实时，越耗电。
 * - [framesToSkipAfterMask]：掩码后丢弃的帧数（这些帧仍可能含悬浮窗，
 *   丢弃后取到的第一帧才保证干净）。
 */
data class AnalysisSettings(
    val hasDragons: Boolean = true,
    val mode: AnalysisMode = AnalysisMode.BALANCED,
    val analysisIntervalMs: Long = 500,
    val framesToSkipAfterMask: Int = 2,
) {
    companion object {
        /** 性能档位预设（毫秒）。 */
        val INTERVAL_PRESETS = listOf(250L, 500L, 1000L)
    }
}
