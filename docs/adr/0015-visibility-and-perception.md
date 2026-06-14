# ADR-0015: Visibility & perception — three layers, composable senses

- **Status:** Accepted
- **Date:** 2026-06-13

## Context

Today the engine decides what the player sees inside `MapPanel`: a cell is drawn if it is in
line-of-sight **and** lit (`zone.lightMap[cell] != null`), and an entity is drawn if its cell is
visible. This bakes one policy into the renderer and silently conflates three different things.

Building the Rogue example (ADR-0012) exposed the muddle from several directions:

- A globally-lit room rendered as a small disc because `MapPanel` applied a hard 30-tile circular
  view-distance cap (krogue-f50) — *distance* masquerading as *lighting*.
- "Does Rogue model light?" has no clean answer in the current model. Rogue has **no light
  intensity** (no emitters, no falloff); it has a binary per-room **lit/dark flag** that is really a
  *visibility* rule (lit room → see the whole room; dark room → see only adjacent cells). The engine
  has no place to put "this is a visibility rule, not an illumination value."
- Rogue itself needs concepts the model can't express: **invisible** monsters (in LOS and lit, but
  not seen unless you have see-invisible), **detect-monsters / magic-mapping** (see things *outside*
  LOS and light), and dark rooms (a reduced perception, not an unlit one).

Generalising past Rogue, the missing concept is **perception**, and it is observer- and
target-relative in ways lighting and LOS are not:

- **See in the dark / infravision** — perceive an *unlit* cell.
- **A changing sight radius** — a curse shrinks how far you see for a while, then it's restored.
- **Invisibility** — a target that *is* in LOS and lit is still not perceived.
- **Telepathy / detect** — perceive specific entities with no LOS at all.
- **Multiple observers** — perception is *not* a player-only question. Companions, a charmed
  monster, the camera following a different unit, and games with **multiple mutually-hostile
  factions** all need "what can *this* unit perceive?", and "can faction A see this enemy?".

