# korogue Architecture

> Durable design knowledge (what is true now). The live work backlog lives in
> **beads** (`bd list`); current state snapshots live in [STATUS.md](STATUS.md); the
> **why** behind major decisions is recorded in [adr/](adr/).

## The two libraries

korogue is a general-purpose, extensible roguelike **game engine** (a library other
roguelike-builders depend on), built on top of **kotile** (the `:kotile:library`
subproject in this repo), a general-purpose libGDX tile **renderer**.

- **kotile** — tile rendering, layered tilemaps, animation, camera/viewport, input,
  fonts. The rendering primitive layer. Reusable by any libGDX tile game.
- **korogue** — world/zone model, entities, FOV, lighting behavior, AI, generation,
  the turn loop, game UI. The roguelike game-engine layer.

### Boundary principle

**If a non-roguelike libGDX game could plausibly use it, it belongs in kotile.**
Roguelike-specific helpers (FOV, lighting application) belong in korogue.

kotile lives in this repo as Gradle subprojects (`:kotile:library`, `:kotile:demo`),
folded in from its former sibling repo. It keeps its own maven coordinates
(`com.sletmoe:kotile`, published from `:kotile:library`); the engine depends on
`project(":kotile:library")`. Atomic cross-cutting commits, one CI, no composite-build
substitution.

## Entity model (decided)

korogue uses a **lightweight component model** ("Option B") — not pure ECS (no
archetype/column storage, no Fleks/Artemis), not OOP inheritance.

- An `Entity` is a thin container of components keyed by concrete type (one per type).
- Components are immutable data classes; systems are plain classes/functions that
  query entities by component set.
- Typed access via Kotlin reified generics.
- Code-first: consumers extend by writing Kotlin. A data-driven (JSON/YAML) content
  layer is explicitly out of scope for v1.

Rationale: hundreds-of-entities scale doesn't justify pure-ECS overhead; roguelikes
need runtime mutability (status effects, polymorph) which composition handles well.

## ECS core (`com.sletmoe.korogue.ecs`)

The implemented core (one entity/system container others build on):

- **`Component`** — empty marker interface.
- **`EntityId`** — `@JvmInline value class(Long)`; monotonic, never reused (matters
  for save/load stability).
- **`Entity`** — read-only to callers; minted by `World.spawn`. Reified accessors:
  `get<T>(): T?`, `require<T>(): T`, `has<T>(): Boolean`, plus `components`. Keyed by
  **concrete** class (no polymorphic lookup).
- **`System`** (`fun interface`) + **`TickContext(turn, elapsedMs, random)`** — a unit
  of behavior run once per `World.tick`, in registration order.
- **`World`** — owns entities + systems.
  - Lifecycle: `spawn`, `despawn`, `get`, `contains`, `entityCount`, `entities()`.
  - Mutation seam (the single chokepoint): `set(id, component)`, `update<T>(id){…}: T?`,
    `remove<T>(id): T?`.
  - Queries: `entitiesWith<A>()` / `<A,B>()` / `<A,B,C>()` — **index-free** O(n) scan
    over a **snapshot** (safe to spawn/despawn during iteration).
  - `events: EventBus` — pub/sub for decoupled notifications; `tick` dispatches it
    after all systems run (4c, ADR-0010).

### Locked decisions (do not relitigate)

_Rationale and alternatives for each are in the ADRs: 0002 (entity model), 0003
(immutable), 0004 (index-free), 0005 (mutation chokepoint)._

1. **Immutable everywhere.** Components are deeply-immutable data classes. "Mutate" =
   produce a new value and replace it via `World.update`. This makes entity state
   snapshottable — the basis for change-tracking / undo / save-load / net-diff. Copies
   are cheap at korogue's scale; what would change at much larger scale is the query
   index, not the copy model.
2. **Index-free queries.** Scan all entities; add a component-type index behind the
   same methods only if profiling demands it at much larger scale.
3. **Enforced mutation chokepoint.** `Entity` is read-only; all writes go through
   `World`, so 4f change-tracking is not a breaking change.
