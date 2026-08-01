import com.badlogic.gdx.graphics.Color
import com.sletmoe.kotile.utilities.Vector2Int

// Layout, timing, and scene constants shared across the animation showcase's files
// (AnimationShowcaseHarness.kt, its sprite/glyph half builders, and TorchLighting). These were
// file-private `const`/`val`s on the original single-file harness; splitting that file (krogue-iid)
// makes them `internal` so the sibling files in this module can read the same values.

/** DawnLike's own source tile size — a property of the art, not a display choice. */
internal const val DAWNLIKE_SRC_PX = 16

// How much bigger than DawnLike's source art the canvas's native cell is (see TILE_PX). The window is
// sized to exactly the native grid so the fixed-grid IntegerScale policy (kotile/library/.../
// ScalePolicy.kt) picks a clean whole-number factor — nearest-neighbor upscale, no letterboxing, no
// fractional-scale blur.
internal const val DISPLAY_SCALE = 2

/**
 * The canvas's **native** tile px — the resolution everything is authored at, before the window's
 * [com.sletmoe.kotile.rendering.IntegerScale] magnifies it to the backbuffer.
 *
 * [DISPLAY_SCALE]x DawnLike's source size, not equal to it (krogue-tg5). It was 16, which cost the TTF
 * glyph half most of its detail: a 16px atlas magnified 4x (2x window scale x 2x HiDPI backbuffer)
 * turns every atlas pixel into a 4x4 screen block, and at 16px a TTF glyph is mostly antialiasing, so
 * those grey edge pixels became grey blocks. Measured on the harness PNG: 256/256 of the '@' cell's
 * 4x4 blocks were perfectly uniform — pure nearest magnification of a 16px source. At 32 the
 * magnification halves (verified: largest uniform block 4x4 -> 2x2).
 *
 * Why not larger still: the two halves want opposite things from a *shared* canvas, and this is the
 * balance point. Raising the master to 64 and letting FitScale + SUPERSAMPLE resolve it down (kotile's
 * tier-2 "rasterise big, then downsample" route) does make the TTF pixel-exact — atlas at 64px, 1:1,
 * no magnification at all — but the same change resamples DawnLike's 16px art up to the 64px cell
 * instead of magnifying it by nearest at the very end, and a flat wall cell went from 2 distinct
 * colours to 11. Pixel art wants to be magnified last; a TTF wants to be authored at final size. One
 * canvas has one native resolution, so it cannot serve both — see krogue-pm6.
 */
internal const val TILE_PX = DAWNLIKE_SRC_PX * DISPLAY_SCALE
internal const val SPRITE_COLS = 25
internal const val GLYPH_COLS = 25
internal const val TOTAL_COLS = SPRITE_COLS + GLYPH_COLS
internal const val ROWS = 7 // roughly a quarter of the original 25 — less empty floor to cross
internal const val MID_ROW = ROWS / 2

// The window is exactly the native grid — TOTAL_COLS x ROWS cells of [TILE_PX] — so the
// fixed-grid IntegerScale policy picks a clean whole-number factor onto the backbuffer: no
// letterboxing, no fractional-scale blur. That factor is 2 on a 2x HiDPI backbuffer, 1 otherwise.
internal const val WINDOW_W_PX = TOTAL_COLS * TILE_PX
internal const val WINDOW_H_PX = ROWS * TILE_PX

/** Advance steps and per-step delta applied before the snapshot, so animated tiles are mid-cycle. */
internal const val PRE_ADVANCE_STEPS = 10
internal const val STEP_MS = 150L

// Wall-mounted torches (row 0) — diminishing, flickering light (below) radiates from these
// positions. DiminishingLightValueCalculator + LightFlicker are the engine's real lighting/
// flicker model (used by LightingSystem/MapPanel for the player's lantern in MyGame, krogue-ncl),
// reused as-is rather than reinvented. Two per side — directly over the ranger/scorpion columns,
// so the arrow at the midpoint (12 tiles apart) sits in the deepest part of the valley.
internal val SPRITE_WALL_TORCH_COLS = listOf(6, 18)
internal val SPRITE_TORCH_POSITIONS = SPRITE_WALL_TORCH_COLS.map { Vector2Int(it, 0) }

