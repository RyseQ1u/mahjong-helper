package com.weile.liuzhou.mahjong.capture

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 采集状态机（跨 Activity / Service 共享的轻量状态）。
 * 与方案第 3.1 节的状态枚举对应，P0 主要用到 Idle / Starting / Capturing / Paused / Stopped / Error。
 */
sealed interface CaptureStatus {
    data object Idle : CaptureStatus
    data object RequestingPermission : CaptureStatus
    data object Starting : CaptureStatus
    data class Capturing(val frameCount: Long, val fps: Float) : CaptureStatus
    data object Paused : CaptureStatus
    data class Stopped(val reason: String) : CaptureStatus
    data class Error(val message: String) : CaptureStatus
}

/**
 * 进程内共享状态。只保存元数据（帧数、FPS、最近错误、最近保存路径），
 * 不长期持有整帧 Bitmap，避免内存压力。单帧保存由 FrameSaver 按需触发。
 *
 * 说明：P0 为单进程 App，用 object 单例足够；后续若拆分 :capture 独立进程，
 * 应改为通过 Messenger / Broadcast / 文件共享状态。
 */
object CaptureStateHolder {
    private val _status = MutableStateFlow<CaptureStatus>(CaptureStatus.Idle)
    val status: StateFlow<CaptureStatus> = _status.asStateFlow()

    private val _lastSavedFramePath = MutableStateFlow<String?>(null)
    val lastSavedFramePath: StateFlow<String?> = _lastSavedFramePath.asStateFlow()

    fun setStatus(status: CaptureStatus) {
        _status.value = status
    }

    fun setLastSavedFramePath(path: String) {
        _lastSavedFramePath.value = path
    }
}
