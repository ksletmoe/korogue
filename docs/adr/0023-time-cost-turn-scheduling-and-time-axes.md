# ADR-0023: Time-cost turn scheduling (energy model) and the game-time vs animation-time split

- **Status:** Proposed
- **Date:** 2026-07-04

## Context

Today the engine offers two turn policies via the `GameLoop` seam (krogue-lhw):
`RealTimeLoop` (fixed timestep) and `TurnBasedLoop` (turn-on-input). Both assume
**one `world.tick()` = one round in which every creature acts once**;
`BehaviorSystem` gives every active creature an intent each tick, and `actChance`
is the only (crude, probabilistic) speed control.

A future game wants **variable action costs**: walking a tile takes ~1s, a sword
swing ~0.5s, a rapier stab ~0.25s, swapping armor ~5s, expressed in game *ticks*.
An actor should act only when the world clock reaches its next-action time; a slow
action means that actor is simply absent from play for many ticks while faster
actors act repeatedly. This is the classic roguelike **energy / action-cost**
(time-unit) model — Brogue, DCSS ("auts"), Angband, ADOM, Cogmind.

Two forces make this a real decision rather than a bolt-on:

1. **What "advance the world" means changes.** "Everyone acts once per tick" must
   become "advance the clock to the next actor; that actor acts and is recharged
   by its action's cost."
2. **Two independent time axes must not be conflated.** Discrete *game time*
   (advanced by actions, frozen while awaiting input, deterministic, saved) is a
   different thing from continuous *presentation time* (wall-clock, drives idle
   animation like flickering torches and shimmering walls, never affects game
   state, runs even while game time is frozen). The engine already embodies this
   split — `world.tick()` advances `TickContext.turn`, while `drawFrame(elapsedMs)`
   → kotile `AnimatedAsciiTile` animates on wall-clock `elapsedMs` every rendered
   frame — but it has never been named as an invariant, and the energy model makes
   getting it right load-bearing.

## Decision

Adopt the **time-ordered actor scheduler** (event-driven "next-event" model) as an
optional third turn policy, and make the two time axes an explicit invariant.

### Time axes (invariant)

- **Game time — ticks.** A monotonic `Long` clock advanced *only* by action costs.
  Deterministic and serialized. Frozen while the player is due and has committed no
  action. The daemon `Scheduler` (hunger/regen/status, krogue-6uq) is keyed on this
  same clock, so effects and actor turns share one axis.
- **Presentation time — wall-clock ms.** Continuous, advanced every rendered frame
  via `elapsedMs`, drives ambient animation (flicker/shimmer). **Never serialized,
  never reads or writes game state.** A save/load resumes identical game state
  regardless of animation phase. This is what already lets torches flicker while a
  turn-based world sits frozen; the energy model keeps it exactly as-is.
