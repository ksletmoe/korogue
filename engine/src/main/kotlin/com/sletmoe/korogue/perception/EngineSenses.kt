package com.sletmoe.korogue.perception

import com.sletmoe.korogue.algorithms.los.LineOfSightCalculator
import com.sletmoe.korogue.components.Health
import com.sletmoe.korogue.components.Position
import com.sletmoe.korogue.components.ZoneMember
import com.sletmoe.korogue.ecs.Entity
import com.sletmoe.korogue.ecs.EntityId
import com.sletmoe.korogue.utilities.distance
import com.sletmoe.korogue.world.GameWorld
import com.sletmoe.korogue.world.Zone
import com.sletmoe.kotile.utilities.Vector2Int

/*
 * The engine's built-in Sense contributors (ADR-0015): the behaviour behind the Sight, Darkvision,
 * Tremorsense, and Telepathy components. `GameModule.engineDefaults()` registers them under the ids
 * named on those components, so a basic game just attaches a `Sight` and these do the rest; a game
 * overrides one by re-registering its id, or adds a wholly new sense (`HeatSense`) the same way.
 *
 * Each is stateless and shared across every observer bearing its id (per-observer data lives on the
 * component), and pure — `reveal` only reads the world. They declare Sense.tags so concealments and
 * suppressors interact by tag without a sense×effect matrix; none Sense.pierces anything by default
 * (see-invisible/true-sight are a game's piercing senses, krogue-1my.3).
 */

/**
 * `{visual, light-dependent}` line-of-sight, gated on lighting: a cell is revealed only where it is both
 * in line-of-sight from the observer and lit (`lightMap != null`) — the interim `MapPanel` rule, now a
 * sense. The [Sight.radius] caps the line-of-sight ([null] = limited only by walls). Entities standing
 * on a revealed cell are perceived. The [los] calculator is injected at registration (there is no LOS
 * registry yet); a game wanting a different one re-registers this sense with its own.
 */
class SightSense(
    private val los: LineOfSightCalculator,
) : Sense {
    override val tags = setOf(PerceptionTags.VISUAL, PerceptionTags.LIGHT_DEPENDENT)

    override fun reveal(
        observer: Entity,
        sense: SenseComponent,
        world: GameWorld,
    ): Contribution = lineOfSightContribution(observer, world, los, (sense as? Sight)?.radius, requireLit = true)

    companion object {
        const val ID = "sight"
    }
}

/**
 * `{visual}` line-of-sight to [Darkvision.radius] *regardless of lighting* — it sees in the dark.
 * Otherwise identical to [SightSense]: walls still block, and entities on revealed cells are perceived.
 */
class DarkvisionSense(
    private val los: LineOfSightCalculator,
) : Sense {
    override val tags = setOf(PerceptionTags.VISUAL)

    override fun reveal(
        observer: Entity,
        sense: SenseComponent,
        world: GameWorld,
    ): Contribution = lineOfSightContribution(observer, world, los, (sense as? Darkvision)?.radius, requireLit = false)

    companion object {
        const val ID = "darkvision"
    }
}

/**
 * `{visual}` line-of-sight that **pierces `{visual}` concealment** — see-invisible / true-sight.
 * Reveals like [DarkvisionSense] (line-of-sight to [TrueSight.radius] regardless of lighting), but
 * because it [Sense.pierces] `visual` it perceives [Invisible] targets. Piercing defeats concealment
 * only, not suppression (ADR-0015), so true-sight is **still blinded by [Blind]/[Dazzled]** — it is
 * better eyesight, not a non-visual sense. Surviving blindness is a job for a different channel.
 */
class TrueSightSense(
    private val los: LineOfSightCalculator,
) : Sense {
    override val tags = setOf(PerceptionTags.VISUAL)
    override val pierces = setOf(PerceptionTags.VISUAL)

    override fun reveal(
        observer: Entity,
        sense: SenseComponent,
        world: GameWorld,
    ): Contribution = lineOfSightContribution(observer, world, los, (sense as? TrueSight)?.radius, requireLit = false)

    companion object {
        const val ID = "truesight"
    }
}