4. **Serialization convention now, registry later.** Components hold serializable data;
   for non-serializable things (behavior/light-calculator objects) store a stable
   string id (e.g. `calculatorId`, `strategyId`) resolved via a registry at load. The
   registry itself is deferred to the save/load phase (4f).

### Standard components (`com.sletmoe.korogue.components`)

`Position(x,y)`, `ZoneMember(zoneId)`, `Named(name, description?)`,
`Health(current, max)` (with `alive`/`dead`), `Renderable(glyph, color: NormalizedRgb,
layer: RenderLayer)`, `Player` (marker), `LightEmitter(color: NormalizedRgb, radius,
calculatorId)`, `Behavior(strategyId)`, `Portal(targetZoneId, targetX, targetY)`. Intent
components (transient, consumed by systems each tick): `MoveIntent(dx, dy)`,
`AttackIntent(targetId)`. Colors are `NormalizedRgb` (immutable), not GDX `Color`; the
renderer converts at the draw boundary.

### Systems (`com.sletmoe.korogue.systems`, `.schedule`, `.perception`)

Registered on the ECS `World` and run in registration order each `tick`. **One
construction convention** (ADR-0032, krogue-cjv): a zone-scoped system takes the
`GameWorld` and reads terrain (`.zones`) and active-zone scope (`.simulatedZones()`,
default `{ currentZoneId }`, ADR-0008/0021) from it — there is no per-system `activeZones`
knob; a system that resolves ids takes the typed `Registry<T>` it resolves through. Read
one built-in's constructor and you can predict the others'.

Feature packages own their whole slice (model + components + system), so `PerceptionSystem`
lives in `perception/` and `SchedulerSystem` in `schedule/`, while the general-purpose
built-ins stay in `systems/` (krogue-elm).

- **`BehaviorSystem(gameWorld, strategies)`** — resolves each `Behavior` entity's
  `strategyId` through the `Registry<BehaviorStrategy>` (the AI extension point — see
  "Extending the engine") and runs it to emit a `MoveIntent`. Built-ins
  (`BehaviorStrategies.kt`): `wander`, `hunt-player`.
- **`MovementSystem(gameWorld)`** — consumes `MoveIntent`s: step onto walkable, unoccupied
  terrain; bump into a (non-portal) occupant → emit `AttackIntent`; into a wall → no-op (4b-s6).
- **`PortalSystem(gameWorld)`** — sends the player through a `Portal` it stands on: moves
  it to the target zone/position and switches `currentZoneId` so the active zone follows
  the player (4e, ADR-0008).
- **`CombatSystem(damage)`** — consumes `AttackIntent`s, applies damage to the target's
  `Health`, then despawns dead non-player entities (player death is out of scope —
  krogue-4zi) (4b-s6).
- **`LightingSystem(gameWorld, calculators, ambientLight?)`** — recomputes each simulated
  zone's `lightMap` from its `LightEmitter` entities (replaced `Zone.recalculateLightMap`;
  4b-s5); resolves `calculatorId` through the `Registry<LightValueCalculator>`.
- **`PerceptionSystem(gameWorld, model)`** (`perception/`) — caches each observer's
  `Perceived` (ADR-0015); must run after lighting.
- **`SchedulerSystem(scheduler, effects)`** (`schedule/`) — fires the timers due this turn,
  resolving effect ids through the `Registry<TimedEffect>`.

