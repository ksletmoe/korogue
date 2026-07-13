import com.badlogic.gdx.ApplicationAdapter
import com.badlogic.gdx.Gdx
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.graphics.Pixmap
import com.badlogic.gdx.graphics.PixmapIO
import com.badlogic.gdx.math.Vector2
import com.sletmoe.korogue.algorithms.color.toNormalizedRgb
import com.sletmoe.korogue.algorithms.lighting.DiminishingLightValueCalculator
import com.sletmoe.korogue.algorithms.lighting.LightFlicker
import com.sletmoe.korogue.demo.animation.DawnLikeAmmoTiles
import com.sletmoe.korogue.demo.animation.DawnLikeCreatureTiles
import com.sletmoe.korogue.demo.animation.DawnLikeFloorTiles
import com.sletmoe.korogue.demo.animation.DawnLikeTorchTile
import com.sletmoe.korogue.demo.animation.DawnLikeWallTiles
import com.sletmoe.kotile.display.KotileCanvas
import com.sletmoe.kotile.display.ascii.AsciiTileDescriptor
import com.sletmoe.kotile.display.ascii.AsciiTileWindow
import com.sletmoe.kotile.display.ascii.Font
import com.sletmoe.kotile.display.ascii.Fonts
import com.sletmoe.kotile.rendering.IntegerScale
import com.sletmoe.kotile.rendering.SpriteTileRenderer
import com.sletmoe.kotile.rendering.rotationTowards
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

// Wall-mounted torches (row 0) — diminishing, flickering light (below) radiates from these
// positions. DiminishingLightValueCalculator + LightFlicker are the engine's real lighting/
// flicker model (used by LightingSystem/MapPanel for the player's lantern in MyGame, krogue-ncl),
// reused as-is rather than reinvented. Two per side (was three) — directly over the
// ranger/scorpion columns, so the arrow at the midpoint (12 tiles apart) sits in the deepest
// part of the valley.
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