/**
 * `{vibration}` sense: reveals living creatures (those with [Health]) within [Tremorsense.radius],
 * ignoring walls, line-of-sight, and lighting — you feel them through the ground but don't perceive the
 * terrain, so it contributes entities only, no cells.
 */
class TremorsenseSense : Sense {
    override val tags = setOf(PerceptionTags.VIBRATION)

    override fun reveal(
        observer: Entity,
        sense: SenseComponent,
        world: GameWorld,
    ): Contribution = creaturesWithinContribution(observer, world, (sense as? Tremorsense)?.radius)

    companion object {
        const val ID = "tremorsense"
    }
}

/**
 * `{mental}` sense: reveals living creatures (those with [Health]) within [Telepathy.radius] — the whole
 * zone when `null` — ignoring walls, line-of-sight, and lighting. Like [TremorsenseSense] it perceives
 * minds, not terrain, so it contributes entities only.
 */
class TelepathySense : Sense {
    override val tags = setOf(PerceptionTags.MENTAL)

    override fun reveal(
        observer: Entity,
        sense: SenseComponent,
        world: GameWorld,
    ): Contribution = creaturesWithinContribution(observer, world, (sense as? Telepathy)?.radius)

    companion object {
        const val ID = "telepathy"
    }
}

// --- shared reveal helpers ------------------------------------------------------------------------

/**
 * The reveal shared by [SightSense] and [DarkvisionSense]: line-of-sight cells from the observer capped
 * at [radius] (optionally gated on lighting when [requireLit]), plus the other entities standing on them.
 */
private fun lineOfSightContribution(
    observer: Entity,
    world: GameWorld,
    los: LineOfSightCalculator,
    radius: Double?,
    requireLit: Boolean,
): Contribution {
    val zone = observer.zoneIn(world) ?: return Contribution.EMPTY
    val origin = observer.get<Position>()?.point ?: return Contribution.EMPTY

    val visibility = los.calculateLineOfSight(origin, zone.tiles, radius)
    val cells = HashSet<Vector2Int>()
    visibility.forEachCoordinate { coord ->
        if (visibility[coord] && (!requireLit || zone.lightMap[coord] != null)) cells += coord
    }
    return Contribution(cells, entitiesOn(world, zone.zoneId, cells, observer.id))
}

/**
 * The reveal shared by [TremorsenseSense] and [TelepathySense]: living creatures ([Health]) in the
 * observer's zone within [radius] (whole zone when `null`), excluding the observer. No cells.
 */
private fun creaturesWithinContribution(
    observer: Entity,
    world: GameWorld,
    radius: Double?,
): Contribution {
    val zoneId = observer.get<ZoneMember>()?.zoneId ?: return Contribution.EMPTY
    val origin = observer.get<Position>()?.point ?: return Contribution.EMPTY

    val entities =
        world.ecs
            .entitiesWith<Health, Position, ZoneMember>()
            .filter { it.id != observer.id && it.require<ZoneMember>().zoneId == zoneId }
            .filter { radius == null || origin.distance(it.require<Position>().point) <= radius }
            .mapTo(HashSet()) { it.id }
    return Contribution(entities = entities)
}

/** The other entities standing on one of [cells] in [zoneId] (excludes [observerId]). */
private fun entitiesOn(
    world: GameWorld,
    zoneId: String,
    cells: Set<Vector2Int>,
    observerId: EntityId,
): Set<EntityId> {
    if (cells.isEmpty()) return emptySet()
    return world.ecs
        .entitiesWith<Position, ZoneMember>()
        .filter {
            it.id != observerId &&
                it.require<ZoneMember>().zoneId == zoneId &&
                it.require<Position>().point in cells
        }.mapTo(HashSet()) { it.id }
}

/** The [Zone] this entity is a member of, or null if it has no `ZoneMember` or the zone is unknown. */
private fun Entity.zoneIn(world: GameWorld): Zone? = get<ZoneMember>()?.let { world.zones[it.zoneId] }