**The standard pipeline as one call** (ADR-0032, krogue-32d):
`GameWorld.installStandardSystems(module, …)` registers the built-ins in a known-good order
and returns the instances (a host drives lighting/perception once at startup so the first
frame isn't black). It is a baseline for a game that wants korogue's default simulation, not
a mandate — a game that diverges (the Rogue port uses four built-ins and interleaves ~17 of
its own) builds its pipeline by hand.

**Order is enforced.** A `Staged` system declares a `pipeline.StandardStage`, each stage
naming the stages that must precede it (only real same-tick data dependencies —
`MOVEMENT` after `BEHAVIOR`; `COMBAT`/`PICKUP` after `MOVEMENT`; `LIGHTING` after
`MOVEMENT`/`PORTAL` (it reads the moving lantern's position and the current zone);
`PERCEPTION` after `LIGHTING`/`MOVEMENT`/`PORTAL`; the order is *partial*, so independent
stages like `PICKUP`/`COMBAT` may go either way). `World.tick()` calls `validateSystemOrder()` on the first tick after any
registration and throws `PipelineOrderException` on a violation — a mis-order was previously
a *silent* correctness bug. The check keys on the stage, not the class, so a game replacing
a built-in (Rogue's `RogueCombatSystem` declares `COMBAT`) keeps the guarantee for its
hand-built pipeline.

### Events (`ecs.EventBus`, `com.sletmoe.korogue.events`) — 4c, ADR-0010

`World.events` is a generic pub/sub bus for **decoupled notifications**, distinct from the
intent-component pipeline above: intents drive *intra-tick mechanics* (ordered, consumed,
feed the next system); events fan out *observe-only notifications* delivered **after** the
tick. `publish` only enqueues; `World.tick` calls `dispatch()` once all systems have run, so
subscribers see a consistent end-of-tick world. Handlers are keyed by **concrete event class**
(`subscribe<T>`, mirroring component keying), plus `subscribeAll` for catch-all observers;
`subscribe` returns a `Subscription` with idempotent `cancel()`. Dispatch drains the queue
fully (cascades resolve in-tick) in deterministic order. Events are immutable, self-contained
snapshots (the described entity may already be gone by delivery). The bus is **transient** —
not part of save state. Game events: `EntityDamaged`, `EntityDied` (emitted by `CombatSystem`),
`ZoneChanged` (emitted by `PortalSystem`). Handlers are observers — drive world changes through
systems/components (ADR-0005), not handlers.

## Extending the engine — pluggable strategies, calculators, components (4d, ADR-0009)

korogue is **code-first**: a consuming game extends it by writing Kotlin and registering the
pieces on a `GameModule`, the single seam the engine and save codec read from. Three concerns
plug in through the same id → instance shape — name a thing by a stable string id, store that
id in a (serializable) component, and resolve it through the module's narrow registry at run
time. This keeps components serializable (ADR-0009) and the engine extensible without the core
knowing the consumer's types.

**AI behaviour — the worked example (`BehaviorStrategy`, Phase 4d).** The extension point is the
`BehaviorStrategy` fun-interface (`systems/BehaviorStrategy.kt`): `decide(world, self, ctx):
MoveIntent?`. To add an AI:

1. **Write it.** Implement `BehaviorStrategy` — a *pure decision* that reads the world and
   returns a `MoveIntent` (or null to stay put). Don't mutate the world (`BehaviorSystem`
   applies the intent through the mutation seam); draw randomness from `ctx.random` — the
   world's own seeded gameplay stream, since the world owns its RNG (ADR-0025) — so runs
   replay and saves resume mid-stream; keep per-entity state in components, since one instance
   is shared across every entity bearing its id.
2. **Register it** under a stable id on the module:
   `GameModule.engineDefaults().strategy("patrol", PatrolStrategy()).build()`.
3. **Tag entities** with `Behavior("patrol")`. Each tick `BehaviorSystem` resolves the id via
   `module.strategies::resolve` and runs the strategy. Built-ins (`wander`, `hunt-player`) are
   pre-loaded by `engineDefaults()`; registering an existing id overrides it.

The decision surface is currently movement; richer action types (attack, use, cast) would
broaden `decide`'s return type, not the registration path.

**The same shape, two more registries.** *Light calculators* — implement `LightValueCalculator`,
register with `.calculator(id, impl)`, reference via `LightEmitter.calculatorId` (resolved by
`LightingSystem`). *Components* — annotate a `@Serializable` data class implementing `Component`
and register it with `.component<Foo>()` so the CBOR save codec can round-trip it. Resolving an
unknown id fails loudly (a programmer error), and `GameModule` is the one place to check
cross-registry coherence (e.g. a `strategyId` with no registered strategy).

## Kotlin gotchas encountered (relevant to ongoing ECS work)

- Generic-arity overloads (`entitiesWith<A>` vs `<A,B>`) erase to the same JVM
  signature — disambiguate with `@JvmName`.
- A **file-private** type used as a reified type argument to an inline function from
  inside a lambda fails to compile; use `internal` instead.
