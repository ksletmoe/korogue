# korogue / kotile Status

> Current-state snapshots. Design rationale is in [ARCHITECTURE.md](ARCHITECTURE.md);
> the live task backlog is in **beads** — run `bd list` / `bd ready`.

_Last updated: 2026-07-17 (TileSurface dynamic-tile seam + kotile shared-canvas blend fix)._

## Recent (2026-07-17)

- **`TileSurface` carries kotile's dynamic-tile branch (krogue-2co, ADR-0033).** The engine UI
  seam gained `put(x, y, z, tile: AsciiTile)` beside the glyph/`fg`/`bg` primitive, so a widget can
  place a time-varying cell (e.g. `AnimatedAsciiTile` torch flicker) that the window animates on its
  own clock. Non-breaking: the overload defaults to the tile's first frame, and every existing caller
  (incl. the whole korogue-rogue port) compiles/tests unchanged. `WindowSurface`/`RegionSurface`
  carry the live tile through; the animation showcase demo now places its torch flicker through the
  engine instead of bypassing it.
- **kotile: shared-canvas composite blend (krogue-a24).** `GridCompositeCache`'s blit is
  `BlendMode.REPLACE` (authoritative — erases a cell's own stale pixel; krogue-asz), which erased
  *neighbor panes* on a shared canvas because a window's unpopulated cells overwrote them. New
  `sharesCanvas` flag on `SpriteTileRenderer`/`TileRenderer` and `AsciiTileWindowConfig` blits
  `NORMAL` instead for multi-pane layouts (default stays REPLACE). This was a pre-existing regression
  (bisected to `3bff9b4f1`) that had left the split-screen animation showcase's sprite half blank.

## Migration arc (done)

korogue was migrated off its old AWT/Swing/AsciiPanel rendering onto kotile/libGDX,
then given a tested ECS foundation:

- **Phase 0–2** ✅ — toolchain modernized (Kotlin 2.3.21 / JDK 21 / Gradle 9.5.1);
  kotile API hardened (layers, viewport, animation, input).
- **Phase 3** ✅ — korogue renders entirely through kotile; 100% `java.awt`-free
  (color → GDX, geometry → kotile `Vector2Int` + `IntRect`); test suite built from
  zero; lighting verified.
- **Phase 4a** ✅ — ECS core (`com.sletmoe.korogue.ecs`).
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
  Game events live in `com.sletmoe.korogue.events` (`EntityDamaged`/`EntityDied`/`ZoneChanged`);
  `CombatSystem` and `PortalSystem` emit them. Transient — not part of save state. Decouples
  notifications from the intent-component mechanics pipeline.
- **Phase 4d** ✅ — AI strategy abstraction (the machinery landed with the 4f registry;
  ADR-0009): formalized the `BehaviorStrategy` extension point into its own file with a
  consumer-facing contract, built-ins `wander`/`hunt-player` (`BehaviorStrategies.kt`), and
  documented the extension path (ARCHITECTURE.md "Extending the engine" — write → register on
  `GameModule` → tag with `Behavior(id)` → `BehaviorSystem` resolves), proven end-to-end by a
  test driving a custom-registered strategy through the real registry. **Phase 4 complete.**

## korogue current state

- Renders via `com.sletmoe.korogue.app.Game` (ApplicationAdapter loop, `render()`→`onTick()`),
  `MyGame` (demo: player + lantern, two zones linked by `>`/`<`
  stairs), `Main` (Lwjgl3 entry), `KotileZoneRenderer` (FOV + lighting tints + viewport +
  previously-seen dimming; reads ECS entities; rebuilt on zone change but handed the zone's
  retained fog grid, so exploration persists — see `perception.ZoneFog`, krogue-ro8). Run with
  `./gradlew :demo:run`.
- **100% `java.awt`-free.** Colors are GDX `Color`; geometry is kotile `Vector2Int`
  + `com.sletmoe.korogue.utilities.IntRect` (helpers in `utilities/Geometry.kt`).
- **ECS-based (Phase 4b done).** `GameWorld` composes `ecs.World` + a zone registry
  (ADR-0007); occupants (player, creatures, lights) are ECS entities, `Zone` is terrain
  + a `LightingSystem`-written `lightMap`. Behavior/movement/combat/lighting are systems
  (`Behavior`→`MoveIntent`→`MovementSystem`→`AttackIntent`→`CombatSystem`); player input
  and AI both emit `MoveIntent`s. The `world/` package is now just `GameWorld`, `Zone`,
  `Tile` — all legacy entity classes (`Creature`/`LightSource`/`MovableEntity`/`Entity`/
  `ZonalPosition`/`Pointer`) are deleted.
- **System API + standard pipeline (ADR-0032).** The built-in system constructors share one
  convention: a zone-scoped system takes the `GameWorld` (reading terrain + `simulatedZones()`
  from it, no per-system `activeZones` knob) and resolves ids through a typed `Registry<T>`.
  `GameWorld.installStandardSystems(module, …)` registers the built-ins in a known-good order
  for a game that wants the default simulation. Order is enforced: `Staged` systems declare a
  `pipeline.StandardStage`, and `World.tick()` throws `PipelineOrderException` on a violated
  ordering constraint (e.g. perception before lighting) — previously a silent bug. Keys on the
  stage not the class, so a game replacing a built-in keeps the check for its hand-built
  pipeline. `PerceptionSystem` now lives in `perception/` beside its model (krogue-elm).
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
- **UI toolkit (krogue-ui-layout epic, ADR-0011).** A korogue-side, retained-mode TUI widget
  toolkit in `com.sletmoe.korogue.ui`, on top of kotile's z-layered `AsciiTileWindow` (no kotile
  changes). `TileSurface` is the draw seam (fake-able → the UI, incl. the map, is headlessly
  testable); `UiRoot` composites a widget layer stack in one pass and routes input to the topmost
  modal (dim-behind via `RegionSurface`). Widgets: `MapPanel` (the map is now a widget, retiring
  `KotileZoneRenderer`), `Frame` (CP437 border+title), `BarWidget` (HP/mana), `Menu` (selectable
  list), `Dialog` (opaque modal that dims the rest), `LogPanel` (scrollable bordered log),
  `InventoryPanel` (bordered item list). The demo is a `MapPanel` + a sidebar (combat/pickup
  `LogPanel` over an `InventoryPanel`, both fed by the **event bus**) over a status strip with a
  live **HP bar**, plus an Esc **system menu** (resume/save/load/quit, pauses the world) and a
  **game-over dialog** at 0 HP (krogue-4zi). **Epic complete (6/6).**
