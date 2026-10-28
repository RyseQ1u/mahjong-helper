package com.weile.liuzhou.mahjong.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.weile.liuzhou.mahjong.capture.CaptureStateHolder
import com.weile.liuzhou.mahjong.capture.CaptureStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 仅作为 UI 状态容器：把 [CaptureStateHolder]（采集服务写入）桥接到 Compose。
 * 启动/停止采集服务由 MainActivity 持有 Context 完成。
 */
class MainViewModel : ViewModel() {

    private val _status = MutableStateFlow<CaptureStatus>(CaptureStatus.Idle)
    val status: StateFlow<CaptureStatus> = _status.asStateFlow()

    private val _lastSavedFramePath = MutableStateFlow<String?>(null)
    val lastSavedFramePath: StateFlow<String?> = _lastSavedFramePath.asStateFlow()

    private val _overlayPermission = MutableStateFlow(false)
    val overlayPermission: StateFlow<Boolean> = _overlayPermission.asStateFlow()

    private val _captureReady = MutableStateFlow(false)
    val captureReady: StateFlow<Boolean> = _captureReady.asStateFlow()

    init {
        viewModelScope.launch { CaptureStateHolder.status.collect { _status.value = it } }
        viewModelScope.launch {
            CaptureStateHolder.lastSavedFramePath.collect { _lastSavedFramePath.value = it }
        }
    }

    fun markCaptureReady() {
        _captureReady.value = true
    }

    fun markCaptureDenied() {
        _captureReady.value = false
    }

    fun setOverlayPermission(granted: Boolean) {
        _overlayPermission.value = granted
    }
}
