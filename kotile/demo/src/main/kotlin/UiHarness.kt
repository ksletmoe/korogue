import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.badlogic.gdx.graphics.Texture
import com.badlogic.gdx.graphics.g2d.TextureRegion
import com.sletmoe.kotile.display.KotileCanvas
import com.sletmoe.kotile.display.ascii.AsciiTileWindow
import com.sletmoe.kotile.display.ascii.Font
import com.sletmoe.kotile.display.ascii.Fonts
import com.sletmoe.kotile.display.ascii.StaticAsciiTile
import com.sletmoe.kotile.rendering.LayerStack
import com.sletmoe.kotile.rendering.PixelRect
import com.sletmoe.kotile.rendering.UiLayer
import com.sletmoe.kotile.rendering.Widget
import java.util.zip.Deflater

/**
 * Visual harness for the free (pixel-space) UI layer (krogue-tvm, ADR-0018): a
 * [UiLayer] of persistent widgets composited over an ASCII grid at **sub-tile**
 * pixel positions — a panel and two buttons deliberately placed between cell rows
 * so the free (non-grid-snapped) placement is obvious against the character grid
 * beneath.
 *
 * Unlike the effects harness (transient, motion-only), this exercises the
 * *input-aware* half of the layer: before the snapshot it drives a synthetic
 * pointer through [GridLayout.contentPixelAt][com.sletmoe.kotile.rendering.GridLayout.contentPixelAt]
 * → [UiLayer.onPointerMoved], so pixel-space hit-testing picks the button under
 * the cursor and that button renders in its hover tint — proving the whole
 * input → hit-test → render loop, not just placement.
 *
 *   ./gradlew :kotile:demo:uiHarness                     # -> demo/build/ui-harness.png
 *   ./gradlew :kotile:demo:uiHarness -PoutFile=/tmp/ui.png
 */
private const val WINDOW_W_PX = 600
private const val WINDOW_H_PX = 300
private const val COLS = 60
private const val ROWS = 30

/**
 * A rounded rectangle "button" [Widget] that fills its [bounds] with a solid
 * texture, tinted differently while [hovered]. Proves both free-pixel rendering
 * and input hit-testing: [UiLayer] flips [hovered] via [onPointerMoved].
 */
private class ButtonWidget(
    override val bounds: PixelRect,
    private val texture: Texture,
    private val idleTint: Color,
    private val hoverTint: Color,
) : Widget {
    override var visible = true
    var hovered = false
        private set

    override fun render(canvas: KotileCanvas) {
        canvas.drawSprite(
            bounds.x,
            bounds.y,
            TextureRegion(texture),
            bounds.width,
            bounds.height,
            tint = if (hovered) hoverTint else idleTint,
        )
    }

    // The cursor is over this widget (UiLayer only dispatches hits here).
    override fun onPointerMoved(
        px: Float,
        py: Float,
    ): Boolean {
        hovered = true
        return true
    }

    fun clearHover() {
        hovered = false
    }
}

private class UiHarness(private val outPath: String) : ApplicationAdapter() {
    private lateinit var font: Font
    private lateinit var canvas: KotileCanvas
    private lateinit var window: AsciiTileWindow
    private lateinit var solid: Texture
    private val ui = UiLayer()
    private lateinit var stack: LayerStack
    private lateinit var buttons: List<ButtonWidget>
    private var frame = 0

    override fun create() {
        font = Fonts.cp437_10x10()
        canvas = KotileCanvas(font.charWidthPx, font.charHeightPx)
        window =
            AsciiTileWindow.createWithCanvas(canvas, font) {
                widthInTiles = COLS
                heightInTiles = ROWS
                fitToWindow = false // fixed grid: widget pixel positions stay put across resize
            }
        solid = solidTexture(Color.WHITE)

        buildGrid()
        buildWidgets()

        stack = LayerStack(canvas)
        stack.add(window.asLayer()) // grid, below
        stack.add(ui) // free UI, on top

        // Drive a synthetic hover so the button under the cursor lights up in the
        // snapshot: pick a window pixel over the first button's center, map it to
        // content-pixel space exactly as the input processor would, and dispatch.
        buttons.forEach { it.clearHover() }
        val target = buttons.first().bounds
        val cx = target.x + target.width / 2f
        val cy = target.y + target.height / 2f
        // Fixed 1x grid with no letterbox here, so window px == content px; going
        // through contentPixelAt keeps the harness honest about the mapping.
        canvas.layout.contentPixelAt(cx, cy)?.let { (px, py) -> ui.onPointerMoved(px, py) }
    }

