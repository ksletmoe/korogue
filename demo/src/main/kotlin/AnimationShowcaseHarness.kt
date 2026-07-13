import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.sletmoe.korogue.algorithms.color.toNormalizedRgb
import com.sletmoe.korogue.algorithms.lighting.DiminishingLightValueCalculator
import com.sletmoe.korogue.demo.animation.DawnLikeAmmoTiles
import com.sletmoe.korogue.demo.animation.DawnLikeCreatureTiles
import com.sletmoe.korogue.demo.animation.DawnLikeFloorTiles
import com.sletmoe.korogue.demo.animation.DawnLikeTorchTile
import com.sletmoe.korogue.demo.animation.DawnLikeWallTiles
import com.sletmoe.kotile.display.KotileCanvas
import com.sletmoe.kotile.display.ascii.AnimatedAsciiTile
import com.sletmoe.kotile.display.ascii.AsciiTileDescriptor
import com.sletmoe.kotile.display.ascii.AsciiTileWindow
import com.sletmoe.kotile.display.ascii.Font
import com.sletmoe.kotile.display.ascii.Fonts
import com.sletmoe.kotile.rendering.IntegerScale
import com.sletmoe.kotile.rendering.SpriteTileRenderer
import com.sletmoe.kotile.tiles.AnimatedSpriteTile
import com.sletmoe.kotile.tiles.AnimationFrame
import com.sletmoe.kotile.tiles.PlaybackMode
import com.sletmoe.kotile.tiles.StaticTile
import com.sletmoe.kotile.tiles.TileSheet
import com.sletmoe.kotile.utilities.Vector2Int
import java.io.File
import java.util.zip.Deflater
import kotlin.math.sqrt

/**
 * Standalone showcase for the animation work (krogue-aqo): a single room split down the middle —
 * sprite tiles (DawnLike, CC-BY 4.0, see `demo/assets/dawnlike/ATTRIBUTION.md`) on the left,
 * glyph (ASCII) tiles on the right — so both render paths sit side by side, and krogue-wuq's
 * presentation-side sequences (hit-flash today; grid-snapped/rotated projectiles once
 * krogue-tnf/krogue-m05 land) can be proven out on both at once.
 *
 * Both halves share one [KotileCanvas] (the multi-pane pattern `AsciiTileWindow.createWithCanvas`
 * documents): sprite content is drawn via a [SpriteTileRenderer] per DawnLike sheet at grid
 * columns `[0, SPRITE_COLS)`; glyph content via an [AsciiTileWindow] sharing the same canvas at
 * columns `[SPRITE_COLS, TOTAL_COLS)`. Neither renderer offsets its own coordinates — they simply
 * write to disjoint column ranges of the one canvas, which is why no wall/floor pieces are needed
 * on the seam side of either half (see [DawnLikeWallTiles]/[DawnLikeFloorTiles]): that boundary
 * is a rendering seam, not a wall. Fixed-grid mode (not reflow) is used so the grid dimensions are
 * correct synchronously at construction, before the first draw call (`AsciiTileWindow`'s
 * fixed-grid path calls `canvas.useFixedGrid` in `init{}`; reflow only resolves on the next
 * `resize()` event, which could otherwise wipe cells drawn before it fires).
 *
 * Requires `demo/assets/dawnlike/` (`./gradlew :demo:fetchDawnlikeAssets` first); its path is
 * resolved from the `korogue.demo.assetsDir` system property, defaulting to
 * `demo/assets/dawnlike` relative to the working directory.
 *
 *   ./gradlew :demo:animationShowcaseHarness            # -> demo/build/animation-showcase.png
 *   ./gradlew :demo:animationShowcaseHarness -PoutFile=/tmp/a.png
 */
private const val TILE_PX = 16
private const val SPRITE_COLS = 25
private const val GLYPH_COLS = 25
private const val TOTAL_COLS = SPRITE_COLS + GLYPH_COLS
private const val ROWS = 7 // roughly a quarter of the original 25 — less empty floor to cross
private const val MID_ROW = ROWS / 2

// Window is sized to an exact multiple of DISPLAY_SCALE x TILE_PX so the fixed-grid IntegerScale
// policy (kotile/library/.../ScalePolicy.kt) picks a clean whole-number factor — nearest-neighbor
// upscale, no letterboxing, no fractional-scale blur.
private const val DISPLAY_SCALE = 2
private const val WINDOW_W_PX = TOTAL_COLS * TILE_PX * DISPLAY_SCALE
private const val WINDOW_H_PX = ROWS * TILE_PX * DISPLAY_SCALE

