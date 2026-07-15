# ADR-0025: The world owns its RNG — determinism by construction, not by discipline

- **Status:** Accepted
- **Date:** 2026-07-15

## Context

ADR-0009 gave korogue a strong reproducibility guarantee — a master seed, named
xoshiro256\*\* streams, "same master seed ⇒ same world + loot", and exact save/resume — and
built the machinery to honour it ([GameRandom], [SerializableRandom], RNG state in the save
format). What it did *not* do is make that guarantee structural. The RNG lived **outside**
the world and was threaded in by the caller, per call:

```kotlin
fun tick(elapsedMs: Long = 0L, random: Random = Random.Default)          // ecs/World.kt
fun zone(..., random: Random = Random.Default, ...)                       // world/GameWorld.kt
class Zone.Builder(..., private val random: Random = Random.Default)      // world/Zone.kt
fun Zone.create(..., random: Random = Random.Default, ...)                // world/Zone.kt
```

Four public entry points defaulted to `kotlin.random.Random.Default` — a source that is
neither seeded nor serializable. Omitting an argument silently forfeited the engine's
headline feature: no error, no warning, just a world that could not be reproduced and a save
that would not resume identically. The footgun was already known and already documented
around rather than fixed — `BehaviorStrategy`'s KDoc warned "never `Random.Default`" while
the default sat one file away.

The evidence that this was a design fault rather than a caller mistake:

- **Both** consumers (the demo and the Rogue example) independently invented the *same*
  hand-threading (`world.ecs.tick(random = gameRandom.stream("gameplay"))`), the same stream
  name, and the same `GameRandom` field carried alongside the world.
- Both also independently hand-rolled `TickContext(..., random = Random.Default)` to prime
  lighting/perception before the first tick — because the API offered no seeded way to run
  one system out of band. Two consumers, two identical wrong answers.
- `SaveCodec.save(world, random, ...)` took the world and its RNG as *separate* arguments,
  so nothing stopped a caller passing a `GameRandom` the world had never drawn from. The
  engine's own `SaveCodecTest` did exactly that and still passed.
- Rogue's `LevelFactory` built its world with `GameWorld.create { }` while `RogueGame` held
  the "real" `GameRandom` beside it — two RNG roots for one game, reconciled only by
  convention.

A guarantee every consumer must re-implement, and that fails silently when they get it
wrong, is not a guarantee. And with 1.0 approaching, all four signatures were about to be
frozen: fixing them later is a source-breaking change.

## Decision

**The world owns its RNG.** [`World`][w] takes a [`GameRandom`][gr] at construction and
exposes it; there is no API path to a world with an unseeded one.

```kotlin
class World(val random: GameRandom = GameRandom.random()) {
    fun tick(elapsedMs: Long = 0L)                       // no `random` parameter at all
    fun tickContext(elapsedMs: Long = 0L): TickContext   // seeded ctx for out-of-band system runs
}
```

Concretely:

- **`World.tick` loses its `random` parameter.** Systems receive `TickContext.random` =
  the world's `GameRandom.stream(GAMEPLAY)`. A caller cannot supply an RNG, so a caller
  cannot supply a bad one.
- **Two stream names are engine constants**, not consumer convention: `GameRandom.GAMEPLAY`
  ("gameplay") and `GameRandom.WORLDGEN` ("worldgen") — both consumers had already
  converged on these strings.
- **`GameWorld.Builder`/`create` take the `GameRandom`** and thread it into the built
  `World`. `Builder.zone(...)`'s `random` now defaults to the world's `WORLDGEN` stream
  rather than `Random.Default`, so zones are reproducible by default.
- **`GameWorld.random`** surfaces `ecs.random`, so game code holding a world can draw its
  own named streams (`world.random.stream("loot")`) without plumbing one alongside.
- **`SaveCodec.save(world, fog, schedule)` drops its `random` parameter** and reads
  `world.random`; `load` restores into `World(GameRandom.restore(...))`. The RNG rides with
  the world, so a mismatched pair is now unrepresentable. `LoadedGame.random` remains as a
  derived convenience (`world.random`), not a second source of truth.
