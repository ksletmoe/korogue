import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.sletmoe.korogue.algorithms.geometry.lineOfCellsStoppingAtBlocker
import com.sletmoe.korogue.demo.animation.DawnLikeAmmoTiles
import com.sletmoe.korogue.presentation.EventAnimationQueue
import com.sletmoe.korogue.presentation.VisualEffectPool
import com.sletmoe.korogue.presentation.VisualEvent
import com.sletmoe.korogue.ui.MapCamera
import com.sletmoe.korogue.ui.WindowSurface
import com.sletmoe.kotile.display.KotileCanvas
import com.sletmoe.kotile.display.ascii.AnimatedAsciiTile
import com.sletmoe.kotile.display.ascii.AsciiTileWindow
import com.sletmoe.kotile.display.ascii.Fonts
import com.sletmoe.kotile.display.ascii.FreeTypeGlyphSource
import com.sletmoe.kotile.display.ascii.GlyphFit
import com.sletmoe.kotile.display.ascii.StaticAsciiTile
import com.sletmoe.kotile.rendering.IntegerScale
import com.sletmoe.kotile.rendering.SpriteTileRenderer
import com.sletmoe.kotile.tiles.AnimationFrame
import com.sletmoe.kotile.tiles.PlaybackMode
import com.sletmoe.kotile.tiles.TileSheet
import com.sletmoe.kotile.utilities.Vector2Int
import java.io.File
import java.util.zip.Deflater

/**
 * Standalone showcase for the animation work (krogue-aqo): a single room split down the middle —
 * sprite tiles (DawnLike, CC-BY 4.0, see `demo/assets/dawnlike/ATTRIBUTION.md`) on the left,
 * glyph (ASCII) tiles on the right — so both render paths sit side by side, and krogue-wuq's
 * presentation-side sequences can be proven out on both at once. The two combatants' shot is a
 * real [EventAnimationQueue] on each half (krogue-tnf's grid-snapped glyph mode, krogue-2ua's
 * pixel-space sprite mode) — a scripted timer fires both queues' events at once (there's no real
 * ranged-combat trigger yet, krogue-4tn), which is what keeps them in lockstep: same start time,
 * same [PROJECTILE_FLIGHT_MS] duration.
 *
 * Both halves share one [KotileCanvas] (the multi-pane pattern `AsciiTileWindow.createWithCanvas`
 * documents): sprite content is drawn via a [SpriteTileRenderer] per DawnLike sheet at grid
 * columns `[0, SPRITE_COLS)`; glyph content via an [AsciiTileWindow] sharing the same canvas at
 * columns `[SPRITE_COLS, TOTAL_COLS)`. Neither renderer offsets its own coordinates — they simply
 * write to disjoint column ranges of the one canvas, which is why no wall/floor pieces are needed
 * on the seam side of either half (see [com.sletmoe.korogue.demo.animation.DawnLikeWallTiles]/
 * [com.sletmoe.korogue.demo.animation.DawnLikeFloorTiles]): that boundary is a rendering seam, not
 * a wall. Fixed-grid mode (not reflow) is used so the grid dimensions are correct synchronously at
 * construction, before the first draw call (`AsciiTileWindow`'s fixed-grid path calls
 * `canvas.useFixedGrid` in `init{}`; reflow only resolves on the next `resize()` event, which could
 * otherwise wipe cells drawn before it fires).
 *
 * Requires `demo/assets/dawnlike/` (`./gradlew :demo:fetchDawnlikeAssets` first); its path is
 * resolved from the `korogue.demo.assetsDir` system property, defaulting to
 * `demo/assets/dawnlike` relative to the working directory.
 *
 *   ./gradlew :demo:animationShowcaseHarness            # -> demo/build/animation-showcase.png
 *   ./gradlew :demo:animationShowcaseHarness -PoutFile=/tmp/a.png
 *
 * File layout (krogue-iid split the original ~795-line single file): this file holds the harness's
 * shared state, [create] setup, the combat script that drives both halves' queues/pools, and
 * [render]/[resize]/[dispose]. The two room builders live beside it as extension functions on this
 * class — `buildSpriteRoom`/`torchTile`/`creatureTile`/`drawScorpionFlashOverlay` in
 * `AnimationShowcaseSpriteHalf.kt`, `buildGlyphRoom`/`PhaseOffsetTile` in
 * `AnimationShowcaseGlyphHalf.kt`. The shared torchlight model is [TorchLighting], and the layout/
 * timing constants are in `AnimationShowcaseConstants.kt`.
 *
 * [outPath] null runs live/interactively (no auto-exit) — for `./gradlew :demo:runAnimationShowcase`.
 * Non-null (`kotile.harness.out`, set by `:demo:animationShowcaseHarness`) snapshots a PNG after
 * [PRE_ADVANCE_STEPS] frames and exits, matching kotile:demo's other *Harness tasks.
 */
