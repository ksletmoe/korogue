# ADR-0034: Vector2Int is the canonical 2D coordinate across the public API

- **Status:** Accepted
- **Date:** 2026-07-19

## Context

The public API had no single rule for naming a 2D grid coordinate. It mixed bare
same-type `Int` pairs with kotile's [`Vector2Int`] value type, inconsistently:

- Components: `Position(x, y)` exposed a `.point: Vector2Int` getter but had no
  `Vector2Int` constructor; `MoveIntent(dx, dy)` had a `Direction` constructor but
  no `Vector2Int` one; `Portal(zoneId, targetX, targetY)` had neither.
- `GameWorld.relocate` / `entityAt` / `isWalkable`, `Zone.isPassable` /
  `isWalkable` / `Builder.setTile`, and `ZoneGenContext.spawn` / `SpawnRequest`
  all took trailing `(x: Int, y: Int)`.
- Only kotile's `Grid` was consistent: `get`/`set` accept both `(x, y)` and
  `Vector2Int`, the latter delegating to the former (ADR-0026).

Kotlin does not force named arguments at call sites, so identical adjacent `Int`
params (`x, y` / `dx, dy` / `targetX, targetY`) are transposition footguns, and a
caller cannot tell without reading each type whether a `Vector2Int` form exists.
These are public constructors and method signatures under semver, so the
convention is worth locking before 1.0 and before downstream callers proliferate.

`Vector2Int` lives in kotile and is not `@Serializable` (kotile has no
kotlinx.serialization dependency), so serializable components must keep their
primitive `Int` fields as the wire format.

## Decision

**Every public API that names a 2D grid coordinate offers a `Vector2Int` form,
and that form is the canonical one callers should reach for.** Primitive
`(x: Int, y: Int)` stays available as a secondary convenience, delegating to /
from the `Vector2Int` form (matching `Grid`, ADR-0026).

Concretely:

- **Serializable components** keep their `Int` fields as the primary constructor
  (the serialized shape) and add a `Vector2Int` **secondary constructor** plus a
  `Vector2Int` **accessor**: `Position(point)` / `.point`, `MoveIntent(delta)` /
  `.delta`, `Portal(zoneId, target)` / `.target`.
- **Methods** (`GameWorld`, `Zone`, `ZoneGenContext`) add a `Vector2Int` overload
  delegating to the existing `(x, y)` body; `SpawnRequest` gains a `Vector2Int`
  secondary constructor and a `.point` accessor.
- `Vector2Int` gains `plus` / `minus` operators so callers compute deltas and
  targets as whole vectors (`next - from`, `origin + delta`) instead of
  per-axis by hand — the arithmetic that was the main source of swap bugs.

## Consequences

- One rule to remember: a `Vector2Int` form always exists, so callers that
  already hold a coordinate pass it whole and never transpose it.
- Additive and non-breaking — every existing `(x, y)` call still compiles; the
  serialized component shape is unchanged.
- The Rogue example (`../korogue-rogue`) is migrated to the canonical forms where
  it already held a `Vector2Int` (e.g. `Position(spot.pos)`, `MoveIntent(next - from)`).
  Its own spawn factories follow the convention too: `FloorItem.spawn` and
  `GoldPile.spawn` gained `Vector2Int at` overloads to match the sibling
  `Actors.spawnPlayer` / `MonsterFactory` factories, which already took `at: Vector2Int`.
- kotile's public surface grows two operators; a small, conventional addition to a
  grid coordinate type.
- Not enforced by the compiler — reviewers must keep new coordinate APIs to the
  convention. The `(x, y)` overloads remain, so the footgun is reduced, not removed.

## Alternatives considered

- **Make `Vector2Int` the sole field of each component** — cleaner reads
  (`position.coordinate`) but changes the serialized shape and forces a
  kotlinx.serialization dependency into kotile (or a custom serializer) for a
  general-purpose renderer type. Rejected as breaking and out of layer.
- **Deprecate the `(x, y)` forms outright** — maximally consistent but breaks
  every loose-int call site (`Position(x, y)` from generators, teleport scans)
  for no safety gain where the two ints come from genuinely separate sources.
- **Leave it inconsistent, add overloads ad hoc** — the status quo; keeps the
  "does this one have a `Vector2Int` form?" guessing game the issue is about.

[`Vector2Int`]: ../../kotile/library/src/main/kotlin/com/sletmoe/kotile/utilities/Vector2Int.kt
