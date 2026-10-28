package com.weile.liuzhou.mahjong.analysis

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.weile.liuzhou.mahjong.domain.analysis.Advice
import com.weile.liuzhou.mahjong.domain.analysis.DefaultRecommendationEngine
import com.weile.liuzhou.mahjong.domain.analysis.RecommendationEngine
import com.weile.liuzhou.mahjong.domain.game.DefaultGameStateMachine
import com.weile.liuzhou.mahjong.domain.game.GameEvent
import com.weile.liuzhou.mahjong.domain.game.GameState
import com.weile.liuzhou.mahjong.domain.game.GameStateMachine
import com.weile.liuzhou.mahjong.domain.game.ObserveResult
import com.weile.liuzhou.mahjong.domain.vision.toFrameObservation
import com.weile.liuzhou.mahjong.vision.BitmapPixelAccessor
import com.weile.liuzhou.mahjong.vision.HeuristicScreenStateClassifier
import com.weile.liuzhou.mahjong.vision.ScreenRecognizer
import com.weile.liuzhou.mahjong.domain.vision.TileTemplateLibrary
import com.weile.liuzhou.mahjong.vision.BlackBarViewportDetector
import com.weile.liuzhou.mahjong.vision.NccTileTemplateMatcher

/**
 * 实时分析管线（P4 整合层）。
 *
 * 串联 P1 → P3 → analysis：
 * ```
 * 帧 → ScreenRecognizer → RecognitionResult → toFrameObservation
 *      → GameStateMachine.observe → RecommendationEngine.advise → Advice → 悬浮窗
 * ```
 *
 * 关键机制：
 * 1. **节流**：按 [AnalysisSettings.analysisIntervalMs] 触发分析，不在每帧上跑识别；
 * 2. **自掩码**（方案 3.2 第 6 点）：分析前隐藏自己的悬浮窗，并丢弃随后若干帧
 *    （这些帧在隐藏前就已渲染、仍含悬浮窗），之后取到的第一帧才保证干净，
 *    避免推荐文字污染截图识别；
 * 3. **停止即停荐**：采集停止/无权限时（[onCaptureStopped]）清空状态与建议。
 *
 * 线程：`onFrame` 在采集服务的 image handler 线程调用；识别与状态机在同一线程
 * 串行执行；结果通过 [mainHandler] 回主线程给 UI。位图只读，不回收。
 */