internal class AnimationShowcaseHarness(private val outPath: String?) : ApplicationAdapter() {
    private val assetsDir = File(System.getProperty("korogue.demo.assetsDir", "demo/assets/dawnlike")).absoluteFile

    internal lateinit var canvas: KotileCanvas
    private lateinit var glyphs: FreeTypeGlyphSource
    internal lateinit var asciiWindow: AsciiTileWindow

    private lateinit var wallSheet: TileSheet
    private lateinit var floorSheet: TileSheet
    private lateinit var ammoSheet: TileSheet
    internal lateinit var decor0Sheet: TileSheet
    internal lateinit var decor1Sheet: TileSheet
    internal lateinit var player0Sheet: TileSheet
    internal lateinit var player1Sheet: TileSheet
    internal lateinit var pest0Sheet: TileSheet
    internal lateinit var pest1Sheet: TileSheet

    internal lateinit var floorRenderer: SpriteTileRenderer
    internal lateinit var wallRenderer: SpriteTileRenderer
    internal lateinit var overlayRenderer: SpriteTileRenderer // creatures, torch, arrow

    // Torchlight shared by both halves (extracted whole, krogue-iid) — see [TorchLighting]. Must be
    // constructed before glyphTorchTiles below, which reads its torchSeed at field-init time.
    internal val lighting = TorchLighting()

    // A thin '.' glyph covers little of its cell, so tinting only the foreground barely reads as
    // a glow (unlike the sprite side's fully-opaque stone). A lit background wash -- dim
    // everywhere, brighter near a torch -- makes the same falloff visible here too. Class-level
    // (not local to buildGlyphRoom) so maybeFireProjectiles can give the glyph arrow's own
    // background the same treatment via VisualEvent.GlyphProjectile.backgroundAt.
    internal val floorGlowBase = Color(0.16f, 0.13f, 0.08f, 1f)

    // Real wuq queues (krogue-wuq), one per half so each plays its own render-mode sequence
    // (renderSprite vs render) independently -- see the class doc comment for how firing both
    // from maybeFireProjectiles keeps them synchronized despite being two separate queues.
    private val spriteProjectileQueue = EventAnimationQueue()
    private val glyphProjectileQueue = EventAnimationQueue()

    // Shared AnimatedAsciiTile for all glyph torches (krogue-2co) — created once and reused,
    // not per-frame allocation. Each torch wraps it in its own PhaseOffsetTile (below) so they
    // each run at a different phase.
    private val torchFlame =
        AnimatedAsciiTile(
            frames =
                listOf(
                    AnimationFrame(
                        StaticAsciiTile('!', Color.ORANGE, Color(0.5f, 0.25f, 0f, 1f)),
                        ANIMATION_FRAME_MS,
                    ),
                    AnimationFrame(
                        StaticAsciiTile('!', Color.YELLOW, Color(0.4f, 0.2f, 0f, 1f)),
                        ANIMATION_FRAME_MS,
                    ),
                ),
            mode = PlaybackMode.LOOP,
        )

    // Per-torch PhaseOffsetTile instances wrapping the shared torchFlame — one per torch,
    // cached for reuse instead of being allocated per frame.
    internal val glyphTorchTiles =
        GLYPH_TORCH_POSITIONS.associateWith { torch ->
            PhaseOffsetTile(torchFlame, lighting.torchSeed(torch))
        }

