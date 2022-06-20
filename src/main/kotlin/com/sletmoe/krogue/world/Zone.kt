package com.sletmoe.krogue.world

import com.sletmoe.krogue.algorithms.zonegen.ZoneFeatureGenerator
import com.sletmoe.krogue.utilities.Grid
import com.sletmoe.krogue.utilities.initialize
import java.awt.Color
import kotlin.random.Random

open class Zone(val zoneId: String, val tiles: Grid<Tile>, var creatures: List<Creature> = mutableListOf()) {
    val width: Int
        get() = tiles.width
    val height: Int
        get() = tiles.height

    fun creatureAt(x: Int, y: Int): Creature? = creatures.firstOrNull { it.x == x && it.y == y }
    fun isWalkable(x: Int, y: Int): Boolean = tiles[x, y].isWalkable && creatureAt(x, y) == null

    fun getCreaturesInArea(x: Int, y: Int, width: Int, height: Int): List<Creature> {
        return creatures.filter { creature ->
            creature.x > x - width / 2.0 && creature.x < x + width / 2.0 && creature.y > y - height / 2.0 && creature.y < y + height / 2.0
        }
    }

    open class Builder(
        private val zoneId: String,
        private val width: Int,
        private val height: Int,
        private val random: Random = Random.Default
    ) {
        private val tiles: Grid<Tile> = Grid(width, height, BLANK_TILE)
        private val creatures: MutableList<Creature> = mutableListOf()


        fun fill(tile: Tile) {
            tiles.forEachCoordinate { coordinate ->
                tiles[coordinate] = tile
            }
        }

        fun setTile(x: Int, y: Int, tile: Tile) {
            tiles[x, y] = tile
        }

        fun addCreature(creature: Creature) {
            creatures.add(creature)
        }

        fun addCreatures(creatures: List<Creature>) {
            this.creatures.addAll(creatures)
        }

        private fun createCreature(type: String, x: Int, y: Int): Creature {
            return when (type) {
                "zombie" -> {
                    Creature(x, y, "zombie", 'z', Color.green, "aggressive")
                }
                "sheep" -> {
                    Creature(x, y, "sheep", 's', Color.white, "docile")
                }
                else -> {
                    throw RuntimeException()
                }
            }
        }

        fun populateZone(numCreatures: Int) {
            repeat(numCreatures) {
                var rndX = 0
                var rndY = 0

                do {
                    rndX = random.nextInt(width)
                    rndY = random.nextInt(height)
                } while (!tiles[rndX, rndY].isWalkable)

                val creatureType = random.nextInt(2)

                val creature = if (creatureType == 0) {
                    createCreature("zombie", rndX, rndY)
                } else {
                    createCreature("sheep", rndX, rndY)
                }

                creatures.add(creature)
            }
        }

        fun addFeature(featureGenerator: ZoneFeatureGenerator) {
            featureGenerator(tiles, random)
        }

        fun build(): Zone = Zone(zoneId, tiles, creatures)
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
