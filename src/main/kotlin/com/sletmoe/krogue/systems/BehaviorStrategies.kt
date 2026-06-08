package com.sletmoe.krogue.systems

import com.sletmoe.krogue.components.MoveIntent
import com.sletmoe.krogue.components.Player
import com.sletmoe.krogue.components.Position
import com.sletmoe.krogue.ecs.Entity
import com.sletmoe.krogue.ecs.TickContext
import com.sletmoe.krogue.ecs.World
import kotlin.math.abs

/**
 * Decides an entity's move for the current tick, or null to stay put. Strategies are
 * pure decisions — they read the [World] and return an intent; `BehaviorSystem` applies it.
 */
fun interface BehaviorStrategy {
    fun decide(
        world: World,
        self: Entity,
        ctx: TickContext,
    ): MoveIntent?
}

/**
 * Wander: with probability [actChance] per tick, step one tile in a random cardinal
 * direction; otherwise stay put.
 */
class WanderStrategy(
    private val actChance: Double = DEFAULT_ACT_CHANCE,
) : BehaviorStrategy {
    override fun decide(
        world: World,
        self: Entity,
        ctx: TickContext,
    ): MoveIntent? {
        if (ctx.random.nextDouble() >= actChance) return null
        val (dx, dy) = STEPS.random(ctx.random)
        return MoveIntent(dx, dy)
    }

    companion object {
        const val ID = "wander"
    }
}

/**
 * Hunt the player: with probability [actChance] per tick, if the player is within
 * [range] (Chebyshev) tiles, step one tile toward them; otherwise stay put.
 */
class HuntPlayerStrategy(
    private val range: Int = DEFAULT_RANGE,
    private val actChance: Double = DEFAULT_ACT_CHANCE,
) : BehaviorStrategy {
    override fun decide(
        world: World,
        self: Entity,
        ctx: TickContext,
    ): MoveIntent? {
        if (ctx.random.nextDouble() >= actChance) return null
        val selfPos = self.require<Position>()
        val playerPos = world.entitiesWith<Player, Position>().firstOrNull()?.require<Position>() ?: return null
        if (chebyshev(selfPos, playerPos) > range) return null

        val dx = (playerPos.x - selfPos.x).coerceIn(-1, 1)
        val dy = if (dx == 0) (playerPos.y - selfPos.y).coerceIn(-1, 1) else 0
        return MoveIntent(dx, dy)
    }

    private fun chebyshev(
        a: Position,
        b: Position,
    ): Int = maxOf(abs(a.x - b.x), abs(a.y - b.y))

    companion object {
        const val ID = "hunt-player"
    }
}

private const val DEFAULT_ACT_CHANCE = 0.02
private const val DEFAULT_RANGE = 10
private val STEPS = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