    // Impact effects (hit-flash, floating damage number) are cosmetic and must render
    // simultaneously with each other and with the projectile-in-flight -- exactly what
    // EventAnimationQueue's own doc comment says it isn't for (one sequence at a time, for
    // input-gating). VisualEffectPool (krogue-mhh) is the non-gating counterpart: one pool per
    // half holds every currently-active cosmetic effect independently.
    //
    // spriteFlashPool is query-only: buildSpriteRoom reads its activeEvents to re-tint the
    // scorpion's own sprite, and drawScorpionFlashOverlay reads it again to draw the additive
    // flash overlay -- .renderSprite() is deliberately never called on this pool (see
    // drawScorpionFlashOverlay's doc comment: the sprite's transparent tile margins don't fully
    // cover HitFlashSequence's generic quad, so the generic render would show as a white border
    // instead of a flash). spriteEffectPool remains for effects meant to be drawn generically
    // (the floating damage number).
    internal val spriteFlashPool = VisualEffectPool()
    private val spriteEffectPool = VisualEffectPool()
    private val glyphEffectPool = VisualEffectPool()

    // Identity mapping (origin at 0,0): this demo's grid columns are already absolute, so a
    // camera with no offset resolves EventAnimationQueue's "zone" positions straight through to
    // WindowSurface's/canvas's real columns -- no translation needed for either half.
    private val demoCamera = MapCamera(originX = 0, originY = 0, width = TOTAL_COLS, height = ROWS)
    internal lateinit var glyphSurface: WindowSurface

    private var elapsedMs = 0L
    private var firedCycle = -1L
    private var impactAtMs = -1L
    private var impactFired = false
    private var frame = 0

    // -------------------------------------------------------------------------
    // Setup
    // -------------------------------------------------------------------------

