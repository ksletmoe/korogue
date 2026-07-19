package com.sletmoe.kotile

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.badlogic.gdx.graphics.glutils.FrameBuffer
import com.badlogic.gdx.utils.BufferUtils
import com.sletmoe.kotile.display.KotileCanvas
import com.sletmoe.kotile.display.ascii.AnimatedAsciiTile
import com.sletmoe.kotile.display.ascii.AsciiTile
import com.sletmoe.kotile.display.ascii.AsciiTileWindow
import com.sletmoe.kotile.display.ascii.StaticAsciiTile
import com.sletmoe.kotile.rendering.TileRenderer
import com.sletmoe.kotile.tiles.AnimationFrame
import com.sletmoe.kotile.tiles.StaticSpriteTile
import com.sletmoe.kotile.tiles.TileSheet
import com.sletmoe.kotile.utilities.LayeredTilemap
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Integration tests for ASCII glyph/background layer resolution (krogue-7va,\n * ADR-0030) and consumer-owned [com.badlogic.gdx.graphics.glutils.FrameBuffer]\n * survival across kotile rendering (krogue-s5h). Skipped unless a (software) GL\n * context is available; see [HeadlessGl].
 */
class RenderingLayerResolutionIntegrationTest : FunSpec({
    // -------------------------------------------------------------------------
    // krogue-7va (ADR-0030): an ASCII cell's glyph and background resolve from different
    // layers -- glyph top-cell-wins, background from the top-most cell that paints one. These
    // supersede the ADR-0029 cases that pinned background as top-cell-wins too; that policy
    // is what made a transparent background do nothing. Pinned so it can't drift back.
    // -------------------------------------------------------------------------

    /** Renders one 10x10 ASCII cell: z=0 is always '#' white on blue; [overlay] goes on z=1. */
    fun asciiOverlayCell(overlay: StaticAsciiTile?): Color {
        val pixels =
            HeadlessGl.render(10, 10, Color.BLACK) {
                val window =
                    AsciiTileWindow.create {
                        widthInTiles = 1
                        heightInTiles = 1
                    }
                window.drawTile(0, 0, z = 0, tile = StaticAsciiTile('#', Color.WHITE, Color.BLUE))
                if (overlay != null) window.drawTile(0, 0, z = 1, tile = overlay)
                window.render()
                window.dispose()
            }
        val avg = pixels.averageColor(0, 0, 10, 10)
        pixels.dispose()
        return avg
    }

    test("krogue-7va: a CLEAR-background ASCII cell keeps the background of the cell beneath it").config(
        enabled = HeadlessGl.available,
    ) {
        // The point of per-channel resolution (ADR-0030): an overlay sits ON the terrain instead of
        // erasing it. The '@' takes the glyph channel; z=0's blue still supplies the background,
        // without the creature cell having to know what colour the floor is.
        val baseline = asciiOverlayCell(null)
        baseline.b.toDouble() shouldBe (1.0 plusOrMinus 0.1)

        // Averaged over the whole cell the blue lands near 0.72, not 1.0, because the '@' glyph
        // itself covers roughly a quarter of the cell in red. Asserting "most of the cell is still
        // blue" rather than a tuned constant, and pinning the contrast against BLACK below.
        val overlaid = asciiOverlayCell(StaticAsciiTile('@', Color.RED, Color.CLEAR))
        overlaid.b.toDouble() shouldBeGreaterThan 0.5 // z=0's background survived under the glyph
        overlaid.r.toDouble() shouldBeGreaterThan 0.1 // ...and the '@' drew on top of it
    }

    test("krogue-7va: a transparent ASCII background is now distinguishable from an opaque black one").config(
        enabled = HeadlessGl.available,
    ) {
        // The inverse of what ADR-0029 measured and pinned. CLEAR used to be byte-identical to
        // BLACK once layered, which is what made StaticAsciiTile's KDoc a lie; now CLEAR defers to
        // the layer below and BLACK paints black, so they must differ.
        val clear = asciiOverlayCell(StaticAsciiTile('@', Color.RED, Color.CLEAR))
        val black = asciiOverlayCell(StaticAsciiTile('@', Color.RED, Color.BLACK))
        clear.b.toDouble() shouldBeGreaterThan 0.5 // defers to z=0's blue
        black.b.toDouble() shouldBe (0.0 plusOrMinus 0.1) // paints its own black
    }

    test("krogue-7va: an opaque ASCII background still wins outright, hiding the one beneath").config(
        enabled = HeadlessGl.available,
    ) {
        // Per-channel is not alpha blending: the first cell that paints a background wins and its
        // colour is used as-is. An opaque overlay must still hide the terrain colour completely.
        val opaque = asciiOverlayCell(StaticAsciiTile('@', Color.RED, Color.GREEN))
        opaque.g.toDouble() shouldBeGreaterThan 0.5 // the overlay's green
        opaque.b.toDouble() shouldBe (0.0 plusOrMinus 0.1) // z=0's blue is gone
    }

    test("krogue-7va: a blank ASCII cell still wins the glyph channel, but not the background").config(
        enabled = HeadlessGl.available,
    ) {
        // The remaining footgun, now narrower than it was: a space is keyed out and draws nothing,
        // and it still takes the GLYPH channel (so '#' below is hidden) -- but the background below
        // now shows through, where before the whole cell went black. clearTile still beats a space.
        val blanked = asciiOverlayCell(StaticAsciiTile(' ', Color.WHITE, Color.CLEAR))
        blanked.b.toDouble() shouldBe (1.0 plusOrMinus 0.15) // z=0's background survives in full...
        blanked.r.toDouble() shouldBe (0.0 plusOrMinus 0.1) // ...but z=0's white '#' is not drawn
    }

    test("krogue-7va: a background-only overlay must sit below what it tints, not above").config(
        enabled = HeadlessGl.available,
    ) {
        // Pins the layer ordering the KDoc and ADR-0030 recommend, because the first draft of both
        // claimed the opposite and was wrong: a highlight ABOVE a creature still wins the glyph
        // channel (a space is a glyph, just a keyed-out one) and erases it.
        fun cellOf(vararg layers: StaticAsciiTile): Color {
            val pixels =
                HeadlessGl.render(10, 10, Color.BLACK) {
                    val window =
                        AsciiTileWindow.create {
                            widthInTiles = 1
                            heightInTiles = 1
                        }
                    layers.forEachIndexed { z, tile -> window.drawTile(0, 0, z = z, tile = tile) }
                    window.render()
                    window.dispose()
                }
            val avg = pixels.averageColor(0, 0, 10, 10)
            pixels.dispose()
            return avg
        }

        val terrain = StaticAsciiTile('#', Color.WHITE, Color.BLUE)
        val creature = StaticAsciiTile('@', Color.RED, Color.CLEAR)
        val highlight = StaticAsciiTile(' ', Color.WHITE, Color.GREEN)

        // Recommended: terrain, highlight, creature. Creature keeps the glyph, highlight the bg.
        val correct = cellOf(terrain, highlight, creature)
        correct.r.toDouble() shouldBeGreaterThan 0.1 // the creature is visible...
        correct.g.toDouble() shouldBeGreaterThan 0.4 // ...on the highlight's tint...
        correct.b.toDouble() shouldBe (0.0 plusOrMinus 0.1) // ...which replaced the terrain's blue

        // The tempting-but-wrong order: highlight on top blanks the creature entirely.
        val wrong = cellOf(terrain, creature, highlight)
        wrong.r.toDouble() shouldBe (0.0 plusOrMinus 0.1) // no creature at all
    }

    test("krogue-7va: a dynamic background under a static glyph keeps animating through a viewport").config(
        enabled = HeadlessGl.available,
    ) {
        // The regression guard for this PR's other half. render(source, viewport) decides a cell is
        // animated by asking whether ANY layer there holds a DynamicAsciiTile -- it used to ask only
        // about the top cell. Now that a background can come from underneath, a dynamic cell below a
        // static glyph changes the cell every frame while never being the top cell: under the old
        // predicate it would be marked clean and freeze. Two renders at different elapsed times must
        // therefore produce different backgrounds.
        val flickering =
            AnimatedAsciiTile(
                frames =
                    listOf(
                        AnimationFrame(StaticAsciiTile(' ', Color.WHITE, Color.BLUE), durationMs = 100),
                        AnimationFrame(StaticAsciiTile(' ', Color.WHITE, Color.GREEN), durationMs = 100),
                    ),
            )

        fun frameAt(elapsedMs: Long): Color {
            val pixels =
                HeadlessGl.render(10, 10, Color.BLACK) {
                    val window =
                        AsciiTileWindow.create {
                            widthInTiles = 1
                            heightInTiles = 1
                        }
                    val source = LayeredTilemap<AsciiTile>(1, 1)
                    source.setCell(0, 0, 0, flickering) // dynamic BACKGROUND, underneath...
                    source.setCell(0, 0, 1, StaticAsciiTile('@', Color.RED, Color.CLEAR)) // ...a static glyph
                    window.render(source, elapsedMs = 0) // first paint establishes the cache
                    window.render(source, elapsedMs = elapsedMs)
                    window.dispose()
                }
            val avg = pixels.averageColor(0, 0, 10, 10)
            pixels.dispose()
            return avg
        }

        val frame0 = frameAt(0) // blue frame
        val frame1 = frameAt(150) // green frame

        frame0.b.toDouble() shouldBeGreaterThan 0.5 // the dynamic background is showing...
        frame1.g.toDouble() shouldBeGreaterThan 0.5 // ...and it advanced rather than freezing
        frame1.b.toDouble() shouldBe (0.0 plusOrMinus 0.15)
    }

    test("krogue-7va: both paths let an upper layer that paints nothing reveal the one beneath").config(
        enabled = HeadlessGl.available,
    ) {
        // Same setup on both paths, asserted side by side. They still resolve differently by
        // construction (sprite alpha-blends pixels; ASCII picks a background layer per channel),
        // but the consumer-visible promise now agrees: an upper layer that paints nothing does not
        // erase what is under it. See ADR-0029 for the divergence, ADR-0030 for the convergence.
        val sprite = renderLayered(background = Color.BLUE, foreground = Color(1f, 0f, 0f, 0f))
        sprite.b.toDouble() shouldBe (1.0 plusOrMinus 0.1)

        val ascii = asciiOverlayCell(StaticAsciiTile(' ', Color.WHITE, Color.CLEAR))
        ascii.b.toDouble() shouldBe (1.0 plusOrMinus 0.15)
    }

    // -------------------------------------------------------------------------
    // krogue-s5h: a consumer's own FrameBuffer must survive kotile rendering. libGDX FBOs
    // do not nest -- FrameBuffer.end() binds 0 unconditionally -- so the composite cache
    // used to silently steal it and put the frame on screen instead, with no error.
    // -------------------------------------------------------------------------

    test("krogue-s5h: rendering inside a consumer's own FrameBuffer leaves it bound, and lands in it").config(
        enabled = HeadlessGl.available,
    ) {
        // The consumer case this protects: render kotile into your own FBO to post-process it,
        // render to a texture, do a transition, take a screenshot. Drive an AsciiTileWindow inside
        // a caller-owned FBO and read that FBO back -- if the cache steals the binding, the content
        // goes to the window and this reads an empty buffer.
        var boundAfterFirst = -1
        var boundAfterCacheHit = -1
        var boundAfterRealloc = -1
        var callerHandle = -1
        // (before, after) of the GL viewport around each render call.
        var viewportsAroundRenders = listOf<Pair<List<Int>, List<Int>>>()
        var pixelsInCallerFbo: Color? = null

        // The Pixmap HeadlessGl returns is deliberately unused here (hence the bare dispose): this
        // test's own `consumerFbo.end()` binds framebuffer 0, so that capture reads the shared
        // window rather than HeadlessGl's capture FBO. Harmless -- every assertion below comes from
        // the closure vars, captured while the right buffer was bound -- but don't add an assertion
        // on the returned pixmap here without rebinding first.
        HeadlessGl.render(20, 20, Color.BLACK) {
            val query = BufferUtils.newIntBuffer(16)

            fun frameBufferBinding(): Int {
                query.clear()
                Gdx.gl.glGetIntegerv(GL20.GL_FRAMEBUFFER_BINDING, query)
                return query.get(0)
            }

            fun viewport(): IntArray {
                query.clear()
                Gdx.gl.glGetIntegerv(GL20.GL_VIEWPORT, query)
                return IntArray(4) { query.get(it) }
            }

            val consumerFbo = FrameBuffer(Pixmap.Format.RGBA8888, 20, 20, false)
            consumerFbo.begin()
            Gdx.gl.glClearColor(0f, 0f, 0f, 1f)
            Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)
            callerHandle = consumerFbo.framebufferHandle

            val window =
                AsciiTileWindow.create {
                    widthInTiles = 2
                    heightInTiles = 2
                    fitToWindow = false // so resize() below reallocates rather than reflowing to nothing
                }
            window.fill(StaticAsciiTile(' ', Color.WHITE, Color.BLUE))

            // Three passes, because they hit different code: the first ALLOCATES the cache's
            // FrameBuffer (whose constructor leaves 0 bound), the second is a pure cache hit
            // (nothing dirty, so no FBO pass at all), and the resize DISPOSES and reallocates.
            // Each must leave the caller's binding and viewport exactly as it found them.
            //
            // Note what is compared: the viewport around EACH render, not every render against
            // the original. A resize is *supposed* to change the viewport -- that is what resize
            // means -- so asserting pass 3 against the pre-resize value would assert the opposite
            // of the intended behaviour (and did, briefly: CI caught it).
            fun renderPreservingState(): Int {
                val before = viewport().toList()
                window.render()
                viewportsAroundRenders += before to viewport().toList()
                return frameBufferBinding()
            }

            boundAfterFirst = renderPreservingState()

            // Did the blue actually land in the caller's buffer? Read it explicitly:
            // createFromFrameBuffer reads whatever is bound, so reading blind would happily report
            // the window's contents and pass even when the binding was stolen. Checked here, after
            // the first render, while the grid still matches this 20x20 buffer.
            Gdx.gl.glBindFramebuffer(GL20.GL_FRAMEBUFFER, callerHandle)
            val inside = Pixmap.createFromFrameBuffer(0, 0, 20, 20)
            pixelsInCallerFbo = inside.averageColor(0, 0, 20, 20)
            inside.dispose()

            boundAfterCacheHit = renderPreservingState()

            window.resize(40, 40)
            window.fill(StaticAsciiTile(' ', Color.WHITE, Color.BLUE))
            boundAfterRealloc = renderPreservingState()

            consumerFbo.end()
            window.dispose()
            consumerFbo.dispose()
        }.dispose()

        boundAfterFirst shouldBe callerHandle // survived the allocating first render...
        boundAfterCacheHit shouldBe callerHandle // ...the cache hit that does no FBO work...
        boundAfterRealloc shouldBe callerHandle // ...and the resize that disposes and reallocates
        // Every render left the viewport as it found it -- checked per render, so a pass that
        // transiently clobbers it can't hide behind a later pass that happens to look right.
        viewportsAroundRenders shouldHaveSize 3
        viewportsAroundRenders.forEach { (before, after) -> after shouldBe before }
        pixelsInCallerFbo!!.b.toDouble() shouldBe (1.0 plusOrMinus 0.15) // the frame went where the caller asked
    }

    test("krogue-s5h: a throwing draw callback still leaves the caller's framebuffer bound").config(
        enabled = HeadlessGl.available,
    ) {
        // The restore has to survive the unhappy path too. If a consumer's own code throws
        // mid-recomposite -- here a regionFor() that blows up between fbo.begin() and fbo.end() --
        // libGDX never runs its end(), so kotile's internal cache FBO is still bound. Restoring
        // only "when someone else's buffer was bound" would leave that cache FBO bound for good,
        // and every later draw in the app would silently land inside it.
        var boundBeforeThrow = -1
        var boundAfterThrow = -2
        var threw = false

        HeadlessGl.render(16, 16, Color.BLACK) {
            val tilePixmap = Pixmap(8, 8, Pixmap.Format.RGBA8888)
            tilePixmap.setColor(Color.RED)
            tilePixmap.fill()
            val file = File.createTempFile("kotile-s5h-throw", ".png").apply { deleteOnExit() }
            PixmapIO.writePNG(Gdx.files.absolute(file.absolutePath), tilePixmap)
            tilePixmap.dispose()

            val sheet = TileSheet(Gdx.files.absolute(file.absolutePath), 8, 8)
            val canvas = KotileCanvas(8, 8)
            val exploding =
                object : TileRenderer(canvas) {
                    override fun regionFor(staticTile: StaticSpriteTile): TextureRegion =
                        error("boom -- a consumer's region lookup failed mid-recomposite")
                }
            exploding.drawTile(0, 0, z = 0, tile = StaticSpriteTile(0, 0))

            val query = BufferUtils.newIntBuffer(16)

            fun binding(): Int {
                query.clear()
                Gdx.gl.glGetIntegerv(GL20.GL_FRAMEBUFFER_BINDING, query)
                return query.get(0)
            }

            // Bind framebuffer 0 explicitly. This is the whole point of the test: the bug lived in
            // an `if (handle != 0)` skip, so it only ever bit when nothing of the caller's was
            // bound. HeadlessGl.render has its own capture FBO bound around draw(), which would
            // make handle non-zero and let the buggy code restore correctly -- the test would pass
            // against the very code it exists to catch.
            Gdx.gl.glBindFramebuffer(GL20.GL_FRAMEBUFFER, 0)
            boundBeforeThrow = binding()
            try {
                exploding.render()
            } catch (expected: IllegalStateException) {
                threw = true
            }
            boundAfterThrow = binding()

            exploding.dispose()
            canvas.dispose()
            sheet.dispose()
        }.dispose()

        threw shouldBe true // the consumer's exception propagated, as it should
        boundBeforeThrow shouldBe 0 // guards the guard: if this is ever non-zero the test is toothless
        // ...and framebuffer 0 is bound again afterwards, rather than kotile's cache FBO being left
        // behind. This is the assertion the old `if (handle != 0)` skip fails: it leaves the cache
        // FBO bound (measured: 0 -> 1), silently redirecting every later draw into the cache.
        boundAfterThrow shouldBe boundBeforeThrow
    }
})
