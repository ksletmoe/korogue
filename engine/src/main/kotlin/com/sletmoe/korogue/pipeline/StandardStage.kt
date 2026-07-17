package com.sletmoe.korogue.pipeline

import com.sletmoe.korogue.ecs.PipelineStage

/**
 * The stages of korogue's standard turn pipeline, and the ordering constraints between them
 * (krogue-32d). Each built-in system declares one; a game that replaces a built-in declares the
 * same stage on its stand-in and inherits the same checks.
 *
 * Only genuine data dependencies are encoded. A same-tick write/read is *necessary* for an edge but
 * not sufficient: the missed read must change **behavior** — not leave merely inert-stale data, and
 * not just shift timing by a tick. Anything absent is deliberately unordered: [PICKUP] and [COMBAT]
 * carry no constraint between them because they touch different state, which is why the demo can pick
 * up before resolving combat while the Rogue port does the reverse and both are correct. Adding a
 * constraint that merely reflects one game's habit would reject the other's legitimate pipeline.
 *
 * Two same-tick relationships are therefore left as documented *non-edges* rather than constraints:
 * - [COMBAT] consumes the `AttackIntent` [RANGED_ATTACK] produces, but that is ordered transitively
 *   in the standard pipeline via [MOVEMENT]; a partial pipeline lacking that chain would resolve a
 *   ranged hit one tick late — a timing wobble, not a wrong result.
 * - [PERCEPTION] reads the entity set [COMBAT] prunes (a slain monster it would otherwise reveal),
 *   but a just-killed id left in `Perceived.entities` for one tick is inert: consumers ask "is this
 *   *live* entity perceived?" (the renderer iterates live entities and calls `Perceived.sees`), so a
 *   despawned id is never queried.
 * Both shipped pipelines order these the safe way regardless; encoding either would risk rejecting a
 * legitimate pipeline for no behavioral gain.
 *
 * [SCHEDULE] is likewise unconstrained despite both consumers registering it first. "Timed effects
 * resolve at the top of the turn" is a design convention, not a dependency — a game that wants fuses
 * to burn down *after* the player's move is unusual, not wrong, and the engine has no business
 * failing it.
 */
enum class StandardStage : PipelineStage {
    /**
     * Timed effects fire (regen, hunger, fuses). Unconstrained — see the class doc.
     */
    SCHEDULE,

    /**
     * Ranged attackers commit to an `AttackIntent` before AI decides moves, so [BEHAVIOR] can see
     * the intent and skip moving that entity (krogue-4tn).
     */
    RANGED_ATTACK,

    /** AI resolves each `Behavior` to a `MoveIntent`. Must see [RANGED_ATTACK]'s intents first. */
    BEHAVIOR {
        override val runsAfter: Set<PipelineStage> get() = setOf(RANGED_ATTACK)
    },

    /** `MoveIntent`s resolve into actual movement, raising `AttackIntent` on a blocked bump. */
    MOVEMENT {
        override val runsAfter: Set<PipelineStage> get() = setOf(BEHAVIOR)
    },

    /** An entity standing on a `Portal` is sent through it — after it has had a chance to move. */
    PORTAL {
        override val runsAfter: Set<PipelineStage> get() = setOf(MOVEMENT)
    },

    /** Items on the tile the mover just stepped onto are taken — so, after it stepped. */
    PICKUP {
        override val runsAfter: Set<PipelineStage> get() = setOf(MOVEMENT)
    },

    /** `AttackIntent`s resolve into damage. Consumes what [MOVEMENT]'s bumps raised. */
    COMBAT {
        override val runsAfter: Set<PipelineStage> get() = setOf(MOVEMENT)
    },

    /**
     * Each active zone's `lightMap` is recomputed from its emitters. Runs after [MOVEMENT] and
     * [PORTAL] because it reads *moved* state in the same tick: an emitter's [Position] is its light
     * origin (the player's carried lantern moves every turn), and the set of zones lit tracks
     * `simulatedZones()`, which [PORTAL] shifts when the player changes level. Register lighting
     * before movement and the light lags the player by a tick, every turn, with no error — the same
     * silent staleness [PERCEPTION] guards against, one layer earlier.
     *
     * Deliberately *not* after [PICKUP]/[COMBAT]: those can add or remove an emitter (a picked-up
     * torch, a slain light-bearer), but no shipped content does, and coupling the presentation
     * stages to every world-mutation stage would over-constrain — the every-turn dependency is the
     * moving *origin*, which [MOVEMENT] covers.
     */
    LIGHTING {
        override val runsAfter: Set<PipelineStage> get() = setOf(MOVEMENT, PORTAL)
    },

    /**
     * Each observer's `Perceived` is cached. Depends on three same-tick writes: [LIGHTING]'s
     * `lightMap` (ADR-0015 — the `Sight` sense reveals only lit cells), the observer's [Position]
     * that [MOVEMENT] writes (line-of-sight is cast from it), and the zone membership [PORTAL]
     * changes. The [LIGHTING] edge is the one that motivated the whole check; the [MOVEMENT]/[PORTAL]
     * edges are named directly rather than left to ride transitively through [LIGHTING], so a game
     * with perception but no lighting stage still gets them. It is deliberately *not* ordered after
     * [COMBAT] even though it also reads the entity set combat prunes — that staleness is inert (see
     * the class doc).
     */
    PERCEPTION {
        override val runsAfter: Set<PipelineStage> get() = setOf(LIGHTING, MOVEMENT, PORTAL)
    },
    ;

    override val id: String get() = name
}
