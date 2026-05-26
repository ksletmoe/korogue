# AGENTS.md

Guidance for AI coding agents working in this repository.

## What kotile is

kotile is a Kotlin/JVM library for rendering grids of tiles, built on
**LibGDX** with its **LWJGL3** desktop backend. It supports two tile styles
on the same rendering core:

- **Image sprite sheets** — slice any image into tiles and draw them, with an
  optional per-tile color tint.
- **ASCII tiles** — a CP437 bitmap font drawn as foreground/background colored
  cells.

Rendering is immediate-mode: tile state is held in memory and the whole
visible grid is redrawn every frame via a batched `SpriteBatch`.

## Build, run, test

The project uses the Gradle wrapper (Gradle 9.5.1, Kotlin 2.3.21, JDK 21
toolchain). Run everything through `./gradlew`.

```bash
./gradlew compileKotlin   # fast compile check
./gradlew build           # compile + assemble + tests
./gradlew run             # launch the demo app (opens a window)
./gradlew installDist     # stage a runnable distribution under build/install/kotile
```

There are no tests yet; `build` still validates compilation and assembly.

## Running headless (no display / CI / agents)

The app is a GUI program, so a display and an OpenGL context are required.
In a headless environment use a virtual framebuffer plus Mesa software GL, and
the built-in snapshot hook (`-Dkotile.snapshot=<path>`) which renders one
frame to a PNG and exits:

```bash
./gradlew installDist -q
LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe \
  KOTILE_OPTS="-Dkotile.snapshot=$PWD/render.png" \
  xvfb-run -a -s "-screen 0 1024x768x24" build/install/kotile/bin/kotile
```

`render.png` is gitignored. Pass JVM flags to the installed app via the
`KOTILE_OPTS` env var (Gradle's `run` does NOT forward `-D` to the forked JVM).

## Architecture

Source lives under `src/main/kotlin`. Package root: `com.sletmoe.kotile`.

- `display/KotileCanvas` — wraps a `SpriteBatch`; draws tile-sized
  `TextureRegion`s with an optional tint. Tile coordinates are **top-left
  origin, y increasing downwards**; the canvas maps that onto the GPU's
  bottom-left origin.
- `tiles/TileSheet` — slices an image into a grid of `TextureRegion`s. Takes
  an optional `keyColor` that is zeroed out to transparent on load (so a sheet
  with a solid background color can alpha-blend). Nearest filtering keeps
  pixel art crisp.
- `tiles/StaticTile` — a sheet cell `(sheetX, sheetY)` plus a `tint`
  (default `Color.WHITE` = unmodified).
- `rendering/TileRenderer` — abstract; holds a `LayeredTilemap` of
  `StaticTile`s (z-ordered) and redraws it each frame, applying each tile's
  tint. Subclasses map a tile to its region.
- `rendering/SpriteTileRenderer` — concrete `TileRenderer` backed by a
  `TileSheet`. The entry point for image sprite-sheet rendering.
- `display/ascii/` — the ASCII layer: `AsciiTileWindow` (holds a grid of
  descriptors, draws a background quad + foreground-tinted glyph per cell),
  `AsciiTileDescriptor` (char + fg/bg color), and `Font`/`Fonts` (loads a
  16x16 CP437 sheet; key color defaults to black, overridable).
- `utilities/` — `Grid<T>` (flat 2D array), `LayeredTilemap` (z-layered tile
  storage with dirty tracking), `Vector2Int`, `Vector3Int`.

## How tint works

`SpriteBatch` multiplies each texel by the tint color (including alpha), so a
tint can only darken/recolor and can fade via alpha. `Color.WHITE` is the
identity (no manipulation) and is the default everywhere tint is accepted.

## Conventions

- Kotlin official code style.
- LibGDX resources that own native memory (`Texture`, `SpriteBatch`,
  `TileSheet`, `KotileCanvas`, `AsciiTileWindow`) implement/use `Disposable`;
  dispose them when done.
- Assets go in `src/main/resources` and are loaded via
  `Gdx.files.classpath(...)`. Bundled third-party assets must be open-licensed;
  record provenance next to the file (see `vaarn-8x8.license.txt`, CC0).

## Git

Active development happens on the `task/initial-implementation` branch.