- **`World.tickContext(elapsedMs)` is new**, and replaces the priming hack both consumers
  invented: it hands back a seeded `TickContext` for running one system outside a tick
  (priming derived state like the light map before the first turn) without advancing the
  turn or fabricating an RNG.
- **`Zone.Builder`/`Zone.create` keep an explicit `random: Random` — now required, with no
  default.** These are the standalone terrain escape hatch, built without a `GameWorld` and
  so without a master seed to inherit. Requiring the argument makes the caller's choice
  explicit rather than defaulting them into non-determinism.

The invariant, stated once: **randomness that affects game state is reachable only through
the world that owns it.**

## Consequences

Easier:

- Reproducibility is the default and the path of least resistance. `world.ecs.tick()` is
  both the shortest thing to write and the correct one; the old correct call was the longer
  one. `DeterminismTest` proves same-seed replay and exact save/resume end-to-end while
  never passing an RNG anywhere.
- Consumers shed the bookkeeping. Rogue's `Dungeon` lost its `worldgen` constructor
  parameter (it reads `world.random`), `Dungeon.begin` collapsed from two stream arguments
  to one `GameRandom`, `Dungeon.restore` lost one, and `SaveRestoreTest`'s helper went from
  `Triple<Dungeon, GameRandom, ZoneFog>` to `Pair<Dungeon, ZoneFog>`.
- A latent Rogue bug is now unrepresentable: `LevelFactory` built its world *without* the
  game's `GameRandom`, so the world and the game had different RNG roots. Making the world
  own the RNG forced them into one.

Harder / accepted:

- **Source-breaking** for the four signatures plus `SaveCodec.save` and `LoadedGame` —
  deliberately taken now, before 1.0 freezes them. Migration is mechanical (drop the
  argument; pass the seed at construction).
- A per-tick RNG override is gone. Tests that wanted a fixed sequence pass
  `World(GameRandom.fromSeed(n))` instead of `tick(random = Random(n))` — arguably better,
  since it pins the whole world rather than one call. Tests whose systems draw no randomness
  simply call `tick()`.
- The ECS core now depends on `korogue.random`. This does not violate ADR-0007's
  "`ecs.World` stays game-agnostic": seeded randomness is engine infrastructure, like the
  event bus, not a game concept like zones or tiles.
- A strategy reaching for `Random.Default` *inside* `decide` still breaks replay. That is
  now the only remaining way to do so, it requires writing the words, and
  `BehaviorStrategy`'s contract calls it out.

Not addressed here: normalizing the built-in systems' constructor shapes (krogue-cjv) and
an engine-provided default pipeline (krogue-32d), both of which touch the same wiring.

## Alternatives considered

- **Remove the dangerous defaults; keep `random` a required parameter.** The minimal fix:
  still source-breaking, but the caller keeps hand-threading the RNG and `tick(random =
  Random.Default)` stays perfectly legal. It narrows the footgun without closing it, and
  fails ADR-0009's actual requirement — that reproducibility hold *without the caller having
  to remember*. Rejected: same breakage, less of the benefit.
- **Keep the defaults; document harder.** Already tried, in the exact place it mattered
  (`BehaviorStrategy`'s KDoc), and both consumers still got it wrong. A guarantee enforced
  by documentation is a guarantee enforced by nothing.
- **`World` holds a bare `SerializableRandom` instead of a `GameRandom`.** Simpler, but it
  gives the world one unnamed stream with no master seed, so it can't derive the isolated
  named streams ADR-0009 requires, and the save codec would have to persist it separately
  from the `GameRandom` a game already keeps.
- **A lint rule / detekt check banning `Random.Default`.** Catches the symptom in *our*
  tree only. Consuming games — the whole point of an engine — get nothing, and the API
  still reads as though `Random.Default` were an acceptable answer.
- **Give `Zone.create` a seeded default (e.g. derived from `zoneId`).** Would spare the
  escape-hatch caller an argument, but silently ties terrain to a hash of the zone id rather
  than the master seed — deterministic yet disconnected from the game's seed, which is a
  subtler surprise than being asked for the stream outright.

[w]: ../../engine/src/main/kotlin/com/sletmoe/korogue/ecs/World.kt
[gr]: ../../engine/src/main/kotlin/com/sletmoe/korogue/random/GameRandom.kt
