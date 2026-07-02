# ADR-0019: Prefab-based, entity-aware world generation

- **Status:** Accepted
- **Date:** 2026-07-02

## Context

World-gen extensibility today is a single terrain-only seam:

```kotlin
typealias ZoneFeatureGenerator = (Grid<Tile>, Random) -> Vector2Int
```

applied via `Zone.Builder.addFeature`, with one built-in (`randomWalkCave`).
`Zone.Builder.build()` produces a terrain-only `Zone`; entities are separately
created with `World.spawn(components)`. Building the Rogue generator surfaced
four limitations (krogue-b1p):

1. **Terrain-only** — the seam can only mutate `Grid<Tile>`; it cannot place
   entities (monsters, gold, items, traps), so real generators bypass it.
2. **No prefab/room primitive** — nothing defines a parameterized structure
   (e.g. a 2–5×2–5 "shack") and stamps it.
3. **No placement framework** — no overlap/spacing/density/try-N/bounds-fit.
4. **No generator registry** — unlike strategies, senses, perception models, and
   light calculators (first-class `GameModule` registries, ADR-0009/0014),
   world-gen has no resolve-by-id, save/load-aware registry.

Per ADR-0012, the *faithful* Rogue generator (the 3×3-grid port) stays
example-side in korogue-rogue; this ADR is the complementary **general engine
capability**.

## Decision

Introduce four pieces, layered so generation stays pure (no live `World`
dependency) and fully headless-testable.

**1. Entity-aware generation context + seam.** Replace the terrain-only
lambda with a context-carrying seam:

```kotlin
fun interface ZoneGenerator { fun generate(ctx: ZoneGenContext) }
```

`ZoneGenContext` exposes the tile grid, the RNG, and an **entity sink** — a
*buffered* way to declare entities to spawn (position + component list), because
`Zone.Builder` has no `World`. `GameWorld` materializes the buffered spawns
(`World.spawn`, with `Position`/`ZoneMember`) **after** `build()`. Generation
therefore produces (terrain + a list of spawn requests), not live entities —
keeping it deterministic, ordering-independent, and testable without a World.
The terrain-only `ZoneFeatureGenerator`/`randomWalkCave` are **retained as-is**
(terrain-only sugar, still widely used); `ZoneGenerator` generalizes them, and
`Zone.Builder.addFeature` carries an overload for each.

**2. Prefab / room-stamp primitive.** A `Prefab` describes a parameterized
structure — size range, tiles, entity placements, and anchor/door metadata —
relative to a local origin. Stamping it into a `ZoneGenContext` at a position
writes tiles and buffers entity spawns translated to that origin.

**3. Placement framework.** A placer takes a prefab (or feature) + constraints
(overlap avoidance, spacing, density, try-N attempts, bounds-fit) + RNG and finds
valid positions, tracking already-occupied regions so repeated stamps don't
collide.

**4. `GameModule.generators` registry.** A `Registry<ZoneGenerator>` with a
Builder `.generator(id, gen)` and engine built-ins registered in
`engineDefaults()` (starting with `randomWalkCave`). Zones/levels reference a
generator **by id** (like `strategyId`, `calculatorId`), so the id persists and
re-resolves on load — mirroring the existing registry pattern exactly.

**Acceptance:** a "shack stamper" generator that scatters variable-size
single-room structures across a zone with **no overlap** — exercising context +
prefab + placement + registry together.

## Consequences

- Generators become first-class, entity-aware, and registry-resolved/save-aware.
- The terrain-only `ZoneFeatureGenerator`/`randomWalkCave` are kept unchanged as
  terrain-only sugar; `ZoneGenerator` generalizes them for entity-aware
  generation, and `Zone.Builder.addFeature` has an overload for each (lower churn
  than removing the typealias — existing callers/tests are untouched).
- Entity spawning during generation flows through a **buffered sink** the
  `GameWorld` materializes, so `Zone.Builder` stays World-free and generation
  stays pure/unit-testable (all four pieces verify headless — no GL).
- The faithful Rogue 3×3 generator can be re-expressed via these primitives, or
  stay bespoke example-side; either way the engine gains the general capability.

## Alternatives considered

- **Give generators a live `World`** — rejected: couples generation to a running
  world, hurts determinism/testability, and entangles spawn ordering with build.
  The buffered sink keeps generation a pure function of (grid, RNG) → (tiles,
  spawn requests).
- **Keep terrain-only + place entities in a separate post-pass** — rejected;
  that is the status quo generators already bypass, and it can't express
  "this prefab includes this monster at this offset."
- **Keep the lambda seam, no registry** — rejected: inconsistent with the
  ADR-0014 registry pattern and not save/load-aware (a level couldn't persist
  which generator produced it).

## Follow-ups

Decomposed under epic krogue-b1p: (1) entity-aware context + seam [foundation],
(2) prefab/room-stamp primitive, (3) placement framework, (4) generators
registry. All engine-side and headless-testable.
