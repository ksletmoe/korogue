# ADR-0026: One canonical Grid — kotile owns the 2D grid type

- **Status:** Accepted
- **Date:** 2026-07-15

## Context

Both published modules exposed a public class named `Grid<T>`:

- `com.sletmoe.kotile.utilities.Grid` — a single row-major `Array` backing;
  `get`/`set[x, y]`, `fill`, `clear`, `forEachIndexed((Int, Int, T))`; threw
  `IndexOutOfBoundsException`.
- `com.sletmoe.korogue.utilities.Grid` — a `MutableList<MutableList<T>>` backing;
  the above *plus* `Vector2Int` accessors, `forEach`,
  `forEachIndexed((Vector2Int, T))`, `forEachCoordinate`,
  `forEachCoordinateInRadius`, `lastColumnIndex`/`lastRowIndex`, and a
  `companion of()` copy; threw raw `RuntimeException`.

Same name, two packages, incompatible method sets, incompatible `forEachIndexed`
signatures, different exception types, different performance characteristics. A
consumer importing both libraries had two `Grid` symbols to disambiguate — and the
engine's `Grid` leaked into the published save API (`SaveCodec.save(fog=)`,
`LoadedGame.fog`), so the duplication was not an internal detail.

Both types are in a semver surface. Consolidating or renaming after 1.0 is a
breaking change, so the single canonical `Grid` had to be chosen now.

## Decision

**kotile's `Grid` is the one canonical grid type.** The engine's `Grid` is deleted;
korogue imports `com.sletmoe.kotile.utilities.Grid`.

This follows the boundary principle in `ARCHITECTURE.md` — *if a non-roguelike libGDX
game could plausibly use it, it belongs in kotile* — which a dense 2D array plainly
satisfies. kotile is also the lower layer, so the dependency direction only works this
way round. Its implementation was the stronger of the two: one flat array instead of a
list of lists, and a correct `IndexOutOfBoundsException` instead of raw
`RuntimeException`.

The engine's genuinely general API moved onto kotile's `Grid`:

- `get`/`set(Vector2Int)` — `Vector2Int` is already a kotile type.
- `forEach`, `forEachCoordinate`, `lastColumnIndex`/`lastRowIndex`.
- `copy()`, replacing the `Grid.of(other)` companion.

Two members did **not** move:

- `forEachIndexed((Vector2Int, T))` was **dropped**. A sweep of both repos found zero
  call sites — every `forEachIndexed` hit was on a `List` or `String`. Keeping it would
  have meant two overloads distinguished only by lambda arity.
- `forEachCoordinateInRadius` stays in korogue, as an extension function on kotile's
  `Grid`. It depends on engine geometry (`IntRect`, `Vector2Int.distanceSq`), which is
  above kotile's boundary. `Grid<Boolean>.or` is an engine extension for the same
  reason.

The `x, y` vs `Vector2Int` duality in the coordinate API is deliberately preserved
here; choosing a single idiom is krogue-dat's decision, not this one.

## Consequences

- One `Grid` in the 1.0 surface. No disambiguation for consumers of both libraries;
  the save API (`SaveCodec`, `LoadedGame.fog`) now speaks kotile's type.
- **Breaking for existing consumers**, but only as an import change: the Rogue port
  (`../korogue-rogue`, the exemplary consumer) needed *nothing* but
  `import com.sletmoe.korogue.utilities.Grid` →
  `import com.sletmoe.kotile.utilities.Grid` across 20 files. Its 585 tests pass
  unchanged, which is the evidence that the merged API is a true superset in practice.
- Out-of-bounds access now throws `IndexOutOfBoundsException` where the engine's grid
  threw `RuntimeException`. `IndexOutOfBoundsException` *is* a `RuntimeException`, so
  existing `catch`/`shouldThrow<RuntimeException>` sites keep working — the change only
  narrows the type. This also resolves part of krogue-fuz for `Grid`.
- Zero-sized grids are now legal and copyable. The old `Grid.of` threw
  `RuntimeException("Can't copy an empty Grid")` because its copy constructor read
  `other[0, 0]` to recover a default value; `copy()` copies the backing array and the
  default value directly, so the restriction is gone.
- Cell iteration order is unchanged (column-major: all rows of column 0, then column 1,
  …), matching both prior implementations, so determinism guarantees (ADR-0025) hold.

## Alternatives considered

- **Engine `Grid` wins, kotile imports it** — inverts the dependency: kotile cannot
  depend on the engine, and a general tile renderer would be importing a roguelike
  engine for its array type. Also keeps the weaker list-of-lists backing.
- **`typealias korogue.utilities.Grid = kotile.utilities.Grid`** — source-compatible for
  consumers, but leaves two names for one type in the docs and IDE completion, which is
  most of the confusion the bead is about. A pre-1.0 import rewrite is cheap; carrying a
  vestigial alias past 1.0 is not.
- **Move `IntRect` + `distanceSq` into kotile too**, so `forEachCoordinateInRadius` could
  live on the class — plausible (both are general geometry), but a larger surface change
  that this decision does not need. Left for a separate bead if the geometry types ever
  earn their way down.
