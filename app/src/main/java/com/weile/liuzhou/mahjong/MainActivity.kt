package com.weile.liuzhou.mahjong

import android.app.Activity
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
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
                    viewModel.markCaptureReady()
                    startCaptureService(result.resultCode, result.data!!)
                } else {
                    viewModel.markCaptureDenied()
                }
            }

        overlaySettingsLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                viewModel.setOverlayPermission(hasOverlayPermission(this))
            }

        notificationPermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

        // Android 13+：前台服务通知需要 POST_NOTIFICATIONS 权限，否则通知不显示。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            WeileLiuzhouMahjongTheme {
                val status by viewModel.status.collectAsState()
                val lastSavedFramePath by viewModel.lastSavedFramePath.collectAsState()
                val overlayPermission by viewModel.overlayPermission.collectAsState()
                val canCapture by viewModel.captureReady.collectAsState()
                MainScreen(
                    status = status,
                    lastSavedFramePath = lastSavedFramePath,
                    overlayPermission = overlayPermission,
                    canCapture = canCapture,
                    settings = analysisSettings,
                    onRequestCapture = { requestCapturePermission() },
                    onStopCapture = { sendToService(CaptureService.ACTION_STOP) },
                    onSaveFrame = { sendToService(CaptureService.ACTION_SAVE_FRAME) },
                    onRequestOverlayPermission = { requestOverlayPermission() },
                    onSettingsChange = ::onAnalysisSettingsChanged,
                    onResetRound = { AnalysisPipelineHolder.pipeline.resetRound() },
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

    private fun requestCapturePermission() {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as android.media.projection.MediaProjectionManager
        capturePermissionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            overlaySettingsLauncher.launch(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }
    }

    private fun startCaptureService(resultCode: Int, data: Intent) {
        val intent = Intent(this, CaptureService::class.java).apply {
            putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(CaptureService.EXTRA_DATA, data)
        }
        startServiceCompat(intent)
    }

    private fun sendToService(action: String) {
        val intent = Intent(this, CaptureService::class.java).apply { this.action = action }
        startServiceCompat(intent)
    }

    private fun startServiceCompat(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}
