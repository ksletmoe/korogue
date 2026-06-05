# krogue / kotile Status

> Current-state snapshots. Design rationale is in [ARCHITECTURE.md](ARCHITECTURE.md);
> the live task backlog is in **beads** — run `bd list` / `bd ready`.

_Last updated: 2026-06-04 (after Phase 4b step 5)._

## Migration arc (done)

krogue was migrated off its old AWT/Swing/AsciiPanel rendering onto kotile/libGDX,
then given a tested ECS foundation:

- **Phase 0–2** ✅ — toolchain modernized (Kotlin 2.3.21 / JDK 21 / Gradle 9.5.1);
  kotile API hardened (layers, viewport, animation, input).
- **Phase 3** ✅ — krogue renders entirely through kotile; 100% `java.awt`-free
  (color → GDX, geometry → kotile `Vector2Int` + `IntRect`); test suite built from
  zero; lighting verified.
- **Phase 4a** ✅ — ECS core (`com.sletmoe.krogue.ecs`).
- **Phase 4b** — in progress (steps 1–5 of 7 done: ECS core, components, `GameWorld`
  composes `ecs.World` + occupants are entities (ADR-0007), renderer reads ECS,
  `LightingSystem`). Steps 6–7 (movement/combat, behavior + delete legacy) remain.
  See `bd list`.

## krogue current state

- Renders via `com.sletmoe.krogue.kotile.*`: `Game` (ApplicationAdapter loop,
  `render()`→`onTick()`), `MyGame` (demo: player + lantern), `Main` (Lwjgl3 entry),
  `KotileZoneRenderer` (FOV + lighting tints + viewport + previously-seen dimming;
  reads ECS entities). Run with `./gradlew runKotile`.
- **100% `java.awt`-free.** Colors are GDX `Color`; geometry is kotile `Vector2Int`
  + `com.sletmoe.krogue.utilities.IntRect` (helpers in `utilities/Geometry.kt`).
- **ECS migration underway (Phase 4b).** `GameWorld` composes `ecs.World` + a zone
  registry (ADR-0007); occupants (player, creatures, lights) are ECS entities, `Zone`
  is terrain + a `LightingSystem`-written `lightMap`. Legacy `World`/`Creature`/
  `LightSource` are gone; `MovableEntity`/`Entity`/`ZonalPosition`/`Pointer` remain
  until 4b-s7. Movement and AI are still interim glue in `MyGame` (→ systems in s6/s7).
- **~190 tests** (`./gradlew test`). Example-based Kotest.
- Single map pane only — the old multi-pane topbar/sidebar layout was removed in the
  renderer cutover; how to restore it is an open question (kotile layout primitives vs
  krogue-side).

### Known issues / cleanups still open
- Interim name-based AI (`"sheep"/"zombie"`) and direct movement live in `MyGame`
  (placeholders → `MovementSystem`/`CombatSystem` in 4b-s6, `BehaviorSystem` in 4b-s7).
- Legacy `MovableEntity`/`Entity`/`ZonalPosition`/`Pointer` still present; deleted in 4b-s7.
- Ticking the ECS world once per frame is interim (turn structure arrives with 4b-s6).
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
