# krogue Architecture

> Durable design knowledge (what is true now). The live work backlog lives in
> **beads** (`bd list`); current state snapshots live in [STATUS.md](STATUS.md); the
> **why** behind major decisions is recorded in [adr/](adr/).

## The two libraries

krogue is a general-purpose, extensible roguelike **game engine** (a library other
roguelike-builders depend on), built on top of **kotile** (`~/development/kotile`),
a general-purpose libGDX tile **renderer**.

- **kotile** — tile rendering, layered tilemaps, animation, camera/viewport, input,
  fonts. The rendering primitive layer. Reusable by any libGDX tile game.
- **krogue** — world/zone model, entities, FOV, lighting behavior, AI, generation,
  the turn loop, game UI. The roguelike game-engine layer.

### Boundary principle

**If a non-roguelike libGDX game could plausibly use it, it belongs in kotile.**
Roguelike-specific helpers (FOV, lighting application) belong in krogue.

During development krogue consumes kotile via Gradle `includeBuild("../kotile")`
(no version bumps while iterating), with a `dependencySubstitution` redirecting
`com.sletmoe:kotile` → `project(":library")`.

## Entity model (decided)

krogue uses a **lightweight component model** ("Option B") — not pure ECS (no
archetype/column storage, no Fleks/Artemis), not OOP inheritance.

- An `Entity` is a thin container of components keyed by concrete type (one per type).
- Components are immutable data classes; systems are plain classes/functions that
  query entities by component set.
- Typed access via Kotlin reified generics.
- Code-first: consumers extend by writing Kotlin. A data-driven (JSON/YAML) content
  layer is explicitly out of scope for v1.

Rationale: hundreds-of-entities scale doesn't justify pure-ECS overhead; roguelikes
need runtime mutability (status effects, polymorph) which composition handles well.

## ECS core (`com.sletmoe.krogue.ecs`)

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

### Locked decisions (do not relitigate)

_Rationale and alternatives for each are in the ADRs: 0002 (entity model), 0003
(immutable), 0004 (index-free), 0005 (mutation chokepoint)._

1. **Immutable everywhere.** Components are deeply-immutable data classes. "Mutate" =
   produce a new value and replace it via `World.update`. This makes entity state
   snapshottable — the basis for change-tracking / undo / save-load / net-diff. Copies
   are cheap at krogue's scale; what would change at much larger scale is the query
   index, not the copy model.
2. **Index-free queries.** Scan all entities; add a component-type index behind the
   same methods only if profiling demands it at much larger scale.
3. **Enforced mutation chokepoint.** `Entity` is read-only; all writes go through
   `World`, so 4f change-tracking is not a breaking change.
4. **Serialization convention now, registry later.** Components hold serializable data;
   for non-serializable things (behavior/light-calculator objects) store a stable
   string id (e.g. `calculatorId`, `strategyId`) resolved via a registry at load. The
   registry itself is deferred to the save/load phase (4f).

### Standard components (`com.sletmoe.krogue.components`)

`Position(x,y)`, `ZoneMember(zoneId)`, `Named(name, description?)`,
`Health(current, max)` (with `alive`/`dead`), `Renderable(glyph, color: NormalizedRgb,
layer: RenderLayer)`, `Player` (marker), `LightEmitter(color: NormalizedRgb, radius,
calculatorId)`, `Behavior(strategyId)`, `Portal(targetZoneId, targetX, targetY)`. Intent
components (transient, consumed by systems each tick): `MoveIntent(dx, dy)`,
`AttackIntent(targetId)`. Colors are `NormalizedRgb` (immutable), not GDX `Color`; the
renderer converts at the draw boundary.

### Systems (`com.sletmoe.krogue.systems`)

Registered on the ECS `World` and run in order each `tick`: behavior → movement → portal
→ combat → lighting. Zone-scoped systems (`BehaviorSystem`, `LightingSystem`) take an
`activeZones` provider — `GameWorld.simulatedZones()`, default `{ currentZoneId }` — so
only the active zone(s) are simulated (ADR-0008); dormant zones freeze.

- **`BehaviorSystem(resolveStrategy, activeZones?)`** — runs each `Behavior` entity's
  strategy (via `BehaviorStrategies`: `wander`, `hunt-player`) to emit a `MoveIntent`.
  The resolver is injectable for testing (4b-s7).
- **`MovementSystem(zones)`** — consumes `MoveIntent`s: step onto walkable, unoccupied
  terrain; bump into a (non-portal) occupant → emit `AttackIntent`; into a wall → no-op (4b-s6).
- **`PortalSystem(gameWorld)`** — sends the player through a `Portal` it stands on: moves
  it to the target zone/position and switches `currentZoneId` so the active zone follows
  the player (4e, ADR-0008).
- **`CombatSystem(damage)`** — consumes `AttackIntent`s, applies damage to the target's
  `Health`, then despawns dead non-player entities (player death is out of scope —
  krogue-4zi) (4b-s6).
- **`LightingSystem(zones, activeZones?)`** — recomputes each active zone's `lightMap` from
  its `LightEmitter` entities (replaced `Zone.recalculateLightMap`; 4b-s5). `calculatorId`
  is resolved via `LightCalculators` — the minimal stand-in for the component registry
  deferred to save/load (4f).

## Kotlin gotchas encountered (relevant to ongoing ECS work)

- Generic-arity overloads (`entitiesWith<A>` vs `<A,B>`) erase to the same JVM
  signature — disambiguate with `@JvmName`.
- A **file-private** type used as a reified type argument to an inline function from
  inside a lambda fails to compile; use `internal` instead.