internal val GLYPH_WALL_TORCH_COLS = listOf(31, 43)
internal val GLYPH_TORCH_POSITIONS = GLYPH_WALL_TORCH_COLS.map { Vector2Int(it, 0) }

// The two combatants each half's projectile flies between — same columns as that half's
// torches, so the shot crosses the deepest part of the lighting valley.
internal const val SPRITE_RANGER_COL = 6
internal const val SPRITE_SCORPION_COL = 18
internal const val GLYPH_PLAYER_COL = SPRITE_COLS + 6
internal const val GLYPH_MONSTER_COL = TOTAL_COLS - 7

// Both halves' projectiles read this same clock, so they launch simultaneously; since the two
// combatants sit the same tile-distance apart on both sides (12 cols) and both halves share one
// canvas's tile size, using one flight duration for both automatically gives them the same
// on-screen speed too -- no separate per-half tuning needed.
internal const val PROJECTILE_FLIGHT_MS = 700L
internal const val PROJECTILE_CYCLE_MS = 1800L // flight + pause before the next shot

// DawnLikeAmmoTiles.ARROW's head (the pale cream/gray end -- not the blue end, which is the
// fletching, see that object's doc comment) is drawn facing southwest at rotationDeg=0, not along
// +X -- so rotating it to face due-east (this scene's only travel direction, left-to-right) needs
// this fixed offset on top of rotationTowards' 0deg. Rotation direction is easy to get backwards
// -- verify empirically against a real render before trusting it (see KotileCanvas.drawSprite's
// own doc note and kotile:demo's RotationHarness), not just from reading the source art.
internal const val ARROW_NATIVE_BEARING_DEG = 135f

/** The glyph-side arrow's own (unlit) foreground color -- a light tan, matching a wooden shaft. */
internal val ARROW_GLYPH_COLOR = Color(0.82f, 0.71f, 0.55f, 1f)

// The glyph half's room border, as CP437 slots (krogue-tg5) — double-line box drawing, which reads as
// masonry beside the sprite half's DawnLike stone in a way a row of '#' does not. These are *cell-filling*
// glyphs (ADR-0040): the TTF source maps the face's design cell onto the whole cell rect, so the strokes
// run edge to edge and neighbouring cells join into one continuous line rather than a dotted row of
// separate marks — which is the entire reason the border can be drawn this way at all. Passed as
// `Char(slot)`: GlyphSource.glyph is indexed by CP437 slot, not by Unicode code point.
internal val WALL_HORIZONTAL = Char(205) // ═
internal val WALL_VERTICAL = Char(186) // ║
internal val WALL_CORNER_TOP_RIGHT = Char(187) // ╗
internal val WALL_CORNER_BOTTOM_RIGHT = Char(188) // ╝

// The glyph half's TTF cell. Square and small, with a tall face — exactly the aspect mismatch ADR-0040's
// edge-snapped placement and ADR-0041's stroke warp exist for, so the border needs snapToPixelGrid to come
// out as crisp whole-pixel lines rather than grey smears straddling two rows.
internal const val GLYPH_CELL_PX = TILE_PX

// Torches are 12 cols apart; radius sized so the midpoint between two torches still reads as a
// visible (if dim) valley, not full black or a flat plateau.
internal const val LIGHT_RADIUS = 7.7

/** Cells beyond every torch's radius still show at this minimum, rather than going pure black. */
internal const val AMBIENT_MIN = 0.12

// One shared cadence for every 2-frame animation (torches and creatures, both halves) so
// everything flips frames in lockstep off the same elapsedMs clock, rather than each picking its
// own ad hoc timing.
internal const val ANIMATION_FRAME_MS = 400L
