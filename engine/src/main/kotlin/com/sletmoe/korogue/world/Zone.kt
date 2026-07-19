package com.sletmoe.korogue.world

import com.sletmoe.korogue.algorithms.lighting.LightValue
import com.sletmoe.korogue.algorithms.zonegen.SpawnRequest
import com.sletmoe.korogue.algorithms.zonegen.ZoneFeatureGenerator
import com.sletmoe.korogue.algorithms.zonegen.ZoneGenContext
import com.sletmoe.korogue.algorithms.zonegen.ZoneGenerator
import com.sletmoe.korogue.components.MovementTags
import com.sletmoe.korogue.utilities.IntRect
import com.sletmoe.korogue.utilities.initialize
import com.sletmoe.kotile.utilities.Grid
import com.sletmoe.kotile.utilities.Vector2Int
import kotlin.random.Random

/**
 * Terrain for one map: its tile grid, bounds, and a derived light map. Occupants
 * (creatures, the player, light emitters) are ECS entities tagged with `ZoneMember`
 * and owned by [GameWorld.ecs] — a Zone no longer holds them (ADR-0007, 4b-s3).
 *
 * The [lightMap] is derived state recomputed each tick by `LightingSystem` from the
 * `LightEmitter` entities in this zone (4b-s5); a Zone no longer owns light sources.
 */
open class Zone(
    val zoneId: String,
    val tiles: Grid<Tile>,
) {
    val width: Int
        get() = tiles.width
    val height: Int
        get() = tiles.height

    val bounds: IntRect by lazy { IntRect(0, 0, tiles.width, tiles.height) }

    /** Per-tile light, recomputed each tick by `LightingSystem`; a null cell is unlit. */
    val lightMap: Grid<LightValue?> = Grid(tiles.width, tiles.height, null)

    /**
     * Per-cell environmental concealment tags (ADR-0020): tags that hide a cell — and any entity
     * standing on it — from perception senses that don't
     * [pierce][com.sletmoe.korogue.perception.Sense.pierces] them. **Magical darkness** sets
     * `{visual}` ([com.sletmoe.korogue.perception.PerceptionTags.VISUAL]), so it blocks `Sight` and
     * `Darkvision` while `TrueSight` pierces it; an empty set means no concealment. Derived /
     * effect-driven state like [lightMap] — an effect or generator writes regions into it; unlike
     * [lightMap] it is *not* about light, which is what lets it stop light-independent darkvision.
     */
    val concealment: Grid<Set<String>> = Grid(tiles.width, tiles.height, emptySet<String>())

    /**
     * Terrain-only per-mode passability: whether a mover using [moverModes] can
     * enter the tile at ([x], [y]) — true iff it has at least one [MovementTags]
     * mode the tile's [Tile.blocks] set does not stop (krogue-xeb, ADR-0022). The
     * same set relation `MovementSystem` applies to entity occupants, so a flyer
     * clears a cell that blocks only `{walk}`. Empty [moverModes] never passes.
     *
     * Occupancy is an ECS concern — combine with [GameWorld.entityAt] (see
     * [GameWorld.isWalkable]) when a move also needs the cell to be unoccupied.
     */
    fun isPassable(
        x: Int,
        y: Int,
        moverModes: Set<String>,
    ): Boolean {
        val blocks = tiles[x, y].blocks
        return moverModes.any { it !in blocks }
    }

    /** [isPassable] at the canonical [Vector2Int] cell [at] (ADR-0034). */
    fun isPassable(
        at: Vector2Int,
        moverModes: Set<String>,
    ): Boolean = isPassable(at.x, at.y, moverModes)

    /** Terrain-only walkability: the walk-mode special case of [isPassable]. */
    fun isWalkable(
        x: Int,
        y: Int,
    ): Boolean = isPassable(x, y, setOf(MovementTags.WALK))

    /** [isWalkable] at the canonical [Vector2Int] cell [at] (ADR-0034). */
    fun isWalkable(at: Vector2Int): Boolean = isWalkable(at.x, at.y)

    /**
     * @property random the stream feature generators draw from. Required, and deliberately
     *   has no default: an unseeded generator silently forfeits ADR-0009's reproducible
     *   worldgen (ADR-0025). Prefer
     *   [GameWorld.Builder.zone][com.sletmoe.korogue.world.GameWorld.Builder.zone], which
     *   supplies the world's own
     *   [worldgen stream][com.sletmoe.korogue.random.GameRandom.WORLDGEN]; pass one
     *   explicitly here only when building terrain standalone.
     */
    open class Builder(
        private val zoneId: String,
        width: Int,
        height: Int,
        private val random: Random,
    ) {
        private val tiles: Grid<Tile> = Grid(width, height, BLANK_TILE)
        private val genContext = ZoneGenContext(tiles, random)

        /**
         * Entity spawns buffered by entity-aware [addFeature] generators, to be materialized by
         * [GameWorld.Builder] once the ECS world exists. Terrain-only generation leaves this empty.
         */
        val pendingSpawns: List<SpawnRequest> get() = genContext.pendingSpawns

        fun fill(tile: Tile) {
            tiles.forEachCoordinate { coordinate ->
                tiles[coordinate] = tile
            }
        }

        fun setTile(
            x: Int,
            y: Int,
            tile: Tile,
        ) {
            tiles[x, y] = tile
        }

        /** [setTile] at the canonical [Vector2Int] cell [at] (ADR-0034). */
        fun setTile(
            at: Vector2Int,
            tile: Tile,
        ) = setTile(at.x, at.y, tile)

        /** Runs a terrain-only feature generator (mutates tiles; places no entities). */
        fun addFeature(featureGenerator: ZoneFeatureGenerator) {
            featureGenerator(tiles, random)
        }

        /**
         * Runs an entity-aware [ZoneGenerator] against this builder's [ZoneGenContext]: it paints
         * terrain and may buffer entity spawns into [pendingSpawns] (materialized later by
         * [GameWorld.Builder]).
         */
        fun addFeature(generator: ZoneGenerator) {
            generator.generate(genContext)
        }

        fun build(): Zone = Zone(zoneId, tiles)
    }

    companion object {
        /**
         * Builds a standalone terrain [Zone]. **Terrain only:** it does not materialize entities,
         * so it rejects (rather than silently drops) any entity spawns buffered by an entity-aware
         * [Builder.addFeature] generator — use
         * [GameWorld.Builder.zone][com.sletmoe.korogue.world.GameWorld.Builder.zone] for
         * entity-aware generation, which captures and materializes them.
         *
         * @param random the stream feature generators draw from — see [Builder.random] for why
         *   it is required rather than defaulted.
         * @throws IllegalStateException if the [zoneBuilderInit] buffered any entity spawns.
         */
        fun create(
            zoneId: String,
            width: Int,
            height: Int,
            random: Random,
            zoneBuilderInit: Builder.() -> Unit = {},
        ): Zone {
            val builder = initialize(Builder(zoneId, width, height, random), zoneBuilderInit)
            check(builder.pendingSpawns.isEmpty()) {
                "Zone.create is terrain-only but ${builder.pendingSpawns.size} entity spawn(s) were " +
                    "buffered by an entity-aware generator. Use GameWorld.Builder.zone(...) for " +
                    "entity-aware generation so the spawns are materialized into the ECS world."
            }
            return builder.build()
        }
    }
}