/** Advance steps and per-step delta applied before the snapshot, so animated tiles are mid-cycle. */
private const val PRE_ADVANCE_STEPS = 10
private const val STEP_MS = 150L

// Wall-mounted torches (row 0) — diminishing light (below) radiates from these positions.
// DiminishingLightValueCalculator is the engine's real lighting model (used by LightingSystem
// for the player's lantern in MyGame), reused as-is rather than reinvented for this static,
// non-flickering falloff. Two per side (was three) — directly over the ranger/scorpion columns,
// so the arrow at the midpoint (12 tiles apart) sits in the deepest part of the valley.
private val SPRITE_WALL_TORCH_COLS = listOf(6, 18)
private val SPRITE_TORCH_POSITIONS = SPRITE_WALL_TORCH_COLS.map { Vector2Int(it, 0) }

private val GLYPH_WALL_TORCH_COLS = listOf(31, 43)
private val GLYPH_TORCH_POSITIONS = GLYPH_WALL_TORCH_COLS.map { Vector2Int(it, 0) }

// Torches are now 12 cols apart (was 6). Radius bumped ~10% from the previous 7.0.
private const val LIGHT_RADIUS = 7.7

/** Cells beyond every torch's radius still show at this minimum, rather than going pure black. */
private const val AMBIENT_MIN = 0.12

// One shared cadence for every 2-frame animation (torches and creatures, both halves) so
// everything flips frames in lockstep off the same elapsedMs clock, rather than each picking its
// own ad hoc timing.
private const val ANIMATION_FRAME_MS = 400L

/**
 * [outPath] null runs live/interactively (no auto-exit) — for `./gradlew :demo:runAnimationShowcase`.
 * Non-null (`kotile.harness.out`, set by `:demo:animationShowcaseHarness`) snapshots a PNG after
 * [PRE_ADVANCE_STEPS] frames and exits, matching kotile:demo's other *Harness tasks.
 */
private class AnimationShowcaseHarness(private val outPath: String?) : ApplicationAdapter() {
    private val assetsDir = File(System.getProperty("korogue.demo.assetsDir", "demo/assets/dawnlike")).absoluteFile

    private lateinit var canvas: KotileCanvas
    private lateinit var font: Font
    private lateinit var asciiWindow: AsciiTileWindow

    private lateinit var wallSheet: TileSheet
    private lateinit var floorSheet: TileSheet
    private lateinit var ammoSheet: TileSheet
    private lateinit var decor0Sheet: TileSheet
    private lateinit var decor1Sheet: TileSheet
    private lateinit var player0Sheet: TileSheet
    private lateinit var player1Sheet: TileSheet
    private lateinit var pest0Sheet: TileSheet
    private lateinit var pest1Sheet: TileSheet

    private lateinit var floorRenderer: SpriteTileRenderer
    private lateinit var wallRenderer: SpriteTileRenderer
    private lateinit var overlayRenderer: SpriteTileRenderer // creatures, torch, arrow

    private val lightCalculator = DiminishingLightValueCalculator()
    private val torchLightColor = Color(1f, 0.92f, 0.72f, 1f).toNormalizedRgb()

    private var elapsedMs = 0L
    private var frame = 0

    override fun create() {
        canvas = KotileCanvas(TILE_PX, TILE_PX)
        font = Fonts.cp437_10x10()
        asciiWindow =
            AsciiTileWindow.createWithCanvas(canvas, font) {
                widthInTiles = TOTAL_COLS
                heightInTiles = ROWS
                fitToWindow = false
                scalePolicy = IntegerScale
            }

        wallSheet = sheet("Objects/Wall.png")
        floorSheet = sheet("Objects/Floor.png")
        ammoSheet = sheet("Items/Ammo.png")
        decor0Sheet = sheet("Objects/Decor0.png")
        decor1Sheet = sheet("Objects/Decor1.png")
        player0Sheet = sheet("Characters/Player0.png")
        player1Sheet = sheet("Characters/Player1.png")
        pest0Sheet = sheet("Characters/Pest0.png")
        pest1Sheet = sheet("Characters/Pest1.png")

        floorRenderer = SpriteTileRenderer(canvas, floorSheet)
        wallRenderer = SpriteTileRenderer(canvas, wallSheet)
        overlayRenderer = SpriteTileRenderer(canvas, ammoSheet)

        buildSpriteRoom()
        buildGlyphRoom()
    }

