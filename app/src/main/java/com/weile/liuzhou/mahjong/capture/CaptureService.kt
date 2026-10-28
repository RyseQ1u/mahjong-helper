package com.weile.liuzhou.mahjong.capture

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
import android.view.Display
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.weile.liuzhou.mahjong.BuildConfig
import com.weile.liuzhou.mahjong.MainActivity
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

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

    /** 当前 surface 的尺寸；与屏幕实际尺寸一致时无需重建（防止重复重建产生黑帧）。 */
    private var surfaceWidth = 0
    private var surfaceHeight = 0

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private val mainHandler = Handler(Looper.getMainLooper())
    private var imageThread: HandlerThread? = null
    private var imageHandler: Handler? = null

    /** 存储协程（局历史/样本元数据）；独立于采集 scope，避免停止采集时写库被取消。 */
    private val storageScope = CoroutineScope(Dispatchers.IO + Job())

    private var overlay: FloatingOverlay? = null
    private var paused = false

    /** 停止流程幂等标记：系统回调 / 用户操作 / 异常路径可能重复触发 stopCapture。 */
    @Volatile
    private var stopping = false

    /** 单帧保存去重：避免连点造成并发的全屏位图拷贝与 PNG 编码。 */
    private val saveInFlight = AtomicBoolean(false)

    @Volatile
    private var frameCount = 0L
    private var fpsWindowStart = 0L
    @Volatile
    private var fps = 0f

    /**
     * 一次性提示（如「已保存一帧」）的保护截止时间。
     * 每秒一次的 FPS 刷新会立刻覆盖这类提示，导致用户看不到操作结果。
     */
    @Volatile
    private var overlayHoldUntilMs = 0L

    private val mediaProjectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "MediaProjection stopped by system")
            stopCapture("MediaProjection 被系统停止")
        }
    }

    /**
     * 显示变化监听（屏幕旋转 / 分辨率变化时重建 surface，方案第 5 节）。
     *
     * **必须过滤 [Display.DEFAULT_DISPLAY]**：MediaProjection 创建的 VirtualDisplay 自身
     * 也会向 DisplayManager 派发 added/changed/removed 事件，其 displayId 是虚拟屏 id。
     * 若不过滤，`setSurface` 会让系统再次派发 changed，形成
     * 「changed → 重建 → setSurface → changed」的高频重建循环，
     * 真机上表现为采集画面持续闪烁并伴随高耗电。
     * 模拟器不派发该事件，因此该缺陷此前未在模拟器验收中暴露。
     */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
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
                    // NonCancellable：一局结束往往紧接停止采集，onDestroy 会取消 storageScope，
                    // 若不加保护，最后一局的落库会被取消（数据静默丢失）。
                    withContext(NonCancellable) {
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
                        }.onFailure { Log.w(TAG, "round persist failed: ${it.message}") }
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action != null) {
            when (action) {
                ACTION_STOP -> stopCapture("用户停止")
                ACTION_TOGGLE_PAUSE -> togglePause()
                ACTION_SAVE_FRAME -> saveCurrentFrame()
                else -> Log.w(TAG, "unknown action: $action")
            }
            // 控制指令可能唤起了一个未在采集的实例（服务已被系统回收后从通知栏/悬浮窗回跳）。
            // 此类实例没有 MediaProjection，却不会有前台通知，必须立即回收，
            // 否则成为无通知的常驻后台服务。
            if (mediaProjection == null) stopSelf()
            return START_NOT_STICKY
        }

        // 进入采集路径：先挂前台通知（满足 startForegroundService 时限）。
        // 显式指定 mediaProjection 类型：Android 14+ 要求 FGS 类型与实际使用的能力一致，
        // 隐式依赖 manifest 声明在类型增多后会抛 MissingForegroundServiceTypeException。
        ServiceCompat.startForeground(
            this,
            NOTIF_ID,
            buildNotification("准备采集…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )

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
        // 重复启动保护：连点「开始采集」会带来两次 onStartCommand，第二次的
        // getMediaProjection 无法复用首个令牌（Android 14+ 直接抛 SecurityException），
        // 且首个 projection 会泄漏（未 stop 的投影会持续占用编码器资源）。
        if (mediaProjection != null) {
            Log.w(TAG, "startCapture ignored: projection already active")
            return
        }
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
                    // 保护期内不覆盖一次性提示（保存结果等）。
                    if (System.currentTimeMillis() >= overlayHoldUntilMs) {
                        updateOverlay("采集中 | 帧:$frameCount | ${"%.1f".format(fps)}fps")
                    }
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

        imageReader = newImageReader(width, height)
        surfaceWidth = width
        surfaceHeight = height
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

    private fun newImageReader(width: Int, height: Int): ImageReader {
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
            // 逐帧日志在 30–60fps 下等于每秒数十次 logcat 写入（格式化 + IPC），
            // 是采集热路径上的纯开销：仅 debug 构建保留低频诊断（首帧 + 每 300 帧）。
            if (BuildConfig.DEBUG && (frameCount < 2L || frameCount % 300L == 0L)) {
                val plane = image.planes[0]
                Log.d(
                    TAG,
                    "frame ${image.width}x${image.height} rowStride=${plane.rowStride} " +
                        "pixelStride=${plane.pixelStride} cap=${plane.buffer.capacity()}"
                )
            }
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
        // 尺寸未变则跳过：多数 onDisplayChanged 由刷新率/亮度变化触发，与 surface 无关，
        // 重建会强制产生一帧黑屏（视觉闪烁）且无收益。
        if (width == surfaceWidth && height == surfaceHeight) {
            Log.d(TAG, "display changed but size unchanged ($width x $height), skip rebuild")
            return
        }
        val old = imageReader
        val newReader = newImageReader(width, height)
        imageReader = newReader
        try {
            virtualDisplay?.setSurface(newReader.surface)
            surfaceWidth = width
            surfaceHeight = height
        } catch (e: Exception) {
            Log.w(TAG, "setSurface failed, recreate VirtualDisplay: ${e.message}")
            virtualDisplay?.release()
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "WeileCapture", width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                newReader.surface, null, mainHandler
            )
            surfaceWidth = width
            surfaceHeight = height
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

    /**
     * 保存当前帧（P0-4）。
     *
     * 调用链：`onStartCommand(ACTION_SAVE_FRAME)` → 本方法，**在主线程执行**。
     * 全屏位图拷贝（1440×3200 ARGB ≈ 18MB）+ PNG 编码 + 落盘若留在主线程，
     * 单次点击即可造成数百毫秒到数秒的卡顿（ANR 风险），因此整体下放到 IO 协程。
     */
    private fun saveCurrentFrame() {
        if (!saveInFlight.compareAndSet(false, true)) {
            updateOverlayTransient("正在保存上一帧…")
            return
        }
        storageScope.launch {
            try {
                // NonCancellable：保存常发生在停止采集前后，onDestroy 会取消 storageScope。
                withContext(NonCancellable) {
                    val bmp = synchronized(frameLock) {
                        latestBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                    }
                    if (bmp == null) {
                        updateOverlayTransient("暂无帧可保存")
                        return@withContext
                    }
                    try {
                        val path = FrameSaver.saveFrame(this@CaptureService, bmp, "manual")
                        CaptureStateHolder.setLastSavedFramePath(path)
                        // 调试样本只记元数据（不存图像内容；隐私：仅本地）
                        runCatching {
                            StorageModule.debugSamples(this@CaptureService).record(
                                DebugSampleRecord(
                                    filePath = path,
                                    tag = "manual",
                                    savedAt = System.currentTimeMillis(),
                                )
                            )
                        }
                        updateOverlayTransient("已保存一帧")
                    } catch (e: Exception) {
                        CaptureStateHolder.setStatus(CaptureStatus.Error("保存帧失败：${e.message}"))
                    } finally {
                        bmp.recycle()
                    }
                }
            } finally {
                saveInFlight.set(false)
            }
        }
    }

    private fun stopCapture(reason: String) {
        // 幂等：MediaProjection.Callback / 通知栏「停止」/ 悬浮窗关闭 / 授权失败 都会走到这里，
        // 重复执行会二次 recycle 位图、重复 stopSelf，并让 Stopped 状态被后到的 reason 覆盖。
        if (stopping) return
        stopping = true
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
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
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

    /** 一次性提示：在 [holdMs] 内不被每秒的 FPS 刷新覆盖。 */
    private fun updateOverlayTransient(text: String, holdMs: Long = 2500L) {
        overlayHoldUntilMs = System.currentTimeMillis() + holdMs
        updateOverlay(text)
    }

    private fun dismissOverlay() {
        overlay?.dismiss()
        overlay = null
    }

    private fun createNotificationChannel() {
        // minSdk 29，NotificationChannel 必然可用，无需再做 O 版本判断。
        val chan = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(chan)
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = Intent(this, CaptureService::class.java).apply { action = ACTION_STOP }
        val stopPi = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // 点击通知主体应回到应用界面；只有「停止」动作用来向 Service 下指令。
        // 两者混用会让点击通知只触发停止、待机界面无法回到 App（lint: LaunchActivityFromNotification）。
        val openAppPi = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_capture_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopPi)
            .setContentIntent(openAppPi)
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
