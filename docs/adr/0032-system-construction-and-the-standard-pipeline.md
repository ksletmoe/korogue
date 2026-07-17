# ADR-0032: one system-construction convention, and a validated standard pipeline

- **Status:** Accepted
- **Date:** 2026-07-16

## Context

Three related gaps in the engine's system layer surfaced together while hardening the
public API for 1.0 (beads krogue-cjv, krogue-32d, krogue-elm):

1. **The built-in `System` constructors each took their dependencies a different way.**
   Terrain arrived as a bare `Map<String, Zone>` in some (`MovementSystem`,
   `LightingSystem`, `RangedAttackSystem`) and as the whole `GameWorld` in others
   (`PortalSystem`, `PerceptionSystem`), with no rule for which. Registry access was a
   decomposed `(String) -> T` resolver lambda in some and absent in others. Zone-scoped
   systems took a *separate* optional `activeZones: (() -> Set<String>)?` provider that
   every caller filled with `world::simulatedZones` by hand — even the systems that
   already received a `GameWorld` and could have read `simulatedZones()` themselves. A
   consumer instantiating the pipeline faced an arbitrary grab-bag with no convention to
   generalize from.

2. **No engine-provided pipeline: every consumer hand-wired the built-ins in a
   correctness-critical order.** The demo's `registerSystems()` was the only reference,
   and the order was load-bearing in ways nothing enforced: `PerceptionSystem` must run
   *after* `LightingSystem` (its `Sight` sense reads the light map that tick produced);
   `MovementSystem` after `BehaviorSystem` (consumes its `MoveIntent`s); `CombatSystem`
   after `MovementSystem` (consumes the `AttackIntent`s a bump raises). Register
   perception before lighting and you get visibility that is a turn stale — a **silent**
   correctness bug, not a crash. For a "reusable, extensible game engine," the very first
   thing every consumer had to do was re-derive this wiring from the demo by hand.

3. **`PerceptionSystem` lived in `systems/`, away from the rest of the perception
   feature** (`PerceptionModel`, `Perceived`, `Sense`, and — after ADR-0031 — `ZoneFog`),
   while `SchedulerSystem` already lived in `schedule/` with `Scheduler`. The engine mixed
   layer-based and feature-based packaging with no rule (krogue-elm).

These are one design problem: what is the shape of a built-in system, and how does a
consumer assemble the built-ins safely. All three are public API that 1.0 freezes.

## Decision

**One construction convention for the built-in systems.** A zone-scoped system takes the
`GameWorld` and reads both terrain (`.zones`) and active-zone scope (`.simulatedZones()`)
from it; the per-system `activeZones` knob is gone. Zone scope is the world's
`SimulatedZonePolicy` (ADR-0021), which is where that decision already lived — a system no
longer carries its own copy. A system that resolves ids takes the typed `Registry<T>` it
resolves through, not a bare resolver lambda. A consumer can now read one built-in's
constructor and predict the others'. `Registry` gains a public `Registry.of(...)` factory
so a system can be tested in isolation without standing up a whole `GameModule`.

**A standard pipeline as one call.** `GameWorld.installStandardSystems(module, …)`
registers the built-ins in their known-good order and hands back the instances (so a host
can drive lighting/perception once at startup). It is a *baseline, not a mandate*: a game
that wants korogue's default simulation takes it; a game that diverges builds its pipeline
by hand.

**Ordering is enforced, for any pipeline.** The ECS core gains a `PipelineStage`
(a named stage plus the stages that must precede it) and a `Staged` marker interface;
`World.validateSystemOrder()` checks the registered `Staged` systems against their
constraints and `World.tick()` calls it on the first tick after any registration. korogue's
concrete stages and their constraints live in `pipeline.StandardStage`. Crucially the check
keys on the **stage, not the class**: a game that replaces a built-in — as the Rogue port
replaces `CombatSystem` with `RogueCombatSystem` — declares the matching stage and is held
to the same constraints, so a hand-built pipeline gets the same guarantee as an installed
one.

