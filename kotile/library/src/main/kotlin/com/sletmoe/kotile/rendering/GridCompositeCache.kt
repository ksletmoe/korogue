package com.sletmoe.kotile.rendering

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.OrthographicCamera
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.SpriteBatch
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.graphics.glutils.FrameBuffer
import com.badlogic.gdx.utils.BufferUtils
import com.badlogic.gdx.utils.Disposable

/**
 * Caches a grid renderer's composited output in an offscreen [FrameBuffer] so an
 * unchanged frame costs one blit instead of a full per-cell repaint, and a
 * partially-changed frame (e.g. one animated tile on an otherwise-static grid)
 * only redraws the cells that actually changed (krogue-drk/krogue-oxi; see
 * ADR-0024). The owner ([TileRenderer],
 * [com.sletmoe.kotile.display.ascii.AsciiTileWindow]) calls [markCellDirty] on
 * every single-cell write and [markAllDirty] on bulk operations (fill/clear/
 * resize), then [recompositeIfDirty] with the same per-cell draw logic it would
 * otherwise run directly against the screen; cells that were never marked keep
 * whatever pixels the cache already had for them. [cachedRegion] is then drawn
 * through the owner's normal [com.sletmoe.kotile.display.KotileCanvas.drawSprite]
 * as a single sprite, so scaling/letterboxing/sharp-bilinear smoothing at the
 * final on-screen size is unaffected — this cache only ever draws at native
 * (1:1) resolution internally.
 *
 * ## Two recomposite paths
 *
 * - **Fully dirty** (first paint, a resize, or any bulk write): the whole
 *   [FrameBuffer] is cleared once and every cell is redrawn in one batched
 *   pass — the same total draw-call count as not caching at all.
 * - **Partially dirty** (the steady-state case this cache exists for): each
 *   dirty cell's native-pixel rectangle is individually scissored, cleared,
 *   drawn, and **flushed** before moving to the next cell — see
 *   [recompositeIfDirty]'s doc for why the flush is required — so a cell that
 *   was never marked keeps its previous, already-correct pixels untouched.
 *
 * Internally this uses a **y-up world with a manual per-draw flip**
 * ([TileDrawer.drawTile]), the same convention [GridViewport] and
 * [com.sletmoe.kotile.display.KotileCanvas] already use, rather than a y-down
 * camera — ADR-0017 deliberately avoided a y-down world project-wide to sidestep
 * inverting texture V-coordinates, a risk that is unverifiable without a live GL
 * context; this cache follows the same already-proven convention rather than
 * introducing a second one.
 */
