import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.sletmoe.korogue.algorithms.color.toNormalizedRgb
import com.sletmoe.korogue.algorithms.geometry.lineOfCellsStoppingAtBlocker
import com.sletmoe.korogue.algorithms.lighting.DiminishingLightValueCalculator
import com.sletmoe.korogue.algorithms.lighting.LightFlicker
import com.sletmoe.korogue.demo.animation.DawnLikeAmmoTiles
import com.sletmoe.korogue.demo.animation.DawnLikeCreatureTiles
import com.sletmoe.korogue.demo.animation.DawnLikeFloorTiles
import com.sletmoe.korogue.demo.animation.DawnLikeTorchTile
import com.sletmoe.korogue.demo.animation.DawnLikeWallTiles
import com.sletmoe.korogue.presentation.EventAnimationQueue
import com.sletmoe.korogue.presentation.VisualEffectPool
import com.sletmoe.korogue.presentation.VisualEvent
import com.sletmoe.korogue.ui.MapCamera
import com.sletmoe.korogue.ui.WindowSurface
import com.sletmoe.kotile.display.BlendMode
import com.sletmoe.kotile.display.KotileCanvas
import com.sletmoe.kotile.display.ascii.AnimatedAsciiTile
import com.sletmoe.kotile.display.ascii.AsciiTile
import com.sletmoe.kotile.display.ascii.AsciiTileWindow
import com.sletmoe.kotile.display.ascii.DynamicAsciiTile
import com.sletmoe.kotile.display.ascii.Font
import com.sletmoe.kotile.display.ascii.Fonts
import com.sletmoe.kotile.display.ascii.StaticAsciiTile
import com.sletmoe.kotile.rendering.IntegerScale
import com.sletmoe.kotile.rendering.SpriteTileRenderer
import com.sletmoe.kotile.tiles.AnimatedSpriteTile
import com.sletmoe.kotile.tiles.AnimationFrame
import com.sletmoe.kotile.tiles.PlaybackMode
import com.sletmoe.kotile.tiles.StaticSpriteTile
import com.sletmoe.kotile.tiles.TileSheet
import com.sletmoe.kotile.utilities.Vector2Int
import java.io.File
import java.util.zip.Deflater
import kotlin.math.sqrt

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
 *
 * File layout: constants, then the class in five sections — lifecycle setup ([create]/[sheet]),
 * lighting shared by both halves, the combat script that drives both halves' queues/pools, the
 * sprite-only room builder, the glyph-only room builder, and finally [render]/[resize]/[dispose].
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

// Wall-mounted torches (row 0) — diminishing, flickering light (below) radiates from these
// positions. DiminishingLightValueCalculator + LightFlicker are the engine's real lighting/
// flicker model (used by LightingSystem/MapPanel for the player's lantern in MyGame, krogue-ncl),
// reused as-is rather than reinvented. Two per side — directly over the ranger/scorpion columns,
// so the arrow at the midpoint (12 tiles apart) sits in the deepest part of the valley.
private val SPRITE_WALL_TORCH_COLS = listOf(6, 18)
private val SPRITE_TORCH_POSITIONS = SPRITE_WALL_TORCH_COLS.map { Vector2Int(it, 0) }

private val GLYPH_WALL_TORCH_COLS = listOf(31, 43)
private val GLYPH_TORCH_POSITIONS = GLYPH_WALL_TORCH_COLS.map { Vector2Int(it, 0) }

// The two combatants each half's projectile flies between — same columns as that half's
// torches, so the shot crosses the deepest part of the lighting valley.
private const val SPRITE_RANGER_COL = 6
private const val SPRITE_SCORPION_COL = 18
private const val GLYPH_PLAYER_COL = SPRITE_COLS + 6
private const val GLYPH_MONSTER_COL = TOTAL_COLS - 7

// Both halves' projectiles read this same clock, so they launch simultaneously; since the two
// combatants sit the same tile-distance apart on both sides (12 cols) and both halves share one
// canvas's tile size, using one flight duration for both automatically gives them the same
// on-screen speed too -- no separate per-half tuning needed.
private const val PROJECTILE_FLIGHT_MS = 700L
private const val PROJECTILE_CYCLE_MS = 1800L // flight + pause before the next shot

