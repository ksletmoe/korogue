package com.sletmoe.korogue.systems

import com.sletmoe.korogue.components.MoveIntent
import com.sletmoe.korogue.components.Player
import com.sletmoe.korogue.components.Position
import com.sletmoe.korogue.ecs.Entity
import com.sletmoe.korogue.ecs.TickContext
import com.sletmoe.korogue.ecs.World
import com.sletmoe.korogue.utilities.Direction
import kotlin.math.abs

/**
 * The engine's built-in [BehaviorStrategy] implementations (Phase 4d). The extension-point
 * contract is in `BehaviorStrategy.kt`; a consuming game adds its own strategies the same
 * way these are registered (`GameModule.engineDefaults()` pre-loads both by id).
 *
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
        return MoveIntent(Direction.CARDINAL.random(ctx.random))
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
