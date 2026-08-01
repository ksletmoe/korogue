import com.badlogic.gdx.graphics.Color
import com.sletmoe.kotile.display.ascii.AnimatedAsciiTile
import com.sletmoe.kotile.display.ascii.AsciiTile
import com.sletmoe.kotile.display.ascii.DynamicAsciiTile
import com.sletmoe.kotile.display.ascii.StaticAsciiTile

// The glyph (right) half's per-frame drawing, split out of AnimationShowcaseHarness (krogue-iid)
// as an extension function on it: the receiver threads the harness's shared state (asciiWindow,
// glyphSurface, cached torch tiles, lighting, floorGlowBase) exactly as when this was a member
// function — no behavior change, just a smaller file.

/**
 * Redrawn every frame for the same reason as [buildSpriteRoom]: [TorchLighting.litColor]'s flicker
 * needs a fresh call each frame.
 */
internal fun AnimationShowcaseHarness.buildGlyphRoom(elapsedMs: Long) {
    val wallColor = Color(0.55f, 0.55f, 0.6f, 1f)
    val floorColor = Color(0.35f, 0.32f, 0.3f, 1f)
    for (x in SPRITE_COLS until TOTAL_COLS) {
        for (y in 0 until ROWS) {
            val lit = lighting.litColor(floorColor, x, y, GLYPH_TORCH_POSITIONS, elapsedMs)
            val glow = lighting.litColor(floorGlowBase, x, y, GLYPH_TORCH_POSITIONS, elapsedMs)
            asciiWindow.drawTile(x, y, StaticAsciiTile('.', lit, glow))
        }
    }
    // Room border in double-line box drawing rather than a row of '#' (krogue-tg5). These are
    // cell-filling glyphs, so each cell's strokes run edge to edge and meet the next cell's: the run
    // reads as one continuous wall. There is no LEFT edge — the glyph half's left boundary is the
    // rendering seam with the sprite half (see the harness's class doc), not a wall, so the horizontal
    // runs simply continue off that side. Only the right edge gets corners.
    val rightCol = TOTAL_COLS - 1
    for (x in SPRITE_COLS until TOTAL_COLS) {
        val topLit = lighting.litColor(wallColor, x, 0, GLYPH_TORCH_POSITIONS, elapsedMs)
        val topGlyph = if (x == rightCol) WALL_CORNER_TOP_RIGHT else WALL_HORIZONTAL
        asciiWindow.drawTile(x, 0, StaticAsciiTile(topGlyph, topLit, Color.BLACK))
        val bottomLit = lighting.litColor(wallColor, x, ROWS - 1, GLYPH_TORCH_POSITIONS, elapsedMs)
        val bottomGlyph = if (x == rightCol) WALL_CORNER_BOTTOM_RIGHT else WALL_HORIZONTAL
        asciiWindow.drawTile(x, ROWS - 1, StaticAsciiTile(bottomGlyph, bottomLit, Color.BLACK))
    }
    for (y in 1 until ROWS - 1) {
        val lit = lighting.litColor(wallColor, rightCol, y, GLYPH_TORCH_POSITIONS, elapsedMs)
        asciiWindow.drawTile(rightCol, y, StaticAsciiTile(WALL_VERTICAL, lit, Color.BLACK))
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
            lighting.litColor(Color.CYAN, GLYPH_PLAYER_COL, MID_ROW, GLYPH_TORCH_POSITIONS, elapsedMs),
            lighting.litColor(floorGlowBase, GLYPH_PLAYER_COL, MID_ROW, GLYPH_TORCH_POSITIONS, elapsedMs),
        ),
    )
    asciiWindow.drawTile(
        GLYPH_MONSTER_COL,
        MID_ROW,
        StaticAsciiTile(
            's',
            lighting.litColor(Color.GREEN, GLYPH_MONSTER_COL, MID_ROW, GLYPH_TORCH_POSITIONS, elapsedMs),
            lighting.litColor(floorGlowBase, GLYPH_MONSTER_COL, MID_ROW, GLYPH_TORCH_POSITIONS, elapsedMs),
        ),
    )
    // The dash itself is drawn by glyphProjectileQueue.render (called from render()) --
    // krogue-tnf's real grid-snapped VisualSequence, not hand-rolled here.
}

/**
 * A consumer-supplied [DynamicAsciiTile] (ADR-0033's open extension point): wraps [base] and shifts
 * its clock by [offsetMs], so several cells backed by one shared animation can each run at their own
 * phase. Here it gives each torch its own flicker offset (the same `torchSeed` the light uses) from a
 * single shared [AnimatedAsciiTile], instead of the built-in — which resolves every cell at the same
 * wall-clock time — flickering all torches in lockstep.
 */
internal class PhaseOffsetTile(
    private val base: AsciiTile,
    private val offsetMs: Long,
) : DynamicAsciiTile {
    override fun resolveAt(elapsedMs: Long): StaticAsciiTile = base.resolveAt(elapsedMs + offsetMs)
}
