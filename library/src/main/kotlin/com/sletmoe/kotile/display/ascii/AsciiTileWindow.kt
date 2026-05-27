package com.sletmoe.kotile.display.ascii

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.utils.Disposable
import com.sletmoe.kotile.display.KotileCanvas
import com.sletmoe.kotile.utilities.Grid

/**
 * A grid of ASCII cells rendered with a bitmap [Font].
 *
 * Cell state is set with [drawTile], [drawText], and [fill], then drawn by
 * [render], which redraws every populated cell each frame: a background quad
 * tinted by the cell's background color, then the glyph tinted by its
 * foreground color. Create instances with [create]. Owns GPU resources and must
 * be [dispose]d.
 *
 * Both static and animated cell content are supported:
 * - [AsciiTileDescriptor] — fixed glyph and colors; the most common case.
 * - [AnimatedAsciiTile] — cycles through a sequence of descriptors over time,
 *   enabling Brogue-style effects such as torch flicker. Pass the elapsed
 *   wall-clock time to [render] to drive animation.
 *
 * When [fitToWindow] is `true` (the default), [resize] recomputes the tile
 * grid dimensions from the new pixel size and rebuilds the internal cell
 * array. Cells that still fit within the new bounds are preserved; cells
 * outside the new bounds are dropped and new cells default to `null`. When
 * [fitToWindow] is `false`, the grid stays at its construction-time
 * dimensions and is scaled/letterboxed by the canvas to fit the window.
 *
 * @property widthInTiles grid width in cells
 * @property heightInTiles grid height in cells
 */