// DawnLikeAmmoTiles.ARROW's source art is drawn at a fixed northeast-pointing diagonal (its
// rotationDeg=0 bearing), not pointing along +X -- so rotating it to face due-east (this scene's
// only travel direction, left-to-right) needs this fixed offset on top of rotationTowards' 0deg.
// Verified empirically via a snapshot (rotation direction is easy to get backwards -- see
// KotileCanvas.drawSprite's own doc note and kotile:demo's RotationHarness).
private const val ARROW_NATIVE_BEARING_DEG = -45f

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

    // krogue-ncl's real presentation-side flicker (engine/algorithms/lighting), reused as-is —
    // one shape shared by every torch, but each torch reads it at its own offset (via
    // torchSeed/torchFrameIndex below) so torches don't pulse in lockstep, mirroring MapPanel's
    // per-emitter phase. periodMs matches the torch sprite's full 2-frame loop (2x
    // ANIMATION_FRAME_MS) and the flicker carries no seed of its own, so this exact phase is also
    // what torchFrameIndex uses to pick the sprite/glyph frame — the light only brightens while
    // that same torch's own flame frame is the bright one.
    private val flicker = LightFlicker(amplitude = 0.05, periodMs = ANIMATION_FRAME_MS * 2)

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
        // Room content (tint included) is (re)built every frame in render() now that lighting
        // flickers — see buildSpriteRoom/buildGlyphRoom's doc comments.
    }

    private fun sheet(relativePath: String): TileSheet =
        TileSheet(Gdx.files.absolute(File(assetsDir, relativePath).path), TILE_PX, TILE_PX)

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

    /** The on-screen pixel center of grid cell ([col], [row]), per [KotileCanvas.drawSprite]'s coordinate space. */
    private fun cellCenterPx(
        col: Int,
        row: Int,
    ): Vector2 {
        val l = canvas.layout
        return Vector2((col + 0.5f) * l.tileWidthPx, (row + 0.5f) * l.tileHeightPx)
    }

    /** Linear pixel-space interpolation between two cells' centers at [t] (0..1). */
    private fun lerpPx(
        fromCol: Int,
        fromRow: Int,
        toCol: Int,
        toRow: Int,
        t: Float,
    ): Vector2 {
        val from = cellCenterPx(fromCol, fromRow)
        val to = cellCenterPx(toCol, toRow)
        return Vector2(from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t)
    }

    /** The grid cell containing pixel position [px] -- the graphical-side pixel position "snapped to the appropriate tile" for the glyph side. */
    private fun snapToTile(px: Vector2): Vector2Int {
        val l = canvas.layout
        return Vector2Int((px.x / l.tileWidthPx).toInt(), (px.y / l.tileHeightPx).toInt())
    }

    /**
     * Progress (0..1) through the current shot at [elapsedMs], or `null` during the pause between
     * shots. Both halves call this with the same [elapsedMs], so they launch, travel, and land in
     * lockstep -- the single source of truth for "is a projectile in flight right now."
     */
    private fun projectileFlightT(elapsedMs: Long): Float? {
        val phase = elapsedMs % PROJECTILE_CYCLE_MS
        if (phase >= PROJECTILE_FLIGHT_MS) return null
        return phase.toFloat() / PROJECTILE_FLIGHT_MS
    }

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
    // Sprite (left) half
    // -------------------------------------------------------------------------

    /**
     * Redrawn every frame (not just once at startup) so the flicker computed by [litTint] at the
     * current [elapsedMs] actually shows: a [StaticTile]'s `tint` is baked in at draw time, so
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
                floorRenderer.drawTile(x, y, z = 0, staticTile = floor.copy(tint = tint))
            }
        }
        for (x in 0 until SPRITE_COLS) {
            val top = if (x == 0) DawnLikeWallTiles.UPPER_LEFT_CORNER else DawnLikeWallTiles.TOP_WALL
            val bottom = if (x == 0) DawnLikeWallTiles.BOTTOM_LEFT_CORNER else DawnLikeWallTiles.BOTTOM_WALL
            val topTint = litTint(x, 0, SPRITE_TORCH_POSITIONS, elapsedMs)
            wallRenderer.drawTile(x, 0, z = 0, staticTile = top.copy(tint = topTint))
            val bottomTint = litTint(x, ROWS - 1, SPRITE_TORCH_POSITIONS, elapsedMs)
            wallRenderer.drawTile(x, ROWS - 1, z = 0, staticTile = bottom.copy(tint = bottomTint))
        }
        for (y in 1 until ROWS - 1) {
            val wall = DawnLikeWallTiles.LEFT_WALL.copy(tint = litTint(0, y, SPRITE_TORCH_POSITIONS, elapsedMs))
            wallRenderer.drawTile(0, y, z = 0, staticTile = wall)
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
            tile = creatureTile(rangerSheet, rangerCell, rangerTint),
        )

        val (scorpionSheet, scorpionCell) = DawnLikeCreatureTiles.SCORPION
        val scorpionTint = litTint(SPRITE_SCORPION_COL, MID_ROW, SPRITE_TORCH_POSITIONS, elapsedMs)
        overlayRenderer.drawTile(
            SPRITE_SCORPION_COL,
            MID_ROW,
            z = 1,
            tile = creatureTile(scorpionSheet, scorpionCell, scorpionTint),
        )
        // The arrow itself is drawn separately by drawSpriteArrow, via canvas.drawSprite directly
        // rather than through this grid-locked renderer -- see its doc comment for why.
    }

    /**
     * The in-flight arrow at [elapsedMs] (nothing drawn between shots): true sub-pixel motion via
     * [KotileCanvas.drawSprite], not [overlayRenderer]'s grid-locked `drawTile` -- unlike every
     * other sprite in this scene, a flying projectile needs to be *between* cells most of the
     * time, not snapped to one. [buildGlyphRoom]'s dash reads the exact same [lerpPx] position
     * (then snaps it, since glyphs are grid-only) so both halves' shots move at the same speed and
     * land at the same instant, per [projectileFlightT].
     */
    private fun drawSpriteArrow(elapsedMs: Long) {
        val t = projectileFlightT(elapsedMs) ?: return
        val pos = lerpPx(SPRITE_RANGER_COL, MID_ROW, SPRITE_SCORPION_COL, MID_ROW, t)
        val l = canvas.layout
        val col = (pos.x / l.tileWidthPx).toInt().coerceIn(0, SPRITE_COLS - 1)
        val tint = litTint(col, MID_ROW, SPRITE_TORCH_POSITIONS, elapsedMs)
        val region = ammoSheet.region(DawnLikeAmmoTiles.ARROW.sheetX, DawnLikeAmmoTiles.ARROW.sheetY)
        canvas.begin()
        canvas.drawSprite(
            pxX = pos.x - l.tileWidthPx / 2f,
            pxY = pos.y - l.tileHeightPx / 2f,
            region = region,
            w = l.tileWidthPx,
            h = l.tileHeightPx,
            tint = tint,
            rotationDeg = rotationTowards(1f, 0f) - ARROW_NATIVE_BEARING_DEG,
        )
        canvas.end()
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

    /** Redrawn every frame for the same reason as [buildSpriteRoom]: [litColor]'s flicker needs a fresh call each frame. */
    private fun buildGlyphRoom(elapsedMs: Long) {
        val wallColor = Color(0.55f, 0.55f, 0.6f, 1f)
        val floorColor = Color(0.35f, 0.32f, 0.3f, 1f)
        // A thin '.' glyph covers little of its cell, so tinting only the foreground barely reads
        // as a glow (unlike the sprite side's fully-opaque stone). A lit background wash — dim
        // everywhere, brighter near a torch — makes the same falloff visible here too.
        val floorGlowBase = Color(0.16f, 0.13f, 0.08f, 1f)
        for (x in SPRITE_COLS until TOTAL_COLS) {
            for (y in 0 until ROWS) {
                val lit = litColor(floorColor, x, y, GLYPH_TORCH_POSITIONS, elapsedMs)
                val glow = litColor(floorGlowBase, x, y, GLYPH_TORCH_POSITIONS, elapsedMs)
                asciiWindow.drawTile(x, y, AsciiTileDescriptor('.', lit, glow))
            }
        }
        for (x in SPRITE_COLS until TOTAL_COLS) {
            val topLit = litColor(wallColor, x, 0, GLYPH_TORCH_POSITIONS, elapsedMs)
            asciiWindow.drawTile(x, 0, AsciiTileDescriptor('#', topLit, Color.BLACK))
            val bottomLit = litColor(wallColor, x, ROWS - 1, GLYPH_TORCH_POSITIONS, elapsedMs)
            asciiWindow.drawTile(x, ROWS - 1, AsciiTileDescriptor('#', bottomLit, Color.BLACK))
        }
        for (y in 1 until ROWS - 1) {
            val lit = litColor(wallColor, TOTAL_COLS - 1, y, GLYPH_TORCH_POSITIONS, elapsedMs)
            asciiWindow.drawTile(TOTAL_COLS - 1, y, AsciiTileDescriptor('#', lit, Color.BLACK))
        }

        // Brogue-style torch flicker: background color shifts to simulate an unsteady flame; no
        // sprite art needed for this side. Reads the same torchFrameIndex (torchSeed-offset)
        // clock as this torch's own light and the sprite side's Decor0/Decor1 pick, so a bright
        // background here always coincides with this torch's own light being on its bright phase
        // — a single-descriptor draw per frame (not AnimatedAsciiTile's own clock) keeps that in
        // lockstep, the same reasoning as [torchTile] on the sprite side.
        for (torch in GLYPH_TORCH_POSITIONS) {
            val bright = torchFrameIndex(torch, elapsedMs) == 0
            val fg = if (bright) Color.ORANGE else Color.YELLOW
            val bg = if (bright) Color(0.5f, 0.25f, 0f, 1f) else Color(0.4f, 0.2f, 0f, 1f)
            asciiWindow.drawTile(torch.x, torch.y, AsciiTileDescriptor('!', fg, bg))
        }

        // Placeholder combatants — specific glyph/color choices are provisional until
        // krogue-tnf's grid-snapped mode lands.
        asciiWindow.drawTile(
            GLYPH_PLAYER_COL,
            MID_ROW,
            AsciiTileDescriptor(
                '@',
                litColor(Color.CYAN, GLYPH_PLAYER_COL, MID_ROW, GLYPH_TORCH_POSITIONS, elapsedMs),
                litColor(floorGlowBase, GLYPH_PLAYER_COL, MID_ROW, GLYPH_TORCH_POSITIONS, elapsedMs),
            ),
        )
        asciiWindow.drawTile(
            GLYPH_MONSTER_COL,
            MID_ROW,
            AsciiTileDescriptor(
                's',
                litColor(Color.GREEN, GLYPH_MONSTER_COL, MID_ROW, GLYPH_TORCH_POSITIONS, elapsedMs),
                litColor(floorGlowBase, GLYPH_MONSTER_COL, MID_ROW, GLYPH_TORCH_POSITIONS, elapsedMs),
            ),
        )

        // The dash's cell is the sprite-side arrow's exact same pixel-space position (lerpPx),
        // snapped to whichever tile it currently falls inside (snapToTile) -- not a separate,
        // independently-timed cell walk. That's what keeps the two halves' shots at the same
        // speed: both derive from one pixel-space model, this side just quantizes the result.
        projectileFlightT(elapsedMs)?.let { t ->
            val pos = lerpPx(GLYPH_PLAYER_COL, MID_ROW, GLYPH_MONSTER_COL, MID_ROW, t)
            val cell = snapToTile(pos)
            asciiWindow.drawTile(
                cell.x,
                cell.y,
                AsciiTileDescriptor(
                    '-',
                    litColor(Color.WHITE, cell.x, cell.y, GLYPH_TORCH_POSITIONS, elapsedMs),
                    litColor(floorGlowBase, cell.x, cell.y, GLYPH_TORCH_POSITIONS, elapsedMs),
                ),
            )
        }
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
        buildSpriteRoom(elapsedMs)
        buildGlyphRoom(elapsedMs)
        floorRenderer.render(elapsedMs)
        wallRenderer.render(elapsedMs)
        overlayRenderer.render(elapsedMs)
        drawSpriteArrow(elapsedMs) // free pixel-space motion, so drawn via canvas directly (see its doc comment)
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
