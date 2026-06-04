# ADR-0005: Enforced mutation chokepoint

- **Status:** Accepted
- **Date:** 2026-06-01

## Context

With immutable components (ADR-0003), "mutation" means replacing a component value. That
replacement could happen anywhere a caller holds an `Entity`, or it could be funnelled
through a single place. A future save/load phase (Phase 4f) will want change-tracking /
state-delta hooks, and we want adding those to not be a breaking change.

## Decision

`Entity` is **read-only to callers**; all component mutation flows through `World`
(`set` / `update` / `remove`). `Entity.set`/`remove` are not part of the public surface.
`World.update<T>` writes back under the *queried* type's key (so a transform returning a
subtype can't orphan the entry).

## Consequences

- A single seam onto which change-tracking, undo, and save-state deltas can later be
  hooked, with no change to call sites — so ADR-0003's snapshot features stay reachable.
- Slightly less direct ergonomics, mitigated by `World.update<T>(id) { … }`.
- Surfaced and fixed during review: queries returning live sequences were a
  `ConcurrentModificationException` hazard during spawn/despawn; queries now return
  snapshots.

## Alternatives considered

- **Public `Entity.set`/`remove` (advisory chokepoint, documented)** — rejected: it
  leaves the seam unenforced, so adding tracking in 4f becomes a breaking change for any
  code (including our own 4b) that took the direct path. Enforcing now, before anything
  depends on it, is nearly free.
