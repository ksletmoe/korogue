package com.sletmoe.korogue.ui

import com.badlogic.gdx.Input
import com.badlogic.gdx.graphics.Color
import com.sletmoe.korogue.utilities.IntRect

/**
 * A letter-keyed selection menu (ADR-0011): the classic roguelike "wield what?  a) mace  b) bow"
 * prompt, where each row's key selects it directly rather than being reached by cursor. Unlike
 * [Menu]'s arrow-key + Enter highlight navigation, there is no selection cursor or highlight state
 * at all — a keypress that matches one of [rows]' keys fires [onPick] with that key immediately.
 * A non-matching key returns false (unconsumed), so a host [Dialog]/modal can swallow it or let it
 * fall through to gameplay untouched.
 *
 * [rows] are `(key, label)` pairs drawn one per line, top to bottom; rows past the bottom of
 * [bounds] are not drawn. The label is shown exactly as given — whether it includes a "a) " prefix,
 * a price, or anything else is entirely up to the caller, matching the toolkit's convention that the
 * engine makes no representation choices of its own (see [PagedTextList]). Only `a`..`z` keys are
 * recognized, matching Rogue's pack-letter alphabet; matching is against [rows]' keys, not position,
 * so callers may list rows out of letter order.
 */
class HotkeyMenu(
    override val bounds: IntRect,
    private val rows: List<Pair<Char, String>>,
    private val fg: Color = Color.WHITE,
    private val bg: Color = Color.BLACK,
    private val z: Int = 0,
    private val onPick: (Char) -> Unit,
) : Widget {
    override fun draw(surface: TileSurface) {
        rows.take(surface.height).forEachIndexed { row, (_, label) ->
            surface.text(0, row, z, label.take(surface.width), fg, bg)
        }
    }

    override fun handleKey(keycode: Int): Boolean {
        val letter = charFor(keycode) ?: return false
        if (rows.none { it.first == letter }) return false
        onPick(letter)
        return true
    }

    private companion object {
        /** libGDX key code → the lowercase letter it selects, or null outside `a`..`z`. */
        fun charFor(keycode: Int): Char? =
            if (keycode in Input.Keys.A..Input.Keys.Z) 'a' + (keycode - Input.Keys.A) else null
    }
}
