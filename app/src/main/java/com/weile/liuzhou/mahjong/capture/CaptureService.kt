package com.weile.liuzhou.mahjong.capture

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.weile.liuzhou.mahjong.R
import com.weile.liuzhou.mahjong.analysis.AnalysisPipelineHolder
import com.weile.liuzhou.mahjong.domain.analysis.Advice
import com.weile.liuzhou.mahjong.domain.storage.DebugSampleRecord
import com.weile.liuzhou.mahjong.domain.storage.RoundRecord
import com.weile.liuzhou.mahjong.overlay.FloatingOverlay
import com.weile.liuzhou.mahjong.storage.StorageModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 屏幕采集前台服务（P0 核心）。
 *
 * 职责（方案第 5 节 / P0-2）：
 * - 用户授权后创建 MediaProjection，经 VirtualDisplay + ImageReader 持续取帧；
 * - 单次取帧（保存）与连续取帧（状态展示）都支持；
 * - Android 14+ 每次新会话需重新授权，令牌不可复用；
 * - 横竖屏变化优先 resize / setSurface，不无故新建 VirtualDisplay；
 * - 通过前台通知 + 可选悬浮窗展示状态；停止时安全释放全部资源。
 *
 * 不做自动代打、不读取微信进程、不上传任何数据。
 */