    private fun sheet(relativePath: String): TileSheet =
        TileSheet(Gdx.files.absolute(File(assetsDir, relativePath).path), TILE_PX, TILE_PX)

    /**
     * Light intensity at cell ([x], [y]) from the nearest of [torches] (wall-mounted or on a
     * pillar — any position), via [DiminishingLightValueCalculator] — the brightest torch wins
     * rather than summing, and [AMBIENT_MIN] floors it so cells outside every radius stay dimly
     * visible, not pitch black.
     */
    private fun lightIntensityAt(
        x: Int,
        y: Int,
        torches: List<Vector2Int>,
    ): Double {
        val brightest =
            torches.maxOf { torch ->
                val dx = (x - torch.x).toDouble()
                val dy = (y - torch.y).toDouble()
                val distance = sqrt(dx * dx + dy * dy)
                lightCalculator.calculateLightValue(torchLightColor, LIGHT_RADIUS, distance).intensity
            }
        return brightest.coerceIn(AMBIENT_MIN, 1.0)
    }

    /** [torchLightColor] scaled by the light intensity at ([x], [y]), as a [StaticTile] tint. */
    private fun litTint(
        x: Int,
        y: Int,
        torches: List<Vector2Int>,
    ) = (torchLightColor * lightIntensityAt(x, y, torches)).toColor()

    /** [base] multiplied by the light at ([x], [y]) — the ASCII-side counterpart to [litTint]. */
    private fun litColor(
        base: Color,
        x: Int,
        y: Int,
        torches: List<Vector2Int>,
    ) = (base.toNormalizedRgb() * torchLightColor * lightIntensityAt(x, y, torches)).toColor()

    // -------------------------------------------------------------------------
    // Sprite (left) half
    // -------------------------------------------------------------------------

    private fun buildSpriteRoom() {
        // The wall renderer fully covers row 0 / row ROWS-1 / col 0 (drawn after floor, opaque),
        // so the floor's edge-shaded pieces belong one cell *inside* the wall — on the first
        // interior row/column adjacent to it — not on the wall's own row/column, which would be
        // invisible under it.
        for (x in 0 until SPRITE_COLS) {
            for (y in 0 until ROWS) {
                val nearTop = y == 1
                val nearBottom = y == ROWS - 2
                val nearLeft = x == 1
                val floor =
                    when {
                        nearTop && nearLeft -> DawnLikeFloorTiles.TOP_LEFT_CORNER
                        nearBottom && nearLeft -> DawnLikeFloorTiles.BOTTOM_LEFT_CORNER
                        nearTop -> DawnLikeFloorTiles.TOP_EDGE
                        nearBottom -> DawnLikeFloorTiles.BOTTOM_EDGE
                        nearLeft -> DawnLikeFloorTiles.LEFT_EDGE
                        else -> DawnLikeFloorTiles.MIDDLE
                    }
                val tint = litTint(x, y, SPRITE_TORCH_POSITIONS)
                floorRenderer.drawTile(x, y, z = 0, staticTile = floor.copy(tint = tint))
            }
        }
        for (x in 0 until SPRITE_COLS) {
            val top = if (x == 0) DawnLikeWallTiles.UPPER_LEFT_CORNER else DawnLikeWallTiles.TOP_WALL
            val bottom = if (x == 0) DawnLikeWallTiles.BOTTOM_LEFT_CORNER else DawnLikeWallTiles.BOTTOM_WALL
            wallRenderer.drawTile(x, 0, z = 0, staticTile = top.copy(tint = litTint(x, 0, SPRITE_TORCH_POSITIONS)))
            wallRenderer.drawTile(
                x,
                ROWS - 1,
                z = 0,
                staticTile = bottom.copy(tint = litTint(x, ROWS - 1, SPRITE_TORCH_POSITIONS)),
            )
        }
        for (y in 1 until ROWS - 1) {
            val wall = DawnLikeWallTiles.LEFT_WALL.copy(tint = litTint(0, y, SPRITE_TORCH_POSITIONS))
            wallRenderer.drawTile(0, y, z = 0, staticTile = wall)
        }

        for (torchX in SPRITE_WALL_TORCH_COLS) {
            overlayRenderer.drawTile(torchX, 0, z = 1, tile = torchTile())
        }

        val (rangerSheet, rangerCell) = DawnLikeCreatureTiles.RANGER
        val rangerTint = litTint(6, MID_ROW, SPRITE_TORCH_POSITIONS)
        overlayRenderer.drawTile(6, MID_ROW, z = 1, tile = creatureTile(rangerSheet, rangerCell, rangerTint))

        val (scorpionSheet, scorpionCell) = DawnLikeCreatureTiles.SCORPION
        val scorpionTint = litTint(18, MID_ROW, SPRITE_TORCH_POSITIONS)
        overlayRenderer.drawTile(18, MID_ROW, z = 1, tile = creatureTile(scorpionSheet, scorpionCell, scorpionTint))

        // Placeholder mid-flight arrow: fixed native diagonal until krogue-m05 (kotile rotation
        // support) lands, and not yet animated/moving (that's krogue-wuq's event queue wiring).
        val arrow = DawnLikeAmmoTiles.ARROW.copy(tint = litTint(12, MID_ROW, SPRITE_TORCH_POSITIONS))
        overlayRenderer.drawTile(12, MID_ROW, z = 2, staticTile = arrow)
    }

