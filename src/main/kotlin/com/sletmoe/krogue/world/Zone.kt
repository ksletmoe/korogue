package com.sletmoe.krogue.world

import com.sletmoe.krogue.algorithms.color.ColorBlending
import com.sletmoe.krogue.algorithms.lighting.LightValue
import com.sletmoe.krogue.algorithms.zonegen.ZoneFeatureGenerator
import com.sletmoe.krogue.utilities.Grid
import com.sletmoe.krogue.utilities.initialize
import com.sletmoe.krogue.utilities.IntRect
import com.sletmoe.krogue.utilities.distance
import kotlin.random.Random

/**
 * Terrain for one map: its tile grid, bounds, and a derived light map. Occupants
 * (creatures, the player, light emitters) are ECS entities tagged with `ZoneMember`
 * and owned by [GameWorld.ecs] — a Zone no longer holds them (ADR-0007, 4b-s3).
 *
 * Lighting still lives here for now ([lightSources] + [recalculateLightMap]); it moves
 * to a `LightEmitter` component + `LightingSystem` in 4b-s5.
 */
open class Zone(
    val zoneId: String,
    val tiles: Grid<Tile>,
    private val lightSources: MutableList<LightSource> = mutableListOf(),
) {
    val width: Int
        get() = tiles.width
    val height: Int
        get() = tiles.height

    val bounds: IntRect by lazy { IntRect(0, 0, tiles.width, tiles.height) }

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

    fun addLightSource(lightSource: LightSource) {
        lightSources.add(lightSource)
    }

    fun removeLightSource(lightSource: LightSource) {
        lightSources.remove(lightSource)
    }

    fun recalculateLightMap() {
        lightMap.fill(null)

        lightSources.forEach { lightSource ->
            lightMap.forEachCoordinateInRadius(lightSource.position.point, lightSource.lightRadius) { lightMapCoord ->
                val existingLightMapVal = lightMap[lightMapCoord]
                val newLightVal =
                    lightSource.calculateLightValue(
                        lightSource.position.point.distance(lightMapCoord),
                    )

                lightMap[lightMapCoord] =
                    if (existingLightMapVal != null) {
                        LightValue(
                            ColorBlending.softLight(existingLightMapVal.normalizedColor, newLightVal.normalizedColor),
                            ColorBlending.screen(existingLightMapVal.intensity, newLightVal.intensity),
                        )
                    } else {
                        newLightVal
                    }
            }
        }
    }

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
