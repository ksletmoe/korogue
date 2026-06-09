# krogue / kotile Status

> Current-state snapshots. Design rationale is in [ARCHITECTURE.md](ARCHITECTURE.md);
> the live task backlog is in **beads** — run `bd list` / `bd ready`.

_Last updated: 2026-06-08 (after Phase 4c: event bus, and 4d: AI strategy abstraction — Phase 4 complete)._

## Migration arc (done)

krogue was migrated off its old AWT/Swing/AsciiPanel rendering onto kotile/libGDX,
then given a tested ECS foundation:

- **Phase 0–2** ✅ — toolchain modernized (Kotlin 2.3.21 / JDK 21 / Gradle 9.5.1);
  kotile API hardened (layers, viewport, animation, input).
- **Phase 3** ✅ — krogue renders entirely through kotile; 100% `java.awt`-free
  (color → GDX, geometry → kotile `Vector2Int` + `IntRect`); test suite built from
  zero; lighting verified.
- **Phase 4a** ✅ — ECS core (`com.sletmoe.krogue.ecs`).
- **Phase 4b** ✅ — existing entities migrated onto ECS: `GameWorld` composes `ecs.World`
  (ADR-0007); occupants are entities; renderer reads ECS; behavior, movement, combat, and
  lighting are systems; all legacy `world/` entity classes deleted. Unblocks 4c–4f.
- **Phase 4e** ✅ — multi-zone transitions (ADR-0008): active-only simulation
  (`GameWorld.simulatedZones()` seam, scoped `Behavior`/`Lighting`), `Portal` + `PortalSystem`,
  zones persist while dormant.
- **Phase 4f** ✅ — save/load (ADR-0009): seeded serializable RNG (xoshiro256** + named
  streams → shareable seeds + exact resume), `GameModule`/registries (strategy/calculator/
  component), and a CBOR `SaveCodec` (`save`/`load` over entities + terrain + RNG + per-zone
  fog (krogue-k77), versioned envelope).
- **Phase 4c** ✅ — event bus (ADR-0010): a generic engine-side `EventBus` (`ecs`) with a
  publish-enqueues / `dispatch`-delivers model, owned by `World` and drained once per tick
  after all systems run (deterministic, observers see a consistent end-of-tick world).
  Game events live in `com.sletmoe.krogue.events` (`EntityDamaged`/`EntityDied`/`ZoneChanged`);
  `CombatSystem` and `PortalSystem` emit them. Transient — not part of save state. Decouples
  notifications from the intent-component mechanics pipeline.
- **Phase 4d** ✅ — AI strategy abstraction (the machinery landed with the 4f registry;
  ADR-0009): formalized the `BehaviorStrategy` extension point into its own file with a
  consumer-facing contract, built-ins `wander`/`hunt-player` (`BehaviorStrategies.kt`), and
  documented the extension path (ARCHITECTURE.md "Extending the engine" — write → register on
  `GameModule` → tag with `Behavior(id)` → `BehaviorSystem` resolves), proven end-to-end by a
  test driving a custom-registered strategy through the real registry. **Phase 4 complete.**

## krogue current state

- Renders via `com.sletmoe.krogue.kotile.*`: `Game` (ApplicationAdapter loop,
  `render()`→`onTick()`), `MyGame` (demo: player + lantern, two zones linked by `>`/`<`
  stairs), `Main` (Lwjgl3 entry), `KotileZoneRenderer` (FOV + lighting tints + viewport +
  previously-seen dimming; reads ECS entities; rebuilt on zone change but handed the zone's
  retained fog grid, so exploration persists — see `ZoneFog`, krogue-ro8). Run with `./gradlew run`.
- **100% `java.awt`-free.** Colors are GDX `Color`; geometry is kotile `Vector2Int`
  + `com.sletmoe.krogue.utilities.IntRect` (helpers in `utilities/Geometry.kt`).
- **ECS-based (Phase 4b done).** `GameWorld` composes `ecs.World` + a zone registry
  (ADR-0007); occupants (player, creatures, lights) are ECS entities, `Zone` is terrain
  + a `LightingSystem`-written `lightMap`. Behavior/movement/combat/lighting are systems
  (`Behavior`→`MoveIntent`→`MovementSystem`→`AttackIntent`→`CombatSystem`); player input
  and AI both emit `MoveIntent`s. The `world/` package is now just `GameWorld`, `Zone`,
  `Tile` — all legacy entity classes (`Creature`/`LightSource`/`MovableEntity`/`Entity`/
  `ZonalPosition`/`Pointer`) are deleted.
- **Multi-zone (Phase 4e).** Only the player's zone is simulated/rendered; others freeze
  and persist. Transitions are `Portal` entities resolved by `PortalSystem`. The simulated
  set is a seam (`GameWorld.simulatedZones()`) so "current + adjacent" later is config, not
  a redesign (krogue-s67).
- **Event bus (Phase 4c).** `World.events` (`EventBus`) is pub/sub for decoupled
  notifications, distinct from the intent-component pipeline: intents drive intra-tick
  mechanics (ordered, consumed), events fan out observe-only notifications dispatched at
  end of tick (ADR-0010). `CombatSystem`/`PortalSystem` emit `EntityDamaged`/`EntityDied`/
  `ZoneChanged`; no runtime subscriber yet — it's infrastructure for death handling
  (krogue-4zi), a combat-log UI, and per-zone fog memory (krogue-ro8).
