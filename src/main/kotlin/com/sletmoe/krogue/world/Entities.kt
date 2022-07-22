package com.sletmoe.krogue.world

import asciiPanel.AsciiCharacterData
import mu.KotlinLogging
import java.awt.Color
import java.awt.Point
import java.lang.Integer.max
import java.lang.Integer.min
import kotlin.random.Random

open class Entity(
    val name: String,
    val glyph: Char,
    val color: Color,
    val description: String? = null,
)

open class Creature(
    x: Int,
    y: Int,
    name: String,
    glyph: Char,
    color: Color,
    description: String? = null,
    val maxHp: Int = 100,
) : Entity(name, glyph, color, description) {
    companion object {
        protected val logging = KotlinLogging.logger { }
    }

    private var _x = x
    val x: Int
        get() = _x
    private var _y = y
    val y: Int
        get() = _y
    val position: Point
        get() = Point(x, y)

    private var _health = maxHp
    val health: Int
        get() = _health
    private val random = Random.Default

    open fun damage(hp: Int) {
        _health = max(0, _health - hp)
        logging.info { "$name took $hp damage; $_health remaining" }
    }

    open fun heal(hp: Int) {
        _health = min(maxHp, _health + hp)
    }

    val alive: Boolean
        get() = _health > 0
    val dead: Boolean
        get() = !alive

    open fun attack(other: Creature) {
        logging.info { "$name is attacking ${other.name}" }
        other.damage(20)
    }

    fun move(zone: Zone, dx: Int, dy: Int) {
        val destinationX = x + dx
        val destinationY = y + dy

        if (zone.isWalkable(destinationX, destinationY)) {
            _x = destinationX
            _y = destinationY
        } else {
            val otherCreature = zone.creatureAt(destinationX, destinationY)
            otherCreature?.let { attack(otherCreature) }
        }
    }

    open fun update(zone: Zone) {
        val performAction = random.nextInt(100)
        if (name == "sheep" && performAction > 98) {
            when (random.nextInt(3)) {
                0 -> {
                    move(zone, 1, 0)
                }
                1 -> {
                    move(zone, -1, 0)
                }
                2 -> {
                    move(zone, 0, 1)
                }
                3 -> {
                    move(zone, 0, -1)
                }
            }
        } else if (name == "zombie" && performAction > 98) {
            val creatures = zone.getCreaturesInArea(x, y, 10, 10).filter { it != this }

            if (creatures.isNotEmpty()) {
                val creature = creatures[0]

                if (x > creature.x) {
                    move(zone, -1, 0)
                } else if (x < creature.x) {
                    move(zone, 1, 0)
                } else if (y > creature.y) {
                    move(zone, 0, -1)
                } else if (y < creature.y) {
                    move(zone, 0, 1)
                }
            }
        }
    }
}

open class Tile(
    name: String,
    glyph: Char,
    color: Color,
    val backgroundColor: Color,
    val isWalkable: Boolean,
    val blocksLineOfSight: Boolean,
    description: String? = null,
) : Entity(name, glyph, color, description)

val BLANK_CHARACTER = AsciiCharacterData(' ', Color.white, Color.black)
val BLANK_TILE = Tile(
    "The Void",
    BLANK_CHARACTER.character,
    BLANK_CHARACTER.foregroundColor,
    BLANK_CHARACTER.backgroundColor,
    isWalkable = true,
    blocksLineOfSight = false,
)
