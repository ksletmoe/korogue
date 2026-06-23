package com.sletmoe.korogue.ui

import com.badlogic.gdx.Input
import com.badlogic.gdx.graphics.Color
import com.sletmoe.korogue.utilities.IntRect

/**
 * A single-line text field (ADR-0011): the toolkit's counterpart to a curses `get_str` prompt. It
 * accumulates typed characters into a buffer, draws them on one row with a trailing caret, and
 * resolves on Enter (calls [onSubmit] with the text) — Escape is left to the enclosing [Dialog]'s
 * cancel. Backspace deletes the last character; keys it can't turn into text fall through unconsumed
 * (a modal [Dialog]/[UiRoot] above still swallows them, so they never reach gameplay).
 *
 * Input maps libGDX key codes to characters directly, so the widget is headlessly testable with raw
 * key codes and carries no dependency on live `Gdx.input` state. The mapped set is lowercase letters,
 * digits, space, hyphen, and apostrophe — enough for an item nickname; shifted/uppercase entry is out
 * of scope (there is no key-up signal here to track Shift).
 *
 * When the buffer is longer than [bounds] is wide the tail is shown, so the caret stays visible.
 */
class TextInput(
    override val bounds: IntRect,
    initial: String = "",
    private val fg: Color = Color.WHITE,
    private val bg: Color = Color.BLACK,
    private val z: Int = 0,
    private val onSubmit: (String) -> Unit,
) : Widget {
    private val buffer = StringBuilder(initial)

    /** The text typed so far (without the caret). */
    val value: String get() = buffer.toString()

    override fun draw(surface: TileSurface) {
        val shown = buffer.toString() + CARET
        val visible = if (shown.length > surface.width) shown.takeLast(surface.width) else shown
        surface.text(0, 0, z, visible.padEnd(surface.width), fg, bg)
    }

    override fun handleKey(keycode: Int): Boolean =
        when (keycode) {
            Input.Keys.ENTER, Input.Keys.NUMPAD_ENTER -> {
                onSubmit(buffer.toString())
                true
            }
            Input.Keys.BACKSPACE -> {
                if (buffer.isNotEmpty()) buffer.deleteCharAt(buffer.length - 1)
                true
            }
            else -> {
                val character = charFor(keycode)
                if (character == null) {
                    false
                } else {
                    buffer.append(character)
                    true
                }
            }
        }

    private companion object {
        /** The block caret shown at the insertion point (always at the end — this is append-only). */
        const val CARET = '_'

        /** libGDX key code → the character it types, for the printable subset this field accepts. */
        fun charFor(keycode: Int): Char? =
            when (keycode) {
                in Input.Keys.A..Input.Keys.Z -> 'a' + (keycode - Input.Keys.A)
                in Input.Keys.NUM_0..Input.Keys.NUM_9 -> '0' + (keycode - Input.Keys.NUM_0)
                Input.Keys.SPACE -> ' '
                Input.Keys.MINUS -> '-'
                Input.Keys.APOSTROPHE -> '\''
                else -> null
            }
    }
}
