package com.sletmoe.korogue.ui

import kotlin.math.max
import kotlin.math.min

/**
 * The observer-centred viewport [MapPanel] samples: a fixed [width]x[height] window onto a zone,
 * with its top-left at ([originX], [originY]) in zone-cell coordinates. Owns the origin clamp and
 * the zone<->screen coordinate conversion so anything drawing into the same viewport (the panel
 * itself, and game-side overlays that decorate it) shares one source of truth instead of
 * re-deriving the math — which silently drifts if the clamping ever changes.
 *
 * Coordinates are top-left origin, matching [TileSurface]. Screen cells run `[0, width) x
 * [0, height)`; zone cells are unbounded here (callers clamp to their zone's own extent).
 */
class MapCamera(
    val originX: Int,
    val originY: Int,
    val width: Int,
    val height: Int,
) {
    /** The zone column shown in screen column [screenX]. */
    fun zoneX(screenX: Int): Int = screenX + originX

    /** The zone row shown in screen row [screenY]. */
    fun zoneY(screenY: Int): Int = screenY + originY

    /** The screen column showing zone column [zoneX] (may fall outside `[0, width)`). */
    fun screenX(zoneX: Int): Int = zoneX - originX

    /** The screen row showing zone row [zoneY] (may fall outside `[0, height)`). */
    fun screenY(zoneY: Int): Int = zoneY - originY

    /** True when screen cell ([screenX], [screenY]) is inside the viewport. */
    fun containsScreen(
        screenX: Int,
        screenY: Int,
    ): Boolean = screenX in 0 until width && screenY in 0 until height

    companion object {
        /**
         * Centre a [width]x[height] viewport on the focus cell ([focusX], [focusY]) within a
         * [zoneWidth]x[zoneHeight] zone, sliding the window back inside the zone edges so it never
         * shows past the border (and pinning to the origin when the zone is smaller than the viewport).
         */
        fun centeredOn(
            focusX: Int,
            focusY: Int,
            width: Int,
            height: Int,
            zoneWidth: Int,
            zoneHeight: Int,
        ): MapCamera {
            val originX = max(0, min(focusX - width / 2, zoneWidth - width))
            val originY = max(0, min(focusY - height / 2, zoneHeight - height))
            return MapCamera(originX, originY, width, height)
        }
    }
}
