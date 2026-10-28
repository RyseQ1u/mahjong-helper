package com.weile.liuzhou.mahjong.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.weile.liuzhou.mahjong.analysis.AnalysisSettings
import com.weile.liuzhou.mahjong.capture.CaptureStatus
import com.weile.liuzhou.mahjong.domain.analysis.AnalysisMode
import com.weile.liuzhou.mahjong.ui.viewmodel.UiHint
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    status: CaptureStatus,
    lastSavedFramePath: String?,
    overlayPermission: Boolean,
    canCapture: Boolean,
    settings: AnalysisSettings,
    hint: UiHint?,
    onRequestCapture: () -> Unit,
    onStopCapture: () -> Unit,
    onSaveFrame: () -> Unit,
    onRequestOverlayPermission: () -> Unit,
    onSettingsChange: (AnalysisSettings) -> Unit,
    onResetRound: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onDismissHint: () -> Unit,
) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(title = { Text("微乐柳州麻将助手 · P4") })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "实时辅助：采集 → 识别 → 向听/进张 → 舍牌建议（悬浮窗展示）。" +
                        "识别不确定时会显示「等待确认」；置信度仅表示牌面识别质量，不是胜率。",
                style = MaterialTheme.typography.bodyMedium,
            )
            hint?.let { h ->
                HintCard(
                    hint = h,
                    onOpenNotificationSettings = onOpenNotificationSettings,
                    onDismiss = onDismissHint,
                )
            }
            StatusCard(status)

            Button(onClick = onRequestCapture, modifier = Modifier.fillMaxWidth()) {
                Text("开始屏幕采集（授权）")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onStopCapture,
                    enabled = canCapture,
                    modifier = Modifier.weight(1f),
                ) { Text("停止采集") }
                Button(
                    onClick = onSaveFrame,
                    enabled = canCapture,
                    modifier = Modifier.weight(1f),
                ) { Text("保存当前一帧") }
            }

            HorizontalDivider()
            SettingsCard(settings, onSettingsChange, onResetRound)

            HorizontalDivider()
            Text("悬浮窗权限（用于显示实时建议，可选）：", style = MaterialTheme.typography.labelLarge)
            if (overlayPermission) {
                Text("已授予", color = MaterialTheme.colorScheme.primary)
            } else {
                Text(
                    "未授予 —— 采集仍可运行，建议仅通过通知栏提示。",
                    color = MaterialTheme.colorScheme.error,
                )
                Button(onClick = onRequestOverlayPermission, modifier = Modifier.fillMaxWidth()) {
                    Text("去开启悬浮窗权限")
                }
            }

            lastSavedFramePath?.let {
                Text("最近保存帧：\n$it", style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "提示：采集时需将微信《微乐广西麻将》置于前台并切到横屏；" +
                        "分析帧期间悬浮窗会自动隐藏（自掩码），避免推荐文字污染识别；" +
                        "本工具默认不上传任何屏幕/昵称/头像/牌局数据。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun SettingsCard(
    settings: AnalysisSettings,
    onSettingsChange: (AnalysisSettings) -> Unit,
    onResetRound: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("对局配置", style = MaterialTheme.typography.titleMedium)

            // 箭牌开关（hasDragons：136 / 124）。切换会清空本局（方案 2.1）。
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("包含箭牌（中发白）", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (settings.hasDragons) "136 张牌" else "124 张牌（切换将清空本局）",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = settings.hasDragons,
                    onCheckedChange = { onSettingsChange(settings.copy(hasDragons = it)) },
                )
            }

            Text("出牌模式", style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AnalysisMode.entries.forEach { mode ->
                    FilterChip(
                        selected = settings.mode == mode,
                        onClick = { onSettingsChange(settings.copy(mode = mode)) },
                        label = {
                            Text(
                                when (mode) {
                                    AnalysisMode.SPEED -> "速度"
                                    AnalysisMode.BALANCED -> "综合"
                                    AnalysisMode.SAFETY -> "安全"
                                }
                            )
                        },
                    )
                }
            }

            Text("分析频率（性能档位）", style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AnalysisSettings.INTERVAL_PRESETS.forEach { ms ->
                    FilterChip(
                        selected = settings.analysisIntervalMs == ms,
                        onClick = { onSettingsChange(settings.copy(analysisIntervalMs = ms)) },
                        label = { Text("${ms}ms") },
                    )
                }
            }

            Button(onClick = onResetRound, modifier = Modifier.fillMaxWidth()) {
                Text("清空本局")
            }

            Text(
                "安全模式只做有限信息估计，不读对手暗牌；" +
                        "规则歧义未核实前不显示确定性得分。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun HintCard(
    hint: UiHint,
    onOpenNotificationSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                hint.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (hint.offerNotificationSettings) {
                    Button(onClick = onOpenNotificationSettings) { Text("去通知设置") }
                }
                TextButton(onClick = onDismiss) { Text("知道了") }
            }
        }
    }
}

@Composable
private fun StatusCard(status: CaptureStatus) {
    val text = when (status) {
        CaptureStatus.Idle -> "空闲"
        CaptureStatus.RequestingPermission -> "等待授权…"
        CaptureStatus.Starting -> "正在初始化采集…"
        is CaptureStatus.Capturing ->
            "采集中：帧=${status.frameCount}，FPS=${String.format(Locale.US, "%.1f", status.fps)}"
        CaptureStatus.Paused -> "已暂停"
        is CaptureStatus.Stopped -> "已停止：${status.reason}"
        is CaptureStatus.Error -> "错误：${status.message}"
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Text(
            text,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}