class AnalysisPipeline(
    recognizer: ScreenRecognizer? = null,
    private val engine: RecommendationEngine = DefaultRecommendationEngine(),
    val stateMachine: GameStateMachine = DefaultGameStateMachine(),
) {

    private val recognizer: ScreenRecognizer = recognizer ?: ScreenRecognizer(
        viewportDetector = BlackBarViewportDetector(),
        stateClassifier = HeuristicScreenStateClassifier(),
        tileMatcher = NccTileTemplateMatcher(TileTemplateLibrary.EMPTY),
    )

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    var settings: AnalysisSettings = AnalysisSettings()

    /** 新建议（主线程）。 */
    var onAdvice: ((Advice) -> Unit)? = null

    /** 状态文本（主线程）。 */
    var onStatus: ((String) -> Unit)? = null

    /** 掩码切换（主线程）：true=隐藏悬浮窗（避免污染截图），false=恢复。 */
    var onMaskChanged: ((Boolean) -> Unit)? = null

    /**
     * 一局结束（自动重置前）回调：传入该局最后的状态快照与起止时间，
     * 供 storage 层持久化（P3 回溯）。在管线线程调用，调用方需自行切 IO。
     */
    var onRoundEnded: ((state: GameState, startedAt: Long, endedAt: Long) -> Unit)? = null

    private var lastAnalysisAtMs = 0L
    private var skipFramesRemaining = 0
    private var roundStartedAtMs = 0L

    /**
     * 悬浮窗恢复可见的时刻。用于限制掩码频率：
     * 掩码会让悬浮窗从屏幕上消失，若每轮分析（默认 500ms）都执行一次，
     * 用户看到的就是悬浮窗持续闪烁。恢复后至少保持 [MIN_OVERLAY_VISIBLE_MS] 可见。
     */
    @Volatile
    private var lastUnmaskAtMs = 0L

    /** 由采集服务在每帧处理后调用（image handler 线程）。 */
    fun onFrame(bitmap: Bitmap) {
        // 掩码后的丢帧窗口：这些帧仍可能含悬浮窗，直接丢弃
        if (skipFramesRemaining > 0) {
            skipFramesRemaining--
            if (skipFramesRemaining == 0) {
                analyzeCleanFrame(bitmap)
            }
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastAnalysisAtMs < settings.analysisIntervalMs) return
        // 悬浮窗尚未恢复足够长的可见时间：跳过本轮，沿用上一次建议。
        // 代价是一次建议最多延后约 1 秒，远小于悬浮窗高频闪烁对可用性的影响。
        if (now - lastUnmaskAtMs < MIN_OVERLAY_VISIBLE_MS) return

        // 进入掩码：隐藏悬浮窗 → 丢 framesToSkipAfterMask 帧 → 下一帧必干净
        lastAnalysisAtMs = now
        skipFramesRemaining = settings.framesToSkipAfterMask.coerceAtLeast(1)
        postMain { onMaskChanged?.invoke(true) }
    }

    private fun analyzeCleanFrame(bitmap: Bitmap) {
        try {
            val result = recognizer.recognize(BitmapPixelAccessor(bitmap))
            val frame = result.toFrameObservation()
            val before = stateMachine.snapshot()
            val observeResult = stateMachine.observe(frame)
            val advice = engine.advise(stateMachine.snapshot(), settings.mode, settings.hasDragons)
            postMain { onAdvice?.invoke(advice) }

            // 回合结束持久化（自动重置前把该局事件流交出去）
            val endedAt = System.currentTimeMillis()
            if (roundStartedAtMs == 0L && before.events.isNotEmpty()) {
                roundStartedAtMs = endedAt
            }
            if (observeResult is ObserveResult.Applied &&
                observeResult.events.any { it is GameEvent.RoundReset } &&
                before.events.isNotEmpty()
            ) {
                val startedAt = if (roundStartedAtMs > 0) roundStartedAtMs else endedAt
                roundStartedAtMs = 0L
                onRoundEnded?.invoke(before, startedAt, endedAt)
            }
        } catch (e: Exception) {
            Log.w(TAG, "analysis failed: ${e.message}")
            postMain {
                onAdvice?.invoke(
                    Advice(
                        waitingConfirmation = true,
                        confirmationHint = "分析异常：${e.message}",
                        phase = stateMachine.snapshot().phase,
                    )
                )
            }
        } finally {
            lastUnmaskAtMs = System.currentTimeMillis()
            postMain { onMaskChanged?.invoke(false) }
        }
    }

    /** 采集停止 / 无权限：停止推荐并清空本局（方案 P4：无法采集则停止推荐）。 */
    fun onCaptureStopped() {
        skipFramesRemaining = 0
        stateMachine.reset("capture stopped")
        postMain {
            onMaskChanged?.invoke(false)
            onAdvice?.invoke(Advice())
            onStatus?.invoke("已停止")
        }
    }

    /** 手动清空本局（UI「清空本局」按钮；切换箭牌开关时也必须调用）。 */
    fun resetRound() {
        skipFramesRemaining = 0
        stateMachine.reset("manual reset")
        postMain { onAdvice?.invoke(Advice()) }
    }

    private fun postMain(block: () -> Unit) {
        mainHandler.post(block)
    }

    private companion object {
        const val TAG = "AnalysisPipeline"

        /**
         * 悬浮窗在一次自掩码后必须保持可见的最短时长（毫秒）。
         * 掩码 = 悬浮窗消失；该值决定用户能连续看到的窗口时长，
         * 取 900ms 时实际分析周期约 1 秒，肉眼不再产生闪烁感。
         */
        const val MIN_OVERLAY_VISIBLE_MS = 900L
    }
}

/**
 * 管线单例（P4）。
 *
 * 采集服务与 UI 都需要访问同一条管线：服务喂帧，UI 读建议/改设置。
 * 单 Activity + 单 Service 的应用形态下用 object 持有最稳妥。
 */
object AnalysisPipelineHolder {
    val pipeline: AnalysisPipeline = AnalysisPipeline()

    @Volatile
    var settings: AnalysisSettings = AnalysisSettings()
        set(value) {
            field = value
            pipeline.settings = value
        }
}
