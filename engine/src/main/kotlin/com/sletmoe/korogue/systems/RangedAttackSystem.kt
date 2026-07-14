package com.sletmoe.korogue.systems

import com.sletmoe.korogue.algorithms.geometry.lineOfCellsStoppingAtBlocker
import com.sletmoe.korogue.components.AttackIntent
import com.sletmoe.korogue.components.Player
import com.sletmoe.korogue.components.Position
import com.sletmoe.korogue.components.RangedAttacker
import com.sletmoe.korogue.components.ZoneMember
import com.sletmoe.korogue.ecs.System
import com.sletmoe.korogue.ecs.TickContext
import com.sletmoe.korogue.ecs.World
import com.sletmoe.korogue.events.RangedAttackFired
import com.sletmoe.korogue.world.Zone
import kotlin.math.abs
import kotlin.math.max

/**
 * Lets a [RangedAttacker] hit a distant player instead of only ever chasing/bumping into them
 * (krogue-4tn). For each `RangedAttacker` + [Position] + [ZoneMember] entity sharing the player's
 * zone, Chebyshev distance strictly between 1 (melee already owns adjacent — see `MovementSystem`)
 * and the attacker's [RangedAttacker.range] inclusive, with a [lineOfCellsStoppingAtBlocker] path
 * that reaches the player's own cell unbroken: emits an [AttackIntent] on the attacker (resolved by
 * `CombatSystem` exactly like a melee hit) and publishes a [RangedAttackFired] so a presentation
 * observer can animate the shot.
 *
 * Registered **before** `BehaviorSystem` (see its own doc) so a firing attacker's `AttackIntent`
 * is already present when `BehaviorSystem` runs, letting it skip that entity's move for the tick
 * instead of also stepping it — one action per tick, not both.
 */
class RangedAttackSystem(
    private val zones: Map<String, Zone>,
) : System {
    override fun update(
        world: World,
        ctx: TickContext,
    ) {
        val player = world.entitiesWith<Player, Position, ZoneMember>().firstOrNull() ?: return
        val playerPos = player.require<Position>()
        val playerZoneId = player.require<ZoneMember>().zoneId

        for (entity in world.entitiesWith<RangedAttacker, Position, ZoneMember>().toList()) {
            if (entity.require<ZoneMember>().zoneId != playerZoneId) continue
            val pos = entity.require<Position>()
            val range = entity.require<RangedAttacker>().range
            val distance = chebyshev(pos, playerPos)
            if (distance <= 1 || distance > range) continue

            val zone = zones[playerZoneId] ?: continue
            val path =
                lineOfCellsStoppingAtBlocker(pos.point, playerPos.point) { cell ->
                    zone.tiles[cell.x, cell.y].blocksLineOfSight
                }
            if (path.last() != playerPos.point) continue // something in the way before reaching the target

            world.set(entity.id, AttackIntent(player.id))
            world.events.publish(RangedAttackFired(entity.id, player.id, pos.point, playerPos.point, path))
        }
    }

    private fun chebyshev(
        a: Position,
        b: Position,
    ): Int = max(abs(a.x - b.x), abs(a.y - b.y))
}