- **Items & inventory (krogue-sgs).** A minimal model toward the Rogue example: `Item(name)` +
  `Inventory(items)` components (serializable, registered), a `PickupSystem` (walk onto a
  non-blocking item → despawn + add to inventory + `ItemPickedUp` event), and the `InventoryPanel`.
  Names-only for now; richer items grow with krogue-sdh.
- **Game loop modes (krogue-lhw, krogue-k7q).** A pluggable `GameLoop` (`com.sletmoe.korogue.loop`)
  decides when the world advances: `TurnBasedLoop` ticks once per committed player action
  (turn-on-input — the demo's default now, so monsters wait for you and you can't die instantly), or
  `RealTimeLoop` ticks continuously at a **fixed timestep** (`MyGame(turnBased = false)`).
  `RealTimeLoop` accumulates the per-frame `deltaMs` (threaded through `Game.onTick(deltaMs)`) and
  advances once per whole `stepMs` (default 100ms / 10 Hz), with a `maxCatchUpSteps` cap that drops
  the backlog after a long frame — so game speed is independent of render FPS (no more "faster
  machine = faster monsters") and frame hitches don't stutter the sim. Turn-based AI acts every turn
  (the demo registers strategies with `actChance = 1.0`); real-time keeps the per-tick act-chance
  throttle so creatures don't move at frame rate.
- **Timed-effects scheduler (krogue-6uq).** A turn-keyed `Scheduler` (`com.sletmoe.korogue.schedule`,
  modelled on Rogue's `daemons.c`) schedules **fuses** (one-shot, fire after N turns) and **daemons**
  (recurring, every N turns), with `cancel`/`lengthen` by handle. Effects are `TimedEffect`s
  registered by id in `GameModule` (new `effects` registry) — so the schedule is fully serializable
  (`SchedulerState`, a defaulted `SaveData` field) and timers resume across save/load. `SchedulerSystem`
  advances it once per world turn (register it in the system pipeline). The demo wires it through
  registerSystems + save/load but registers no effects yet; it's the foundation for Rogue's hunger
  clock, regen, status-effect expiry, and wandering-monster spawns (krogue-fwh/pyh/m07).
- **Engine pieces surfaced by the Rogue example (ADR-0012).** Building `korogue-rogue` (the separate
  downstream repo at `~/development/korogue-rogue`, consuming korogue via a Gradle composite build)
  as a real consumer turned up gaps now filled in the engine:
  - **Global-illumination lighting (krogue-0vg).** `LightingSystem` takes an `ambientLight:
    LightValue?` baseline (default `null` = the old emitter-only behaviour). Pass
    `LightValue.FULLBRIGHT` for a uniformly-lit zone — the model for games that don't track light
    per-source (Rogue), where map visibility is just line-of-sight; emitters still blend on top.
  - **`Direction` (krogue-0uz).** The 8 compass directions as a grid primitive in
    `utilities` (unit `dx`/`dy`, `opposite`, `from`, `CARDINAL`/`DIAGONAL`, `ofStep`/`between`),
    plus a `MoveIntent(Direction)` constructor. `WanderStrategy` now uses it instead of an ad-hoc
    step list.
  - **`Label` widget (krogue-e43).** A single line of (live) text in the UI toolkit, for status
    lines / captions / HUD readouts — the gap that previously forced a hand-rolled widget.
  - **Uncapped view distance (krogue-f50 → ADR-0015).** Was the interim `MapPanel`-owned
    `maximumVisibilityDistance` (`Double?`, defaulting to `null` = wall-limited). Now **superseded**:
    the per-observer sight radius lives on `Sight.radius` and `MapPanel` no longer owns a cap
    (krogue-1my.4, below).
- **Perception core (krogue-1my.1, ADR-0015).** The third visibility layer's skeleton, in
  `com.sletmoe.korogue.perception`: a `PerceptionModel` query (`perceive(observer, world) ->
  Perceived`) resolved by id via a new `GameModule.perceptionModels` registry, with the engine's
  default `StandardPerception` implementing the **two-phase reveal/suppress** model — phase 1 unions
  every `SenseComponent` the observer carries (each resolved to a registered `Sense` contributor via
  the new `GameModule.senses` registry), phase 2 drops contributions whose sense tags an observer
  `Suppressor` or a target `Concealment` negates, unless the sense `pierces` the tag. `Perceived`
  (perceived cells + entities) doubles as a **derived, per-observer cache component**: opt in by
  attaching an empty `Perceived` and `PerceptionSystem` rewrites it each tick (the `lightMap`
  pattern, active-zone-scoped), while the query stays callable directly for AI/occasional observers.
- **Built-in senses (krogue-1my.2, ADR-0015).** The engine now ships four opt-in sense
  *components* — `Sight`, `Darkvision`, `Tremorsense`, `Telepathy` (each its own type; the ECS keys
  by class) — and their `Sense` *contributors*, registered in `engineDefaults()`. `Sight`
  (`{visual, light-dependent}`) is LOS gated on lighting; `Darkvision` (`{visual}`) is LOS ignoring
  lighting; `Tremorsense` (`{vibration}`) and `Telepathy` (`{mental}`) reveal living creatures
  (`Health`) within radius through walls, contributing entities only. The **view distance moved onto
  `Sight.radius`** (per-observer, mutable, `null` = wall-limited), superseding `MapPanel`'s old cap.
  Engine tag ids live in `PerceptionTags`. LOS senses take a `LineOfSightCalculator` injected at
  registration (defaults to `SymmetricShadowCaster`; no LOS registry yet). The sense components are
  registered for save/load. A basic game now just attaches a `Sight` and perception works; concrete
  concealments/suppressors (`Invisible`, `Blind`) arrive in krogue-1my.3.
- **`MapPanel` renders `Perceived` (krogue-1my.4, ADR-0015).** The renderer no longer computes
  `LOS ∧ lit` or owns a view distance: it draws a **chosen observer's `Perceived`** (the player by
  default, via a `observer: (GameWorld) -> Entity?` selector) and centres the camera on that
  observer — a cell is drawn when perceived, an occupant when *that entity* is perceived (so an
  invisible monster on a lit cell is hidden, a tremor/telepathy contact behind a wall is shown). It
  reads the per-tick `Perceived` cache, so the host attaches an empty `Perceived` to the player and
  runs `PerceptionSystem` after `LightingSystem`. `MapPanel.maximumVisibilityDistance` is **gone**
  (relocated to `Sight.radius`), finishing the krogue-f50 → ADR-0015 migration; `LineOfSightCalculator`
  is now a `fun interface`. Both the demo (`MyGame`, with SPACE still toggling FOV by swapping the
  LOS behind its `SightSense`) and the Rogue example (`korogue-rogue`: player gets a `Sight` + an
  empty `Perceived`) are wired onto the model.
- **Concealment, piercing & suppressors (krogue-1my.3, ADR-0015) — epic complete.** The concrete
  payload for the suppress phase: `Invisible` (a `Concealment` of `{visual}`), `Blind`/`Dazzled`
  (`Suppressor`s of `{visual}`) — all dataless `data object`s in `PerceptionEffects.kt`, registered
  for save/load — and `TrueSight`, a `{visual}` LOS sense that *pierces* `{visual}` (engine built-in,
  registered in `engineDefaults()`). So an invisible monster is hidden from `Sight` (its floor still
  seen) yet felt by `Tremorsense` and seen by `TrueSight`; blindness drops visual senses without
  deleting `Sight`. **Piercing defeats concealment, never suppression** — `TrueSight` sees the
  invisible but is *still blinded by `Blind`*, because a suppressor disables the channel a sense runs
  on; immunity to blindness comes from being on a non-`{visual}` channel (`Tremorsense`), not from
  piercing (ADR-0015, refined here; a game wanting uniform piercing swaps the `PerceptionModel`).
  Transient effects are scheduler-expirable: a fuse (krogue-6uq) removes the `Blind` component to
  restore an untouched `Sight` (proven end-to-end in `PerceptionScenarioTest`).
- **~366 tests** (`./gradlew test`). Example-based Kotest.

### Known issues / cleanups still open
- Player HP is shown (HP bar) and death is handled (game-over dialog at 0 HP; krogue-4zi done).
  `CombatSystem` still spares the player from despawn — the game-over modal freezes the world and
  offers load/quit rather than removing the entity.
- Save files are large (~13 MB for the demo's two 200×200 = 80k-cell zones): terrain dominates
  because each `Tile` serializes its full state (name string, colors) per cell, with no interning
  or RLE (walls are tiles too — only ~6k of 40k cells per zone are carved floor). Functionally
  fine; compaction is a follow-up — krogue-yox.
- README is still a TODO.

## kotile current state (in-repo subprojects `:kotile:library` / `:kotile:demo`, under `kotile/`)

- `com.sletmoe:kotile`, libGDX 1.14.1, 1.0-SNAPSHOT. maven-publish configured but **not
  yet on Sonatype** (needs credentials).
- API: generic `LayeredTilemap<T>` z-layers; `AsciiTileWindow` (z-layered, fitToWindow,
  viewport render, animation); sprite `TileRenderer`; `TileViewport`; animation framework;
  `Layer`/`LayerStack` + free (pixel-space) `EffectsLayer`/`UiLayer` (ADR-0018);
  `com.sletmoe.kotile.input` (pixel→tile **and** pixel→content-pixel).
- **The sprite and ascii render paths share one vocabulary (krogue-0y8, ADR-0028).**
  `TileRenderer` adopted `AsciiTileWindow`'s names — `widthInTiles`/`heightInTiles` (was
  `windowWidth`/`windowHeight`), `resize` (was `onResize`) — and gained the clear/fill/query
  methods it lacked (`clear`, `clearLayer`, `fill`, `topTileAt`, z-defaulted `drawTile`/
  `clearTile`) plus `tileWidthPx`/`tileHeightPx`/`layout`. Its `drawTile` overload pair
  collapsed to one taking the sealed `SpriteTile`. `RenderPathParityTest` reflects over both
  classes and fails on unexplained drift, so mirroring is enforced, not remembered. Kept
  asymmetric on purpose: compositing policy (see krogue-8mr) and construction (abstract
  `regionFor` vs the resource-owning `create {}` factory).
- **`Grid<T>` is the one canonical grid type (krogue-ld1, ADR-0026).** The engine's rival
  `com.sletmoe.korogue.utilities.Grid` is gone; korogue imports
  `com.sletmoe.kotile.utilities.Grid`. kotile's row-major-array version absorbed the general
  parts of the engine's API (`Vector2Int` accessors, `forEach`, `forEachCoordinate`,
  `lastColumnIndex`/`lastRowIndex`, and `copy()` in place of the `Grid.of()` companion).
  `forEachIndexed((Vector2Int, T))` was dropped — it had zero call sites in either repo.
  Gotchas: out-of-bounds now throws `IndexOutOfBoundsException`, not raw `RuntimeException`
  (a narrowing — `IndexOutOfBoundsException` is one, so existing catches hold); zero-sized
  grids are now legal and copyable, where `Grid.of` used to reject them. The roguelike-side
  `forEachCoordinateInRadius` and `Grid<Boolean>.or` stayed in korogue as extensions on
  kotile's `Grid` (they need `IntRect` / `distanceSq`) — see
  `engine/.../utilities/GridExtensions.kt`.
