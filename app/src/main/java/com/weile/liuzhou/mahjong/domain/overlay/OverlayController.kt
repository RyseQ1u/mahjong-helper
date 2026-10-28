package com.weile.liuzhou.mahjong.domain.overlay

/**
 * 悬浮窗控制器（overlay-android 模块接口占位）。
 *
 * 方案第 3 节 / P0-3：开始/暂停分析、显示采集状态、收起/关闭；
 * 支持拖动、透明度调节。注意：采集时需对自己的悬浮窗区域加掩码或隐藏，
 * 否则推荐文字会污染截图识别（方案第 3.2 节第 6 点）。
 *
 * P0 已由 [com.weile.liuzhou.mahjong.overlay.FloatingOverlay] 实现。
 */
interface OverlayController {
    fun show(initialStatus: String)
    fun updateStatus(text: String)
    fun setPaused(paused: Boolean)
    /**
     * 显示/隐藏悬浮窗（不销毁内部状态）。
     * P4 自掩码用：分析帧期间隐藏，避免推荐文字污染截图识别（方案 3.2 第 6 点）。
     */
    fun setVisible(visible: Boolean)
    fun dismiss()
    val isShown: Boolean
}
