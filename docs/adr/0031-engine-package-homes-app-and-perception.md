# ADR-0031: `app.Game` and `perception.ZoneFog` — retiring the `korogue.kotile` package

- **Status:** Accepted
- **Date:** 2026-07-15

## Context

`com.sletmoe.korogue.kotile.*` held two of korogue's own engine classes, `Game` and
`ZoneFog`. The package name collided conceptually with **kotile**, the separate
lower-level tile renderer published as `com.sletmoe:kotile` (ADR-0001). A newcomer
reading `import com.sletmoe.korogue.kotile.Game` has every reason to think `Game`
belongs to the renderer, when it is the engine's own libGDX application shell.

Two forces made this worth doing now rather than later:

1. **The package name is the import path**, and it is part of the published API surface
   of `com.sletmoe.korogue:engine`. Moving these classes after 1.0 breaks every
   consumer's imports. Before 1.0 it costs a `git mv` and an import rewrite.
2. **The package meant two unrelated things.** `Game` is an `ApplicationAdapter`
   subclass: frame loop, input wiring, `requestRedraw`. `ZoneFog` is per-zone
   explored-cell memory — a `Grid<Boolean>` store that game logic mutates (the Rogue
   port's scroll of magic mapping and `DarkRoomMemorySystem` both write to it) and that
   `SaveCodec` round-trips. The only thing the two shared was "touches the renderer
   somewhere", which is not a package.

The tracking bead (krogue-edn) proposed moving both to `.rendering`. That fixes the
name but keeps one package meaning two things, and `ui.*` already owns the rendering
surfaces (`TileSurface`, `WindowSurface`, `MapPanel`, `MapCamera`) — so `.rendering`
vs `.ui` would have become a fresh coin-flip for the same newcomer.

## Decision

Split the two classes by role and delete the `korogue.kotile` package:

- `com.sletmoe.korogue.app.Game` — the application entry point. `app` names the role
  (this is where a game starts) rather than the backend, so it does not advertise the
  libGDX dependency in the import path, and it reads distinctly against
  `loop.GameLoop` (world-time stepping) and `ui.*` (the surfaces `Game` paints into).
- `com.sletmoe.korogue.perception.ZoneFog` — fog-of-war is accumulated player
  knowledge, so it belongs with the model that produces it. `perception` answers "can
  the observer see it *now*?" (`PerceptionModel`, `Perceived`, `Sense`); `ZoneFog`
  answers "has the player *ever* seen it?". Same axis, different tense.

## Consequences

- The published import path is settled before 1.0; no breaking move later.
- `perception` is now the single place to look for player-knowledge questions, which is
  where `SaveCodec.LoadedGame.fog` and the renderer's `fogFor` lambda already point.
- `app` currently holds exactly one class. That is fine — it is the package a consumer
  reaches for first, and it is where later app-shell concerns (lifecycle hooks, screen
  management) would land rather than being scattered.
- Consumers rewrite two imports. The in-repo demo and the Rogue port (a separate repo,
  consumed via `includeBuild`, ADR-0012) were both updated in lockstep with this change.
- `ZoneFog` sitting in `perception` while carrying no dependency on the perception
  types is a mild wart; the grouping is conceptual, not structural.

## Alternatives considered

- **Both classes to `.rendering`** (the bead's original proposal) — fixes the misleading
  name but preserves a package that means two unrelated things, and creates a
  `.rendering` vs `.ui` ambiguity when `ui.*` already holds the render surfaces.
  `ZoneFog` is also not a rendering class: game logic writes it and saves persist it.
- **`.gdx` for `Game`** — honest about the boundary (this is the one class that
  hard-depends on `ApplicationAdapter`), but it names the vendor instead of the role and
  ties the package to a backend that could be swapped.
- **`ZoneFog` to `world`** — it is keyed by `zoneId` and sized to the zone, so it would
  sit plausibly beside `GameWorld`/`Zone`/`Tile`. Rejected because those are world
  *facts*; fog is *player knowledge*, a different axis that the engine deliberately keeps
  separate (ADR-0015).
- **Leave it alone** — the cost is only paid once, but it is paid by every consumer
  forever after 1.0 freezes the import path.
