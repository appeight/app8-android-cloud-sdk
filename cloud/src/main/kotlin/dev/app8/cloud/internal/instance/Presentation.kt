package dev.app8.cloud.internal.instance

import android.content.res.Configuration
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Fires once, the first time content is laid out at a non-zero size (iOS `LayoutSentinelView`):
 * returned content has no size until the host places it, and later resizes don't re-fire.
 */
internal class FirstLayoutSentinel {
    private val fired = AtomicBoolean(false)

    val hasFired: Boolean get() = fired.get()

    fun onSize(width: Int, height: Int, onFirstLayout: (width: Int, height: Int) -> Unit) {
        if (width <= 0 || height <= 0) return
        if (fired.compareAndSet(false, true)) onFirstLayout(width, height)
    }
}

/**
 * iOS size-class wire values from a window dimension in dp: `compact` below [compactBelowDp]
 * (600dp wide / 480dp tall, the Material compact breakpoints), else `regular`;
 * `unspecified` when unknown.
 */
internal fun sizeClassString(dp: Int, compactBelowDp: Int): String = when {
    dp <= 0 || dp == Configuration.SCREEN_WIDTH_DP_UNDEFINED -> "unspecified"
    dp < compactBelowDp -> "compact"
    else -> "regular"
}

/** iOS idiom wire values: `phone`, `pad` (smallest width >= 600dp), `tv`, `carPlay` (car mode), `unspecified`. */
internal fun deviceIdiomString(uiModeType: Int, smallestWidthDp: Int): String = when {
    uiModeType == Configuration.UI_MODE_TYPE_TELEVISION -> "tv"
    uiModeType == Configuration.UI_MODE_TYPE_CAR -> "carPlay"
    smallestWidthDp <= 0 || smallestWidthDp == Configuration.SMALLEST_SCREEN_WIDTH_DP_UNDEFINED -> "unspecified"
    smallestWidthDp >= 600 -> "pad"
    else -> "phone"
}
