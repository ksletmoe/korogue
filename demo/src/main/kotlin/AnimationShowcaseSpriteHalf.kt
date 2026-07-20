import com.badlogic.gdx.graphics.Color
import com.sletmoe.korogue.demo.animation.DawnLikeCreatureTiles
import com.sletmoe.korogue.demo.animation.DawnLikeFloorTiles
import com.sletmoe.korogue.demo.animation.DawnLikeTorchTile
import com.sletmoe.korogue.demo.animation.DawnLikeWallTiles
import com.sletmoe.korogue.presentation.VisualEvent
import com.sletmoe.kotile.display.BlendMode
import com.sletmoe.kotile.tiles.AnimatedSpriteTile
import com.sletmoe.kotile.tiles.AnimationFrame
import com.sletmoe.kotile.tiles.PlaybackMode
import com.sletmoe.kotile.utilities.Vector2Int

// The sprite (left) half's per-frame drawing, split out of AnimationShowcaseHarness (krogue-iid)
// as extension functions on it: the receiver threads the harness's shared state (renderers, sheets,
// canvas, lighting, spriteFlashPool) exactly as when these were member functions — no behavior
// change, just a smaller file.

/**
 * Redrawn every frame (not just once at startup) so the flicker computed by [TorchLighting.litTint]
 * at the current [elapsedMs] actually shows: a [com.sletmoe.kotile.tiles.StaticSpriteTile]'s `tint`
 * is baked in at draw time, so showing a changing tint means re-issuing the draw call with a freshly
 * computed one each frame — the same thing `MapPanel.draw()` does every frame in the real game.
 */
internal fun AnimationShowcaseHarness.buildSpriteRoom(elapsedMs: Long) {
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
            val tint = lighting.litTint(x, y, SPRITE_TORCH_POSITIONS, elapsedMs)
            floorRenderer.drawTile(x, y, z = 0, tile = floor.copy(tint = tint))
        }
    }
    for (x in 0 until SPRITE_COLS) {
        val top = if (x == 0) DawnLikeWallTiles.UPPER_LEFT_CORNER else DawnLikeWallTiles.TOP_WALL
        val bottom = if (x == 0) DawnLikeWallTiles.BOTTOM_LEFT_CORNER else DawnLikeWallTiles.BOTTOM_WALL
        val topTint = lighting.litTint(x, 0, SPRITE_TORCH_POSITIONS, elapsedMs)
        wallRenderer.drawTile(x, 0, z = 0, tile = top.copy(tint = topTint))
        val bottomTint = lighting.litTint(x, ROWS - 1, SPRITE_TORCH_POSITIONS, elapsedMs)
        wallRenderer.drawTile(x, ROWS - 1, z = 0, tile = bottom.copy(tint = bottomTint))
    }
    for (y in 1 until ROWS - 1) {
        val wall = DawnLikeWallTiles.LEFT_WALL.copy(tint = lighting.litTint(0, y, SPRITE_TORCH_POSITIONS, elapsedMs))
        wallRenderer.drawTile(0, y, z = 0, tile = wall)
    }

    for (torchX in SPRITE_WALL_TORCH_COLS) {
        val torch = Vector2Int(torchX, 0)
        overlayRenderer.drawTile(torchX, 0, z = 1, tile = torchTile(torch, elapsedMs))
    }

    val (rangerSheet, rangerCell) = DawnLikeCreatureTiles.RANGER
    val rangerTint = lighting.litTint(SPRITE_RANGER_COL, MID_ROW, SPRITE_TORCH_POSITIONS, elapsedMs)
    overlayRenderer.drawTile(
        SPRITE_RANGER_COL,
        MID_ROW,
        z = 1,
        // The source art faces left; the ranger stands on the left shooting right, so it needs
        // mirroring to actually face its target instead of shooting backward over its shoulder.
        tile = creatureTile(rangerSheet, rangerCell, rangerTint, flipX = true),
    )

    val (scorpionSheet, scorpionCell) = DawnLikeCreatureTiles.SCORPION
    val scorpionTint = lighting.litTint(SPRITE_SCORPION_COL, MID_ROW, SPRITE_TORCH_POSITIONS, elapsedMs)
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
 * The torch sprite showing whichever flame state [TorchLighting.torchFrameIndex] says is active for
 * [torch] at [elapsedMs] — a single-frame tile rebuilt fresh each call (matching
 * [buildSpriteRoom]'s per-frame rebuild) rather than an [AnimatedSpriteTile] with its own
 * independent 2-frame clock, so the art stays locked to the same phase as its light.
 * Sheet choice is deliberately Decor1-for-bright/Decor0-for-dim — see [DawnLikeTorchTile]'s
 * doc comment: the file names suggest the opposite, but the actual pixels don't.
 */
internal fun AnimationShowcaseHarness.torchTile(
    torch: Vector2Int,
    elapsedMs: Long,
): AnimatedSpriteTile {
    val cell = DawnLikeTorchTile.CELL
    val sheet = if (lighting.torchFrameIndex(torch, elapsedMs) == 0) decor1Sheet else decor0Sheet
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
internal fun AnimationShowcaseHarness.creatureTile(
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
 * If a [VisualEvent.HitFlash] is active on the scorpion's cell (in [AnimationShowcaseHarness]'s
 * spriteFlashPool, never itself `renderSprite()`'d, since its generic quad would show through the
 * sprite's own transparent tile margins as a white border rather than a flash), draws the scorpion's
 * own sprite a second time on top, additively blended. A multiply [Color] tint can only ever
 * reproduce a sprite's native colors (at best, `tint = WHITE`) or darken them -- GDX's `Color`
 * also clamps every component to `[0, 1]`, so an over-bright tint isn't even representable --
 * so tinting the *first* draw can never brighten a dark pixel toward white. Additive blending
 * ([BlendMode.ADDITIVE]) genuinely adds light instead, which is the only way to make a flash
 * actually read as a flash rather than "the same creature, unchanged."
 */
internal fun AnimationShowcaseHarness.drawScorpionFlashOverlay(elapsedMs: Long) {
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
