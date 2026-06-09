# ADR-0007: The game world composes the ECS world

- **Status:** Accepted
- **Date:** 2026-06-04

## Context

Phase 4a introduced `com.sletmoe.korogue.ecs.World` — a generic entity/system
container (`spawn`/`despawn`, the `set`/`update`/`remove` mutation seam,
index-free queries, `tick`). It knows nothing about korogue.

The pre-ECS game state still lives in `com.sletmoe.korogue.world.World`: a
`Map<String, Zone>` + `currentZoneId` + the `World.create { zone(...) { … } }`
builder DSL. Each `Zone` owns its terrain (`Grid<Tile>`) **and** its dynamic
occupants — `_creatures: MutableList<Creature>`, `lightSources`, and a derived
`lightMap` — with behavior baked into the legacy `Creature` / `MovableEntity` /
`LightSource` classes (`Creature.update` branches on `name`; `moveInZone` does
walkability + attack + light recalc inline).

Phase 4b migrates those occupants onto ECS entities (the `Position` /
`ZoneMember` / `Renderable` / `Health` / `Named` / `Player` components from step 2)
and systems. Before steps 3–7 can be sequenced we must settle how the two `World`
types relate — two types literally named `World` is itself part of the problem.
Options: **(a)** the game world *composes* the ECS world; **(b)** *merge* them into
one; **(c)** `ecs.World` *gains* a zone registry.

Two constraints frame the choice:

- **The boundary principle (ARCHITECTURE.md):** `ecs.World` is the generic core
  "others build on." Zones and tiles are korogue game concepts.
- **Preserve the `World.create { zone {} }` builder DSL** — the ergonomic entry
  point used by `MyGame` and the tests.

## Decision

**(a) The game world composes the ECS world.** Keep `ecs.World` generic and
game-agnostic. Introduce a game-level aggregate — rename `world.World` to
**`GameWorld`** to end the `World` / `World` name collision — that *holds* an
`ecs.World` and adds the korogue-specific zone registry: the terrain map
(`zones`), `currentZoneId` / `currentZone`, and the existing builder DSL.

Entities are owned solely by the `ecs.World`. A `Zone` becomes a **terrain**
record (its `Grid<Tile>`, bounds, tile walkability) and stops owning occupants:
creatures, the player, and light emitters become ECS entities tagged with
`ZoneMember(zoneId)` + `Position`, queried per current zone. Dynamic per-zone
derived state (the `lightMap`) becomes the output of a system (step 5), not
hand-maintained `Zone` state.

## Consequences

Easier:

- The reusable ECS core stays pure — no tile/zone concepts leak in; it remains
  testable in isolation and reusable beyond korogue.
- One owner for all game state (`ecs.World`): the mutation chokepoint (ADR-0005)
  and the immutable-snapshot model (ADR-0003) cover creatures, light, and the
  player uniformly — exactly what 4f save/load needs.
- Multi-zone becomes natural: occupants carry `ZoneMember`, so switching zones is
  a query filter, not data migration between `Zone` objects.

Harder / accepted trade-offs:

- One extra indirection: game code reaches entities via `gameWorld.ecs` (a thin
  accessor) rather than `zone.creatures`.
- Migration churn across steps 3–7, plus a rename touching `MyGame`,
  `KotileZoneRenderer`, and tests.

This shapes the remaining 4b steps:

- **s3** — strip `Zone` to terrain; add `GameWorld` composing `ecs.World`; spawn
  former creatures as entities (`ZoneMember` + `Position` + `Renderable` +
  `Health` + `Named`, `Player` marker).
- **s4** — `KotileZoneRenderer` reads `ecs.World` queries (entities in the current
  zone) instead of `zone.creatures`.
- **s5** — `LightEmitter` component + `LightingSystem` produces the per-zone
  `lightMap`.
- **s6** — `MoveIntent` + `MovementSystem` / `CombatSystem` replace
  `Creature.moveInZone`.
- **s7** — `Behavior` + `BehaviorSystem` replace `Creature.update`; delete
  `Creature` / `MovableEntity` / `LightSource` / `world.Entity` / `ZonalPosition`.

## Alternatives considered

- **(b) Merge into one `World`** — a single class owning entities, systems, zones,
  and `currentZoneId`. Rejected: bakes korogue's zone/tile model into the generic
  ECS container, breaking the boundary principle, hurting isolated testability,
  and giving the reusable core a game-specific surface. Its only win — "one object
  to pass around" — is recovered by composition plus a thin accessor.
- **(c) `ecs.World` gains a zone registry** — add `zones` / `currentZoneId`
  directly to `ecs.World`. Rejected for the same boundary reason as (b): the
  generic core shouldn't know what a `Zone` is. It is (b) wearing a smaller hat.
- **Keep both `World`s side by side, unrenamed** — rejected: the duplicate type
  name is an active source of import confusion and is the friction this ADR
  exists to remove.
