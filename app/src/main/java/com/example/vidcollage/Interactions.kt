package com.example.vidcollage

import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator

/**
 * Small touch/animation helpers shared by the activity and the adapters. They are deliberately
 * additive: nothing here consumes an event, so click listeners keep working as usual.
 */

/** Presses the view down slightly while it is held, so taps feel physical. */
fun View.addPressBounce(scale: Float = 0.96f) {
    setOnTouchListener { view, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> view.springTo(scale, DOWN_MS)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.springTo(1f, UP_MS)
        }
        false
    }
}

private fun View.springTo(scale: Float, duration: Long) {
    animate().scaleX(scale).scaleY(scale)
        .setDuration(duration)
        .setInterpolator(if (scale == 1f) OvershootInterpolator(2f) else DecelerateInterpolator())
        .start()
}

/** A short tick on tap. Skipped silently when the device has haptics turned off. */
fun View.tick() = performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)

/** Cross-fades a view in or out, and takes it out of the layout once it is fully hidden. */
fun View.fadeTo(visible: Boolean, duration: Long = 220L) {
    val target = if (visible) 1f else 0f
    if (visibility == View.VISIBLE && alpha == target) return
    if (visible && visibility != View.VISIBLE) alpha = 0f
    if (visible) visibility = View.VISIBLE
    animate().alpha(target)
        .setDuration(duration)
        .setInterpolator(DecelerateInterpolator())
        .withEndAction { if (!visible) visibility = View.GONE }
        .start()
}

private const val DOWN_MS = 90L
private const val UP_MS = 240L
