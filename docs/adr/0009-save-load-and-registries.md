# ADR-0009: Save/load — CBOR, seeded RNG streams, and the registry/module

- **Status:** Accepted
- **Date:** 2026-06-06

## Context

Phase 4f adds save/load. The codebase was built toward it: components are deeply
immutable data classes (ADR-0003) mutated only through the `World` chokepoint
(ADR-0005), so entity state is already snapshottable; non-serializable things are
stored as string ids (`LightEmitter.calculatorId`, `Behavior.strategyId`) and resolved
through ad-hoc `object` registries (`LightCalculators`, `BehaviorStrategies`). The bead
calls for "serialization (kotlinx likely)" and "builds the registry for
calculatorId/strategyId."

korogue is an **engine consumed by games**, which frames every choice: downstream games
add their own component types, AI strategies, and light calculators, so extension points
must be first-class. The owner also wants **shareable seeds** — the same seed yields the
same world gen / creature placement / loot for everyone — alongside **exact resume** of a
saved game. This ADR records the design agreed in co-design.

## Decision

### What a save contains
- **All ECS entities + their components** (the game state).
- **All zones' terrain** — the `Tile` grids, serialized directly (not regenerated from a
  seed), so a save is self-contained, survives world-gen code changes, and supports
  terrain the player has modified.
- **`currentZoneId`** and the **turn counter**.
- **RNG state** (see below).
- **Excluded:** derived state (`Zone.lightMap` — recomputed by `LightingSystem`) and
  transient intent components (`MoveIntent`, `AttackIntent` — consumed each tick).

### RNG: seeded, named, serializable streams
- A **master seed** (a `Long`). Sharing it reproduces a new game's world.
- **Named sub-streams** derived from the master seed via **SplitMix64**:
  `stream("worldgen")`, `stream("loot")`, `stream("ai")`, `stream("combat")`, … Streams
  are isolated, so combat draws never shift world-gen draws — "same seed ⇒ same world +
  loot" holds regardless of how gameplay code evolves. Consumers may name their own streams.
