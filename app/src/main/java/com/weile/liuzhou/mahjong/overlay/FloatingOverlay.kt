package com.weile.liuzhou.mahjong.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
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

    private var isPaused = false
    private var currentStatus: String = "就绪"

    private companion object {
        const val TAG = "FloatingOverlay"
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

    // 悬浮窗的父容器是 WindowManager，没有可用的 ViewGroup 作 parent，
    // 因此传 null 是此场景的正确用法（lint InflateParams 在此为误报）。
    // dragImpl 在 ACTION_UP 未发生位移时调用 performClick，满足无障碍要求；
    // lint 无法跨辅助函数追踪，故在此抑制（ClickableViewAccessibility）。
    @SuppressLint("InflateParams", "ClickableViewAccessibility")
    override fun show(initialStatus: String) {
        onMain {
            // 折叠态下先移除圆点窗口，避免与卡片窗口重复挂窗。
            collapsedView?.let {
                windowManager.removeView(it)
                collapsedView = null
            }
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
                isPaused = !isPaused
                btnPause.text = if (isPaused) "继续" else "暂停"
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

    override fun setPaused(paused: Boolean) {
        isPaused = paused
        onMain {
            cardView?.findViewById<Button>(R.id.btn_pause)?.text =
                if (paused) "继续" else "暂停"
        }
    }

    /**
     * 显示/隐藏，用于采集分析期间的自掩码。
     *
     * **只调整窗口 alpha 与触摸标志，不 add/remove 窗口**：
     * 原先用 `removeView`/`addView` 实现，每轮分析（默认 500ms）都要把窗口
     * 从 WindowManager 摘下再挂回，真机上表现为悬浮窗持续闪烁。
     * alpha=0 的窗口图层对 MediaProjection 是透明合成，截图同样能取到干净的牌面，
     * 因此功能等价但无窗口树抖动。
     *
     * 隐藏期间关闭触摸：alpha=0 的窗口仍会拦截点击，会让用户的点击落空。
     */
    override fun setVisible(visible: Boolean) {
        onMain {
            // view 与 params 必须配对：卡片态用 cardView/cardParams，折叠态用 collapsedView/collapsedParams，
            // 否则折叠态会拿 cardParams 去更新 collapsedView，导致窗口属性错配。
            val view: View
            val params: WindowManager.LayoutParams
            when {
                cardView != null -> {
                    view = cardView!!
                    params = cardParams!!
                }
                collapsedView != null -> {
                    view = collapsedView!!
                    params = collapsedParams!!
                }
                else -> return@onMain
            }
            // 不在窗口树中（尚未显示或已 dismiss）时不做任何事，避免自掩码把已关闭的窗口重新挂上。
            if (view.parent == null) return@onMain

            val targetAlpha = if (visible) 1f else 0f
            val targetFlags = if (visible) {
                params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            } else {
                params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }
            if (params.alpha == targetAlpha && params.flags == targetFlags) return@onMain

            params.alpha = targetAlpha
            params.flags = targetFlags
            runCatching { windowManager.updateViewLayout(view, params) }
                .onFailure { Log.w(TAG, "setVisible($visible) failed: ${it.message}") }
        }
    }

    /** 渲染出牌建议（P4）。置信度仅表示识别置信度，不称为胜率。 */
    fun updateAdvice(advice: com.weile.liuzhou.mahjong.domain.analysis.Advice) {
        onMain {
            val tv = cardView?.findViewById<TextView>(R.id.advice_text) ?: return@onMain
            val rendered = renderAdvice(advice)
            // 内容未变时不重绘：WRAP_CONTENT 窗口会随文本行数变化而重排，
            // 无变化却重设文本会让悬浮窗持续跳动。
            if (tv.text.toString() == rendered) return@onMain
            tv.text = rendered
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

    @SuppressLint("ClickableViewAccessibility")
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
        // minSdk 29：TYPE_APPLICATION_OVERLAY 必然可用，TYPE_PHONE 分支已是死代码。
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
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
    private var dragMoved = false
    private val touchSlop by lazy { ViewConfiguration.get(context).scaledTouchSlop }

    private fun dragImpl(v: View, event: MotionEvent, p: WindowManager.LayoutParams): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                dragStartRawX = event.rawX
                dragStartRawY = event.rawY
                dragStartParamX = p.x
                dragStartParamY = p.y
                dragMoved = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - dragStartRawX
                val dy = event.rawY - dragStartRawY
                if (!dragMoved &&
                    (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop)
                ) {
                    dragMoved = true
                }
                p.x = dragStartParamX + dx.toInt()
                p.y = dragStartParamY + dy.toInt()
                onMain { windowManager.updateViewLayout(v, p) }
            }
            MotionEvent.ACTION_UP -> {
                // 无障碍：拖动结束时若没有实际位移，按一次点击处理。
                // 否则 TalkBack / 无障碍服务无法触发折叠圆点的展开（lint: ClickableViewAccessibility）。
                if (!dragMoved) v.performClick()
            }
        }
        return true
    }
}
