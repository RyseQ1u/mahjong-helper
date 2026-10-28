package com.weile.liuzhou.mahjong.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import com.weile.liuzhou.mahjong.R
import com.weile.liuzhou.mahjong.domain.overlay.OverlayController

/**
 * 最小悬浮窗（P0-3）：开始/暂停分析、显示采集状态、收起/关闭。
 *
 * 通过 WindowManager 以 TYPE_APPLICATION_OVERLAY 添加；支持拖动与折叠为小圆点。
 * 注意：采集时需对自己的悬浮窗区域加掩码/隐藏（方案第 3.2 节第 6 点），
 * 否则推荐文字会污染截图的牌面识别。P0 仅显示状态文本，不渲染麻将内容。
 */
class FloatingOverlay(
    private val context: Context,
    private val callbacks: OverlayCallbacks,
) : OverlayController {

    interface OverlayCallbacks {
        fun onTogglePause()
        fun onClose()
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var cardView: View? = null
    private var collapsedView: View? = null
    private var cardParams: WindowManager.LayoutParams? = null
    private var collapsedParams: WindowManager.LayoutParams? = null

    private var paused = false
    private var currentStatus: String = "就绪"

    private companion object {
        const val NORMAL_COLOR = 0xFFFFFFFF.toInt()
        const val WARNING_COLOR = 0xFFFFCC80.toInt()
    }

    /**
     * 所有 view 操作（addView / removeView / updateViewLayout / setText）必须在主线程执行。
     * 采集服务会从协程线程、ImageReader 线程回调调用本类，这里统一切主线程，
     * 否则触发 ViewRootImpl$CalledFromWrongThreadException 导致进程崩溃。
     */
    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    override val isShown: Boolean
        get() = cardView != null || collapsedView != null

    override fun show(initialStatus: String) {
        onMain {
            if (cardView != null) {
                updateStatus(initialStatus)
                return@onMain
            }
            currentStatus = initialStatus
            cardParams = makeParams()
            val root = LayoutInflater.from(context).inflate(R.layout.overlay_view, null)
            cardView = root

            val statusText = root.findViewById<TextView>(R.id.status_text)
            statusText.text = initialStatus
            val btnPause = root.findViewById<Button>(R.id.btn_pause)
            val btnCollapse = root.findViewById<Button>(R.id.btn_collapse)
            val btnClose = root.findViewById<Button>(R.id.btn_close)
            val dragHandle = root.findViewById<View>(R.id.drag_handle)

            btnPause.setOnClickListener {
                paused = !paused
                btnPause.text = if (paused) "继续" else "暂停"
                callbacks.onTogglePause()
            }
            btnCollapse.setOnClickListener { collapse() }
            btnClose.setOnClickListener { callbacks.onClose() }
            dragHandle.setOnTouchListener { v, event -> dragImpl(v, event, cardParams!!) }

            windowManager.addView(root, cardParams)
        }
    }

    override fun updateStatus(text: String) {
        currentStatus = text
        onMain {
            cardView?.findViewById<TextView>(R.id.status_text)?.text = text
        }
    }

    override fun setPaused(p: Boolean) {
        paused = p
        onMain {
            cardView?.findViewById<Button>(R.id.btn_pause)?.text = if (p) "继续" else "暂停"
        }
    }

    /**
     * 显示/隐藏（保留 view 与参数，直接 remove/add 同一个 view）。
     * 用于采集分析期间的自掩码：隐藏期间不销毁窗口，恢复无闪烁重建。
     */
    override fun setVisible(visible: Boolean) {
        onMain {
            val view = cardView ?: collapsedView ?: return@onMain
            val params = cardParams ?: collapsedParams ?: return@onMain
            if (visible) {
                if (view.parent == null) {
                    runCatching { windowManager.addView(view, params) }
                }
            } else {
                if (view.parent != null) {
                    runCatching { windowManager.removeView(view) }
                }
            }
        }
    }

    /** 渲染出牌建议（P4）。置信度仅表示识别置信度，不称为胜率。 */
    fun updateAdvice(advice: com.weile.liuzhou.mahjong.domain.analysis.Advice) {
        onMain {
            val tv = cardView?.findViewById<TextView>(R.id.advice_text) ?: return@onMain
            tv.text = renderAdvice(advice)
            tv.setTextColor(
                if (advice.waitingConfirmation) WARNING_COLOR else NORMAL_COLOR
            )
        }
    }

    private fun renderAdvice(advice: com.weile.liuzhou.mahjong.domain.analysis.Advice): String =
        buildString {
            appendLine(advice.summary())
            if (!advice.waitingConfirmation && !advice.canWin) {
                advice.candidates.forEachIndexed { i, c ->
                    appendLine(
                        "${i + 1}. ${c.tile.displayName}｜向听 ${c.shantenAfterDiscard}｜" +
                            "进张 ${c.effectiveDrawsAfterDiscard}｜${c.reason}"
                    )
                }
            }
            append("识别置信度 ${(advice.recognitionConfidence * 100).toInt()}%（非胜率）")
        }

    override fun dismiss() {
        onMain {
            cardView?.let { windowManager.removeView(it) }
            collapsedView?.let { windowManager.removeView(it) }
            cardView = null
            collapsedView = null
        }
    }

    private fun collapse() {
        onMain {
            val root = cardView ?: return@onMain
            windowManager.removeView(root)
            cardView = null

            val dot = TextView(context).apply {
                text = "●\n麻将"
                textSize = 12f
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundColor(0xCC1B5E20.toInt())
                setPadding(14, 14, 14, 14)
            }
            collapsedParams = makeParams()
            dot.setOnTouchListener { v, event -> dragImpl(v, event, collapsedParams!!) }
            dot.setOnClickListener { expand() }
            collapsedView = dot
            windowManager.addView(dot, collapsedParams)
        }
    }

    private fun expand() {
        onMain {
            collapsedView?.let { windowManager.removeView(it) }
            collapsedView = null
            show(currentStatus)
        }
    }

    private fun makeParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 140
        }
    }

    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var dragStartParamX = 0
    private var dragStartParamY = 0

    private fun dragImpl(v: View, event: MotionEvent, p: WindowManager.LayoutParams): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                dragStartRawX = event.rawX
                dragStartRawY = event.rawY
                dragStartParamX = p.x
                dragStartParamY = p.y
            }
            MotionEvent.ACTION_MOVE -> {
                p.x = dragStartParamX + (event.rawX - dragStartRawX).toInt()
                p.y = dragStartParamY + (event.rawY - dragStartRawY).toInt()
                onMain { windowManager.updateViewLayout(v, p) }
            }
        }
        return true
    }
}