// DawnLikeAmmoTiles.ARROW's head (the pale cream/gray end -- not the blue end, which is the
// fletching, see that object's doc comment) is drawn facing southwest at rotationDeg=0, not along
// +X -- so rotating it to face due-east (this scene's only travel direction, left-to-right) needs
// this fixed offset on top of rotationTowards' 0deg. Rotation direction is easy to get backwards
// -- verify empirically against a real render before trusting it (see KotileCanvas.drawSprite's
// own doc note and kotile:demo's RotationHarness), not just from reading the source art.
private const val ARROW_NATIVE_BEARING_DEG = 135f

/** The glyph-side arrow's own (unlit) foreground color -- a light tan, matching a wooden shaft. */
private val ARROW_GLYPH_COLOR = Color(0.82f, 0.71f, 0.55f, 1f)

// Torches are 12 cols apart; radius sized so the midpoint between two torches still reads as a
// visible (if dim) valley, not full black or a flat plateau.
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

    // A thin '.' glyph covers little of its cell, so tinting only the foreground barely reads as
    // a glow (unlike the sprite side's fully-opaque stone). A lit background wash -- dim
    // everywhere, brighter near a torch -- makes the same falloff visible here too. Class-level
    // (not local to buildGlyphRoom) so maybeFireProjectiles can give the glyph arrow's own
    // background the same treatment via VisualEvent.GlyphProjectile.backgroundAt.
    private val floorGlowBase = Color(0.16f, 0.13f, 0.08f, 1f)

    // krogue-ncl's real presentation-side flicker (engine/algorithms/lighting), reused as-is —
    // one shape shared by every torch, but each torch reads it at its own offset (via
    // torchSeed/torchFrameIndex below) so torches don't pulse in lockstep, mirroring MapPanel's
    // per-emitter phase. periodMs matches the torch sprite's full 2-frame loop (2x
    // ANIMATION_FRAME_MS) and the flicker carries no seed of its own, so this exact phase is also
    // what torchFrameIndex uses to pick the sprite/glyph frame — the light only brightens while
    // that same torch's own flame frame is the bright one.
    private val flicker = LightFlicker(amplitude = 0.05, periodMs = ANIMATION_FRAME_MS * 2)

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
    private val glyphTorchTiles =
        GLYPH_TORCH_POSITIONS.associateWith { torch ->
            PhaseOffsetTile(torchFlame, torchSeed(torch))
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
    private val spriteFlashPool = VisualEffectPool()
    private val spriteEffectPool = VisualEffectPool()
    private val glyphEffectPool = VisualEffectPool()

    // Identity mapping (origin at 0,0): this demo's grid columns are already absolute, so a
    // camera with no offset resolves EventAnimationQueue's "zone" positions straight through to
    // WindowSurface's/canvas's real columns -- no translation needed for either half.
    private val demoCamera = MapCamera(originX = 0, originY = 0, width = TOTAL_COLS, height = ROWS)
    private lateinit var glyphSurface: WindowSurface

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
        font = Fonts.cp437_10x10()
        asciiWindow =
            AsciiTileWindow.createWithCanvas(canvas, font) {
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
        TileSheet(Gdx.files.absolute(File(assetsDir, relativePath).path), TILE_PX, TILE_PX)

    // -------------------------------------------------------------------------
    // Lighting (shared by both halves)
    // -------------------------------------------------------------------------

    /** A distinct [LightFlicker] phase per torch, so nearby torches don't pulse in lockstep. */
    private fun torchSeed(torch: Vector2Int): Long = torch.x * 137L + torch.y * 271L

    /**
     * Which of a torch's two flame states (0 = bright, 1 = dim — see [DawnLikeTorchTile] for
     * which sheet is which) is showing at [elapsedMs], reading the same [torchSeed]-offset clock
     * [lightIntensityAt] feeds into [flicker] — the single source of truth both the torch's own
     * art and its light read from, so a torch's glow only brightens while its own flame frame is
     * the bright one.
     */
    private fun torchFrameIndex(
        torch: Vector2Int,
        elapsedMs: Long,
    ): Int = ((elapsedMs + torchSeed(torch)) / ANIMATION_FRAME_MS % 2).toInt()

    /**
     * Light intensity at cell ([x], [y]) at wall-clock [elapsedMs] from the brightest of [torches]
     * (wall-mounted or on a pillar — any position), via [DiminishingLightValueCalculator] — the
     * brightest torch wins rather than summing, matching `MapPanel`'s (engine `ui` package) own
     * nearest/brightest-source approximation (real per-source blending is the cost that
     * approximation exists to avoid).
     * Its own [flicker] phase (via [torchSeed]) then modulates that intensity, and [AMBIENT_MIN]
     * floors the result so cells outside every radius stay dimly visible, not pitch black.
     */
    private fun lightIntensityAt(
        x: Int,
        y: Int,
        torches: List<Vector2Int>,
        elapsedMs: Long,
    ): Double {
        var brightest = 0.0
        var brightestTorch = torches.first()
        for (torch in torches) {
            val dx = (x - torch.x).toDouble()
            val dy = (y - torch.y).toDouble()
            val distance = sqrt(dx * dx + dy * dy)
            val intensity = lightCalculator.calculateLightValue(torchLightColor, LIGHT_RADIUS, distance).intensity
            if (intensity > brightest) {
                brightest = intensity
                brightestTorch = torch
            }
        }
        val flickerFactor = flicker.factorAt(elapsedMs + torchSeed(brightestTorch))
        return (brightest * flickerFactor).coerceIn(AMBIENT_MIN, 1.0)
    }

    /** [torchLightColor] scaled by the flickering light intensity at ([x], [y]), as a tint. */
    private fun litTint(
        x: Int,
        y: Int,
        torches: List<Vector2Int>,
        elapsedMs: Long,
    ) = (torchLightColor * lightIntensityAt(x, y, torches, elapsedMs)).toColor()

    /** [base] multiplied by the flickering light at ([x], [y]) — the ASCII-side counterpart to [litTint]. */
    private fun litColor(
        base: Color,
        x: Int,
        y: Int,
        torches: List<Vector2Int>,
        elapsedMs: Long,
    ) = (base.toNormalizedRgb() * torchLightColor * lightIntensityAt(x, y, torches, elapsedMs)).toColor()

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
                    litColor(base, cell.x, cell.y, SPRITE_TORCH_POSITIONS, fireElapsedMs + sequenceElapsedMs)
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
                    litColor(floorGlowBase, cell.x, cell.y, GLYPH_TORCH_POSITIONS, fireElapsedMs + sequenceElapsedMs)
                },
                // Same lit-foreground wash the floor and occupants get (krogue-ea7), instead of
                // GlyphProjectileSequence's default flat, wall-clock-constant color.
                tintAt = { cell, sequenceElapsedMs, base ->
                    litColor(base, cell.x, cell.y, GLYPH_TORCH_POSITIONS, fireElapsedMs + sequenceElapsedMs)
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
                font = font,
                charWidthPx = canvas.layout.tileWidthPx * 0.6f,
                charHeightPx = canvas.layout.tileHeightPx * 0.6f,
            ),
            nowMs,
        )

        val monsterCell = Vector2Int(GLYPH_MONSTER_COL, MID_ROW)
        glyphEffectPool.spawn(VisualEvent.HitFlash(at = monsterCell), nowMs)
    }

    // -------------------------------------------------------------------------
    // Sprite (left) half
    // -------------------------------------------------------------------------

    /**
     * Redrawn every frame (not just once at startup) so the flicker computed by [litTint] at the
     * current [elapsedMs] actually shows: a [StaticSpriteTile]'s `tint` is baked in at draw time, so
     * showing a changing tint means re-issuing the draw call with a freshly computed one each
     * frame — the same thing `MapPanel.draw()` does every frame in the real game.
     */
    private fun buildSpriteRoom(elapsedMs: Long) {
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
                val tint = litTint(x, y, SPRITE_TORCH_POSITIONS, elapsedMs)
                floorRenderer.drawTile(x, y, z = 0, tile = floor.copy(tint = tint))
            }
        }
        for (x in 0 until SPRITE_COLS) {
            val top = if (x == 0) DawnLikeWallTiles.UPPER_LEFT_CORNER else DawnLikeWallTiles.TOP_WALL
            val bottom = if (x == 0) DawnLikeWallTiles.BOTTOM_LEFT_CORNER else DawnLikeWallTiles.BOTTOM_WALL
            val topTint = litTint(x, 0, SPRITE_TORCH_POSITIONS, elapsedMs)
            wallRenderer.drawTile(x, 0, z = 0, tile = top.copy(tint = topTint))
            val bottomTint = litTint(x, ROWS - 1, SPRITE_TORCH_POSITIONS, elapsedMs)
            wallRenderer.drawTile(x, ROWS - 1, z = 0, tile = bottom.copy(tint = bottomTint))
        }
        for (y in 1 until ROWS - 1) {
            val wall = DawnLikeWallTiles.LEFT_WALL.copy(tint = litTint(0, y, SPRITE_TORCH_POSITIONS, elapsedMs))
            wallRenderer.drawTile(0, y, z = 0, tile = wall)
        }

        for (torchX in SPRITE_WALL_TORCH_COLS) {
            val torch = Vector2Int(torchX, 0)
            overlayRenderer.drawTile(torchX, 0, z = 1, tile = torchTile(torch, elapsedMs))
        }

        val (rangerSheet, rangerCell) = DawnLikeCreatureTiles.RANGER
        val rangerTint = litTint(SPRITE_RANGER_COL, MID_ROW, SPRITE_TORCH_POSITIONS, elapsedMs)
        overlayRenderer.drawTile(
            SPRITE_RANGER_COL,
            MID_ROW,
            z = 1,
            // The source art faces left; the ranger stands on the left shooting right, so it needs
            // mirroring to actually face its target instead of shooting backward over its shoulder.
            tile = creatureTile(rangerSheet, rangerCell, rangerTint, flipX = true),
        )

        val (scorpionSheet, scorpionCell) = DawnLikeCreatureTiles.SCORPION
        val scorpionTint = litTint(SPRITE_SCORPION_COL, MID_ROW, SPRITE_TORCH_POSITIONS, elapsedMs)
        overlayRenderer.drawTile(
            SPRITE_SCORPION_COL,
            MID_ROW,
            z = 1,
            tile = creatureTile(scorpionSheet, scorpionCell, scorpionTint),
        )
        // If a HitFlash is active on the scorpion's cell, drawScorpionFlashOverlay (called from
        // render(), after this tile is actually composited) draws its sprite again with additive
        // blending on top -- see that function's doc comment for why a tint alone can't do this.
        // The arrow itself is drawn by maybeFireProjectiles's VisualEvent.SpriteProjectile, via
        // SpriteProjectileSequence.renderSprite (canvas.drawSprite directly) rather than through
        // this grid-locked renderer, since a flying projectile needs sub-tile positions.
    }

    /**
     * The torch sprite showing whichever flame state [torchFrameIndex] says is active for
     * [torch] at [elapsedMs] — a single-frame tile rebuilt fresh each call (matching
     * [buildSpriteRoom]'s per-frame rebuild) rather than an [AnimatedSpriteTile] with its own
     * independent 2-frame clock, so the art stays locked to the same phase as its light.
     * Sheet choice is deliberately Decor1-for-bright/Decor0-for-dim — see [DawnLikeTorchTile]'s
     * doc comment: the file names suggest the opposite, but the actual pixels don't.
     */
    private fun torchTile(
        torch: Vector2Int,
        elapsedMs: Long,
    ): AnimatedSpriteTile {
        val cell = DawnLikeTorchTile.CELL
        val sheet = if (torchFrameIndex(torch, elapsedMs) == 0) decor1Sheet else decor0Sheet
        return AnimatedSpriteTile(
            frames = listOf(AnimationFrame(sheet.region(cell.x, cell.y), durationMs = ANIMATION_FRAME_MS)),
            mode = PlaybackMode.LOOP,
        )
    }

    /**
     * A creature's idle two-frame bounce, tinted by the ambient light at its cell. [flipX] mirrors
     * the tile horizontally via kotile's own primitive (krogue-csc) — the ranger's source art
     * faces the wrong way for where it stands, so it's drawn with `flipX = true`.
     */
    private fun creatureTile(
        sheet: DawnLikeCreatureTiles.Sheet,
        cell: Vector2Int,
        tint: Color = Color.WHITE,
        flipX: Boolean = false,
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
            flipX = flipX,
        )
    }

    /**
     * If a [VisualEvent.HitFlash] is active on the scorpion's cell (in [spriteFlashPool], never
     * itself `renderSprite()`'d, since its generic quad would show through the sprite's own
     * transparent tile margins as a white border rather than a flash), draws the scorpion's own
     * sprite a second time on top, additively blended. A multiply [Color] tint can only ever
     * reproduce a sprite's native colors (at best, `tint = WHITE`) or darken them -- GDX's `Color`
     * also clamps every component to `[0, 1]`, so an over-bright tint isn't even representable --
     * so tinting the *first* draw can never brighten a dark pixel toward white. Additive blending
     * ([BlendMode.ADDITIVE]) genuinely adds light instead, which is the only way to make a flash
     * actually read as a flash rather than "the same creature, unchanged."
     */
    private fun drawScorpionFlashOverlay(elapsedMs: Long) {
        val scorpionCellPos = Vector2Int(SPRITE_SCORPION_COL, MID_ROW)
        spriteFlashPool.activeEvents
            .filterIsInstance<VisualEvent.HitFlash>()
            .firstOrNull { it.at == scorpionCellPos }
            ?: return

        val (sheetKind, cell) = DawnLikeCreatureTiles.SCORPION
        // Mirrors kotile's own LOOP-mode frame-index formula (frameIndexAt) for a 2-frame,
        // equal-duration animation -- needed explicitly here (rather than left to an
        // AnimatedSpriteTile's own internal clock) since this draws a raw TextureRegion directly.
        val frameIndex = ((elapsedMs / ANIMATION_FRAME_MS) % 2).toInt()
        val sheet =
            when (sheetKind) {
                DawnLikeCreatureTiles.Sheet.PLAYER -> if (frameIndex == 0) player0Sheet else player1Sheet
                DawnLikeCreatureTiles.Sheet.PEST -> if (frameIndex == 0) pest0Sheet else pest1Sheet
            }
        val region = sheet.region(cell.x, cell.y)

        val l = canvas.layout
        canvas.begin()
        canvas.drawSprite(
            pxX = SPRITE_SCORPION_COL * l.tileWidthPx,
            pxY = MID_ROW * l.tileHeightPx,
            region = region,
            w = l.tileWidthPx,
            h = l.tileHeightPx,
            blend = BlendMode.ADDITIVE,
        )
        canvas.end()
    }

    // -------------------------------------------------------------------------
    // Glyph (right) half
    // -------------------------------------------------------------------------

    /** Redrawn every frame for the same reason as [buildSpriteRoom]: [litColor]'s flicker needs a fresh call each frame. */
    private fun buildGlyphRoom(elapsedMs: Long) {
        val wallColor = Color(0.55f, 0.55f, 0.6f, 1f)
        val floorColor = Color(0.35f, 0.32f, 0.3f, 1f)
        for (x in SPRITE_COLS until TOTAL_COLS) {
            for (y in 0 until ROWS) {
                val lit = litColor(floorColor, x, y, GLYPH_TORCH_POSITIONS, elapsedMs)
                val glow = litColor(floorGlowBase, x, y, GLYPH_TORCH_POSITIONS, elapsedMs)
                asciiWindow.drawTile(x, y, StaticAsciiTile('.', lit, glow))
            }
        }
        for (x in SPRITE_COLS until TOTAL_COLS) {
            val topLit = litColor(wallColor, x, 0, GLYPH_TORCH_POSITIONS, elapsedMs)
            asciiWindow.drawTile(x, 0, StaticAsciiTile('#', topLit, Color.BLACK))
            val bottomLit = litColor(wallColor, x, ROWS - 1, GLYPH_TORCH_POSITIONS, elapsedMs)
            asciiWindow.drawTile(x, ROWS - 1, StaticAsciiTile('#', bottomLit, Color.BLACK))
        }
        for (y in 1 until ROWS - 1) {
            val lit = litColor(wallColor, TOTAL_COLS - 1, y, GLYPH_TORCH_POSITIONS, elapsedMs)
            asciiWindow.drawTile(TOTAL_COLS - 1, y, StaticAsciiTile('#', lit, Color.BLACK))
        }

        // Brogue-style torch flicker: the background shifts to simulate an unsteady flame; no sprite
        // art needed for this side. This is now a *native* animated cell placed through the engine
        // seam (krogue-2co / ADR-0033): `glyphSurface.put(..., tile)` hands the window an
        // `AnimatedAsciiTile` and the window resolves the frame from its own clock — where this used
        // to hand-compute the bright/dim StaticAsciiTile each frame and bypass the engine via
        // `asciiWindow.drawTile`. `AnimatedAsciiTile` carries no per-cell phase, so each torch wraps
        // it in a [PhaseOffsetTile] keyed on the same torchSeed the surrounding light reads, keeping
        // the bright flame in lockstep with this torch's own light exactly as before. The shared
        // torchFlame and per-torch PhaseOffsetTile wrappers are now cached (glyphTorchTiles) and
        // reused each frame, instead of being allocated per frame.
        for (torch in GLYPH_TORCH_POSITIONS) {
            glyphSurface.put(torch.x, torch.y, z = 0, tile = glyphTorchTiles.getValue(torch))
        }

        // Placeholder combatants — specific glyph/color choices are provisional until
        // krogue-tnf's grid-snapped mode lands.
        asciiWindow.drawTile(
            GLYPH_PLAYER_COL,
            MID_ROW,
            StaticAsciiTile(
                '@',
                litColor(Color.CYAN, GLYPH_PLAYER_COL, MID_ROW, GLYPH_TORCH_POSITIONS, elapsedMs),
                litColor(floorGlowBase, GLYPH_PLAYER_COL, MID_ROW, GLYPH_TORCH_POSITIONS, elapsedMs),
            ),
        )
        asciiWindow.drawTile(
            GLYPH_MONSTER_COL,
            MID_ROW,
            StaticAsciiTile(
                's',
                litColor(Color.GREEN, GLYPH_MONSTER_COL, MID_ROW, GLYPH_TORCH_POSITIONS, elapsedMs),
                litColor(floorGlowBase, GLYPH_MONSTER_COL, MID_ROW, GLYPH_TORCH_POSITIONS, elapsedMs),
            ),
        )
        // The dash itself is drawn by glyphProjectileQueue.render (called from render()) --
        // krogue-tnf's real grid-snapped VisualSequence, not hand-rolled here.
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
        floorRenderer.dispose()
        wallRenderer.dispose()
        overlayRenderer.dispose()
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

/**
 * A consumer-supplied [DynamicAsciiTile] (ADR-0033's open extension point): wraps [base] and shifts
 * its clock by [offsetMs], so several cells backed by one shared animation can each run at their own
 * phase. Here it gives each torch its own flicker offset (the same `torchSeed` the light uses) from a
 * single shared [AnimatedAsciiTile], instead of the built-in — which resolves every cell at the same
 * wall-clock time — flickering all torches in lockstep.
 */
private class PhaseOffsetTile(
    private val base: AsciiTile,
    private val offsetMs: Long,
) : DynamicAsciiTile {
    override fun resolveAt(elapsedMs: Long): StaticAsciiTile = base.resolveAt(elapsedMs + offsetMs)
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
