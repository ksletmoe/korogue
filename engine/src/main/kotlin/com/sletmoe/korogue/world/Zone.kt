package com.sletmoe.korogue.world

import com.sletmoe.korogue.algorithms.lighting.LightValue
import com.sletmoe.korogue.algorithms.zonegen.SpawnRequest
import com.sletmoe.korogue.algorithms.zonegen.ZoneFeatureGenerator
import com.sletmoe.korogue.algorithms.zonegen.ZoneGenContext
import com.sletmoe.korogue.algorithms.zonegen.ZoneGenerator
import com.sletmoe.korogue.utilities.Grid
import com.sletmoe.korogue.utilities.IntRect
import com.sletmoe.korogue.utilities.initialize
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
     * Terrain-only walkability: whether the tile at ([x], [y]) can be stood on.
     * Occupancy is an ECS concern — combine with [GameWorld.entityAt] (see
     * [GameWorld.isWalkable]) when a move also needs the cell to be unoccupied.
     */
    fun isWalkable(
        x: Int,
        y: Int,
    ): Boolean = tiles[x, y].isWalkable

    open class Builder(
        private val zoneId: String,
        width: Int,
        height: Int,
        private val random: Random = Random.Default,
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
         * Builds a standalone terrain [Zone]. **Terrain only:** entity spawns buffered by
         * entity-aware [Builder.addFeature] generators are discarded here (there is no ECS world to
         * materialize them into). For entity-aware generation use
         * [GameWorld.Builder.zone][com.sletmoe.korogue.world.GameWorld.Builder.zone], which captures
         * and materializes them.
         */
        fun create(
            zoneId: String,
            width: Int,
            height: Int,
            random: Random = Random.Default,
            zoneBuilderInit: Builder.() -> Unit = {},
        ): Zone {
            return initialize(Builder(zoneId, width, height, random), zoneBuilderInit).build()
        }
    }
}