    private fun buildGrid() {
        for (y in 0 until ROWS) {
            for (x in 0 until COLS) {
                window.drawTile(x, y, StaticAsciiTile('.', Color(0.25f, 0.28f, 0.32f, 1f), Color.CLEAR))
            }
        }
        window.drawText(1, 0, "free UI layer: pixel-space widgets + hover hit-testing", Color.LIME, Color.CLEAR)
    }

    /** A translucent panel with two buttons, all on half-cell (sub-tile) offsets. */
    private fun buildWidgets() {
        val tile = font.charWidthPx.toFloat()
        // A backing panel straddling cell boundaries (x from 5.5 cells, etc.).
        ui.add(
            object : Widget {
                override val bounds = PixelRect(5.5f * tile, 8.5f * tile, 20f * tile, 8f * tile)
                override var visible = true

                override fun render(canvas: KotileCanvas) {
                    canvas.drawSprite(
                        bounds.x,
                        bounds.y,
                        TextureRegion(solid),
                        bounds.width,
                        bounds.height,
                        tint = Color(0.12f, 0.14f, 0.20f, 0.85f),
                    )
                }
            },
        )
        // Two buttons inside the panel, each on a sub-tile offset.
        buttons =
            listOf(
                ButtonWidget(
                    PixelRect(7f * tile, 10f * tile, 7f * tile, 2.5f * tile),
                    solid, idleTint = Color(0.30f, 0.45f, 0.70f, 1f), hoverTint = Color(0.55f, 0.80f, 1.0f, 1f),
                ),
                ButtonWidget(
                    PixelRect(17f * tile, 10f * tile, 7f * tile, 2.5f * tile),
                    solid, idleTint = Color(0.30f, 0.45f, 0.70f, 1f), hoverTint = Color(0.55f, 0.80f, 1.0f, 1f),
                ),
            )
        buttons.forEach { ui.add(it) }
    }

    override fun render() {
        Gdx.gl.glClearColor(0.06f, 0.07f, 0.09f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)

        stack.render()

        if (++frame >= 2) {
            val pixmap = Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.backBufferWidth, Gdx.graphics.backBufferHeight)
            PixmapIO.writePNG(Gdx.files.absolute(outPath), pixmap, Deflater.DEFAULT_COMPRESSION, true)
            pixmap.dispose()
            val hovered = buttons.count { it.hovered }
            println("UI HARNESS wrote $outPath (widgets=${ui.widgetCount}, hovered=$hovered)")
            Gdx.app.exit()
        }
    }

    override fun resize(
        width: Int,
        height: Int,
    ) = window.resize(width, height)

    override fun dispose() {
        window.dispose() // shared canvas + font not owned by the window
        canvas.dispose()
        font.dispose()
        solid.dispose()
    }

    private fun solidTexture(color: Color): Texture {
        val pixmap = Pixmap(1, 1, Pixmap.Format.RGBA8888)
        pixmap.setColor(color)
        pixmap.fill()
        return Texture(pixmap).also { pixmap.dispose() }
    }
}

fun main() {
    val outPath =
        System.getProperty("kotile.harness.out")
            ?: "${System.getProperty("user.dir")}/ui-harness.png"

    val config =
        Lwjgl3ApplicationConfiguration().apply {
            setTitle("kotile UI harness")
            setWindowedMode(WINDOW_W_PX, WINDOW_H_PX)
            disableAudio(true)
        }
    Lwjgl3Application(UiHarness(outPath), config)
}
