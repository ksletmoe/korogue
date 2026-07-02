package com.sletmoe.korogue.algorithms.zonegen

import com.sletmoe.korogue.ecs.Component
import com.sletmoe.korogue.utilities.Direction
import com.sletmoe.korogue.world.Tile
import com.sletmoe.kotile.utilities.Vector2Int

/**
 * A parameterized structure (e.g. a small room/"shack") that can be **stamped** into a
 * [ZoneGenContext] at any origin: tiles + entity placements defined relative to a local
 * `(0, 0)` origin (ADR-0019, krogue-b1p.2).
 *
 * Build one with [Prefab.builder], authoring the tile layout as ASCII art (see [Builder]).
 * A single [Prefab] is a fixed size; a generator wanting *variable*-size structures (e.g. a
 * "shack stamper" scattering 2-5x2-5 rooms, ADR-0019's acceptance case) builds a different
 * [Prefab] per size from a small factory function rather than encoding a size range here — that
 * keeps this value (and [stamp]) simple, immutable, and trivially testable.
 *
 * Entity placements ([entities]) are independent of each other and of the tile layout: each is
 * just a cell offset + component list. There are **no entity ids at stamp time** — spawns are
 * buffered by [ZoneGenContext.spawn] and only materialized into the ECS after generation (see
 * [ZoneGenContext]) — so a placement cannot reference another placement (e.g. a container can't
 * point at its contents, a wielder can't point at its weapon).
 *
 * [doors] and [anchors] are local-offset metadata for the future placement framework
 * (krogue-b1p.3) to align, connect, or decorate prefabs; [stamp] itself doesn't interpret them.
 *
 * @property width the prefab's bounding box width (local x ranges over `0 until width`)
 * @property height the prefab's bounding box height (local y ranges over `0 until height`)
 * @property tiles local offset -> [Tile] to paint; an offset absent from this map leaves the
 *   zone's existing tile at that cell untouched when stamped (lets a rectangular [Builder] block
 *   describe a non-rectangular room)
 * @property entities independent entity placements: local offset + the components to spawn there
 * @property doors local offsets of door/entrance cells, each with the [Direction] it opens
 *   toward (or `null` if undirected)
 * @property anchors named local offsets (e.g. `"center"`) for placement/decoration logic to read
 */
