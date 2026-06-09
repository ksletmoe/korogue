# ADR-0001: Split korogue (engine) from kotile (renderer)

- **Status:** Accepted
- **Date:** 2026-05-25

## Context

korogue began as a roguelike with its tile rendering baked in. Rendering (tiles,
tilemaps, animation, camera, input, fonts) is a separable concern with far broader
applicability than roguelikes, and we want both the engine and the renderer to be
reusable by third parties.

## Decision

Two sibling Kotlin/JVM libraries under `com.sletmoe`:

- **kotile** — a general-purpose libGDX tile renderer (the rendering primitive layer).
- **korogue** — a reusable roguelike game engine built on kotile (world/zone model,
  entities, FOV, lighting behavior, AI, generation, turn loop, game UI).

Discriminating principle for where code belongs: **if a non-roguelike libGDX game
could plausibly use it, it belongs in kotile.** During development korogue consumes
kotile via Gradle `includeBuild("../kotile")`.

## Consequences

- Clean separation and independent reuse; roguelike-specific helpers (FOV, lighting
  application) stay in korogue, keeping kotile AWT- and roguelike-free.
- Cross-repo coordination overhead (an API change in kotile is a two-repo dance).
- Some gray areas (e.g. multi-pane UI layout) need a deliberate boundary call.

## Alternatives considered

- **Single combined library** — rejected: rendering is independently valuable and
  bundling it muddies both concerns and forces roguelike assumptions onto renderer users.
