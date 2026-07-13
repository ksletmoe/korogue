package com.sletmoe.korogue.systems

import com.sletmoe.korogue.components.AttackIntent
import com.sletmoe.korogue.components.BumpResponse
import com.sletmoe.korogue.components.Collision
import com.sletmoe.korogue.components.Facing
import com.sletmoe.korogue.components.Locomotion
import com.sletmoe.korogue.components.MoveIntent
import com.sletmoe.korogue.components.MovementTags
import com.sletmoe.korogue.components.Position
import com.sletmoe.korogue.components.ZoneMember
import com.sletmoe.korogue.ecs.Entity
import com.sletmoe.korogue.ecs.EntityId
import com.sletmoe.korogue.ecs.System
import com.sletmoe.korogue.ecs.TickContext
import com.sletmoe.korogue.ecs.World
import com.sletmoe.korogue.world.Zone

/**
 * Resolves [MoveIntent]s into actual movement. For each entity with a [MoveIntent] +
 * [Position] + [ZoneMember], the intent is consumed and one of three things happens:
 * a step onto passable terrain not blocked by an occupant (updates [Position]); a bump
 * into a [Collision] blocker in the destination cell (emits an [AttackIntent] for
 * `CombatSystem`, or a solid no-op); or a no-op into terrain the mover cannot enter.
 *
 * Occupancy is data-driven via [Collision] + [Locomotion] (krogue-x9q), not a hardcoded
 * component denylist: an occupant blocks a mover iff every one of the mover's locomotion
 * [modes][Locomotion.modes] is in the occupant's [Collision.blocks] set, and a blocked
 * mover's response is the occupant's [Collision.bump]. Entities without [Collision] are
 * solid + attackable (the creature default); those with an empty [Collision.blocks] (e.g.
 * portals, items) are stepped onto.
 *
 * Resolution is sequential over a snapshot, so earlier movers' new positions are seen
 * by later movers within the same tick.
 *
 * Every intent with a nonzero horizontal component also updates [Facing] (krogue-csc),
 * regardless of whether the move itself succeeds — so a mover's sprite can face the way
 * it last moved or attacked, even while standing still.
 */
class MovementSystem(
    private val zones: Map<String, Zone>,
) : System {
    override fun update(
        world: World,
        ctx: TickContext,
    ) {
        for (entity in world.entitiesWith<MoveIntent, Position, ZoneMember>().toList()) {
            val intent = world.remove<MoveIntent>(entity.id) ?: continue
            val pos = entity.require<Position>()
            val zoneId = entity.require<ZoneMember>().zoneId
            if (intent.dx == 0 && intent.dy == 0) continue

            // Facing tracks the attempted move's horizontal component regardless of whether the
            // move actually succeeds (attack, blocked, or a step) — a pure vertical move leaves
            // the current Facing unchanged rather than picking a side arbitrarily (krogue-csc).
            if (intent.dx != 0) {
                world.set(entity.id, if (intent.dx > 0) Facing.RIGHT else Facing.LEFT)
            }

            val destX = pos.x + intent.dx
            val destY = pos.y + intent.dy
            val moverModes = entity.get<Locomotion>()?.modes ?: setOf(MovementTags.WALK)
            val blocker = blockerAt(world, zoneId, destX, destY, except = entity.id, moverModes = moverModes)

            when {
                blocker != null ->
                    // A blocked mover bumps: attack the occupant, or solid no-op (BLOCK).
                    if (collisionOf(blocker).bump == BumpResponse.ATTACK) {
                        world.set(entity.id, AttackIntent(blocker.id))
                    }
                zones[zoneId]?.isPassable(destX, destY, moverModes) == true ->
                    world.set(
                        entity.id,
                        Position(destX, destY),
                    )
                // else: blocked by terrain — intent already consumed, no move.
            }
        }
    }

    /** The first entity occupying ([x], [y]) in [zoneId] that blocks a mover using [moverModes]. */
    private fun blockerAt(
        world: World,
        zoneId: String,
        x: Int,
        y: Int,
        except: EntityId,
        moverModes: Set<String>,
    ): Entity? =
        world.entitiesWith<Position, ZoneMember>().firstOrNull {
            it.id != except &&
                it.require<ZoneMember>().zoneId == zoneId &&
                it.require<Position>().let { p -> p.x == x && p.y == y } &&
                blocksMover(it, moverModes)
        }

    /** True if [entity]'s occupancy stops a mover using [moverModes] — i.e. every mode is blocked. */
    private fun blocksMover(
        entity: Entity,
        moverModes: Set<String>,
    ): Boolean {
        val blocks = collisionOf(entity).blocks
        return moverModes.isNotEmpty() && moverModes.all { it in blocks }
    }

    /** An entity's [Collision], defaulting to solid + attackable when absent (the creature default). */
    private fun collisionOf(entity: Entity): Collision = entity.get<Collision>() ?: Collision()
}
