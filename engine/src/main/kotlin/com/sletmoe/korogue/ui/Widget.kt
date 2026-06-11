package com.sletmoe.korogue.ui

import com.sletmoe.korogue.utilities.IntRect

/**
 * A unit of UI in the toolkit (ADR-0011). A widget knows its screen [bounds] and draws itself
 * into a [TileSurface] that is already clipped/translated to those bounds (so it draws in local
 * coordinates). Registered on a [UiRoot] as a layer.
 */
interface Widget {
    /** Screen rectangle this widget occupies, in window tile coordinates. */
    val bounds: IntRect

    /** Draws into [surface], whose origin is this widget's top-left and which is clipped to [bounds]. */
    fun draw(surface: TileSurface)

    /**
     * Handles a key press (a libGDX `Input.Keys` code). Returns true if consumed; false lets the
     * key fall through to the next layer down, and ultimately to gameplay. Default: ignores input.
     */
    fun handleKey(keycode: Int): Boolean = false
}