- **(Future) Event-animation time.** Transient visual sequences bound to game events
  (a thrown potion's arc, a hit-flash) that the presentation layer plays over
  wall-clock and that may briefly gate input via a small animation queue drained
  before the next game step is shown. Out of scope here; noted so it is not
  confused with either axis above.

### The actor scheduler (game time)

- **`Actor(nextActTime: Long, speed: Int)`** component (ECS-native so it serializes
  with the entity and cannot desync from spawn/despawn; a priority heap is a later
  optimization, correctness only needs "min `nextActTime` among live actors"). The
  player is just another `Actor`.
- **Action cost** is `baseTicks(action) * NORMAL_SPEED / speed` — higher speed →
  smaller cost → more frequent turns. `baseTicks` comes from a small per-action
  table plus per-item data (e.g. a weapon's `swingTicks`), resolved by the acting
  system that already knows what happened.
- **Step loop** (a new `SchedulerDrivenLoop`, sibling to Real-Time/Turn-Based):

  ```
  fun stepUntilPlayerReady(world):
    loop:
      val next = liveActors.minBy { it.nextActTime } ?: return
      clock = next.nextActTime              // jump game time forward
      daemonScheduler.advanceTo(clock)      // fire hunger/regen/status due by now
      if next.isPlayer && player.committedAction == null:
        return                              // yield to input; game time frozen
      val cost = act(world, next)           // decision -> intents -> action systems; ticks spent
      next.nextActTime = clock + cost       // recharge by the action's cost
  ```

- **`act(world, actor)`** keeps korogue's intent→system pattern but drives it one
  actor at a time: tag the actor with a transient `Acting` marker; `BehaviorSystem`
  (or player input) produces an intent only for the `Acting` entity; the action
  systems (`Movement`, `Combat`, `Pickup`, `Portal`) resolve it as usual (only that
  entity has an intent) and report the cost (via an `Acted(ticks)` marker the loop
  reads, or a `costOf` lookup on the resolved outcome). Ties at equal `nextActTime`
  break deterministically by entity id.

### System pipeline splits by axis

- **Action systems** (game time; run per actor-step inside the loop): Behavior,
  Movement, Combat, Pickup, Portal, daemon Scheduler.
- **Derived/presentation systems** (presentation time; run once per frame before
  draw, not per micro-step): Lighting, Perception. They are already documented as
  "derived state recomputed each tick"; here they recompute per *frame* (or on
  observable change), which avoids redoing FOV/lighting on every sub-second tick.

### Player input

The player is an `Actor`. When the loop reaches the player with no committed action
it yields; input sets `player.committedAction` (an intent + its implied cost); the
loop resumes, charges the cost, and advances until the player is due again. One
keypress can thus advance the clock by, e.g., 100 ticks, during which faster
monsters act several times. This replaces `TurnBasedLoop`'s `requestTurn`/coalesce.

### Relationship to the existing (sweep) loops

The classic "every creature acts once per tick" model — `RealTimeLoop`/
`TurnBasedLoop` today, realized by `BehaviorSystem`'s sweep — is **not** derived
from the actor queue and does not fold into it. It stays a separate,
hot-swappable policy behind the `GameLoop` seam. The sweep needs no per-actor
time state, no queue, and no cost table, and a game that just wants classic Rogue
turns should not pay for the energy machinery. The event-queue scheduler is,
however, the strict **generalization** of the sweep: with uniform action cost and
a stable tie-break it reproduces "everyone acts once per round," so a single
substrate *could* implement the sweep as a uniform-cost scheduler — the engine
simply does not force that, keeping the simple case simple.

Conceptually the seam now spans two orthogonal axes: the **turn-granting model**
(sweep vs event-queue) and the **advance trigger** (on committed input vs on a
wall-clock timestep). Today's loops are (sweep × input) and (sweep × wall-clock);
the scheduler adds the event-queue model, itself drivable on input (a turn-based
energy roguelike: advance to the next event, pause when the player is due) or on
wall-clock (real-time-with-energy: the clock tracks real time). `SchedulerDrivenLoop`
is thus a sibling policy selected per game, not a parent of or replacement for the
sweep loops.

## Consequences

- Variable action costs, per-entity speed, haste/slow, and "5s armor swap = absent
  for 5000 ticks" all fall out naturally; no per-tick sweep over idle actors.
- Reuses the daemon `Scheduler` (same clock), the intent→system pattern, existing
  `MoveIntent`/`AttackIntent`, and the `GameLoop` seam — additive, not an engine
  rewrite. Ships **off by default**: existing every-turn games keep `RealTimeLoop`/
  `TurnBasedLoop` unchanged (ADR-0014 lets a game swap the policy).
- Forces the action-vs-derived system split to be explicit (which systems are game
  time vs presentation time). This is the main new discipline it imposes.
- `BehaviorSystem` gains an "act for a single entity" path (via the `Acting` marker)
  in addition to its current sweep; `actChance` becomes redundant under this policy.
- Determinism/saves hold: `Actor.nextActTime` + the `clock` serialize; tie-break is
  stable; animation (wall-clock) is never persisted.
- Animation is confirmed **orthogonal**: ambient animation stays on wall-clock in
  the renderer and needs no scheduler awareness. Only event-animations (future)
  ever couple to game events, and via a presentation-side queue, not this scheduler.

## Alternatives considered

- **Energy accumulation (per-tick sweep).** Each tick every actor gains `speed`
  energy; any actor over threshold acts and pays cost. Fits "run all systems each
  tick," but runs the whole pipeline on many empty sub-ticks and still needs the
  action-vs-derived split. Rejected in favor of event-driven, which the existing
  turn-keyed `Scheduler` already models and which wastes no cycles.
- **One unified event queue for actors *and* daemons.** Elegant single structure,
  but folds AI + action execution into `TimedEffect` callbacks, bypassing the ECS
  action systems and complicating the "yield to player" pause. Rejected: keep a
  `TurnScheduler` for actors and the daemon `Scheduler` for effects, sharing one
  clock.
- **Actor turn state in a side structure (heap) rather than a component.** A heap is
  a fine optimization later, but the source of truth stays on the `Actor` component
  so it serializes with the entity and never desyncs on spawn/despawn.
- **Folding animation onto the game clock.** Would freeze idle animation whenever
  the game is frozen (no torch flicker while deliberating) and risk animation phase
  leaking into saved/deterministic state. Rejected; the wall-clock axis is kept
  strictly presentation-only.
