package com.sletmoe.korogue.algorithms.zonegen

import com.sletmoe.korogue.ecs.Component
import com.sletmoe.korogue.world.Tile
import com.sletmoe.kotile.utilities.Grid
import com.sletmoe.kotile.utilities.Vector2Int
import kotlin.random.Random

/**
 * The mutable context a [ZoneGenerator] operates on: the tile [Grid] it paints, the [random]
 * source, and a **buffered entity sink** ([spawn]) for placing occupants (monsters, gold, items,
 * traps) as it generates (krogue-b1p, ADR-0019).
 *
 * Entities are *buffered*, not spawned live, because zone generation runs before the ECS
 * [com.sletmoe.korogue.ecs.World] exists (see [com.sletmoe.korogue.world.GameWorld.Builder]).
 * Generation therefore stays a pure function of (tiles, RNG) → (painted tiles, [pendingSpawns]):
 * deterministic, ordering-independent, and unit-testable without a World. The `GameWorld` builder
 * materializes each request via `World.spawn`, adding [com.sletmoe.korogue.components.Position] and
 * [com.sletmoe.korogue.components.ZoneMember] for the cell and zone.
 *
 * @property tiles the zone's terrain grid, mutated in place
 * @property random the generator's RNG (seeded by the caller for reproducibility)
 */
class ZoneGenContext(
    val tiles: Grid<Tile>,
    val random: Random,
) {
    private val _pendingSpawns = mutableListOf<SpawnRequest>()

    /** The entity spawns buffered so far, in insertion order. Read-only view. */
    val pendingSpawns: List<SpawnRequest> get() = _pendingSpawns

    /**
     * Buffers an entity to be spawned at cell ([x], [y]) carrying [components]. `Position` and
     * `ZoneMember` are added at materialization time, so pass only the entity's own components
     * (e.g. `Named`, `Renderable`, `Health`).
     */
    fun spawn(
        x: Int,
        y: Int,
        components: List<Component>,
    ) {
        _pendingSpawns += SpawnRequest(x, y, components)
    }

    /** Buffers an entity to be spawned at cell ([x], [y]) carrying [components] (vararg form). */
    fun spawn(
        x: Int,
        y: Int,
        vararg components: Component,
    ) = spawn(x, y, components.asList())

    /** [spawn] at the canonical [Vector2Int] cell [at] carrying [components] (ADR-0034). */
    fun spawn(
        at: Vector2Int,
        components: List<Component>,
    ) = spawn(at.x, at.y, components)

    /** [spawn] at the canonical [Vector2Int] cell [at] carrying [components] (vararg form). */
    fun spawn(
        at: Vector2Int,
        vararg components: Component,
    ) = spawn(at.x, at.y, components.asList())
}

/**
 * A buffered request to spawn an entity at cell ([x], [y]) with [components], produced by
 * [ZoneGenContext.spawn] during generation and materialized into the ECS `World` afterwards
 * (with `Position`/`ZoneMember` added).
 */
data class SpawnRequest(
    val x: Int,
    val y: Int,
    val components: List<Component>,
) {
    /** Construct from the canonical [Vector2Int] cell [point] (ADR-0034). */
    constructor(point: Vector2Int, components: List<Component>) : this(point.x, point.y, components)

    /** This request's cell as a [Vector2Int]. */
    val point: Vector2Int
        get() = Vector2Int(x, y)
}