- **Sprite alpha layering (krogue-ejd).** The sprite `TileRenderer` composites a cell's
  z-layers **bottom-up** (`LayeredTilemap.layersBottomUp`) instead of drawing only the top
  cell, so a foreground entity sprite alpha-blends over a background terrain tile and its
  transparent pixels reveal the terrain beneath (`TileSheet` `keyColor`/alpha supply the
  transparency; `SpriteBatch` blends). The ASCII path stays winner-takes-all per cell by
  design. Watch: alpha-keyed tiles packed adjacently and downscaled hard can halo/bleed at
  coarse mip levels — use `TileSheet` `spacing`/padding or `useMipMaps=false` for such sheets
  (see `:demo:spriteHarness` bottom row for the downscaled layering check).
- **GL tests were flaky; the harness was the cause (fixed — krogue-8lo).** `HeadlessGl` booted
  a fresh `Lwjgl3Application` per render (~60 per JVM). On a loaded CI runner, context creation
  eventually failed and then *every* GL test from that point on failed, across all specs — so a
  green build was luck and a red one said nothing about the change under test. Proven by
  re-running: one commit flipped red→green, the commit before it flipped green→red. Now one
  application is booted per JVM and reused. Two things this makes load-bearing: **tests must
  dispose what they create** (the context outlives them now), and **renders share the window**,
  so `HeadlessGl` clears framebuffer 0 as well as its capture FBO — libGDX FBOs don't nest, so
  anything drawn via `GridCompositeCache` lands on the window's back buffer, which is also a
  live constraint on consumers (krogue-s5h).
