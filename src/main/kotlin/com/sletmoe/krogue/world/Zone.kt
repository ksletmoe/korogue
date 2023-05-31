package com.sletmoe.krogue.world

import com.sletmoe.krogue.algorithms.color.ColorBlending
import com.sletmoe.krogue.algorithms.lighting.LightValue
import com.sletmoe.krogue.algorithms.zonegen.ZoneFeatureGenerator
import com.sletmoe.krogue.utilities.Grid
import com.sletmoe.krogue.utilities.initialize
import java.awt.Point
import java.awt.Rectangle
import kotlin.random.Random

open class Zone(
    val zoneId: String,
    val tiles: Grid<Tile>,
    private val lightSources: MutableList<LightSource> = mutableListOf(),
    val _creatures: MutableList<Creature> = mutableListOf(),
) {
    val width: Int
        get() = tiles.width
    val height: Int
        get() = tiles.height

    val bounds: Rectangle by lazy { Rectangle(0, 0, tiles.width, tiles.height) }

    val creatures: List<Creature>
        get() = _creatures.toList()

    val lightMap: Grid<LightValue?> = Grid(tiles.width, tiles.height, null)

    fun creatureAt(x: Int, y: Int): Creature? = creatures.firstOrNull { it.position.x == x && it.position.y == y }
    fun isWalkable(x: Int, y: Int): Boolean = tiles[x, y].isWalkable && creatureAt(x, y) == null

    fun getCreaturesInArea(center: Point, width: Int, height: Int): List<Creature> {
        return creatures.filter { creature ->
            creature.position.x > center.x - width / 2.0
                    && creature.position.x < center.x + width / 2.0
                    && creature.position.y > center.y - height / 2.0
                    && creature.position.y < center.y + height / 2.0
        }
    }

    fun addCreature(creature: Creature) {
        _creatures.add(creature)
        creature.lightSource?.let { addLightSource(it) }
    }

    fun removeCreature(creature: Creature) {
        _creatures.remove(creature)
        creature.lightSource?.let { removeLightSource(it) }
    }

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
                val newLightVal = lightSource.calculateLightValue(
                    lightSource.position.point.distance(lightMapCoord)
                )

                lightMap[lightMapCoord] = if (existingLightMapVal != null) {
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
        private val random: Random = Random.Default
    ) {
        private val tiles: Grid<Tile> = Grid(width, height, BLANK_TILE)

        fun fill(tile: Tile) {
            tiles.forEachCoordinate { coordinate ->
                tiles[coordinate] = tile
            }
        }

        fun setTile(x: Int, y: Int, tile: Tile) {
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
            zoneBuilderInit: Builder.() -> Unit = {}
        ): Zone {
            return initialize(Builder(zoneId, width, height, random), zoneBuilderInit).build()
        }
    }
}