    private fun torchTile(): AnimatedSpriteTile {
        val cell = DawnLikeTorchTile.CELL
        return AnimatedSpriteTile(
            frames =
                listOf(
                    AnimationFrame(decor0Sheet.region(cell.x, cell.y), durationMs = ANIMATION_FRAME_MS),
                    AnimationFrame(decor1Sheet.region(cell.x, cell.y), durationMs = ANIMATION_FRAME_MS),
                ),
            mode = PlaybackMode.LOOP,
        )
    }

    private fun creatureTile(
        sheet: DawnLikeCreatureTiles.Sheet,
        cell: Vector2Int,
        tint: Color = Color.WHITE,
    ): AnimatedSpriteTile {
        val (frame0Sheet, frame1Sheet) =
            when (sheet) {
                DawnLikeCreatureTiles.Sheet.PLAYER -> player0Sheet to player1Sheet
                DawnLikeCreatureTiles.Sheet.PEST -> pest0Sheet to pest1Sheet
            }
        return AnimatedSpriteTile(
            frames =
                listOf(
                    AnimationFrame(frame0Sheet.region(cell.x, cell.y), durationMs = ANIMATION_FRAME_MS),
                    AnimationFrame(frame1Sheet.region(cell.x, cell.y), durationMs = ANIMATION_FRAME_MS),
                ),
            mode = PlaybackMode.LOOP,
            tint = tint,
        )
    }

    // -------------------------------------------------------------------------
    // Glyph (right) half
    // -------------------------------------------------------------------------

