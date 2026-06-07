# ADR-0008: Active-only multi-zone simulation; zones persist in one ECS world

- **Status:** Accepted
- **Date:** 2026-06-06

## Context

`GameWorld` already holds a `zones: Map<String, Zone>` registry plus a `currentZoneId`
(ADR-0007), and every occupant is an ECS entity tagged with `ZoneMember(zoneId)`. But
there is no zone-transition feature yet, and the systems were written for the single-zone
demo: they iterate **all** entities across **all** zones every tick. `LightingSystem`
recomputes every zone's `lightMap`; `BehaviorSystem` runs AI for every `Behavior` entity
regardless of zone; `HuntPlayerStrategy` finds "the player" globally with no zone check (a
latent cross-zone bug). Only the renderer scopes to the current zone.

Phase 4e must settle the **simulation model** before transitions are built, because it
shapes how every system iterates. Two questions (from the issue):

1. **What is simulated** when the player is in one zone — only the active zone, or all
   loaded zones?
2. **Do zones persist** across switches (return to a zone and find it as you left it)?

Constraints/forces:
- krogue is small (hundreds of entities), but a multi-zone world multiplies that if every
  zone ticks every frame.
- The roguelike norm is that off-level monsters are frozen until you return — and that
  levels persist.
- The ECS core stays game-agnostic (ADR-0001 boundary): zone concepts must not leak into
  `ecs.World` / `TickContext`.

## Decision

**Active-only simulation, with all zones resident in the single ECS `World`, persisted in
memory and scoped per-tick to the current zone.**

- **One world, entities never leave it on a switch.** Every zone's entities stay in
  `GameWorld.ecs`, tagged by `ZoneMember`. Switching zones is just setting
  `currentZoneId` (and moving the player entity to the destination). Persistence is
  therefore automatic: a zone you leave keeps its exact entity state for your return.
- **Only the current zone is simulated.** Zone-scoped systems take a current-zone provider
  (`() -> String` reading `GameWorld.currentZoneId`) and filter their query to
  `ZoneMember.zoneId == currentZoneId`. Non-current zones are dormant — present but not
  ticked, so their occupants freeze in place.
  - In practice this only needs `BehaviorSystem` and `LightingSystem` to scope to the
    current zone. `MovementSystem`/`CombatSystem` follow for free: intents only originate
    in the current zone (the player is there, and only current-zone AI runs), so no
    off-zone movement or combat is produced. This also fixes the `HuntPlayerStrategy`
    cross-zone bug.
- **A transition is data + a `currentZoneId` switch.** The feature (4e implementation): a
  transition trigger (e.g. a stairs/portal tile or a `Transition(targetZoneId, targetPos)`
  component) sets `currentZoneId` and updates the player's `ZoneMember` + `Position`. The
  trigger's exact shape is an implementation detail, not part of this decision.

## Consequences

Easier:
- Cost scales with the **active** zone, not the whole world — most of the map sits idle.
- Persistence is free; no save/load needed to keep levels stable across visits.
- Off-level monsters freeze, matching roguelike expectations.
- Removes the cross-zone targeting bug as a side effect of scoping.

Harder / accepted trade-offs:
- Zone-scoped systems need a current-zone filter (small: inject the provider, add one
  `zoneId ==` guard). `MovementSystem`/`CombatSystem` are unaffected.
- All zones stay in memory. Fine at krogue's scale; a future huge-world variant could add
  unload/serialize (see alternatives) once 4f exists.
- "Dormant" means literally frozen — no off-screen ecology/clocks. Acceptable for a
  classic roguelike; revisit if background simulation is ever wanted.

## Alternatives considered

- **Always-loaded, simulate every zone each tick** (today's accidental behavior) —
  rejected: cost grows with total world size even though only one zone is visible, cross-
  zone interactions still need guarding, and "frozen off-level" is the behavior we want
  anyway. Its only merit (no scoping code) is marginal.
- **Active-only with unload/serialize of dormant zones** — keep only the current zone in
  memory, serialize others to disk and reload on entry. More memory-efficient for very
  large worlds, but couples directly to 4f serialization, adds load latency and failure
  modes, and is premature at krogue's scale. Defer; revisit as an extension of 4f if a
  world ever outgrows memory.

## Future extension: simulating a zone neighborhood

Active-only is the default, not a ceiling. A plausible future want is simulating the
current zone **plus adjacent ones**, so a monster can chase the player across a transition
instead of freezing at the threshold. To keep that a configuration change rather than a
redesign, the implementation should treat **the set of simulated zones as a seam**: inject
a provider returning the active-zone set (default `{ currentZoneId }`) and have the
zone-scoped systems iterate that set rather than a single id. A "current + adjacent" policy
then only changes what the provider returns.

That mode brings extra requirements, deferred until wanted: a zone-adjacency model (which
zones border which), `HuntPlayerStrategy` (and friends) reaching across zones, and movement
that carries an entity over a boundary (updating `ZoneMember` + `Position` together).
Tracked as a separate enhancement.
