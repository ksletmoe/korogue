package com.sletmoe.krogue.world

import mu.KotlinLogging
import java.awt.Color
import java.awt.Point
import kotlin.random.Random

open class Creature(
    position: Point,
    name: String,
    glyph: Char,
    color: Color,
    description: String? = null,
    val maxHp: Int = 100,
    var lightSource: LightSource? = null,
) : MovableEntity(position, name, glyph, color, description) {
    companion object {
        protected val logging = KotlinLogging.logger { }
    }

    private var _health = maxHp
    val health: Int
        get() = _health
    private val random = Random.Default

    open fun damage(hp: Int) {
        _health = Integer.max(0, _health - hp)
        logging.info { "$name took $hp damage; $_health remaining" }
    }

    open fun heal(hp: Int) {
        _health = Integer.min(maxHp, _health + hp)
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
        val destinationX = position.x + dx
        val destinationY = position.y + dy

        if (zone.isWalkable(destinationX, destinationY)) {
            super.move(dx, dy)
            lightSource?.move(dx, dy)
            zone.recalculateLightMap()
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
            val creatures = zone.getCreaturesInArea(position, 10, 10).filter { it != this }

            if (creatures.isNotEmpty()) {
                val creature = creatures[0]

                if (position.x > creature.position.x) {
                    move(zone, -1, 0)
                } else if (position.x < creature.position.x) {
                    move(zone, 1, 0)
                } else if (position.y > creature.position.y) {
                    move(zone, 0, -1)
                } else if (position.y < creature.position.y) {
                    move(zone, 0, 1)
                }
            }
        }
    }
}
