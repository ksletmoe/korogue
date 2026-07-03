package com.sletmoe.korogue.systems

import com.sletmoe.korogue.components.MoveIntent
import com.sletmoe.korogue.components.Player
import com.sletmoe.korogue.components.Portal
import com.sletmoe.korogue.components.Position
import com.sletmoe.korogue.components.ZoneMember
import com.sletmoe.korogue.ecs.Entity
import com.sletmoe.korogue.ecs.TickContext
import com.sletmoe.korogue.ecs.World
import com.sletmoe.korogue.utilities.Direction
import kotlin.math.abs

/**
 * The engine's built-in [BehaviorStrategy] implementations (Phase 4d). The extension-point
 * contract is in `BehaviorStrategy.kt`; a consuming game adds its own strategies the same
 * way these are registered (`GameModule.engineDefaults()` pre-loads them by id).
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
 * Hunt the player: with probability [actChance] per tick, if the player is within [range]
 * (Chebyshev) tiles **in this entity's own zone**, step one tile toward them; otherwise stay put.
 *
 * The same-zone gate (ADR-0021, Knob 2 default) makes a monster unaware of a player in another
 * zone — necessary once simulation widens past the current zone (`CurrentPlusAdjacent`), since two
 * zones share a coordinate space and comparing across them is meaningless. It is behaviour-
 * preserving under the default `CurrentZoneOnly`, where every simulated entity shares the player's
 * zone. For pursuit *across* a transition, use [CrossZoneHuntPlayerStrategy].
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
        val player = world.entitiesWith<Player, Position>().firstOrNull() ?: return null
        if (differentKnownZone(self, player)) return null
        val playerPos = player.require<Position>()
        if (chebyshev(selfPos, playerPos) > range) return null
        return stepToward(selfPos, playerPos)
    }

    companion object {
        const val ID = "hunt-player"
    }
}

/**
 * Cross-zone hunt (ADR-0021, Knob 2 — krogue-s67.4): like [HuntPlayerStrategy] while the player
 * shares this entity's zone, but when the player is in a **different** zone it paths toward the
 * nearest [Portal] in this entity's zone that leads to the player's zone — pursuing across a
 * transition instead of freezing at the threshold. Once it steps onto the portal, `PortalSystem`
 * carries it across.
 *
 * Opt in by tagging a monster with `Behavior(ID)` **and** widening simulation scope
 * (`CurrentPlusAdjacent`) so the adjacent zone ticks. It targets the **exit**, not the player's
 * exact cell — perception is zone-scoped (ADR-0015), so a monster pursues the way the player went,
 * it doesn't omnisciently track them; a game wanting that supplies its own strategy.
 */
class CrossZoneHuntPlayerStrategy(
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
        val selfZone = self.get<ZoneMember>()?.zoneId
        val player = world.entitiesWith<Player, Position>().firstOrNull() ?: return null
        val playerZone = player.get<ZoneMember>()?.zoneId

        // Same zone (or zones unknown): hunt the player directly, like HuntPlayerStrategy.
        if (selfZone == null || playerZone == null || selfZone == playerZone) {
            val playerPos = player.require<Position>()
            if (chebyshev(selfPos, playerPos) > range) return null
            return stepToward(selfPos, playerPos)
        }

        // Player in another zone: head for the nearest portal in this zone that leads there.
        val portalPos =
            world
                .entitiesWith<Portal, Position, ZoneMember>()
                .filter { portal ->
                    portal.require<ZoneMember>().zoneId == selfZone &&
                        portal.require<Portal>().targetZoneId == playerZone
                }
                .map { it.require<Position>() }
                .minByOrNull { chebyshev(selfPos, it) } ?: return null
        if (chebyshev(selfPos, portalPos) > range) return null
        return stepToward(selfPos, portalPos)
    }

    companion object {
        const val ID = "hunt-player-cross-zone"
    }
}

/** True when [a] and [b] both carry a `ZoneMember` and those zones differ. */
private fun differentKnownZone(
    a: Entity,
    b: Entity,
): Boolean {
    val za = a.get<ZoneMember>()?.zoneId
    val zb = b.get<ZoneMember>()?.zoneId
    return za != null && zb != null && za != zb
}

/** A one-tile cardinal step from [from] toward [to] (x-axis first, then y). */
private fun stepToward(
    from: Position,
    to: Position,
): MoveIntent {
    val dx = (to.x - from.x).coerceIn(-1, 1)
    val dy = if (dx == 0) (to.y - from.y).coerceIn(-1, 1) else 0
    return MoveIntent(dx, dy)
}

private fun chebyshev(
    a: Position,
    b: Position,
): Int = maxOf(abs(a.x - b.x), abs(a.y - b.y))

private const val DEFAULT_ACT_CHANCE = 0.02
private const val DEFAULT_RANGE = 10