- **macOS NPOT glyph gotcha (fixed, but watch for regressions):** `TileSheet` uploaded the
  font atlas as a non-power-of-two texture; Apple's GL driver mishandles sampling
  *sub-regions* of NPOT textures → garbled glyphs on **macOS only**. Fixed by padding the
  sheet to the next power of two + `ClampToEdge`. CI missed it because the GL tests run
  Linux Mesa llvmpipe (handles NPOT) and self-skip on macOS (`$DISPLAY` unset). The
  `:demo:renderHarness` / `:demo:spriteHarness` PNG-dump tasks diagnose on macOS, and
  `NpotTileSheetTest` now guards the regression on CI (asserts the uploaded texture is
  power-of-two and that slicing is unchanged). Done — `krogue-kotile-npot-citest`.
- **Mipmapped minification (krogue-4ni).** `TileSheet` now uploads with a mipmap
  chain and a trilinear (`MipMapLinearLinear`) *min*-filter by default, so drawing
  a tile below its native size (fixed-grid `FitScale` < 1x, or a big sprite sheet
  in a small window) averages source pixels instead of dropping them — the
  downscale shimmer is gone. The *mag*-filter stays `Nearest`, so 1x and every
  upscale are pixel-crisp exactly as before. Opt out per-sheet/font with
  `useMipMaps = false`. Safe because the sheet is already POT-padded (the macOS
  NPOT fix). The 1×1 ascii background texture is always magnified, so it is left
  on the default filter. Verified on Linux by eye via `:kotile:demo:fixedGridHarness`
  (downscale before/after) and guarded by `TileSheetFilterTest` (GL-gated).