- The generator is **xoshiro256\*\*** (state = four `Long`s), chosen because it is
  well-known, fast, and has a **serializable state** — enabling exact save/resume, which
  `kotlin.random.Random` cannot (it doesn't expose its state).
- A save stores the **master seed + each live stream's current state + turn counter**. A
  new game from a seed derives the streams. Both use cases fall out of one model.
- This is an **engine capability with a default**: if a game doesn't expose seed entry, it
  gets a random master seed. Surfacing "enter a seed" is the consuming game's choice.

### Format: CBOR
kotlinx-serialization with the **CBOR** binary format (`kotlinx-serialization-cbor`) —
compact, which matters because saves include full tile grids. (New Gradle dependency:
the `kotlin("plugin.serialization")` plugin + the CBOR runtime.)

### Component (de)serialization
`Component` stays an **open** interface (downstream games extend it, so it cannot be
sealed). Each component is `@Serializable` and registered for polymorphic serialization
under a **stable string discriminator** — an explicit id, *not* the class name, so
renaming a component class doesn't break existing saves. The engine registers its
built-ins into a kotlinx `SerializersModule`; a game registers its own; the modules merge.

### Registries: hybrid (separate internals + one facade)
Generalize the ad-hoc `LightCalculators` / `BehaviorStrategies` into:
- **Separate per-concern registries** internally — `ComponentRegistry` (→ the
  `SerializersModule`), `StrategyRegistry`, `CalculatorRegistry` — each independently
  testable. Runtime systems depend only on the narrow registry they need, so pure-gameplay
  code never pulls in save/load serialization.
- **A `GameModule` builder facade** on top that constructs and holds them, gives consumers
  a single seam (`GameModule.engineDefaults().component<…>(…).strategy(…).build()`), and is
  the one place to validate cross-registry coherence (e.g. a strategy id with no factory).

### EntityId stability
Preserve `EntityId`s and the `World.nextId` counter across save/load. No *currently*
persisted component references another entity by id (the only such field, `AttackIntent`,
is transient and excluded), but preserving ids is cheap insurance for future
entity-referencing components and keeps logs/debugging stable.

### Defaults taken (flag if you disagree)
- **Versioning:** write a top-level format-version `Int`; on mismatch, **fail fast** for
  now. A migration framework is deferred until the format actually changes — but see
  *Migration framework (deferred sketch)* below for the shape we're keeping the door open
  to, and the cheap things v1 does now so we don't corner ourselves.
- **Triggers & location:** the engine exposes `save(sink)` / `load(source)` over a byte
  stream; the *consuming game* decides when (manual, autosave, on-quit) and where (file
  path). No autosave by default.

## Migration framework (deferred sketch)

Not built now (fail-fast on version mismatch), but the v1 format is shaped so this can be
added later without a rewrite.

**Target shape — a version chain over a generic document.** Loading becomes a pipeline:

1. **Read a tiny, frozen envelope first.** The save is `envelope { formatVersion: Int,
   payload }`, where the envelope's own schema never changes and is decoded *independently*
   of the payload. So any engine, forever, can read the version of any save.
2. **Decode the payload to a generic editable document** (a tree of maps/lists/primitives)
   rather than straight into today's typed classes — because old bytes won't match current
   schemas.
3. **Apply an ordered chain of migrations** `vN → vN+1`, each a small, tested
   tree-transform, from the save's version up to current. Component-level changes are tree
   edits keyed by the component's **stable discriminator** (e.g. "for `position`, rename
   field `x`→`col`"); structural changes edit the surrounding tree.
4. **Hand the final (current-version) tree to the registry's `SerializersModule`** for the
   normal typed decode. The registry and current classes never learn about old versions —
   only the migration chain does.

A migration is just `interface SaveMigration { val from: Int; fun apply(doc): doc }`,
registered alongside the `GameModule` so **games can ship migrations for their own
components** the same way they register the components.

**What v1 locks in now (cheap) to avoid corners:**
- The **envelope/payload split** with version-first decoding — so the version is always
  readable and the payload can later be routed through migrations before typed decode. This
  seam is the one thing that's expensive to retrofit, so it exists from v1.
- **Explicit stable discriminators** (already decided) — migrations key off these; class
  renames never matter.
- **Additive, optional-with-default component fields** as the default style — kotlinx
  tolerates added optional fields, so most evolution needs *no* migration at all; reserve
  migrations for genuinely breaking/structural changes.

**One CBOR-specific caveat to verify when we build it:** tree-based migration is most
ergonomic with a format that has a first-class element model (JSON's `JsonElement`).
kotlinx's CBOR support for a generic editable tree is thinner, so the migration step may
need to decode CBOR into a generic structure (or transcode) rather than getting a
`CborElement` for free. If that proves painful, the fallback is per-component
tolerant/custom deserializers for additive changes plus targeted migrations for structural
ones. Storage stays CBOR regardless; this only affects how migrations edit the document.

## Consequences

- New dependency: kotlinx-serialization plugin + CBOR runtime. Every component gains
  `@Serializable` + a registered discriminator.
- `LightCalculators` / `BehaviorStrategies` are replaced by the registries +
  `GameModule`; `BehaviorSystem` / `LightingSystem` take a registry/resolver instead of
  the hard-coded objects.
- Delivers the two headline features: **shareable deterministic seeds** and **exact
  save/resume**, plus a real **engine-extension story** for games.
- Save format is tied to component discriminators (rename-safe) but not yet
  migratable — saves break across format-version changes until migration is added.
- Implementation naturally splits: (1) the serializable RNG + named streams; (2) the
  registry/`GameModule` refactor (replacing the stub objects); (3) the CBOR save/load
  codec over entities/terrain/RNG. (1) and (2) are independently useful before (3).

## Alternatives considered

- **JSON instead of CBOR** — human-readable and easier to debug, but larger (full tile
  grids) and the owner chose compact. (A debug/JSON variant could be added later via the
  same `SerializersModule`.)
- **Single RNG stream** — one `Random` for everything. Rejected: any change to gameplay
  RNG usage shifts world-gen output for the same seed, making shareable seeds fragile.
- **`kotlin.random.Random`** — reproduces from a seed but can't snapshot/restore its
  state, so exact resume would require fragile reseed-and-replay. Rejected for a custom
  serializable generator.
- **Regenerate terrain from the seed** — tiny saves, but ties a save to the world-gen code
  version and can't represent player-modified terrain. Rejected (chose serialized grids).
- **Sealed `Component` hierarchy** — kotlinx would auto-derive polymorphism, but sealed
  types can't be extended from a downstream module. Rejected (engine must be extensible).
- **Single mega-registry (i)** or **fully separate registries (ii)** — (i) couples
  save/load into gameplay code; (ii) is more wiring and has no single coherence point. The
  hybrid (iii) takes the strengths of both.