public class AsciiTileWindow private constructor(
    private val font: Font,
    private val canvas: KotileCanvas,
    widthInTiles: Int,
    heightInTiles: Int,
    private val fitToWindow: Boolean,
) : Disposable {
    /** Current grid width in cells. Updated by [resize] when [fitToWindow] is `true`. */
    public var widthInTiles: Int = widthInTiles
        private set

    /** Current grid height in cells. Updated by [resize] when [fitToWindow] is `true`. */
    public var heightInTiles: Int = heightInTiles
        private set

    private var tiles: Grid<AnimatableAsciiTile?> = Grid(widthInTiles, heightInTiles, null)

    private val backgroundTexture: Texture
    private val backgroundRegion: TextureRegion

    init {
        val pixmap = Pixmap(1, 1, Pixmap.Format.RGBA8888)
        pixmap.setColor(Color.WHITE)
        pixmap.fill()
        backgroundTexture = Texture(pixmap)
        backgroundRegion = TextureRegion(backgroundTexture)
        pixmap.dispose()
    }

    /**
     * Sets the cell at column [x], row [y] to a static [tile].
     *
     * @throws IndexOutOfBoundsException if the cell is outside the grid
     */
    public fun drawTile(x: Int, y: Int, tile: AsciiTileDescriptor) {
        tiles[x, y] = tile
    }

    /**
     * Sets the cell at column [x], row [y] to an animated [tile].
     *
     * The same [AnimatedAsciiTile] instance may be placed at multiple cells;
     * all cells sharing the instance will show the same frame at the same
     * wall-clock time (stateless time model). Pass the current elapsed time to
     * [render] to drive the animation.
     *
     * @throws IndexOutOfBoundsException if the cell is outside the grid
     */
    public fun drawTile(x: Int, y: Int, tile: AnimatedAsciiTile) {
        tiles[x, y] = tile
    }

    /**
     * Writes [text] starting at ([x], [y]), one character per cell to the
     * right, in [foreground] over [background]. Characters that fall outside
     * the window are skipped rather than throwing.
     */
    public fun drawText(
        x: Int,
        y: Int,
        text: String,
        foreground: Color = Color.WHITE,
        background: Color = Color.BLACK,
    ) {
        if (y !in 0 until heightInTiles) return

        text.forEachIndexed { index, character ->
            val cellX = x + index
            if (cellX in 0 until widthInTiles) {
                tiles[cellX, y] = AsciiTileDescriptor(character, foreground, background)
            }
        }
    }

    /** Sets every cell to the static [tile]. */
    public fun fill(tile: AsciiTileDescriptor) {
        for (y in 0 until heightInTiles) {
            for (x in 0 until widthInTiles) {
                tiles[x, y] = tile
            }
        }
    }

    /** Sets every cell to the animated [tile]. */
    public fun fill(tile: AnimatedAsciiTile) {
        for (y in 0 until heightInTiles) {
            for (x in 0 until widthInTiles) {
                tiles[x, y] = tile
            }
        }
    }

    /** Clears the cell at column [x], row [y] so nothing is drawn there. */
    public fun clearTile(x: Int, y: Int) {
        tiles[x, y] = null
    }

    /** Clears every cell. */
    public fun clear() = tiles.clear()

    /**
     * Draws all populated cells to the canvas for this frame.
     *
     * @param elapsedMs monotonically increasing wall-clock time in milliseconds
     *   used to determine the current frame of any [AnimatedAsciiTile] cells.
     *   Defaults to `0`, which always shows the first frame — suitable for
     *   windows that only use static [AsciiTileDescriptor] cells.
     */
    public fun render(elapsedMs: Long = 0L) {
        canvas.begin()
        for (y in 0 until heightInTiles) {
            for (x in 0 until widthInTiles) {
                val descriptor = tiles[x, y]?.descriptorAt(elapsedMs) ?: continue

                canvas.drawTile(x, y, backgroundRegion, descriptor.backgroundColor)
                font.glyph(descriptor.character)?.let { glyph ->
                    canvas.drawTile(x, y, glyph, descriptor.foregroundColor)
                }
            }
        }
        canvas.end()
    }

    /**
     * Updates the canvas projection to the new pixel dimensions.
     *
     * When [fitToWindow] is `true`, also recomputes [widthInTiles] and
     * [heightInTiles] from the new pixel size and rebuilds the internal cell
     * grid. Existing cell content that still fits within the new dimensions is
     * preserved; cells outside the new bounds are dropped.
     *
     * When [fitToWindow] is `false`, only the canvas projection is updated;
     * the tile grid remains unchanged.
     */
    public fun resize(widthPx: Int, heightPx: Int) {
        canvas.resize(widthPx, heightPx)
        if (!fitToWindow) return

        val newWidthInTiles = widthPx / font.charWidthPx
        val newHeightInTiles = heightPx / font.charHeightPx
        if (newWidthInTiles == widthInTiles && newHeightInTiles == heightInTiles) return

        val oldTiles = tiles
        val oldWidth = widthInTiles
        val oldHeight = heightInTiles

        widthInTiles = newWidthInTiles
        heightInTiles = newHeightInTiles
        tiles = Grid(widthInTiles, heightInTiles, null)

        // Copy over cells that still fit in the new bounds.
        for (y in 0 until minOf(oldHeight, heightInTiles)) {
            for (x in 0 until minOf(oldWidth, widthInTiles)) {
                tiles[x, y] = oldTiles[x, y]
            }
        }
    }

    /** Disposes the canvas, font, and background texture. */
    override fun dispose() {
        canvas.dispose()
        font.dispose()
        backgroundTexture.dispose()
    }

    /** Factory for building [AsciiTileWindow] instances. */
    public companion object {
        /**
         * Builds an [AsciiTileWindow] from an [AsciiTileWindowConfig]. The
         * canvas tile size is taken from the font.
         *
         * ```
         * val window = AsciiTileWindow.create {
         *     widthInTiles = 80
         *     heightInTiles = 30
         * }
         * ```
         */
        public fun create(init: AsciiTileWindowConfig.() -> Unit): AsciiTileWindow {
            val config = AsciiTileWindowConfig().apply(init)
            val font = config.font ?: Fonts.cp437_10x10()
            val canvas = KotileCanvas(font.charWidthPx, font.charHeightPx)

            return AsciiTileWindow(font, canvas, config.widthInTiles, config.heightInTiles, config.fitToWindow)
        }
    }
}

/**
 * Configuration for [AsciiTileWindow.create].
 *
 * @property font font to render with; defaults to [Fonts.cp437_10x10] when null
 * @property widthInTiles initial grid width in cells; used when [fitToWindow]
 *   is `false` or before the first [AsciiTileWindow.resize] call
 * @property heightInTiles initial grid height in cells; used when [fitToWindow]
 *   is `false` or before the first [AsciiTileWindow.resize] call
 * @property fitToWindow when `true` (default), [AsciiTileWindow.resize]
 *   recomputes the tile grid to fit the new pixel dimensions; when `false` the
 *   grid stays at its construction-time size
 */
public data class AsciiTileWindowConfig(
    var font: Font? = null,
    var widthInTiles: Int = 80,
    var heightInTiles: Int = 30,
    var fitToWindow: Boolean = true,
)
