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

## Amendment (2026-07-18) — the seam's absence contract (krogue-vrc)

The original decision fixed *where* mutation happens but left *what happens on a missing
id* unspecified, and the three mutators had drifted apart: `set` returned `Unit` and
silently no-op'd on an unknown or already-despawned id, while `update`/`remove` returned
`null`. A write to a typo'd or stale id therefore vanished with zero signal — a footgun
for game code driving the world from input/AI, and one worth closing before 1.0 freezes
the signatures.

**Decision:** the seam **signals absence; it never silently drops.** `World.set` now
returns `Boolean` (`true` when the entity existed and the write applied, `false` — writing
nothing — for an unknown id), so no mutator can be handed a missing id and stay silent.
The absence signals differ in kind: `set`'s `Boolean` reports specifically whether the
*entity* existed, whereas `update`/`remove` return `null` for *either* a missing entity or
a live entity that lacks the requested component — they report operation/component presence,
not entity presence. `GameWorld.relocate` propagates `set`'s `Boolean`. The `Unit → Boolean`
change is source-compatible for the common case — a direct call site that already knows the
entity is live may ignore the result unchanged — but it is not blanket-compatible: a
function reference (`::set`, typed `(EntityId, Component) -> Unit`) and any separately
compiled consumer need recompilation or migration. Within this repo there are no such
references, so the practical migration cost is nil.

**Returning a value, not throwing.** The Rogue example (the engine's exemplary consumer)
was used to choose: all of its ~30 `set` call sites write to a known-live entity, so
throwing would force every one to guard a liveness invariant it already holds. A `Boolean`
leaves that choice to the caller who *doesn't* know — consistent with `get`/`update`/
`remove` returning nullable rather than throwing, while `Entity.require` remains the
throwing accessor for genuine invariants.

**`set` keys by concrete runtime class; a reified `set<T>` was rejected.** `update<T>` and
`remove<T>` key by their reified type, but `set` deliberately keys by `component::class`
(the concrete runtime class), matching ADR-0002's one-component-per-concrete-type model.
This is not an oversight to "fix" by making `set` reified: a reified `T` keys by the
*caller's static type*, so an upcast argument — `val c: Component = Health(...); set(id, c)`
— would store under `Component`, where no `get<Health>()` could find it. Recorded here so
the asymmetry reads as intentional and is not re-litigated.

**Consequences:** stray writes surface at the call site instead of manifesting as a later
"why didn't that take effect?" bug; the seam's contract is now uniform and documented on
each method. The `Boolean` result is additive, so the future change-tracking hook this ADR
protects is unaffected.
