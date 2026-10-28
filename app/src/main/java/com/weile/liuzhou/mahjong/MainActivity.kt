package com.weile.liuzhou.mahjong

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.weile.liuzhou.mahjong.analysis.AnalysisPipelineHolder
import com.weile.liuzhou.mahjong.analysis.AnalysisSettings
import com.weile.liuzhou.mahjong.capture.CaptureService
import com.weile.liuzhou.mahjong.storage.StorageModule
import kotlinx.coroutines.launch
import com.weile.liuzhou.mahjong.ui.MainScreen
import com.weile.liuzhou.mahjong.ui.theme.WeileLiuzhouMahjongTheme
import com.weile.liuzhou.mahjong.ui.viewmodel.MainViewModel
import com.weile.liuzhou.mahjong.util.hasOverlayPermission

class MainActivity : ComponentActivity() {

    private lateinit var capturePermissionLauncher: ActivityResultLauncher<Intent>
    private lateinit var overlaySettingsLauncher: ActivityResultLauncher<Intent>
    private lateinit var notificationPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var notificationSettingsLauncher: ActivityResultLauncher<Intent>
    private lateinit var viewModel: MainViewModel

    /** 对局配置（P4 设置项；变更同步到分析管线）。 */
    private var analysisSettings by mutableStateOf(AnalysisSettings())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this)[MainViewModel::class.java]
        viewModel.setOverlayPermission(hasOverlayPermission(this))

        // 读取持久化的对局配置（storage 模块）
        lifecycleScope.launch {
            val loaded = StorageModule.settings(this@MainActivity).load()
            analysisSettings = loaded
            AnalysisPipelineHolder.settings = loaded
        }

        capturePermissionLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                    // Android 14+：每次新会话需重新授权，令牌不可复用（方案第 5 节）。
                    startCaptureService(result.resultCode, result.data!!)
                } else {
                    viewModel.showHint(
                        "已取消屏幕采集授权。再次点击「开始屏幕采集」可重新请求；" +
                            "系统连续拒绝后会静默拒绝一段时间。"
                    )
                }
            }

        overlaySettingsLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                viewModel.setOverlayPermission(hasOverlayPermission(this))
            }

        // 三态处理：授予 → 继续；可再询问 → 说明后果；永久拒绝 → 引导到系统设置。
        notificationPermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                if (!granted) {
                    // 该权限常量是编译期内联的字符串，仅 API 33+ 有意义；
                    // 显式短路保证低版本不会走到这里（同时满足 lint 的 InlinedApi 检查）。
                    val canAskAgain = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                        shouldShowRequestPermissionRationale(
                            Manifest.permission.POST_NOTIFICATIONS
                        )
                    viewModel.showHint(
                        if (canAskAgain) {
                            "未授予通知权限：采集不受影响，但采集状态不会显示在通知栏。"
                        } else {
                            "通知权限已被永久拒绝：请在系统设置中允许通知，否则无法看到采集状态通知。"
                        },
                        offerNotificationSettings = !canAskAgain,
                    )
                }
                // 通知权限结果不影响屏幕采集授权本身，继续请求 MediaProjection。
                launchProjectionRequest()
            }

        notificationSettingsLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

        setContent {
            WeileLiuzhouMahjongTheme {
                // collectAsStateWithLifecycle：应用进入后台时停止收集，避免无谓的重组与回调。
                val status by viewModel.status.collectAsStateWithLifecycle()
                val lastSavedFramePath by viewModel.lastSavedFramePath.collectAsStateWithLifecycle()
                val overlayPermission by viewModel.overlayPermission.collectAsStateWithLifecycle()
                val canCapture by viewModel.captureReady.collectAsStateWithLifecycle()
                val hint by viewModel.hint.collectAsStateWithLifecycle()

                // 方法引用/闭包每次重组都会产生新实例，破坏子组件的参数稳定性；统一 remember。
                val onRequestCapture = remember { { requestCapturePermission() } }
                val onStopCapture = remember { { sendControlAction(CaptureService.ACTION_STOP) } }
                val onSaveFrame = remember { { sendControlAction(CaptureService.ACTION_SAVE_FRAME) } }
                val onRequestOverlayPermission = remember { { requestOverlayPermission() } }
                val onSettingsChange = remember { { s: AnalysisSettings -> onAnalysisSettingsChanged(s) } }
                val onResetRound = remember { { AnalysisPipelineHolder.pipeline.resetRound() } }
                val onOpenNotificationSettings = remember { { openNotificationSettings() } }
                val onDismissHint = remember { { viewModel.clearHint() } }

                MainScreen(
                    status = status,
                    lastSavedFramePath = lastSavedFramePath,
                    overlayPermission = overlayPermission,
                    canCapture = canCapture,
                    settings = analysisSettings,
                    hint = hint,
                    onRequestCapture = onRequestCapture,
                    onStopCapture = onStopCapture,
                    onSaveFrame = onSaveFrame,
                    onRequestOverlayPermission = onRequestOverlayPermission,
                    onSettingsChange = onSettingsChange,
                    onResetRound = onResetRound,
                    onOpenNotificationSettings = onOpenNotificationSettings,
                    onDismissHint = onDismissHint,
                )
            }
        }
    }

    /**
     * 配置变更同步到分析管线并持久化（storage 模块）。
     * 箭牌开关切换 = 牌集切换（136/124），必须清空本局状态（方案 2.1）。
     */
    private fun onAnalysisSettingsChanged(new: AnalysisSettings) {
        val dragonsChanged = new.hasDragons != analysisSettings.hasDragons
        analysisSettings = new
        AnalysisPipelineHolder.settings = new
        lifecycleScope.launch {
            runCatching { StorageModule.settings(this@MainActivity).save(new) }
        }
        if (dragonsChanged) {
            AnalysisPipelineHolder.pipeline.resetRound()
        }
    }

    /**
     * 开始采集：先确保通知权限（前台服务通知需要），再请求屏幕录制授权。
     * 通知权限延迟到真正需要时才请求，不在冷启动打扰用户。
     */
    private fun requestCapturePermission() {
        viewModel.clearHint()
        val needsNotification = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        if (needsNotification) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        launchProjectionRequest()
    }

    private fun launchProjectionRequest() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        // createScreenCaptureIntent 在极端情况下（无可用显示/策略限制）可能抛异常，
        // 不能让异常直接冒泡杀掉 Activity。
        runCatching { capturePermissionLauncher.launch(mpm.createScreenCaptureIntent()) }
            .onFailure { viewModel.showHint("无法请求屏幕录制授权：${it.message}") }
    }

    private fun requestOverlayPermission() {
        // minSdk 29，ACTION_MANAGE_OVERLAY_PERMISSION 必然可用。
        runCatching {
            overlaySettingsLauncher.launch(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }.onFailure {
            viewModel.showHint("无法打开悬浮窗权限设置：${it.message}")
        }
    }

    private fun openNotificationSettings() {
        runCatching {
            notificationSettingsLauncher.launch(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            )
        }.onFailure { viewModel.showHint("无法打开通知设置：${it.message}") }
    }

    private fun startCaptureService(resultCode: Int, data: Intent) {
        val intent = Intent(this, CaptureService::class.java).apply {
            putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(CaptureService.EXTRA_DATA, data)
        }
        // 仅「启动采集」需要 startForegroundService；服务随后会立即 startForeground。
        startForegroundService(intent)
    }

    /**
     * 控制指令（停止 / 保存一帧）。
     *
     * 必须用 startService：这些指令的目标是**已经在运行的前台服务**，
     * 用 startForegroundService 送达时若服务走的是控制分支、没有再次调用 startForeground，
     * 系统会在 5 秒后抛 ForegroundServiceDidNotStartInTimeException（ANR 类崩溃）。
     * 服务未运行时该调用只会触发一次空指令，服务自行 stopSelf 回收。
     */
    private fun sendControlAction(action: String) {
        val intent = Intent(this, CaptureService::class.java).apply { this.action = action }
        runCatching { startService(intent) }
            .onFailure { viewModel.showHint("采集服务未在运行（${it.message}）") }
    }
}