- **Sharp-bilinear fractional filtering (krogue-m2x).** At a *non-integer* fixed-grid
  scale (`FitScale`), nearest-neighbour makes glyph strokes shimmer/change width.
  `KotileCanvas` now detects a fractional scale and switches its batch to a
  sharp-bilinear shader (`SharpBilinear`) with `Linear` sampling for that frame,
  restoring `Nearest` after — texel interiors stay crisp, only a ~1px edge band
  blends. Because every `drawTile` (glyph **and** sprite tile) flows through the one
  batch, this covers **both** layers, unlike the glyph-only alternatives
  (krogue-19k SDF, krogue-yfs multi-size). Integer/reflow scales are untouched
  (default shader + nearest, pixel-perfect). The heavier FBO supersample
  (krogue-1zo) is now the opt-in alternative for the same fractional case (below).
  Guarded by GL-gated pixel tests in `RenderingIntegrationTest` (sprite blend ≈0.50
  fractional vs 0.0 integer; glyph smoke); eyeball via
  `:kotile:demo:fixedGridHarness -Ppolicy=fit` at a fractional window size.
- **Supersample→gamma-downsample — tier 2 (krogue-1zo, ADR-0036).** Opt in with
  `KotileCanvas(…, fractionalScaleMode = FractionalScaleMode.SUPERSAMPLE)` (or the
  same `fractionalScaleMode` in the `AsciiTileWindow.create` config). At a
  **fractional** scale, instead of the
  lighter sharp-bilinear shader the whole pass — glyphs **and** sprite tiles — is
  captured into an offscreen `SupersampleTarget` FBO at a **large integer** tile
  size (`ceil(scale)`×native, pixel-crisp) and resolved to the window with a
  gamma-correct 2×2 box downsample (`GammaDownsample` shader): each destination
  pixel's source footprint is averaged in **linear light**, so edges keep the
  correct brightness (the black↔white midpoint lands at ~188, not the muddy
  encoded-space 128). This is a canvas-level render-target mode, not a
  `ScalePolicy` and not a glyph-source change; the grid collaborators
  (`AsciiTileWindow`/`TileRenderer`) need no changes because `canvas.layout`
  returns the supersample layout during the capture pass. Integer/reflow scales
  and the default (`SHARP_BILINEAR`) path are untouched. It does **not**
  reproduce Brogue's *smooth-glyph* look (that is tier 3 / krogue-9x7.2 — a bitmap
  source has no high-res detail to recover); it delivers crisp *pixels* at any
  size. The sRGB↔linear curve is unit-tested GL-free (`GammaColorTest`); the
  shader + canvas wiring are guarded by GL-gated pixel tests
  (`SupersampleIntegrationTest`) and, on macOS where those can't run, by the
  main-thread `:kotile:library:ssVerify` harness (which reproduces the specs'
  exact geometry and reads back ~0.735 at a 50/50 edge).
