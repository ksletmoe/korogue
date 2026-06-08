# krogue / kotile Status

> Current-state snapshots. Design rationale is in [ARCHITECTURE.md](ARCHITECTURE.md);
> the live task backlog is in **beads** — run `bd list` / `bd ready`.

_Last updated: 2026-06-06 (after Phase 4e: multi-zone transitions)._

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
  component), and a CBOR `SaveCodec` (`save`/`load` over entities + terrain + RNG, versioned
  envelope). Remaining Phase 4: 4c (event bus), 4d (AI strategy abstraction; mostly done via
  the strategy registry).

## krogue current state

- Renders via `com.sletmoe.krogue.kotile.*`: `Game` (ApplicationAdapter loop,
  `render()`→`onTick()`), `MyGame` (demo: player + lantern, two zones linked by `>`/`<`
  stairs), `Main` (Lwjgl3 entry), `KotileZoneRenderer` (FOV + lighting tints + viewport +
  previously-seen dimming; reads ECS entities; rebuilt on zone change). Run with `./gradlew run`.
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
- **~204 tests** (`./gradlew test`). Example-based Kotest.
- Single map pane only — the old multi-pane topbar/sidebar layout was removed in the
  renderer cutover; how to restore it is an open question (kotile layout primitives vs
  krogue-side).

### Known issues / cleanups still open
- AI strategies (`wander`, `hunt-player`) carry a 2%-per-tick act gate so creatures stay
  sane under the per-frame tick; revisit once the loop is turn-based.
- Ticking the ECS world once per frame is interim — a turn-on-input loop is still wanted
  (would also let AI act every turn without the throttle above).
- Player death/HP feedback is unhandled (`CombatSystem` spares the player) — see krogue-4zi.
- Fog-of-war ("previously seen" dimming) does not persist per zone — the renderer is rebuilt
  on a zone change, so revisiting a zone re-explores it. Per-zone fog memory is a follow-up.
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