class CaptureService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_DATA = "extra_data"
        const val ACTION_STOP = "com.weile.liuzhou.mahjong.action.STOP"
        const val ACTION_TOGGLE_PAUSE = "com.weile.liuzhou.mahjong.action.TOGGLE_PAUSE"
        const val ACTION_SAVE_FRAME = "com.weile.liuzhou.mahjong.action.SAVE_FRAME"
        private const val TAG = "CaptureService"
        private const val CHANNEL_ID = "capture_channel"
        private const val NOTIF_ID = 1001
    }

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null

    private var latestBitmap: Bitmap? = null
    private val frameLock = Any()

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private val mainHandler = Handler(Looper.getMainLooper())
    private var imageThread: HandlerThread? = null
    private var imageHandler: Handler? = null

    /** 存储协程（局历史/样本元数据）；独立于采集 scope，避免停止采集时写库被取消。 */
    private val storageScope = CoroutineScope(Dispatchers.IO + Job())

    private var overlay: FloatingOverlay? = null
    private var paused = false

    @Volatile
    private var frameCount = 0L
    private var fpsWindowStart = 0L
    @Volatile
    private var fps = 0f

    private val mediaProjectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "MediaProjection stopped by system")
            stopCapture("MediaProjection 被系统停止")
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            // 屏幕旋转/分辨率变化：重建显示面，避免重建整个 VirtualDisplay（方案第 5 节）。
            mainHandler.post { rebuildDisplaySurfaces() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        mediaProjectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        createNotificationChannel()
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        dm.registerDisplayListener(displayListener, mainHandler)
        wireAnalysisPipeline()
    }

    /**
     * 接通分析管线（P4）：服务喂帧 → 管线识别/推导/建议 → 悬浮窗展示。
     * - onMaskChanged：分析期间隐藏悬浮窗（自掩码，方案 3.2 第 6 点）；
     * - onAdvice：渲染候选与置信度；
     * - onStatus：状态文本；
     * - onRoundEnded：一局结束 → 持久化事件流（storage 模块，P3 回溯）。
     */
    private fun wireAnalysisPipeline() {
        AnalysisPipelineHolder.pipeline.apply {
            onMaskChanged = { masked ->
                mainHandler.post { overlay?.setVisible(!masked) }
            }
            onStatus = { text ->
                mainHandler.post { updateOverlay(text) }
            }
            onAdvice = { advice: Advice ->
                mainHandler.post { overlay?.updateAdvice(advice) }
            }
            onRoundEnded = { state, startedAt, endedAt ->
                storageScope.launch {
                    runCatching {
                        StorageModule.rounds(this@CaptureService).saveRound(
                            RoundRecord(
                                roundIndex = state.roundIndex,
                                startedAt = startedAt,
                                endedAt = endedAt,
                                eventCount = state.events.size,
                                note = "phase=${state.phase}",
                            ),
                            state.events,
                        )
                        Log.i(TAG, "round persisted: index=${state.roundIndex}, events=${state.events.size}")
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopCapture("用户停止")
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_PAUSE -> {
                togglePause()
                return START_NOT_STICKY
            }
            ACTION_SAVE_FRAME -> {
                saveCurrentFrame()
                return START_NOT_STICKY
            }
        }

        // 进入采集路径：先挂前台通知（满足 startForegroundService 时限）。
        startForeground(NOTIF_ID, buildNotification("准备采集…"))

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_DATA)
        }

        if (resultCode == Activity.RESULT_CANCELED || data == null) {
            CaptureStateHolder.setStatus(
                CaptureStatus.Error("采集授权无效（resultCode=CANCELED 或 data 为空）")
            )
            stopCapture("授权无效")
            return START_NOT_STICKY
        }
        startCapture(resultCode, data)
        return START_NOT_STICKY
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        CaptureStateHolder.setStatus(CaptureStatus.Starting)
        try {
            mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data).apply {
                registerCallback(mediaProjectionCallback, mainHandler)
            }
        } catch (e: Exception) {
            CaptureStateHolder.setStatus(CaptureStatus.Error("创建 MediaProjection 失败：${e.message}"))
            stopCapture("创建失败")
            return
        }

        imageThread = HandlerThread("capture-image").also { it.start() }
        imageHandler = Handler(imageThread!!.looper)

        setupVirtualDisplay()
        showOverlay("正在采集…")

        fpsWindowStart = System.currentTimeMillis()
        frameCount = 0
        // 周期性刷新状态（帧数 / FPS）。
        scope.launch {
            while (isActive && mediaProjection != null) {
                delay(1000)
                val elapsed = (System.currentTimeMillis() - fpsWindowStart).coerceAtLeast(1)
                fps = frameCount * 1000f / elapsed
                if (!paused) {
                    CaptureStateHolder.setStatus(CaptureStatus.Capturing(frameCount, fps))
                    updateOverlay("采集中 | 帧:$frameCount | ${"%.1f".format(fps)}fps")
                }
            }
        }
    }

    private fun setupVirtualDisplay() {
        val metrics = realMetrics()
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi
        Log.i(TAG, "screen size = ${width}x$height @$density")

        imageReader = newImageReader(width, height, density)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "WeileCapture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            object : VirtualDisplay.Callback() {
                override fun onStopped() {
                    Log.i(TAG, "virtual display stopped")
                }
            },
            mainHandler
        )
    }

    private fun newImageReader(width: Int, height: Int, density: Int): ImageReader {
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        reader.setOnImageAvailableListener(imageListener, imageHandler)
        return reader
    }

    private val imageListener = ImageReader.OnImageAvailableListener { reader ->
        // 暂停时丢弃帧（仍须 acquire 释放缓冲，避免堆积）。
        val image = reader.acquireLatestImage() ?: return@OnImageAvailableListener
        if (paused) {
            image.close()
            return@OnImageAvailableListener
        }
        try {
            val plane = image.planes[0]
            Log.i(
                TAG,
                "frame ${image.width}x${image.height} rowStride=${plane.rowStride} " +
                    "pixelStride=${plane.pixelStride} cap=${plane.buffer.capacity()}"
            )
            val bmp = imageToBitmap(image)
            synchronized(frameLock) {
                latestBitmap?.recycle()
                latestBitmap = bmp
            }
            frameCount++
            // 喂给分析管线（P4）。位图只读；管线内部节流 + 自掩码。
            // 管线当前模板库为空，识别结果为 unknown → 建议走「等待确认」安全路径。
            runCatching { AnalysisPipelineHolder.pipeline.onFrame(bmp) }
        } catch (e: Exception) {
            Log.w(TAG, "frame convert failed: ${e.message}")
        } finally {
            image.close()
        }
    }

    private fun rebuildDisplaySurfaces() {
        if (mediaProjection == null || virtualDisplay == null) return
        val metrics = realMetrics()
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi
        val old = imageReader
        val newReader = newImageReader(width, height, density)
        imageReader = newReader
        try {
            virtualDisplay?.setSurface(newReader.surface)
        } catch (e: Exception) {
            Log.w(TAG, "setSurface failed, recreate VirtualDisplay: ${e.message}")
            virtualDisplay?.release()
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "WeileCapture", width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                newReader.surface, null, mainHandler
            )
        }
        // 先摘除 listener 再关闭，避免 imageHandler 线程仍持有旧 reader 的 image 时发生 use-after-close。
        old?.setOnImageAvailableListener(null, null)
        old?.close()
        Log.i(TAG, "rebuilt display surfaces -> ${width}x$height")
    }

    private fun togglePause() {
        paused = !paused
        overlay?.setPaused(paused)
        if (paused) {
            CaptureStateHolder.setStatus(CaptureStatus.Paused)
            updateOverlay("已暂停")
        } else {
            fpsWindowStart = System.currentTimeMillis()
            CaptureStateHolder.setStatus(CaptureStatus.Capturing(frameCount, fps))
            updateOverlay("采集中 | 帧:$frameCount")
        }
    }

    private fun saveCurrentFrame() {
        val bmp = synchronized(frameLock) {
            latestBitmap?.copy(Bitmap.Config.ARGB_8888, false)
        }
        if (bmp == null) {
            updateOverlay("暂无帧可保存")
            return
        }
        try {
            val path = FrameSaver.saveFrame(this, bmp, "manual")
            CaptureStateHolder.setLastSavedFramePath(path)
            // 调试样本只记元数据（不存图像内容；隐私：仅本地）
            storageScope.launch {
                runCatching {
                    StorageModule.debugSamples(this@CaptureService).record(
                        DebugSampleRecord(
                            filePath = path,
                            tag = "manual",
                            savedAt = System.currentTimeMillis(),
                        )
                    )
                }
            }
            updateOverlay("已保存一帧")
        } catch (e: Exception) {
            CaptureStateHolder.setStatus(CaptureStatus.Error("保存帧失败：${e.message}"))
        } finally {
            bmp.recycle()
        }
    }

    private fun stopCapture(reason: String) {
        Log.i(TAG, "stopCapture: $reason")
        scope.cancel()
        // 停止推荐并清空本局（方案 P4：无法采集则停止推荐）
        AnalysisPipelineHolder.pipeline.onCaptureStopped()
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        try { mediaProjection?.unregisterCallback(mediaProjectionCallback) } catch (_: Exception) {}
        try { mediaProjection?.stop() } catch (_: Exception) {}
        virtualDisplay = null
        imageReader = null
        mediaProjection = null
        synchronized(frameLock) { latestBitmap?.recycle(); latestBitmap = null }
        imageThread?.quitSafely()
        imageThread = null
        dismissOverlay()
        CaptureStateHolder.setStatus(CaptureStatus.Stopped(reason))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun realMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
            .getRealMetrics(metrics)
        return metrics
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val width = image.width
        val height = image.height
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowBytes = width * pixelStride
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        // 始终逐行拷贝。实测（模拟器 API 34）：整块 copyPixelsFromBuffer 会在 ImageReader 的
        // buffer 上发生 SIGSEGV（驱动层映射异常，重复采集/横竖屏重建后必现），
        // 逐行 setPixels 每行只拷 rowBytes 字节、且与目标位图 stride 精确一致，稳定优先。
        val rowPixels = IntArray(width)
        for (y in 0 until height) {
            val pos = y.toLong() * rowStride
            if (pos + rowBytes > buffer.capacity()) break
            buffer.position(pos.toInt())
            buffer.asIntBuffer().get(rowPixels)
            bmp.setPixels(rowPixels, 0, width, 0, y, width, 1)
        }
        return bmp
    }

    private fun showOverlay(status: String) {
        if (!Settings.canDrawOverlays(this)) return  // 无悬浮窗权限则仅用通知栏
        if (overlay == null) {
            overlay = FloatingOverlay(
                this,
                object : FloatingOverlay.OverlayCallbacks {
                    override fun onTogglePause() = togglePause()
                    override fun onClose() = stopCapture("用户关闭")
                }
            )
        }
        overlay?.show(status)
    }

    private fun updateOverlay(text: String) {
        overlay?.updateStatus(text)
    }

    private fun dismissOverlay() {
        overlay?.dismiss()
        overlay = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(chan)
        }
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = Intent(this, CaptureService::class.java).apply { action = ACTION_STOP }
        val pi = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_capture_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", pi)
            .setContentIntent(pi)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        try {
            (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
                .unregisterDisplayListener(displayListener)
        } catch (_: Exception) {
        }
        scope.cancel()
        storageScope.cancel()
        imageThread?.quitSafely()
        overlay?.dismiss()
    }
}