    override fun create() {
        canvas = KotileCanvas(TILE_PX, TILE_PX)
        // A TTF face rasterised AT the canvas's cell px (ADR-0036 tier 3) rather than the fixed
        // 10x10 CP437 bitmap sheet, so the glyph half is drawn from real outlines. Two settings
        // matter for the room border (see AnimationShowcaseConstants' wall glyphs):
        //  - TILE fit ink-centres and scale-fits each glyph, which is what single-glyph map cells
        //    want (a '.' stays a small dot, an '@' fills) — TEXT's shared baseline is for prose.
        //  - snapToPixelGrid aligns each glyph to the output pixel grid, and for the cell-filling
        //    box-drawing class snaps the stroke edges to whole rows/columns (ADR-0041). At this
        //    small a square cell with a tall face that is the difference between a crisp masonry
        //    line and a grey one. It costs a CPU pass per rasterise, which here happens once: the
        //    grid is fixed, so nothing re-rasterises after create().
        glyphs = Fonts.cascadiaMono(GLYPH_CELL_PX, GLYPH_CELL_PX, fit = GlyphFit.TILE, snapToPixelGrid = true)
        asciiWindow =
            AsciiTileWindow.createWithCanvas(canvas, glyphs) {
                widthInTiles = TOTAL_COLS
                heightInTiles = ROWS
                fitToWindow = false
                scalePolicy = IntegerScale
                // This window shares one canvas with the sprite renderers below (glyph half on the
                // right, sprites on the left), so it must composite over them, not REPLACE-erase the
                // cells it leaves empty (krogue-a24/ADR-0033-adjacent). The scene is redrawn fresh
                // every frame, which is what makes NORMAL compositing correct here.
                sharesCanvas = true
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

        // All three sprite renderers and the glyph window share one canvas, and their content
        // overlaps by bounding box (the wall ring's box encloses the floor), so each must composite
        // over its neighbors rather than REPLACE-erase them (krogue-a24) — see sharesCanvas.
        floorRenderer = SpriteTileRenderer(canvas, floorSheet, sharesCanvas = true)
        wallRenderer = SpriteTileRenderer(canvas, wallSheet, sharesCanvas = true)
        overlayRenderer = SpriteTileRenderer(canvas, ammoSheet, sharesCanvas = true)
        // Room content (tint included) is (re)built every frame in render() now that lighting
        // flickers — see buildSpriteRoom/buildGlyphRoom's doc comments.

        glyphSurface = WindowSurface(asciiWindow)
    }

    private fun sheet(relativePath: String): TileSheet =
        TileSheet(Gdx.files.absolute(File(assetsDir, relativePath).path), DAWNLIKE_SRC_PX, DAWNLIKE_SRC_PX)

    // -------------------------------------------------------------------------
    // Combat script (drives both halves)
    // -------------------------------------------------------------------------

    /**
     * Fires a shot on both halves' queues at once, once per [PROJECTILE_CYCLE_MS] wall-clock
     * window (`fireElapsedMs / PROJECTILE_CYCLE_MS` ticking over to a new value marks a fresh
     * window, so this fires exactly once per window regardless of frame-timing jitter). Enqueuing
     * both [VisualEvent.SpriteProjectile] and [VisualEvent.GlyphProjectile] at the same
     * [fireElapsedMs] with the same [PROJECTILE_FLIGHT_MS] duration is what keeps the two
     * independent queues in lockstep — not anything about how each renders.
     */
    private fun maybeFireProjectiles(fireElapsedMs: Long) {
        val cycle = fireElapsedMs / PROJECTILE_CYCLE_MS
        if (cycle == firedCycle) return
        firedCycle = cycle
        impactAtMs = fireElapsedMs + PROJECTILE_FLIGHT_MS
        impactFired = false

        val arrowRegion = ammoSheet.region(DawnLikeAmmoTiles.ARROW.sheetX, DawnLikeAmmoTiles.ARROW.sheetY)
        spriteProjectileQueue.enqueue(
            VisualEvent.SpriteProjectile(
                from = Vector2Int(SPRITE_RANGER_COL, MID_ROW),
                to = Vector2Int(SPRITE_SCORPION_COL, MID_ROW),
                region = arrowRegion,
                durationMs = PROJECTILE_FLIGHT_MS,
                nativeBearingDeg = ARROW_NATIVE_BEARING_DEG,
                // A full tile, not half: the arrow sprite itself spans a full tile centered on
                // its position, so stopping its *center* half a tile short only brings its
                // leading *edge* to the scorpion's center -- still overlapping. A full tile short
                // brings the arrow's edge to roughly the scorpion's own leading edge instead.
                stopShortPx = canvas.layout.tileWidthPx,
                // Dims/tints the arrow as it crosses the room's torchlight, matching every other
                // sprite here (krogue-ea7) instead of staying a constant white the whole flight.
                tintAt = { cell, sequenceElapsedMs, base ->
                    lighting.litColor(base, cell.x, cell.y, SPRITE_TORCH_POSITIONS, fireElapsedMs + sequenceElapsedMs)
                },
            ),
        )

        val glyphFrom = Vector2Int(GLYPH_PLAYER_COL, MID_ROW)
        val glyphTo = Vector2Int(GLYPH_MONSTER_COL, MID_ROW)
        // No real Zone/blockers in this scripted demo, so lineOfCellsStoppingAtBlocker never
        // stops the bolt early -- but it still exercises the real stopping-capable line-walk
        // utility. dropLast(1) is a separate, demo-specific rule on top: the monster occupies
        // glyphTo itself, so the bolt should stop one cell short of it (the sprite side's
        // stopShortPx equivalent), not step onto its tile.
        val fullGlyphPath = lineOfCellsStoppingAtBlocker(glyphFrom, glyphTo) { false }
        val glyphPath = if (fullGlyphPath.size > 1) fullGlyphPath.dropLast(1) else fullGlyphPath
        glyphProjectileQueue.enqueue(
            VisualEvent.GlyphProjectile(
                from = glyphFrom,
                to = glyphTo,
                glyph = '-',
                color = ARROW_GLYPH_COLOR,
                durationMs = PROJECTILE_FLIGHT_MS,
                path = glyphPath,
                // Matches the floor's own lit-background wash instead of GlyphProjectileSequence's
                // default flat black. `fireElapsedMs + sequenceElapsedMs` reconstructs the
                // absolute clock buildGlyphRoom's own litColor calls use, since the sequence only
                // ever sees time relative to its own start.
                backgroundAt = { cell, sequenceElapsedMs ->
                    lighting.litColor(
                        floorGlowBase,
                        cell.x,
                        cell.y,
                        GLYPH_TORCH_POSITIONS,
                        fireElapsedMs + sequenceElapsedMs,
                    )
                },
                // Same lit-foreground wash the floor and occupants get (krogue-ea7), instead of
                // GlyphProjectileSequence's default flat, wall-clock-constant color.
                tintAt = { cell, sequenceElapsedMs, base ->
                    lighting.litColor(base, cell.x, cell.y, GLYPH_TORCH_POSITIONS, fireElapsedMs + sequenceElapsedMs)
                },
            ),
        )
    }

    /**
     * Fires the impact effects (hit-flash both sides, plus a floating damage number on the
     * sprite side) once [impactAtMs] (set by [maybeFireProjectiles] to the shot's landing moment)
     * has passed -- not at fire time, since the projectile takes [PROJECTILE_FLIGHT_MS] to arrive.
     * [impactFired] guards against re-firing on every subsequent frame once it's past.
     */
    private fun maybeFireImpactEffects(nowMs: Long) {
        if (impactFired || impactAtMs < 0 || nowMs < impactAtMs) return
        impactFired = true

        val scorpionCell = Vector2Int(SPRITE_SCORPION_COL, MID_ROW)
        spriteFlashPool.spawn(VisualEvent.HitFlash(at = scorpionCell), nowMs)
        spriteEffectPool.spawn(
            VisualEvent.FloatingText(
                at = scorpionCell,
                text = "-4",
                glyphSource = glyphs,
                charWidthPx = canvas.layout.tileWidthPx * 0.6f,
                charHeightPx = canvas.layout.tileHeightPx * 0.6f,
            ),
            nowMs,
        )

        val monsterCell = Vector2Int(GLYPH_MONSTER_COL, MID_ROW)
        glyphEffectPool.spawn(VisualEvent.HitFlash(at = monsterCell), nowMs)
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
        maybeFireProjectiles(elapsedMs)
        maybeFireImpactEffects(elapsedMs)
        spriteProjectileQueue.update(elapsedMs)
        glyphProjectileQueue.update(elapsedMs)
        spriteFlashPool.update(elapsedMs)
        spriteEffectPool.update(elapsedMs)
        glyphEffectPool.update(elapsedMs)

        // glyphProjectileQueue/glyphEffectPool draw on RenderLayer.OVERLAY's z-band (3), separate
        // from buildGlyphRoom's z=0 floor/wall/combatants -- without this, a shot's previous cells
        // never get overwritten as it moves (the real game avoids this the same way, clearing the
        // whole window once per frame in MyGame.drawFrame before redrawing).
        asciiWindow.clear()
        buildSpriteRoom(elapsedMs)
        buildGlyphRoom(elapsedMs)
        glyphProjectileQueue.render(glyphSurface, demoCamera, elapsedMs) // queues the dash, composited below
        glyphEffectPool.render(glyphSurface, demoCamera, elapsedMs) // queues the monster's hit-flash
        floorRenderer.render(elapsedMs)
        wallRenderer.render(elapsedMs)
        // spriteFlashPool is deliberately never generically renderSprite()'d here -- see its
        // field doc comment and drawScorpionFlashOverlay's own doc comment for why it needs its
        // own additive-blended draw instead, issued once the scorpion's normal tile is actually
        // on screen. spriteEffectPool (the floating damage number, positioned in the row *above*
        // the creature) is drawn on top of everything, same as the arrow.
        overlayRenderer.render(elapsedMs)
        drawScorpionFlashOverlay(elapsedMs)
        spriteProjectileQueue.renderSprite(canvas, demoCamera, elapsedMs)
        spriteEffectPool.renderSprite(canvas, demoCamera, elapsedMs)
        asciiWindow.render(elapsedMs)

        val snapshotPath = outPath
        if (snapshotPath != null && ++frame >= PRE_ADVANCE_STEPS) {
            val pixmap = Pixmap.createFromFrameBuffer(0, 0, Gdx.graphics.backBufferWidth, Gdx.graphics.backBufferHeight)
            try {
                PixmapIO.writePNG(Gdx.files.absolute(snapshotPath), pixmap, Deflater.DEFAULT_COMPRESSION, true)
            } finally {
                pixmap.dispose() // release native memory even if the PNG write throws
            }
            println("ANIMATION SHOWCASE HARNESS wrote $snapshotPath")
            Gdx.app.exit()
        }
    }

    override fun resize(
        width: Int,
        height: Int,
    ) = asciiWindow.resize(width, height)

    override fun dispose() {
        floorRenderer.dispose()
        wallRenderer.dispose()
        overlayRenderer.dispose()
        asciiWindow.dispose() // shared canvas + glyph source not owned by the window
        canvas.dispose()
        glyphs.dispose()
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
