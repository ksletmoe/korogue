# AGENTS.md

Guidance for AI coding agents working in this repository.

## What kotile is

kotile is a Kotlin/JVM library for rendering grids of tiles, built on
**LibGDX**. It supports two tile styles on the same rendering core:

- **Image sprite sheets** — slice any image into tiles and draw them, with an
  optional per-tile color tint.
- **ASCII tiles** — a CP437 bitmap font drawn as foreground/background colored
  cells.

Rendering is immediate-mode: tile state is held in memory and the whole
visible grid is redrawn every frame via a batched `SpriteBatch`.

## Project layout

A Gradle multi-module build:

- **`:library`** — the published artifact (`com.sletmoe:kotile`). Depends only
  on **gdx core** (`api`), so consumers choose their own gdx backend. Bundles
  the CP437 font. Its tests add the LWJGL3 backend to get a GL context.
- **`:demo`** — a runnable LWJGL3 application showing both tile styles. Depends
  on `:library` plus the `gdx-backend-lwjgl3` backend and desktop natives, and
  owns the demo-only assets (the Vaarn sprite sheet).

Shared versions live in `gradle.properties` (`gdxVersion`, `kotestVersion`);
the Kotlin plugin version is pinned in `settings.gradle.kts`.

## Build, run, test

The project uses the Gradle wrapper (Gradle 9.5.1, Kotlin 2.3.21, JDK 21
toolchain). Run everything through `./gradlew`.

```bash
./gradlew build                  # compile + assemble + test both modules
./gradlew :library:test          # library unit tests (GL tests skip, see below)
./gradlew :demo:run              # launch the demo app (opens a window)
./gradlew :demo:installDist      # stage the demo under demo/build/install/kotile
./gradlew :library:publishToMavenLocal   # publish com.sletmoe:kotile to ~/.m2
./gradlew :library:dokkaGenerate         # render API docs to library/build/dokka/html
```

Tests use **Kotest** (`FunSpec`) and live in `:library`. Two kinds:

- **Unit tests** for GL-free logic (`Grid`, `LayeredTilemap`, `StaticSpriteTile`) —
  run anywhere via `./gradlew :library:test`.
- **Headless GL integration tests** (`RenderingIntegrationTest`) that boot a
  real offscreen LWJGL3 context, render through the public API, and assert on
  framebuffer pixels. They are auto-skipped when no display is present
  (`HeadlessGl.available`). To run them, provide a virtual display with
  software OpenGL:

  ```bash
  xvfb-run -a -s "-screen 0 1024x768x24" ./gradlew :library:test --no-daemon
  ```

  The `test` task forwards `DISPLAY` and forces Mesa software GL
  (`LIBGL_ALWAYS_SOFTWARE=1`, `GALLIUM_DRIVER=llvmpipe`), and logs full stack
  traces — CI is the only place these tests run, so its log is the only place
  their failures can be read.

  **One GL application per JVM (krogue-8lo).** `HeadlessGl` boots a single
  `Lwjgl3Application` lazily and reuses it for every render, on its own daemon
  thread. It used to boot one *per render* (~60 per JVM), which made the whole GL
  suite flaky: context creation eventually failed on a loaded runner and then
  every later GL test failed, in every spec. Two consequences for anyone writing
  these tests:
  - **Dispose what you create.** Renders share a GL context now, so a leaked
    renderer outlives its test instead of dying with its application.
  - **Renders share the window.** `HeadlessGl` resizes it per render and clears
    both the capture FBO *and* framebuffer 0 — libGDX FBOs do not nest, so
    anything drawn through `GridCompositeCache` actually lands on the window's
    back buffer (see krogue-s5h).

  These tests cannot run on macOS at all: GLFW must own the first thread and
  Gradle's test workers do not, which is what the `DISPLAY` check really gates.
  To drive GL locally on macOS, use the `JavaExec` + `-XstartOnFirstThread`
  harness pattern in `kotile/demo/build.gradle.kts` (`renderHarness`,
  `spriteHarness`) — a `main()` owns the first thread, so GLFW is happy.

## Running headless (no display / CI / agents)

The demo is a GUI program, so a display and an OpenGL context are required.
In a headless environment use a virtual framebuffer plus Mesa software GL, and
the built-in snapshot hook (`-Dkotile.snapshot=<path>`) which renders one
frame to a PNG and exits:

```bash
./gradlew :demo:installDist -q
LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe \
  KOTILE_OPTS="-Dkotile.snapshot=$PWD/render.png" \
  xvfb-run -a -s "-screen 0 1024x768x24" demo/build/install/kotile/bin/kotile
```

