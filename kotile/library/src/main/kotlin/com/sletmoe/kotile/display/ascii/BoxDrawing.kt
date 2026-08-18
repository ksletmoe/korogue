package com.sletmoe.kotile.display.ascii

/** Which of CP437's two box-drawing alphabets [BoxDrawing] draws with. */
enum class BoxLine {
    /** The single-line set: `─ │ ┌ ┐ └ ┘ ├ ┤ ┬ ┴ ┼`. The default; reads as a thin drawn wall. */
    SINGLE,

    /** The double-line set: `═ ║ ╔ ╗ ╚ ╝ ╠ ╣ ╦ ╩ ╬`. Heavier — a masonry look at small cell sizes. */
    DOUBLE,
}

/**
 * Picks the CP437 box-drawing glyph that joins a cell to its orthogonal neighbours — the *connected
 * walls* look, where a room's edge is drawn `┌───┐` instead of Rogue's `-` and `|`.
 *
 * This is deliberately **not** a property of a font or a tilesheet. Which glyph a wall wants depends on
 * its neighbourhood, not on how glyphs become pixels, so it belongs on the other side of the
 * [GlyphSource] seam entirely: decide the character here, let any source draw it. All three shipped
 * sources can, since each indexes [GlyphSource.glyph] by `char.code` as a CP437 slot and every slot
 * below is inside the 256-glyph page. Pair it with a position-aware terrain seam (the engine's
 * `MapPanel.terrainMapper`) to substitute glyphs at render time, leaving stored terrain untouched.
 *
 * ## Slots, not Unicode
 *
 * Every returned [Char] is a **CP437 slot** in `0x00..0xFF` (`─` is `Char(0xC4)`, not U+2500), which is
 * the addressing contract [GlyphSource] uses. Passing a Unicode box-drawing code point instead would
 * index far past the code page and draw nothing.
 *
 * ```kotlin
 * // A wall connects to orthogonally adjacent walls and doorways.
 * val glyph = BoxDrawing.connector(
 *     north = isWall(x, y - 1),
 *     east  = isWall(x + 1, y),
 *     south = isWall(x, y + 1),
 *     west  = isWall(x - 1, y),
 * )
 * ```
 */
object BoxDrawing {
    /**
     * The glyph joining a cell to whichever of its four orthogonal neighbours it [north]/[east]/[south]/
     * [west] connects to, in [line]'s alphabet.
     *
     * A cell connecting to nothing has no junction to draw and yields the horizontal bar — the same
     * choice a lone `-` makes in a classic display, and the least surprising filler for a stray wall.
     */
    fun connector(
        north: Boolean,
        east: Boolean,
        south: Boolean,
        west: Boolean,
        line: BoxLine = BoxLine.SINGLE,
    ): Char {
        val mask =
            (if (north) NORTH else 0) or
                (if (east) EAST else 0) or
                (if (south) SOUTH else 0) or
                (if (west) WEST else 0)
        return if (line == BoxLine.SINGLE) SINGLE_SET[mask] else DOUBLE_SET[mask]
    }

    /**
     * [connector] for the cell at ([x], [y]), sampling the four orthogonal neighbours through
     * [connects] — a predicate answering "does the cell at these coordinates join to this one?".
     *
     * The predicate is asked about coordinates that may lie outside the map; answer `false` there to end
     * a wall at the edge, or `true` to have it read as continuing past the boundary.
     */
    fun connectorAt(
        x: Int,
        y: Int,
        line: BoxLine = BoxLine.SINGLE,
        connects: (Int, Int) -> Boolean,
    ): Char =
        connector(
            north = connects(x, y - 1),
            east = connects(x + 1, y),
            south = connects(x, y + 1),
            west = connects(x - 1, y),
            line = line,
        )

    private const val NORTH = 1
    private const val EAST = 2
    private const val SOUTH = 4
    private const val WEST = 8

    /**
     * Single-line glyphs indexed by the [NORTH]/[EAST]/[SOUTH]/[WEST] bitmask. Built by [set] so the two
     * alphabets are laid out identically and can only disagree in their slot values.
     */
    private val SINGLE_SET =
        set(
            horizontal = 0xC4, vertical = 0xB3,
            topLeft = 0xDA, topRight = 0xBF, bottomLeft = 0xC0, bottomRight = 0xD9,
            teeRight = 0xC3, teeLeft = 0xB4, teeDown = 0xC2, teeUp = 0xC1, cross = 0xC5,
        )

    /** Double-line glyphs, same layout as [SINGLE_SET]. */
    private val DOUBLE_SET =
        set(
            horizontal = 0xCD, vertical = 0xBA,
            topLeft = 0xC9, topRight = 0xBB, bottomLeft = 0xC8, bottomRight = 0xBC,
            teeRight = 0xCC, teeLeft = 0xB9, teeDown = 0xCB, teeUp = 0xCA, cross = 0xCE,
        )

    /**
     * Builds the 16-entry mask→glyph table for one alphabet. The junction a mask needs follows from which
     * arms are set: two opposite arms make a bar, two adjacent arms a corner, three a tee, four the cross.
     * A single arm draws the bar it lies along, so a wall's end cell continues the run rather than
     * stopping short of it.
     */
    @Suppress("LongParameterList")
    private fun set(
        horizontal: Int,
        vertical: Int,
        topLeft: Int,
        topRight: Int,
        bottomLeft: Int,
        bottomRight: Int,
        teeRight: Int,
        teeLeft: Int,
        teeDown: Int,
        teeUp: Int,
        cross: Int,
    ): CharArray {
        val table = CharArray(16)
        for (mask in 0 until 16) {
            val n = mask and NORTH != 0
            val e = mask and EAST != 0
            val s = mask and SOUTH != 0
            val w = mask and WEST != 0
            table[mask] =
                Char(
                    when {
                        n && e && s && w -> cross
                        n && e && s -> teeRight // arms up, down, right: ├
                        n && s && w -> teeLeft
                        e && s && w -> teeDown
                        n && e && w -> teeUp
                        n && s -> vertical
                        e && w -> horizontal
                        s && e -> topLeft // the corner opens down and right: ┌
                        s && w -> topRight
                        n && e -> bottomLeft
                        n && w -> bottomRight
                        n || s -> vertical
                        else -> horizontal // a lone arm east/west, or no arm at all
                    },
                )
        }
        return table
    }
}
