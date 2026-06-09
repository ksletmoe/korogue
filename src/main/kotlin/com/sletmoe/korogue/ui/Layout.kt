package com.sletmoe.korogue.ui

import com.sletmoe.korogue.utilities.IntRect

/** A horizontal cut of a rect into a [top] band and the [bottom] remainder. */
data class HSplit(
    val top: IntRect,
    val bottom: IntRect,
)

/** A vertical cut of a rect into a [left] band and the [right] remainder. */
data class VSplit(
    val left: IntRect,
    val right: IntRect,
)

/*
 * Rect-splitting helpers (ADR-0011): enough layout for roguelike panes without a constraint
 * engine. The requested band is clamped to the rect's size, so over-asking never produces a
 * negative-sized rect.
 */

/** Cuts [rows] off the top; [HSplit.top] is `rows` tall, [HSplit.bottom] is what's left. */
fun IntRect.splitTop(rows: Int): HSplit {
    val n = rows.coerceIn(0, height)
    return HSplit(IntRect(x, y, width, n), IntRect(x, y + n, width, height - n))
}

/** Cuts [rows] off the bottom; [HSplit.bottom] is `rows` tall, [HSplit.top] is what's left. */
fun IntRect.splitBottom(rows: Int): HSplit {
    val n = rows.coerceIn(0, height)
    return HSplit(IntRect(x, y, width, height - n), IntRect(x, y + height - n, width, n))
}

/** Cuts [cols] off the left; [VSplit.left] is `cols` wide, [VSplit.right] is what's left. */
fun IntRect.splitLeft(cols: Int): VSplit {
    val n = cols.coerceIn(0, width)
    return VSplit(IntRect(x, y, n, height), IntRect(x + n, y, width - n, height))
}

/** Cuts [cols] off the right; [VSplit.right] is `cols` wide, [VSplit.left] is what's left. */
fun IntRect.splitRight(cols: Int): VSplit {
    val n = cols.coerceIn(0, width)
    return VSplit(IntRect(x, y, width - n, height), IntRect(x + width - n, y, n, height))
}

/** Shrinks the rect by [by] cells on every side (e.g. to fit content inside a border). Clamped at 0. */
fun IntRect.inset(by: Int): IntRect =
    IntRect(x + by, y + by, (width - 2 * by).coerceAtLeast(0), (height - 2 * by).coerceAtLeast(0))
