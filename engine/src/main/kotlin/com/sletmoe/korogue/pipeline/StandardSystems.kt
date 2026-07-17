package com.sletmoe.korogue.pipeline

import com.sletmoe.korogue.algorithms.lighting.LightValue
import com.sletmoe.korogue.perception.PerceptionModel
import com.sletmoe.korogue.perception.PerceptionSystem
import com.sletmoe.korogue.perception.StandardPerception
import com.sletmoe.korogue.registry.GameModule
import com.sletmoe.korogue.schedule.Scheduler
import com.sletmoe.korogue.schedule.SchedulerSystem
import com.sletmoe.korogue.systems.BehaviorSystem
import com.sletmoe.korogue.systems.CombatSystem
import com.sletmoe.korogue.systems.LightingSystem
import com.sletmoe.korogue.systems.MovementSystem
import com.sletmoe.korogue.systems.PickupSystem
import com.sletmoe.korogue.systems.PortalSystem
import com.sletmoe.korogue.systems.RangedAttackSystem
import com.sletmoe.korogue.world.GameWorld

/**
 * The systems [installStandardSystems] registered, handed back so the caller can hold the ones it
 * needs to drive outside the tick loop — [lighting] and [perception] in particular, since a host
 * typically runs them once at startup so the first frame isn't black before any turn has been taken
 * (see the demo's initial-lighting pass).
 */
data class StandardSystems(
    val scheduler: SchedulerSystem?,
    val rangedAttack: RangedAttackSystem,
    val behavior: BehaviorSystem,
    val movement: MovementSystem,
    val portal: PortalSystem,
    val pickup: PickupSystem,
    val combat: CombatSystem,
    val lighting: LightingSystem,
    val perception: PerceptionSystem,
)

/**
 * Registers korogue's built-in simulation pipeline on this world's ECS in a known-good order
 * (krogue-32d) — the one call that replaces hand-copying the demo's wiring, which was previously the
 * only reference for an ordering where mistakes are silent correctness bugs rather than crashes.
 *
 * This is a **baseline, not a mandate.** It suits a game that wants korogue's default simulation;
 * the more a game diverges, the less it applies. The Rogue port, for instance, uses only four of
 * these systems and interleaves ~17 of its own, so it builds its pipeline by hand — and that is a
 * supported path, not a fallback: [com.sletmoe.korogue.ecs.World.validateSystemOrder] checks any
 * pipeline, hand-built or installed, against the same [StandardStage] constraints. Replacing a
 * built-in keeps its guarantees as long as the replacement declares the matching stage.
 *
 * Order registered: [StandardStage.SCHEDULE] (only if [scheduler] is given), `RANGED_ATTACK`,
 * `BEHAVIOR`, `MOVEMENT`, `PORTAL`, `PICKUP`, `COMBAT`, `LIGHTING`, `PERCEPTION`.
 *
 * @param module the registries the built-ins resolve ids through (strategies, calculators, effects).
 * @param perceptionModel the perception policy (ADR-0015). Defaults to the model registered under
 *   [StandardPerception.ID] in [module].
 * @param scheduler if given, a [SchedulerSystem] is registered first to drive timed effects; omit
 *   for a game with no timers.
 * @param ambientLight baseline light for every cell before emitters (see [LightingSystem]).
 * @param damage the flat damage [CombatSystem] applies per hit.
 */
fun GameWorld.installStandardSystems(
    module: GameModule,
    perceptionModel: PerceptionModel = module.perceptionModels.resolve(StandardPerception.ID),
    scheduler: Scheduler? = null,
    ambientLight: LightValue? = null,
    damage: Int = CombatSystem.DEFAULT_DAMAGE,
): StandardSystems {
    val systems =
        StandardSystems(
            scheduler = scheduler?.let { SchedulerSystem(it, module.effects) },
            rangedAttack = RangedAttackSystem(this),
            behavior = BehaviorSystem(this, module.strategies),
            movement = MovementSystem(this),
            portal = PortalSystem(this),
            pickup = PickupSystem(),
            combat = CombatSystem(damage),
            lighting = LightingSystem(this, module.calculators, ambientLight),
            perception = PerceptionSystem(this, perceptionModel),
        )

    systems.scheduler?.let { ecs.addSystem(it) }
    ecs
        .addSystem(systems.rangedAttack)
        .addSystem(systems.behavior)
        .addSystem(systems.movement)
        .addSystem(systems.portal)
        .addSystem(systems.pickup)
        .addSystem(systems.combat)
        .addSystem(systems.lighting)
        .addSystem(systems.perception)

    // Fail at build time rather than waiting for the first tick's own check -- if this ever throws,
    // the order above and StandardStage's constraints have drifted apart.
    ecs.validateSystemOrder()
    return systems
}
