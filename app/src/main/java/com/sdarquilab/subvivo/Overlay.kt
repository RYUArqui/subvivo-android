package com.sdarquilab.subvivo

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/** 다른 앱 위에 떠 있는 자막 창. 끌어서 위아래로 옮길 수 있고, 위치는 기억한다.
 *  화면 구석에 작은 ■ 끄기 버튼도 함께 띄운다 (한 번 누르면 '끄기?', 3초 안에 한 번 더 누르면 꺼짐). */
class Overlay(private val ctx: Context, private val prefs: Prefs, private val onStop: () -> Unit = {}) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val root = LinearLayout(ctx)
    private val tvSrc = TextView(ctx)
    private val tvKo = TextView(ctx)
    private val params: WindowManager.LayoutParams
    private var clearJob: Runnable? = null
    private var attached = false
    var partialShown = false; private set
    private val bubble = TextView(ctx)
    private val bparams: WindowManager.LayoutParams
    private var armed = false
    private val disarm = Runnable { setArmed(false) }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics)

    init {
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.CENTER_HORIZONTAL
        val pad = dp(8f).toInt()
        root.setPadding(pad, pad / 2, pad, pad / 2)
        for (tv in listOf(tvSrc, tvKo)) {
            tv.gravity = Gravity.CENTER
            tv.typeface = Typeface.DEFAULT_BOLD
            tv.setShadowLayer(dp(3f), 0f, 0f, Color.BLACK)
            tv.visibility = View.GONE
            root.addView(tv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        applyStyle()

        val screenW = ctx.resources.displayMetrics.widthPixels
        val screenH = ctx.resources.displayMetrics.heightPixels
        params = WindowManager.LayoutParams(
            (screenW * 0.94).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        params.y = if (prefs.overlayY >= 0) prefs.overlayY.coerceAtMost(screenH - 100) else (screenH * 0.70).toInt()
        enableDrag()

        // ── 끄기 버튼 (끌어서 옮길 수 있음)
        val size = dp(46f).toInt()
        bubble.gravity = Gravity.CENTER
        bubble.typeface = Typeface.DEFAULT_BOLD
        setArmed(false)
        bparams = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        bparams.gravity = Gravity.TOP or Gravity.START
        bparams.x = if (prefs.bubbleX >= 0) prefs.bubbleX.coerceAtMost(screenW - size) else screenW - size - dp(12f).toInt()
        bparams.y = if (prefs.bubbleY >= 0) prefs.bubbleY.coerceAtMost(screenH - size) else dp(96f).toInt()
        enableBubble()
    }

    private fun setArmed(on: Boolean) {
        armed = on
        handler.removeCallbacks(disarm)
        if (on) {
            bubble.text = "끄기?"
            bubble.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            bubble.setTextColor(Color.WHITE)
            bubble.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xE0D32F2F.toInt())
            }
            handler.postDelayed(disarm, 3000)
        } else {
            bubble.text = "■"
            bubble.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            bubble.setTextColor(0xEEFFFFFF.toInt())
            bubble.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x88000000.toInt())
                setStroke(dp(1.5f).toInt(), 0x99FFFFFF.toInt())
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun enableBubble() {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragged = false
        val slop = dp(8f)
        bubble.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = bparams.x; startY = bparams.y; dragged = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (dragged || abs(dx) > slop || abs(dy) > slop) {
                        dragged = true
                        bparams.x = (startX + dx).toInt().coerceAtLeast(0)
                        bparams.y = (startY + dy).toInt().coerceAtLeast(0)
                        if (attached) wm.updateViewLayout(bubble, bparams)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragged) {
                        prefs.bubbleX = bparams.x
                        prefs.bubbleY = bparams.y
                    } else if (armed) {
                        setArmed(false)
                        onStop()
                    } else {
                        setArmed(true)
                    }
                    true
                }
                else -> false
            }
        }
    }

    fun attach() {
        if (!attached) {
            wm.addView(root, params)
            wm.addView(bubble, bparams)
            attached = true
        }
    }

    fun detach() {
        clearJob?.let { handler.removeCallbacks(it) }
        handler.removeCallbacks(disarm)
        if (attached) {
            runCatching { wm.removeView(root) }
            runCatching { wm.removeView(bubble) }
            attached = false
        }
    }

    fun applyStyle() {
        val size = prefs.fontSize.toFloat()
        tvKo.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        tvSrc.setTextSize(TypedValue.COMPLEX_UNIT_SP, (size * 0.62f).coerceAtLeast(11f))
        if (prefs.boxStyle) {
            root.background = GradientDrawable().apply {
                cornerRadius = dp(8f)
                setColor(0xB0000000.toInt())
            }
        } else {
            root.background = null
        }
    }

    /** partial=true 는 말하는 도중의 미리보기(흐린 글씨). color는 목소리별 색. */
    fun show(korean: String, source: String, partial: Boolean = false, color: Int? = null) {
        partialShown = partial
        val mainColor = if (partial) 0xFFC8C8C8.toInt() else (color ?: Color.WHITE)
        if (korean.isNotBlank()) {
            tvKo.text = korean
            tvKo.setTextColor(mainColor)
            tvKo.visibility = View.VISIBLE
        } else {
            tvKo.visibility = View.GONE
        }
        val showSrc = source.isNotBlank() && (prefs.showSource || korean.isBlank())
        if (showSrc) {
            tvSrc.text = source
            tvSrc.setTextColor(
                when {
                    korean.isBlank() -> mainColor
                    color != null && !partial -> 0xFFE8E8E8.toInt()
                    partial -> 0xFFD9C26A.toInt()
                    else -> 0xFFFFE066.toInt()
                },
            )
            tvSrc.visibility = View.VISIBLE
        } else {
            tvSrc.visibility = View.GONE
        }
        clearJob?.let { handler.removeCallbacks(it) }
        clearJob = Runnable { clear() }.also { handler.postDelayed(it, 6000) }
    }

    fun clear() {
        partialShown = false
        tvKo.visibility = View.GONE
        tvSrc.visibility = View.GONE
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun enableDrag() {
        var downY = 0f
        var startY = 0
        root.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downY = e.rawY; startY = params.y; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = e.rawY - downY
                    if (abs(dy) > 4) {
                        params.y = (startY + dy).toInt().coerceAtLeast(0)
                        if (attached) wm.updateViewLayout(root, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    prefs.overlayY = params.y; true
                }
                else -> false
            }
        }
    }
}