internal class GridCompositeCache(
    private val tileWidthPx: Int,
    private val tileHeightPx: Int,
) : Disposable {
    /** Draws into the cache's native-resolution [FrameBuffer] for the duration of one [recompositeIfDirty] call. */
    interface TileDrawer {
        /** Draws [region] at cell ([x], [y]) (top-left origin, tile-sized), tinted by [tint]. */
        fun drawTile(
            x: Int,
            y: Int,
            region: TextureRegion,
            tint: Color,
            flipX: Boolean = false,
            flipY: Boolean = false,
        )
    }

    // Scratch buffer for glGetIntegerv. Reused rather than allocated per recomposite, which runs
    // every frame that anything changed. glGetIntegerv wants room for the largest query it serves
    // (GL_VIEWPORT: 4 ints); 16 is libGDX's own habit and costs nothing.
    //
    // Shared mutable state, so the invariant matters: preservingFrameBuffer copies everything it
    // reads out of this buffer into locals *before* running its block, and never touches it again
    // afterwards. That is what makes nesting one preservingFrameBuffer inside another safe -- the
    // inner call can clear and reuse the buffer freely, because the outer call is already done with
    // it. Keep it that way: reading from this buffer after the block would silently see the inner
    // call's values instead of its own. (Single-threaded by construction -- all of this runs on the
    // GL thread.)
    private val glQueryBuffer = BufferUtils.newIntBuffer(16)

    private var frameBuffer: FrameBuffer? = null
    private var batch: SpriteBatch? = null
    private var camera: OrthographicCamera? = null
    private var pxWidth = 0
    private var pxHeight = 0
    private var gridWidth = -1
    private var gridHeight = -1

    // fullyDirty wins over dirtyCells (and clears it) -- see markAllDirty/markCellDirty. Kept
    // separate from a "dirty everything individually" encoding so the fully-dirty path can batch
    // every cell's draw without an interleaved flush per cell (see recompositeIfDirty).
    private var fullyDirty = true
    private val dirtyCells = HashSet<Long>()

    /** Marks every cell stale (first paint, a resize, or any bulk write). Supersedes [markCellDirty]. */
    fun markAllDirty() {
        fullyDirty = true
        dirtyCells.clear()
    }

    /** Marks a single cell stale; a no-op if [markAllDirty] already covers this recomposite. */
    fun markCellDirty(
        x: Int,
        y: Int,
    ) {
        if (!fullyDirty) dirtyCells.add(packCell(x, y))
    }

    /**
     * (Re)allocates the backing [FrameBuffer] if the grid's tile dimensions
     * changed since the last call (or this is the first call), forcing a
     * recomposite. No-op if the size is unchanged.
     */
    fun ensureSize(
        widthInTiles: Int,
        heightInTiles: Int,
    ) {
        if (widthInTiles == gridWidth && heightInTiles == gridHeight && frameBuffer != null) return
        // The whole (re)allocation runs under the guard, not just the FrameBuffer constructor:
        // that constructor leaves framebuffer 0 bound once it has built, and deleting a framebuffer
        // that happens to be bound also reverts the binding to 0 per the GL spec. The old cache
        // buffer should never be the bound one by the time we get here, but "should never" is the
        // kind of assumption that quietly stops being true, and guarding the whole block costs one
        // pair of GL queries on a resize (krogue-s5h).
        preservingFrameBuffer {
            disposeGpuResources()
            gridWidth = widthInTiles
            gridHeight = heightInTiles
            pxWidth = (widthInTiles * tileWidthPx).coerceAtLeast(1)
            pxHeight = (heightInTiles * tileHeightPx).coerceAtLeast(1)
            frameBuffer = FrameBuffer(Pixmap.Format.RGBA8888, pxWidth, pxHeight, false)
            // FrameBuffer color attachments default to linear filtering (unlike a plain Texture, which
            // defaults to nearest) -- left alone, blitting this 1:1-native cache back at a >1x on-screen
            // scale comes out blurred instead of crisp. Force nearest so IntegerScale stays pixel-perfect;
            // the outer KotileCanvas.drawSprite call still switches to sharp-bilinear on top of this when
            // the final on-screen scale is fractional (that shader wants Linear, set per-draw, separately).
            frameBuffer!!.colorBufferTexture.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest)
            batch = SpriteBatch()
            camera =
                OrthographicCamera().apply {
                    setToOrtho(false, pxWidth.toFloat(), pxHeight.toFloat())
                    update()
                }
        }
        markAllDirty()
    }

    /**
     * Recomposites into the cache only if [markAllDirty]/[markCellDirty] was
     * called since the last call; no-op otherwise. [draw] is invoked once per
     * cell that needs redrawing — every populated layer's content at that
     * cell, bottom-up — via the supplied [TileDrawer].
     *
     * When fully dirty, every cell in [gridWidth] x [gridHeight] is redrawn in
     * one batched pass after a single whole-buffer clear.
     *
     * When partially dirty, each dirty cell is individually scissored (so its
     * clear doesn't touch neighboring cells), cleared to transparent, drawn,
     * and the batch is **flushed immediately** — before the scissor rectangle
     * moves to the next cell. This flush is required, not optional:
     * [SpriteBatch] buffers draw calls and only actually issues them to the GPU
     * on a flush, so without one every cell's geometry would still be sitting
     * in the buffer when the *next* cell's [Gdx.gl.glScissor] takes effect,
     * and would render clipped to the wrong (last-set) rectangle instead of
     * its own.
     */
    fun recompositeIfDirty(draw: (x: Int, y: Int, drawer: TileDrawer) -> Unit) {
        if (!fullyDirty && dirtyCells.isEmpty()) return
        val fbo = frameBuffer ?: return
        val b = batch ?: return

        preservingFrameBuffer {
            recomposite(fbo, b, draw)
        }

        fullyDirty = false
        dirtyCells.clear()
    }

    private fun recomposite(
        fbo: FrameBuffer,
        b: SpriteBatch,
        draw: (x: Int, y: Int, drawer: TileDrawer) -> Unit,
    ) {
        fbo.begin()
        b.projectionMatrix = camera!!.combined
        val drawer = nativeDrawer(b)
        if (fullyDirty) {
            Gdx.gl.glClearColor(0f, 0f, 0f, 0f)
            Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
            b.begin()
            for (y in 0 until gridHeight) {
                for (x in 0 until gridWidth) {
                    draw(x, y, drawer)
                }
            }
            b.end()
        } else {
            Gdx.gl.glEnable(GL20.GL_SCISSOR_TEST)
            b.begin()
            for (packed in dirtyCells) {
                val x = unpackCellX(packed)
                val y = unpackCellY(packed)
                // Same top-left-origin -> GL bottom-left-origin flip as the draw itself (below).
                Gdx.gl.glScissor(x * tileWidthPx, pxHeight - (y + 1) * tileHeightPx, tileWidthPx, tileHeightPx)
                Gdx.gl.glClearColor(0f, 0f, 0f, 0f)
                Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
                draw(x, y, drawer)
                b.flush() // must land while this cell's scissor rect is still active -- see doc above
            }
            b.end()
            Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST)
        }
        fbo.end()
    }

    /**
     * Runs [block] and puts back whatever framebuffer and viewport were bound before it.
     *
     * libGDX framebuffers do not nest, in two separate places: `FrameBuffer.end()` binds
     * framebuffer 0 and resets the viewport to the whole backbuffer rather than restoring what was
     * bound before `begin()`, **and** the `FrameBuffer` *constructor* leaves 0 bound once it has
     * finished building. Either one silently steals a consumer's own FrameBuffer: someone rendering
     * kotile into a texture for a post-process, a transition or a screenshot would find their buffer
     * empty and their frame on screen instead, with no error at all (krogue-s5h).
     *
     * So both the allocation ([ensureSize]) and the draw pass ([recompositeIfDirty]) are wrapped in
     * this, making the cache invisible to whatever GL state it interrupted.
     */
    private inline fun <T> preservingFrameBuffer(block: () -> T): T {
        glQueryBuffer.clear()
        Gdx.gl.glGetIntegerv(GL20.GL_FRAMEBUFFER_BINDING, glQueryBuffer)
        val handle = glQueryBuffer.get(0)
        glQueryBuffer.clear()
        Gdx.gl.glGetIntegerv(GL20.GL_VIEWPORT, glQueryBuffer)
        val viewport = IntArray(4) { glQueryBuffer.get(it) }

        try {
            return block()
        } finally {
            // Always rebind, including handle 0. Skipping the "redundant" zero case would only be
            // safe on the happy path, where FrameBuffer.end() has already bound 0 for us. If block()
            // throws between fbo.begin() and fbo.end() -- a consumer's regionFor(), a draw callback,
            // an allocation failure -- our internal cache FBO is still bound, and skipping the
            // rebind would leave it bound for good, silently redirecting every later draw into the
            // cache. One redundant GL call per recomposite is the right price for that.
            Gdx.gl.glBindFramebuffer(GL20.GL_FRAMEBUFFER, handle)
            Gdx.gl.glViewport(viewport[0], viewport[1], viewport[2], viewport[3])
        }
    }

    private fun nativeDrawer(b: SpriteBatch): TileDrawer {
        val heightPx = pxHeight.toFloat()
        val tileW = tileWidthPx.toFloat()
        val tileH = tileHeightPx.toFloat()
        return object : TileDrawer {
            override fun drawTile(
                x: Int,
                y: Int,
                region: TextureRegion,
                tint: Color,
                flipX: Boolean,
                flipY: Boolean,
            ) {
                b.color = tint
                if (flipX || flipY) region.flip(flipX, flipY)
                // Same top-left-origin -> y-up-GL flip KotileCanvas.drawSprite uses.
                val glY = heightPx - (y + 1) * tileH
                b.draw(region, x * tileW, glY, tileW, tileH)
                if (flipX || flipY) region.flip(flipX, flipY)
            }
        }
    }

    /**
     * The cached composite as a screen-ready [TextureRegion] (V-flipped to
     * correct for [FrameBuffer] color textures being stored bottom-up), or
     * `null` before the first [ensureSize] call. A fresh [TextureRegion] is
     * returned each time since [TextureRegion.flip] mutates in place and this
     * method must never hand back a region some other draw call could still be
     * using.
     */
    val cachedRegion: TextureRegion?
        get() = frameBuffer?.let { TextureRegion(it.colorBufferTexture).apply { flip(false, true) } }

    private fun packCell(
        x: Int,
        y: Int,
    ): Long = (x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL)

    private fun unpackCellX(packed: Long): Int = (packed shr 32).toInt()

    private fun unpackCellY(packed: Long): Int = packed.toInt()

    private fun disposeGpuResources() {
        frameBuffer?.dispose()
        batch?.dispose()
        frameBuffer = null
        batch = null
    }

    override fun dispose() = disposeGpuResources()
}