- **Per-zone fog memory (krogue-ro8 / krogue-k77).** Fog-of-war ("previously seen") memory is
  owned by the host (`ZoneFog`, one `Grid<Boolean>` per zone) and injected into
  `KotileZoneRenderer` rather than created inside it, so the renderer rebuild on a zone change
  no longer wipes exploration; revisiting a zone keeps remembered areas. It also persists across
  save/load: fog is a per-zone player-knowledge section of the save envelope (`SaveData.fog`,
  additive/default-empty so old payloads still load), carried by `SaveCodec` as a
  `Map<String, Grid<Boolean>>` (the codec stays decoupled from the renderer); `ZoneFog.snapshot()`
  / `restore()` bridge it. The demo wires this to **F5 (save) / F9 (load)** — `MyGame.load`
  swaps in the loaded world/RNG/fog, re-registers systems, re-resolves the player, and rebuilds
  the UI bound to the new world (krogue-ar9). Verified in-game: position, world, RNG, and
  remembered fog all restore, including across zone transitions.
- **UI toolkit (krogue-ui-layout epic, ADR-0011).** A krogue-side, retained-mode TUI widget
  toolkit in `com.sletmoe.krogue.ui`, on top of kotile's z-layered `AsciiTileWindow` (no kotile
  changes). `TileSurface` is the draw seam (fake-able → the UI, incl. the map, is headlessly
  testable); `UiRoot` composites a widget layer stack in one pass and routes input to the topmost
  modal (dim-behind via `RegionSurface`). Widgets: `MapPanel` (the map is now a widget, retiring
  `KotileZoneRenderer`), `Frame` (CP437 border+title), `BarWidget` (HP/mana), `Menu` (selectable
  list), `Dialog` (opaque modal that dims the rest), `LogPanel` (scrollable bordered log). The demo
  is a `MapPanel` + a `LogPanel` combat-log sidebar (the **event bus**'s first consumer —
  `EntityDamaged`/`EntityDied` → messages) over a bordered status strip with a live **HP bar**,
  plus an Esc **system menu** (resume/save/load/quit, pauses the world) and a **game-over dialog**
  at 0 HP (krogue-4zi). Only the inventory panel (sgs) remains on the epic — it waits for a real
  item model.
- **~279 tests** (`./gradlew test`). Example-based Kotest.

### Known issues / cleanups still open
- AI strategies (`wander`, `hunt-player`) carry a 2%-per-tick act gate so creatures stay
  sane under the per-frame tick; revisit once the loop is turn-based.
- Ticking the ECS world once per frame is interim — a turn-on-input loop is still wanted
  (would also let AI act every turn without the throttle above).
- Player HP is shown (HP bar) and death is handled (game-over dialog at 0 HP; krogue-4zi done).
  `CombatSystem` still spares the player from despawn — the game-over modal freezes the world and
  offers load/quit rather than removing the entity.
- Save files are large (~13 MB for the demo's two 200×200 = 80k-cell zones): terrain dominates
  because each `Tile` serializes its full state (name string, colors) per cell, with no interning
  or RLE (walls are tiles too — only ~6k of 40k cells per zone are carved floor). Functionally
  fine; compaction is a follow-up — krogue-yox.
- Package `com.sletmoe.krogue.kotile.*` is an odd home for krogue's own classes
  (consider `.rendering`).
- README is still a TODO.

## kotile current state (sibling repo `~/development/kotile`)

- `com.sletmoe:kotile`, libGDX 1.14.1, 1.0-SNAPSHOT. maven-publish configured but **not
  yet on Sonatype** (needs credentials).
- API: generic `LayeredTilemap<T>` z-layers; `AsciiTileWindow` (z-layered, fitToWindow,
  viewport render, animation); sprite `TileRenderer`; `TileViewport`; animation framework;
  `com.sletmoe.kotile.input` (pixel→tile).
- **macOS NPOT glyph gotcha (fixed, but watch for regressions):** `TileSheet` uploaded the
  font atlas as a non-power-of-two texture; Apple's GL driver mishandles sampling
  *sub-regions* of NPOT textures → garbled glyphs on **macOS only**. Fixed by padding the
  sheet to the next power of two + `ClampToEdge`. CI missed it because the GL tests run
  Linux Mesa llvmpipe (handles NPOT) and self-skip on macOS (`$DISPLAY` unset). The
  `:demo:renderHarness` / `:demo:spriteHarness` PNG-dump tasks diagnose on macOS, and
  `NpotTileSheetTest` now guards the regression on CI (asserts the uploaded texture is
  power-of-two and that slicing is unchanged). Done — `krogue-kotile-npot-citest`.
- Deferred: bundle a 12×12 CP437 font asset (needs a license/provenance decision);
  Dokka V1→V2.

## Two bugs found by the test pass (both fixed)

- `Grid.boundingBoxForCircle` off-by-one dropped the east/south boundary tiles of a
  radius (clipped light circles). Fixed (`+1` on width/height).
- `NormalizedRgb.toColor()` had no lower clamp; negative channels passed through. Fixed
  (`coerceIn(0f, 1f)`).

## Working setup

This project moved off the RuFlo/claude-flow framework to a lean native setup:
**beads** for the task backlog, checked-in markdown (`docs/`) for design knowledge,
and native Claude Code subagents (general-purpose + your own `.claude/agents/*.md`) for
delegation. Match tool weight to task: delegate self-contained slices, do small
interlocking changes inline, and have a fresh-context agent review keystone work.