    private fun buildGlyphRoom() {
        val wallColor = Color(0.55f, 0.55f, 0.6f, 1f)
        val floorColor = Color(0.35f, 0.32f, 0.3f, 1f)
        // A thin '.' glyph covers little of its cell, so tinting only the foreground barely reads
        // as a glow (unlike the sprite side's fully-opaque stone). A lit background wash — dim
        // everywhere, brighter near a torch — makes the same falloff visible here too.
        val floorGlowBase = Color(0.16f, 0.13f, 0.08f, 1f)
        for (x in SPRITE_COLS until TOTAL_COLS) {
            for (y in 0 until ROWS) {
                val lit = litColor(floorColor, x, y, GLYPH_TORCH_POSITIONS)
                val glow = litColor(floorGlowBase, x, y, GLYPH_TORCH_POSITIONS)
                asciiWindow.drawTile(x, y, AsciiTileDescriptor('.', lit, glow))
            }
        }
        for (x in SPRITE_COLS until TOTAL_COLS) {
            asciiWindow.drawTile(
                x,
                0,
                AsciiTileDescriptor('#', litColor(wallColor, x, 0, GLYPH_TORCH_POSITIONS), Color.BLACK),
            )
            asciiWindow.drawTile(
                x,
                ROWS - 1,
                AsciiTileDescriptor('#', litColor(wallColor, x, ROWS - 1, GLYPH_TORCH_POSITIONS), Color.BLACK),
            )
        }
        for (y in 1 until ROWS - 1) {
            val lit = litColor(wallColor, TOTAL_COLS - 1, y, GLYPH_TORCH_POSITIONS)
            asciiWindow.drawTile(TOTAL_COLS - 1, y, AsciiTileDescriptor('#', lit, Color.BLACK))
        }

        // Brogue-style torch flicker (per AnimatedAsciiTile's own doc example): background color
        // shifts between frames to simulate an unsteady flame; no sprite art needed for this side.
        // Same ANIMATION_FRAME_MS and elapsedMs clock as the sprite torch, so both halves flip frames
        // in lockstep. Frame order matters here, not just duration: frame 0 is the brighter
        // background (matching Decor0's bigger flame being frame 0 on the sprite side) so both
        // torches read as "bright" and "dim" at the same wall-clock moments, not inverted.
        for (torch in GLYPH_TORCH_POSITIONS) {
            asciiWindow.drawTile(
                torch.x,
                torch.y,
                AnimatedAsciiTile(
                    frames =
                        listOf(
                            AnimationFrame(
                                AsciiTileDescriptor('!', Color.ORANGE, Color(0.5f, 0.25f, 0f, 1f)),
                                ANIMATION_FRAME_MS,
                            ),
                            AnimationFrame(
                                AsciiTileDescriptor('!', Color.YELLOW, Color(0.4f, 0.2f, 0f, 1f)),
                                ANIMATION_FRAME_MS,
                            ),
                        ),
                    mode = PlaybackMode.LOOP,
                ),
            )
        }

        // Placeholder combatants/projectile — specific glyph/color choices are provisional until
        // krogue-tnf's grid-snapped mode lands.
        asciiWindow.drawTile(
            SPRITE_COLS + 6,
            MID_ROW,
            AsciiTileDescriptor(
                '@',
                litColor(Color.CYAN, SPRITE_COLS + 6, MID_ROW, GLYPH_TORCH_POSITIONS),
                litColor(floorGlowBase, SPRITE_COLS + 6, MID_ROW, GLYPH_TORCH_POSITIONS),
            ),
        )
        asciiWindow.drawTile(
            TOTAL_COLS - 7,
            MID_ROW,
            AsciiTileDescriptor(
                's',
                litColor(Color.GREEN, TOTAL_COLS - 7, MID_ROW, GLYPH_TORCH_POSITIONS),
                litColor(floorGlowBase, TOTAL_COLS - 7, MID_ROW, GLYPH_TORCH_POSITIONS),
            ),
        )
        asciiWindow.drawTile(
            SPRITE_COLS + 12,
            MID_ROW,
            AsciiTileDescriptor(
                '-',
                litColor(Color.WHITE, SPRITE_COLS + 12, MID_ROW, GLYPH_TORCH_POSITIONS),
                litColor(floorGlowBase, SPRITE_COLS + 12, MID_ROW, GLYPH_TORCH_POSITIONS),
            ),
        )
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    override fun render() {
        Gdx.gl.glClearColor(0.05f, 0.05f, 0.06f, 1f)
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT)

        // Snapshot mode advances a fixed synthetic step per frame so PRE_ADVANCE_STEPS
        // deterministically lands mid-cycle; live mode uses real wall-clock time so the
        // flicker/bounce animate at their actual configured speed (Game.render's convention).
        elapsedMs += if (outPath != null) STEP_MS else (Gdx.graphics.deltaTime * 1000).toLong()
        floorRenderer.render(elapsedMs)
        wallRenderer.render(elapsedMs)
        overlayRenderer.render(elapsedMs)
        asciiWindow.render(elapsedMs)

        val snapshotPath = outPath
        if (snapshotPath != null && ++frame >= PRE_ADVANCE_STEPS) {
            val pixmap = Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.backBufferWidth, Gdx.graphics.backBufferHeight)
            PixmapIO.writePNG(Gdx.files.absolute(snapshotPath), pixmap, Deflater.DEFAULT_COMPRESSION, true)
            pixmap.dispose()
            println("ANIMATION SHOWCASE HARNESS wrote $snapshotPath")
            Gdx.app.exit()
        }
    }

    override fun resize(
        width: Int,
        height: Int,
    ) = asciiWindow.resize(width, height)

    override fun dispose() {
        asciiWindow.dispose() // shared canvas + font not owned by the window
        canvas.dispose()
        font.dispose()
        wallSheet.dispose()
        floorSheet.dispose()
        ammoSheet.dispose()
        decor0Sheet.dispose()
        decor1Sheet.dispose()
        player0Sheet.dispose()
        player1Sheet.dispose()
        pest0Sheet.dispose()
        pest1Sheet.dispose()
    }
}

fun main() {
    // Null (unset) runs live/interactively until the window is closed; set by
    // :demo:animationShowcaseHarness for the PNG-snapshot verification path.
    val outPath = System.getProperty("kotile.harness.out")

    val config =
        Lwjgl3ApplicationConfiguration().apply {
            setTitle("korogue animation showcase")
            setWindowedMode(WINDOW_W_PX, WINDOW_H_PX)
            disableAudio(true)
        }
    Lwjgl3Application(AnimationShowcaseHarness(outPath), config)
}