`render.png` is gitignored. Pass JVM flags to the installed app via the
`KOTILE_OPTS` env var (Gradle's `run` does NOT forward `-D` to the forked JVM).

## Publishing

`:library` applies `maven-publish` and publishes `com.sletmoe:kotile` with
three jars: the classes, a **sources** jar, and a **javadoc** jar packaged from
the Dokka HTML output. The POM declares only **gdx core** as a dependency — the
LWJGL3 backend and natives are test-only and do not leak to consumers. Maven
Central would additionally require GPG signing and Sonatype credentials (not
set up here).

## Documentation

The public API is documented with **KDoc**. `org.jetbrains.dokka` (applied in
`:library`) renders it: `./gradlew :library:dokkaGenerate` writes HTML to
`library/build/dokka/html`, and the same output is bundled as the javadoc jar.
The JDK external doc link is disabled (the JDK docs aren't reachable here) and
`reportUndocumented` is on, so undocumented public declarations surface as build
warnings — keep new public API documented.

## Architecture

Library source lives under `library/src/main/kotlin`. Package root:
`com.sletmoe.kotile`. The demo's `Main.kt` is in `:demo`.

- `display/KotileCanvas` — wraps a `SpriteBatch`; draws tile-sized
  `TextureRegion`s with an optional tint. Tile coordinates are **top-left
  origin, y increasing downwards**; the canvas maps that onto the GPU's
  bottom-left origin.
- `tiles/TileSheet` — slices an image into a grid of `TextureRegion`s. Takes
  an optional `keyColor` that is zeroed out to transparent on load (so a sheet
  with a solid background color can alpha-blend). Nearest filtering keeps
  pixel art crisp.
- `tiles/SpriteTile` — sealed base for sprite cell content, with two branches
  (krogue-xcx): `StaticSpriteTile` (a sheet cell `(sheetX, sheetY)` plus a
  `tint`, default `Color.WHITE` = unmodified; the renderer resolves its region)
  and `DynamicSpriteTile` (owns its own region for a given elapsed time).
  `AnimatedSpriteTile` is the built-in `DynamicSpriteTile`, cycling frames.
- `rendering/TileRenderer` — abstract; holds a `LayeredTilemap` of `SpriteTile`s
  (z-ordered) and redraws it each frame, applying each tile's tint. Subclasses
  map a `StaticSpriteTile` to its region; `DynamicSpriteTile`s resolve their own.
  The **sprite sibling of `AsciiTileWindow`**: the two share one vocabulary
  (ADR-0028) — `widthInTiles`/`heightInTiles`, `resize`, `drawTile`/`clearTile`/
  `clear`/`clearLayer`/`fill`, `topTileAt`, `render`, `asLayer`. Adding a member
  to one path means mirroring it on the other, or listing it as deliberately
  path-specific in `RenderPathParityTest` — which fails on unexplained drift.
- `rendering/SpriteTileRenderer` — concrete `TileRenderer` backed by a
  `TileSheet`. The entry point for image sprite-sheet rendering.
- `display/ascii/` — the ASCII layer: `AsciiTileWindow` (holds a grid of cells,
  draws a background quad + foreground-tinted glyph per cell) and `Font`/`Fonts`
  (loads a 16x16 CP437 sheet; key color defaults to black, overridable). Cell
  content mirrors the sprite hierarchy one-for-one: sealed `AsciiTile` ->
  `StaticAsciiTile` (char + fg/bg color) + `DynamicAsciiTile` (resolves to a
  `StaticAsciiTile` at a given time), with `AnimatedAsciiTile` the built-in
  `DynamicAsciiTile`. Keep the two hierarchies' names in step.
- `utilities/` — `Grid<T>` (flat 2D array), `LayeredTilemap` (z-layered tile
  storage), `Vector2Int`, `Vector3Int`.

## How tint works

`SpriteBatch` multiplies each texel by the tint color (including alpha), so a
tint can only darken/recolor and can fade via alpha. `Color.WHITE` is the
identity (no manipulation) and is the default everywhere tint is accepted.

## Conventions

- Kotlin official code style.
- LibGDX resources that own native memory (`Texture`, `SpriteBatch`,
  `TileSheet`, `KotileCanvas`, `AsciiTileWindow`) implement/use `Disposable`;
  dispose them when done.
- Assets are loaded via `Gdx.files.classpath(...)`. Library assets (the CP437
  font) live in `library/src/main/resources` and ship in the published jar;
  demo-only assets live in `demo/src/main/resources`. Bundled third-party
  assets must be open-licensed; record provenance next to the file (see
  `demo/src/main/resources/vaarn-8x8.license.txt`, CC0).

## Git

Active development happens on the `task/initial-implementation` branch.