class Prefab(
    val width: Int,
    val height: Int,
    val tiles: Map<Vector2Int, Tile>,
    val entities: List<EntityPlacement> = emptyList(),
    val doors: List<Door> = emptyList(),
    val anchors: Map<String, Vector2Int> = emptyMap(),
) {
    init {
        require(width > 0 && height > 0) { "Prefab must be at least 1x1, got ${width}x$height" }
        tiles.keys.forEach { requireInBounds(it, "tile") }
        entities.forEach { requireInBounds(Vector2Int(it.dx, it.dy), "entity placement") }
        doors.forEach { requireInBounds(Vector2Int(it.dx, it.dy), "door") }
        anchors.forEach { (name, offset) -> requireInBounds(offset, "anchor \"$name\"") }
    }

    private fun requireInBounds(
        offset: Vector2Int,
        what: String,
    ) {
        require(offset.x in 0 until width && offset.y in 0 until height) {
            "Prefab $what offset $offset is outside its ${width}x$height bounds"
        }
    }

    /**
     * Writes this prefab's [tiles] into [ctx] at ([originX], [originY]) and calls
     * [ZoneGenContext.spawn] for each of [entities], translated by the same origin.
     *
     * Out-of-bounds is **rejected, not clipped**: if the prefab wouldn't fully fit within
     * [ZoneGenContext.tiles] at this origin, [stamp] throws rather than silently painting a
     * truncated structure (e.g. a shack missing its north wall). Finding an origin the whole
     * prefab fits at is a placement concern (krogue-b1p.3, bounds-fit); [stamp] assumes that
     * choice has already been made.
     *
     * @throws IllegalArgumentException if the prefab's `width x height` box at
     *   ([originX], [originY]) doesn't fit entirely within [ZoneGenContext.tiles]
     */
    fun stamp(
        ctx: ZoneGenContext,
        originX: Int,
        originY: Int,
    ) {
        require(
            originX >= 0 && originY >= 0 &&
                originX + width <= ctx.tiles.width && originY + height <= ctx.tiles.height,
        ) {
            "Prefab ${width}x$height at ($originX, $originY) does not fit within " +
                "${ctx.tiles.width}x${ctx.tiles.height} tiles"
        }

        tiles.forEach { (offset, tile) -> ctx.tiles[originX + offset.x, originY + offset.y] = tile }
        entities.forEach { ctx.spawn(originX + it.dx, originY + it.dy, it.components) }
    }

    /**
     * A builder that authors a [Prefab]'s tile layout as ASCII art: each of [rows] is one row of
     * the layout (row 0 is local y = 0; the first character of each row is local x = 0), and
     * [legend] maps each non-[blank] character to the [Tile] it paints.
     *
     * A row shorter than the widest row, or a character equal to [blank], leaves that cell
     * unpainted (the zone's existing tile shows through when stamped) — the way to express
     * non-rectangular rooms inside [rows]' rectangular block. Any other character not present in
     * [legend] is treated as an authoring mistake and rejected by [build], rather than silently
     * leaving a hole.
     *
     * Entity placements, doors, and anchors are added programmatically (they carry component
     * lists / directions / names that don't fit a single character) via [entity], [door], and
     * [anchor].
     *
     * ```kotlin
     * val shack = Prefab.builder(
     *     "#####",
     *     "#...#",
     *     "##.##",
     *     legend = mapOf('#' to WALL, '.' to FLOOR),
     * ).door(2, 2, Direction.SOUTH)
     *  .entity(2, 1, Named("goblin"))
     *  .build()
     * ```
     */
    class Builder(
        private val rows: List<String>,
        private val legend: Map<Char, Tile>,
        private val blank: Char = ' ',
    ) {
        private val entities = mutableListOf<EntityPlacement>()
        private val doors = mutableListOf<Door>()
        private val anchors = mutableMapOf<String, Vector2Int>()

        /** Adds an independent entity placement at local offset ([dx], [dy]) carrying [components]. */
        fun entity(
            dx: Int,
            dy: Int,
            vararg components: Component,
        ): Builder = apply { entities += EntityPlacement(dx, dy, components.asList()) }

        /** Adds a door/entrance at local offset ([dx], [dy]), optionally opening toward [direction]. */
        fun door(
            dx: Int,
            dy: Int,
            direction: Direction? = null,
        ): Builder = apply { doors += Door(dx, dy, direction) }

        /** Names local offset ([dx], [dy]) as [name] (e.g. `"center"`) for placement logic to read. */
        fun anchor(
            name: String,
            dx: Int,
            dy: Int,
        ): Builder = apply { anchors[name] = Vector2Int(dx, dy) }

        /**
         * Builds the [Prefab].
         *
         * @throws IllegalArgumentException if [rows] contains a character that is neither
         *   [blank] nor a key of [legend]
         */
        fun build(): Prefab {
            val width = rows.maxOf { it.length }
            val height = rows.size

            val tiles = mutableMapOf<Vector2Int, Tile>()
            rows.forEachIndexed { y, row ->
                row.forEachIndexed { x, char ->
                    if (char == blank) return@forEachIndexed
                    val tile =
                        legend[char]
                            ?: throw IllegalArgumentException(
                                "Prefab row $y has unmapped character '$char' at column $x; " +
                                    "add it to the legend or use the blank character ('$blank')",
                            )
                    tiles[Vector2Int(x, y)] = tile
                }
            }

            return Prefab(width, height, tiles, entities.toList(), doors.toList(), anchors.toMap())
        }
    }

    companion object {
        /** Starts a [Builder] from ASCII-art [rows] (row 0 = local y = 0) and a char->[Tile] [legend]. */
        fun builder(
            vararg rows: String,
            legend: Map<Char, Tile>,
            blank: Char = ' ',
        ): Builder = Builder(rows.asList(), legend, blank)
    }
}

/** An independent entity to spawn at local offset ([dx], [dy]) carrying [components] (krogue-b1p.2). */
data class EntityPlacement(
    val dx: Int,
    val dy: Int,
    val components: List<Component>,
)

/**
 * A door/entrance cell at local offset ([dx], [dy]) within a [Prefab], optionally opening toward
 * [direction] (e.g. for a placer to align it with a corridor). Metadata only — [Prefab.stamp]
 * doesn't interpret it; a future placer (krogue-b1p.3) reads [Prefab.doors].
 */
data class Door(
    val dx: Int,
    val dy: Int,
    val direction: Direction? = null,
)
