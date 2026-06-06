package com.sletmoe.krogue.world

import com.sletmoe.krogue.algorithms.lighting.LightValue
import com.sletmoe.krogue.algorithms.zonegen.ZoneFeatureGenerator
import com.sletmoe.krogue.utilities.Grid
import com.sletmoe.krogue.utilities.IntRect
import com.sletmoe.krogue.utilities.initialize
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

        fun addFeature(featureGenerator: ZoneFeatureGenerator) {
            featureGenerator(tiles, random)
        }

        fun build(): Zone = Zone(zoneId, tiles)
    }

    companion object {
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
