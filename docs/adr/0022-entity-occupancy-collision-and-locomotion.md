# ADR-0022: Entity occupancy — tag-based collision and locomotion

- **Status:** Accepted
- **Date:** 2026-07-04

## Context

`MovementSystem.occupantAt` decided what blocks movement — and what a step bumps
into (emitting an `AttackIntent`) — with a hardcoded denylist: every positioned
entity (`Position` + `ZoneMember`) was an attackable occupant *except* those
carrying `Portal` or `Item`. This does not scale (krogue-x9q). Any game that
models a non-creature as a positioned entity — traps, secret doors, altars,
pressure plates, decorations, remembered corpses — silently turned it into an
invisible attackable wall until it was added to the skip-list. korogue terrain
holds only a glyph, so the Rogue port keeps traps and secret passages as ECS
entities; they read as attackable occupants, so a step onto a trap/secret cell
became a no-op attack and the hero never reached the cell (found via
korogue-rogue rogue-291, worked around downstream with a game-specific
`RogueMovementSystem` whose occupant = an entity with `Health`).

Two limitations were tangled together:

1. A hardcoded component denylist (`!Portal && !Item`) instead of a data-driven
   per-entity walkability signal (violating ADR-0003: components are data,
   behaviour resolves).
2. `occupantAt` **conflated** "blocks movement" with "is a bump/attack target",
   so a solid-but-not-attackable thing (boulder, statue, closed portcullis)
   could not be expressed.

The perception subsystem (ADR-0015) had already solved the shape of this problem:
selective interaction expressed by **plain string tags** with no registry,
resolved by a **set relation** rather than a subject×object matrix. Movement has
the same shape — a mover's capabilities versus an obstacle's obstruction — and a
second consumer (a new game) is imminent, so a general mechanism is warranted now
rather than deferred on the rule of three.

## Decision

Replace the denylist with two data components resolved by a set relation,
mirroring the perception tag model.

**Locomotion as tags.** `MovementTags` (`WALK`, `FLY`, `SWIM`, plus the
convenience `PHYSICAL = {walk, fly, swim}`) are plain string ids with no
registry, exactly like `PerceptionTags`. Games declare their own (`PHASE`,
`BURROW`, `CLIMB`) as constants without touching the engine.

- **Mover side:** `Locomotion(modes: Set<String>)` — how an entity travels.
  Absent ⇒ `{walk}`, so ordinary ground creatures need not carry it.
- **Obstacle side:** `Collision(blocks: Set<String>, bump: BumpResponse)`.
  - `blocks` — which modes this occupant stops. **Passing rule:** a mover is
    blocked iff *every* one of its modes is blocked (`moverModes ⊆ blocks`); it
    passes if it has one mode the obstacle omits (a flyer over a `{walk}`-only
    blocker). Empty `blocks` = passable to everyone: you step *onto* it.
  - `bump` — the response when a mover *is* blocked: `ATTACK` (emit
    `AttackIntent`, the creature default) or `BLOCK` (solid no-op). This is the
    second, orthogonal axis, splitting "blocks" from "is attackable".

**Fail-closed default.** Absence of `Collision` means `Collision(PHYSICAL, ATTACK)`
— solid + attackable, today's implicit behaviour for a creature. Forgetting the
component on a new monster still blocks and is bump-to-attackable, which is the
safe direction. Passable engine entities (portals, floor items) carry an explicit
`Collision.PASSABLE` (`blocks = ∅`) at their spawn sites; `occupantAt` names no
component type.

## Consequences

- `MovementSystem` no longer references `Portal`/`Item`; any game marks an
  arbitrary positioned entity passable, solid, or attackable via engine data.
- Solid-but-not-attackable obstacles (boulders, portcullises) are now
  expressible via `bump = BLOCK` — previously impossible.
- Locomotion generality (fly/swim, and game-defined modes) is available for
  **entity** occupancy immediately, and extends without engine edits because tags
  are open strings resolved by set relation.
- Passable entities must be tagged at their spawn sites (fail-closed): the engine
  demo's portals/items and the engine tests that step a mover onto a portal now
  carry `Collision.PASSABLE`. Blast radius is engine-only — korogue-rogue bypasses
  engine occupancy via its own `RogueMovementSystem`, so it does not regress.
- Two new registered, serializable components (`Collision`, `Locomotion`).
- **Terrain is not yet mode-aware.** `Zone.isWalkable` remains a walk-only
  boolean; per-mode *terrain* passability (fly over lava, swim) is the natural
  extension of the same tags but touches the Zone/terrain data model, so it is
  deferred to krogue-xeb (`Zone.isPassable(x, y, moverModes)` defaulting to
  today's `isWalkable`). Mode-selectivity mostly earns its keep on terrain;
  entity occupants are usually `PHYSICAL`-or-`∅`.
- A game with a wholly different occupancy policy can still swap `MovementSystem`
  entirely (ADR-0014); `RogueMovementSystem` (occupant = has `Health`) is the
  reference input if the engine later generalizes further.

## Alternatives considered

- **`NonBlocking` opt-out marker (Option A).** A single marker; occupant blocks
  unless it carries `NonBlocking`. Minimal and fail-closed, but a boolean — it
  cannot express solid-vs-attackable or locomotion modes, and a known second
  consumer makes the richer model worth building now.
- **`Blocking` opt-in marker (Option B).** Conceptually clean (blockers explicit)
  but fail-open: forget it on a monster and the hero walks through it, and it
  forces tagging every creature spawn. Rejected for safety/migration cost.
- **Enum instead of tags** for movement modes — closed; a game could not add
  `BURROW` without editing the engine. Rejected in favour of the perception
  subsystem's proven open-string-tag idiom (one mental model across subsystems).
- **A single `Occupancy` enum** (`PASSABLE`/`SOLID`/`ATTACKABLE`) — leanest and
  covers today's cases, but collapses the two axes and cannot carry locomotion
  modes, which we want for the incoming game.
- **Unifying terrain now** — correct end state, but the terrain data-model change
  is larger than the occupancy fix; split to krogue-xeb on the same tags.