**The ordering is a *partial* order.** Only genuine data dependencies are encoded, and a
same-tick write/read is *necessary but not sufficient* — the missed read must change
**behavior**, not leave merely inert-stale data or shift timing by a tick. `PICKUP` and
`COMBAT` carry no constraint between them because they touch different state — the demo
picks up before resolving combat, the Rogue port does the reverse, and both are correct.
`SCHEDULE` is unconstrained despite both consumers registering it first, because "timers
resolve at the top of the turn" is a convention, not a dependency. Two same-tick
relationships are also left as documented non-edges because their staleness is inert or
mere timing, not wrong behavior: `COMBAT` consuming `RANGED_ATTACK`'s intent (transitively
ordered via `MOVEMENT` in the standard pipeline; a one-tick-late ranged hit otherwise), and
`PERCEPTION` reading the entity set `COMBAT` prunes (a despawned id in `Perceived.entities`
is never queried — consumers ask whether a *live* entity is perceived). Encoding a
constraint that isn't a real behavioral dependency would reject a legitimate pipeline.

**`PerceptionSystem` moves to `perception/`** (krogue-elm), applying a single packaging
rule: `components/` and `systems/` are the catalog of general-purpose built-ins, but a
feature with its own model and policy seam (`perception`, `schedule`) owns its whole slice
— model, components, *and* system. Under that rule `schedule/SchedulerSystem` was already
right and only `PerceptionSystem` was misplaced.

## Consequences

- The built-in constructors are source-breaking changes, done once before 1.0 freezes
  them. All in-repo call sites (engine tests, the demo) and the Rogue port were updated in
  lockstep.
- A newcomer stands up the standard simulation with one documented call and can read any
  built-in's constructor to predict the rest.
- A mis-ordered pipeline now fails loudly on turn one — with a message naming both systems
  and their stages — instead of producing quietly wrong results forever. This holds for
  the Rogue port's bespoke 21-system pipeline too, because its `RogueCombatSystem` /
  `RoguePickupSystem` declare `COMBAT` / `PICKUP`.
- The mechanism is generic (`ecs/`), the vocabulary is korogue's (`pipeline/`), so a game
  could define its own stage set without touching the ECS core.
- `installStandardSystems` fits a game that wants the default simulation and grows *less*
  applicable the more a game diverges. The Rogue port uses only four built-ins and
  interleaves ~17 of its own, so it does **not** call the helper — it builds its pipeline
  by hand and relies on the validation, which is the supported path, not a fallback. That
  hand-built pipeline is exercised headlessly: the Rogue port extracted its assembly into
  an `installRoguePipeline(...)` function (its `registerSystems` is now a thin call to it),
  so a test builds the real dungeon + module without a GL window, assembles via the *same*
  function the game uses, and asserts `validateSystemOrder()` passes — the validation is not
  GL-coupled, and neither is the assembly; it was only ever *positioned* inside the game's
  GL lifecycle.

## Alternatives considered

- **A `produces`/`consumes` resource model instead of stages** — rejected because
  `AttackIntent` flows twice per tick (`RangedAttackSystem` produces it, `BehaviorSystem`
  reads it, `MovementSystem` produces it again, `CombatSystem` consumes it). A
  "producers before consumers" rule would demand movement run before behavior, which is
  backwards. Stages with an explicit partial order express the real constraints; a flat
  resource model does not.
- **A phase-slot builder** (`pipeline.standard().replace(COMBAT, x).remove(PORTAL)…`) —
  rejected for the Rogue port's shape: expressing 17 inserts across a dozen slots as
  edits-to-a-baseline reads worse than the explicit, per-line-commented top-to-bottom list
  it has now.
- **`installStandardSystems` as the only supported path** — rejected; it would force the
  exemplary consumer, whose pipeline is mostly bespoke, onto an ill-fitting API. The
  validation, not the installer, is the universal guarantee.
- **Keeping the per-system `activeZones` knob** — rejected; it duplicated a decision the
  `SimulatedZonePolicy` already owns and every caller filled the same way.
- **Leaving `PerceptionSystem` in `systems/`** — rejected; it split the perception feature
  across two packages with no rule, the same discoverability cost ADR-0031 addressed for
  `Game`/`ZoneFog`.
