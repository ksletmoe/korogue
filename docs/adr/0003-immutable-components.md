# ADR-0003: Immutable components

- **Status:** Accepted
- **Date:** 2026-06-01

## Context

Given the component model (ADR-0002), components could be mutable holders (mutate
fields in place) or immutable values (replace to change). This choice sets the default
convention every component — and every consumer — follows.

## Decision

Components are **deeply immutable** data classes. To change state, produce a new value
and replace the old one via the `World.update<T>(id) { … }` chokepoint (see ADR-0005).
Components hold no mutable fields and no mutable types (e.g. colour is stored as
`NormalizedRgb`, not a mutable libGDX `Color`).

## Consequences

- Entity state is **snapshottable**, which is the foundation for change-tracking, undo,
  time-travel debugging, networked state diffing, and clean save/load — features whose
  value the user explicitly wanted to keep open. Near-term, the same property enables
  cheap dirty-tracking (e.g. recompute lighting only when a lit entity changed).
- Eliminates reference-aliasing bugs (the class of bug seen during the geometry
  migration with a mutable `Point`).
- Minor write-side ceremony (`copy()` + reattach), mitigated by the `update<T>` helper.
- Cost is robust to scale: copy cost scales with mutations-per-tick (low in a turn-based
  roguelike), not entity count; small short-lived allocations are the JVM's best case.

## Alternatives considered

- **Mutable fields by default** — rejected: defeats snapshotting for exactly the hot
  state you'd most want tracked (HP, position) and reintroduces aliasing surprises.
- **Pragmatic mix (immutable values, mutable hot fields)** — rejected: the mutable holes
  land precisely where the snapshot-based features need to be solid; an "it depends"
  convention is also the hardest to keep consistent across contributors.