Lighting (how lit a cell is) and line-of-sight (what's geometrically reachable) are **world/geometry
facts**. What an observer *perceives* is a function of those facts plus the observer's senses and the
targets' concealment. The engine models the first two and is missing the third.

This ADR is governed by ADR-0014 (pluggable policy, fixed mechanism): perception is policy, and its
*inputs* (senses, concealment) must be open, not a closed set of fields.

## Decision

### Three layers

1. **Illumination (world).** Per-cell light, produced by `LightingSystem` into `zone.lightMap`
   (ADR includes ambient/global mode, krogue-0vg). *Magical darkness lives here* — it sets light to
   zero in a region; no perception code special-cases it.
2. **Line of sight (geometry).** Which cells are sight-reachable from a point, walls blocking —
   `LineOfSightCalculator`. LOS is *unbounded* geometry: the **radius limit is not part of LOS**, it
   is a property of the observer (see below). This removes `maximumVisibilityDistance` from
   `MapPanel` (superseding the interim krogue-f50).
3. **Perception (observer + targets).** The new layer: an observer-relative combiner that turns the
   world facts into "what this observer perceives" — a set of perceived cells and perceived
   entities.

### Senses are open: data components + registered contributors

Following ADR-0014, perception's inputs are not a closed struct. They are composable:

- **A sense is an opt-in data component** the observer carries — engine ships `Sight`,
  `Darkvision`, `Tremorsense`, `Telepathy`; a game adds its own (`HeatSense`, …). **No sense
  contributes unless the observer has its component**, so per-entity opt-out is automatic: a
  mindless ooze with only `Tremorsense` simply never triggers sight.
- **Each sense is interpreted by a `Sense` contributor registered by id** (ADR-0009 style):
  `interface Sense { fun reveal(observer, world): Contribution }`, where a `Contribution` is the
  cells/entities that sense exposes. The engine registers contributors for its built-in senses; a
  game registers `module.sense("heat", HeatSense())`. Overriding a built-in = registering the same
  id; dropping all engine senses = a builder that starts empty.

So adding a new way to perceive is *one component + one registered contributor* — never editing
engine code — exactly the move as registering a new AI strategy.

### Concealment is the dual, and carries tags

- **Concealment is a data component on the target** (`Invisible`, and a game's own `Phased`, …).
- **Selective interaction is expressed with tags, not a matrix.** Senses declare tags describing
  what they *are*/*depend on*; concealments and suppressors target tags. Built-ins:
  - `sight` → `{visual, light-dependent}`, `darkvision` → `{visual}`, `tremorsense` → `{vibration}`,
    `telepathy` → `{mental}`.
  - `Invisible` conceals from `{visual}`; a future `Silenced` from `{vibration}`.
  A new sense declares its tags and existing concealments handle it; a new concealment targets tags
  and automatically affects all matching senses. **Piercing** is the inverse capability: a sense (or
  a sense-modifier) may declare *pierces* tags — see-invisible is a visual sense that pierces the
  `Invisible` concealment; true-sight pierces more. Concealment-carries-tags + senses-pierce-tags
  composes cleanly and avoids any sense×effect table.

  **Tags are string ids** with engine-provided constants (a game declares its own as constants).
  There is deliberately **no separate tag registry**: a tag carries no behaviour to resolve, so a
  registry would be pure validation overhead and redundant with the string-id convention used
  everywhere else (ADR-0009). If typo-bugs prove painful, a dev-mode validation pass over referenced
  tags can be added without changing the model.

### Subtraction is a second phase; suppressors are components

Most senses are **additive** — they reveal more. The hard cases *remove* perception, and they are
modelled distinctly rather than as negative senses:

- **Execution is two phases.** *Reveal:* union every sense the observer has, each contribution
  tagged by its sense's tags. *Suppress:* apply observer **suppressors** and target **concealments**,
  which remove contributions whose tags they negate. Union-then-suppress keeps phase 1
  order-independent and confines precedence to one place.
- **Piercing defeats concealment, not suppression** (refined during krogue-1my.3 — see the note
  below). A sense's *pierces* set lets it see a **target** that conceals from it (see-invisible /
  true-sight), but a **suppressor** that negates one of the sense's tags drops it unconditionally: a
  suppressor disables the *channel* the sense runs on, and you cannot pierce your own blinded eyes.
  **Immunity to a suppressor comes from being on a channel it doesn't negate** — a non-`{visual}`
  sense (`Tremorsense`, a game's blindsight) survives `Blind` for free — not from piercing. This
  keeps "I see hidden things" and "I can't be blinded" as two separately-chosen capabilities, and
  makes D&D's four special senses fall out of the channel scheme (truesight = better *sight*, blinded
  like any sight; blindsight/tremorsense = non-visual channels, unblinded).
- **Suppressors are components** (`Blind` negates `{visual}`; `Dazzled`, …), so a *transient* blind
  effect suppresses without deleting the observer's permanent `Sight`, and is expired by the
  scheduler (krogue-6uq) — restoring sight cleanly. (Confirmed in design: blindness is a suppressor,
  not component-removal.)

### Perception is per-observer; the renderer picks whose eyes

Perception is computed **for any observer**, not just the player:

- The primitive is a **query**, `PerceptionModel.perceive(observer, world)`, callable for the
  player, a companion, a charmed monster, or a monster asking "can I see the player?" — so AI
  (krogue-d5b) reuses the same model, and **faction visibility** is the union of a faction's
  members' perception.
- **Caching follows the lightMap pattern.** A `PerceptionSystem` writes a derived `Perceived`
  component each tick for observers that are sampled every frame (the rendered unit; AI that checks
  every tick), because perception only changes on a tick but the renderer runs ~60×/sec. Caching is
  **per-observer**, not player-only — a deliberate correction to the earlier "only the player needs
  a full field of view" assumption. The query stays the model; the cache is the optimisation.
- **`MapPanel` renders a chosen observer's `Perceived`** and nothing more. It no longer computes
  `LOS ∧ lit` or owns a view distance — it is handed "what to show". Which observer (or faction) the
  camera shows becomes a presentation choice (the player by default; spectating a companion or
  per-faction fog-of-war fall out for free).

### Defaults keep it invisible to simple games

The engine ships `StandardPerception` (the union/suppress model above) and all common senses and
concealments. A basic game puts a `Sight` on the player and ships; it never writes a contributor,
never sees a tag. Wholesale replacement remains available for a different paradigm — e.g. a
`RoomBasedPerception` for Rogue's lit/dark rooms (krogue-kj5), which needs room metadata from level
generation (krogue-c20).

## Consequences

- **Every scenario becomes data.** Cursed sight = a smaller `Sight.radius` for N turns; see-in-dark
  = `Darkvision`; reveal-all = a transient sense/suppressor bypass; invisible enemy = `Invisible`
  vs. a piercing sense; factions = `perceive` per member. None require engine changes.
- **The renderer is decoupled** from visibility policy, and the same perception model powers AI and
  multi-observer/faction play.
- **Migration.** `maximumVisibilityDistance` leaves `MapPanel` (onto a `Sight`-style component),
  superseding krogue-f50's interim default. `lightMap` and `LineOfSightCalculator` are unchanged and
  become the lower two layers. `Perceived` is derived state (recomputed, not saved), like `lightMap`.
- **Cost: real machinery** — senses, contributors, tags, two-phase reveal/suppress, per-observer
  caching. Justified by the breadth of scenarios and paid down by defaults; the two-phase split and
  tag scheme exist specifically to keep that machinery composable rather than a combinatorial mess.
- **Scope.** Large; decomposed into a perception epic of beads. It lands before its first consumers
  (invisible monsters, detect/see-invisible items, Rogue's dark rooms), not blocking the scaffold,
  which renders fine on the interim `LOS ∧ global-light` path until then.

## Alternatives considered

- **Keep `visible = LOS ∧ lit` in `MapPanel`.** Rejected: conflates three concerns, can't express
  see-in-dark/invisible/detect/dynamic-radius, hardcodes a single observer and policy.
- **A closed `Vision(radius, darkvision, blind, seeInvisible)` struct.** Rejected: it only supports
  replacing the whole model; a game with a *novel* perception input has nowhere to plug in short of
  reimplementing perception — the exact closed-ness ADR-0014 forbids.
- **A sense × effect interaction matrix.** Rejected: not composable — every new sense or effect
  edits the matrix. Tags on senses/concealments + piercing achieve the same selectivity additively.
- **Negative senses (blindness as a sense returning "anti-cells").** Rejected: muddles the additive
  reveal phase; a separate suppress phase is clearer and gives clean transient/scheduler semantics.
- **Player-only field of view.** Rejected: companions, charm, camera-follow, and rival factions all
  need per-observer perception; the query is per-observer with per-observer caching.
- **Behaviour on components (a `Sense` that is itself a component).** Rejected: ADR-0003 keeps
  components data; senses resolve behaviour by id from the registry, like every other policy.
- **Uniform piercing (pierce defeats suppression as well as concealment).** Rejected during
  krogue-1my.3. It conflates two distinct real capabilities — seeing hidden things and being immune
  to blindness — so a see-invisible sense would silently also un-blind itself. Restricting pierce to
  concealment is *simpler* (the suppress check drops the pierce test) and lets the channel/tag scheme
  do the blindness-immunity work (a non-`{visual}` sense survives `Blind` by construction). A game
  that genuinely wants uniform piercing keeps full freedom: `PerceptionModel` is a pluggable policy
  (ADR-0014), so it registers a `StandardPerception` variant whose suppress check honours `pierces` —
  the fusion is opt-in, not baked into the default.
