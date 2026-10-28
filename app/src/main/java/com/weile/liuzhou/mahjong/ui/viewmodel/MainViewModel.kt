package com.weile.liuzhou.mahjong.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.weile.liuzhou.mahjong.capture.CaptureStateHolder
import com.weile.liuzhou.mahjong.capture.CaptureStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * 首页提示条内容。
 *
 * @param offerNotificationSettings 为 true 时提供「去通知设置」入口，
 *   仅在通知权限被**永久拒绝**（无法再次弹窗）时使用。
 */
data class UiHint(
    val text: String,
    val offerNotificationSettings: Boolean = false,
)

/**
 * 仅作为 UI 状态容器：把 [CaptureStateHolder]（采集服务写入）桥接到 Compose。
 * 启动/停止采集服务由 MainActivity 持有 Context 完成。
 *
 * 注意：`status` / `lastSavedFramePath` 直接复用共享 StateFlow，
 * 不再另建 MutableStateFlow 转发（省掉两个常驻收集协程和一次无谓的重复发射）。
 */
class MainViewModel : ViewModel() {

    val status: StateFlow<CaptureStatus> = CaptureStateHolder.status

    val lastSavedFramePath: StateFlow<String?> = CaptureStateHolder.lastSavedFramePath

    private val _overlayPermission = MutableStateFlow(false)
    val overlayPermission: StateFlow<Boolean> = _overlayPermission.asStateFlow()

    /**
     * 是否有采集会话在跑。由 [status] 派生而不是独立标志位：
     * 独立标志位在「停止采集 / 服务异常终止」时不会复位，会让「停止」「保存一帧」按钮
     * 在服务已死的情况下仍可点击。
     */
    val captureReady: StateFlow<Boolean> = status
        .map { s ->
            s is CaptureStatus.Capturing || s is CaptureStatus.Starting || s is CaptureStatus.Paused
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _hint = MutableStateFlow<UiHint?>(null)
    val hint: StateFlow<UiHint?> = _hint.asStateFlow()

    fun setOverlayPermission(granted: Boolean) {
        _overlayPermission.value = granted
    }

    fun showHint(text: String, offerNotificationSettings: Boolean = false) {
        _hint.value = UiHint(text, offerNotificationSettings)
    }

    fun clearHint() {
        _hint.value = null
    }
}
