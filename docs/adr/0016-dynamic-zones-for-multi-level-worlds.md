# ADR-0016: Dynamic zones for multi-level worlds

- **Status:** Accepted
- **Date:** 2026-06-18

## Context

`GameWorld` held its zones in an immutable `Map<String, Zone>` fixed at construction
(ADR-0007/0008): a world knew all its maps up front. That suits a hand-authored set of
linked areas, and `PortalSystem` already moves the player between such pre-existing zones
(setting `currentZoneId`, which lighting, perception and the renderer all follow live).

The Rogue example needs something the immutable registry can't express: a *dungeon* whose
levels are **generated on demand and discarded on departure** (krogue-go8). Pressing `>`
on the staircase must build the next level, drop the player onto it, and free the one left
behind; with the Amulet, `<` does the same upward. Levels are ephemeral — Rogue's
`new_level` frees the previous level's monsters and objects wholesale, and revisiting a
depth regenerates a fresh map — so the world's set of zones grows and shrinks during play
and can't be known at construction.

The decision: how should a multi-level, regenerating world be modelled without a redesign,
and without disturbing the zone-scoped systems (lighting/perception/movement) that already
read the current zone live?

## Decision

Make `GameWorld`'s zone registry **mutable**, with `addZone(zone)` and `removeZone(zoneId)`.
`zones` is exposed as a live, read-only view over one stable backing map, so a system that
captured `world.zones` at wiring time observes later additions and removals with no rebuild
— the same property zone transitions already relied on for `currentZoneId`. `removeZone`
refuses to drop the current zone; occupant entities are the ECS's concern and are not
touched by it.

The Rogue example drives this from a headless `Dungeon` coordinator that owns the one world
and the one persistent player entity. A level change generates a new zone
(`LevelFactory.addLevel`), relocates the player (only `ZoneMember` + `Position` change, so
the pack/stats/HP ride along), then despawns the departed zone's occupants and removes the
zone. Each visit gets a unique zone id, so its fog of war starts blank; `ZoneFog.forget`
drops a departed zone's remembered fog. Per-game state that outlives any level (item
disguises, the wandering-monster clock) lives on zoneless singleton entities and is left
alone. Dungeon depth is read live by the wandering-monster effect, so wanderers scale to
the player's current level.

The `>` / `<` commands and their Rogue messages live in the game shell; taking the stairs
doesn't spend a turn (`after = FALSE`), so the shell re-primes lighting and perception
directly rather than ticking the world. The Amulet gate on `<`, and the level-0 win, are
deferred to krogue-5yu, which flips the gate and calls `Dungeon.ascend()` — the
regeneration machinery already exists.

## Consequences

- A world can grow, shrink, and regenerate its maps over a session; roguelike-style
  infinite/ephemeral dungeons fit without a redesign. Lighting, perception, movement and the
  renderer need no changes — they already read the live current zone (ADR-0008).
- Ephemeral levels keep entity and save-file growth bounded: leaving a level frees its
  occupants and terrain, matching Rogue's `new_level`.
- `removeZone` only discards terrain; callers must despawn that zone's occupants themselves
  (the `Dungeon` does). A caller that forgets would leak dormant entities — acceptable, since
  occupancy is deliberately an ECS concern (ADR-0007), but a sharp edge to remember.
- Persisted state (zones, fog) is now a moving target across a session; save/load already
  snapshots both, so this is consistent, but anything caching a zone *instance* (rather than
  looking it up by id) could hold a freed map.

## Alternatives considered

- **Rebuild the whole `GameWorld` (and re-register systems, module, screen) per level.**
  Faithful to "discard everything," but `world` is referenced throughout the game shell and
  the content module binds to it; rebuilding means re-wiring all of that and migrating the
  player across ECS worlds each descent. Far more churn and failure surface than mutating one
  registry.
- **Model levels as `Portal`s into pre-generated zones.** `PortalSystem` already exists, but
  it targets zones that must exist ahead of time and links fixed cells — neither holds when
  levels are generated on demand and regenerated on revisit. Stairs are command-driven
  (`d_level`/`u_level`), not step-onto-a-cell transitions.
- **One zone whose terrain is replaced in place on each level.** Avoids add/remove, but
  reusing a zone id keeps its stale fog and light map, and replacing a `Zone`'s terrain grid
  is a larger surgery than swapping registry entries; unique per-visit zones give correct
  fresh fog for free.