- **Layer model — grid + free (pixel-space) layers (ADR-0018).** `KotileCanvas.drawSprite(pxX,
  pxY, region, w, h, tint)` is the real drawing primitive (`drawTile` is grid-snapped sugar
  over it); a frame is an ordered list of `Layer`s composited back-to-front by a `LayerStack`
  in one `begin`/`end` — no per-layer buffers, overlay is just draw order. Two free-layer
  consumers ride this, differing only in lifetime + input, not in how they render:
  - **Effects (krogue-tk9)** — `EffectsLayer` of transient `Effect`s (sprite/glyph at a float
    pixel pos, linear velocity, optional lifetime) for projectiles/particles that move at
    sub-tile resolution. Eyeball via `:kotile:demo:effectsHarness`.
  - **Free UI (krogue-tvm)** — `UiLayer` of persistent, input-aware `Widget`s (`PixelRect`
    bounds + `render`/`update` + pointer callbacks) for *graphical* tile games: panels, bars
    between rows, tooltips at the cursor. Draws above the grid at arbitrary pixel positions;
    topmost-first pixel hit-testing via `UiLayer.widgetAt`/`onPointer*`. The **input half** of
    ADR-0018: `GridLayout.contentPixelAt` maps a window pixel → content pixel (letterbox
    offset subtracted), and `KotileInputProcessor` now fires `onPointerDown/Up/Moved/Dragged`
    (content-pixel, default no-op) alongside the tile events — grid/text UI is untouched. The
    grid/text toolkit (`Menu`, `Dialog`, …) stays the right choice for ASCII/text UI; `UiLayer`
    does **not** replace it. Eyeball via `:kotile:demo:uiHarness` (a hovered button lights up
    through pixel-space hit-testing). GL-gated pixel tests in `RenderingIntegrationTest`; pure
    logic in `UiLayerTest` / `GridLayoutTest` / `KotileInputProcessorTest`.
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
