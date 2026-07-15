# Architecture Decision Records

Point-in-time records of **why** significant or hard-to-reverse decisions were made.

- ADRs are **append-only**: once Accepted, an ADR is not rewritten. If a decision is
  later reversed, write a *new* ADR and mark the old one `Superseded by ADR-NNNN`.
- The living design summary is [../ARCHITECTURE.md](../ARCHITECTURE.md); it links here
  for rationale. **Open / unresolved** decisions live in beads (label `decision`).
- To add one: copy [template.md](template.md) to `NNNN-short-title.md` (next number),
  fill it in, and add a row to the index below. When an ADR resolves a beads
  `decision` issue, link the ADR from the issue and close it.

## Index

| ADR | Title | Status |
|-----|-------|--------|
| [0001](0001-split-korogue-kotile.md) | Split korogue (engine) from kotile (renderer) | Accepted |
| [0002](0002-lightweight-component-entity-model.md) | Lightweight component entity model | Accepted |
| [0003](0003-immutable-components.md) | Immutable components | Accepted |
| [0004](0004-index-free-queries.md) | Index-free entity queries | Accepted |
| [0005](0005-world-mutation-chokepoint.md) | Enforced mutation chokepoint | Accepted |
| [0006](0006-tooling-beads-docs-native.md) | Tooling: beads + docs/ADRs + native subagents | Accepted |
| [0007](0007-game-world-composes-ecs-world.md) | Game world composes the ECS world | Accepted |
| [0008](0008-active-only-multi-zone-simulation.md) | Active-only multi-zone simulation | Accepted |
| [0009](0009-save-load-and-registries.md) | Save/load: CBOR, seeded RNG streams, registry/module | Accepted |
| [0010](0010-event-bus.md) | Deferred-dispatch event bus for decoupled notifications | Accepted |
| [0011](0011-korogue-ui-toolkit.md) | A korogue-side TUI widget toolkit; kotile stays a renderer | Accepted |
| [0012](0012-rogue-example.md) | A faithful Rogue 5.4.4 example, ported under BSD-3-Clause with attribution | Accepted |
| [0013](0013-monorepo-and-module-layout.md) | korogue rename, monorepo with kotile, and the module layout | Accepted |
| [0014](0014-extensibility-ethos.md) | Extensibility ethos — pluggable policy, fixed mechanism | Accepted |
| [0015](0015-visibility-and-perception.md) | Visibility & perception — three layers, composable senses | Accepted |
| [0016](0016-dynamic-zones-for-multi-level-worlds.md) | Dynamic zones for multi-level worlds | Accepted |
| [0017](0017-display-scaling-and-resize.md) | Dynamic window resize, display scaling, and centering | Accepted |
| [0018](0018-layer-model-grid-and-free-layers.md) | kotile layer model — grid layers and free (pixel-space) layers | Accepted |
| [0019](0019-prefab-entity-aware-worldgen.md) | Prefab-based, entity-aware world generation | Accepted |
| [0020](0020-magical-darkness-region-concealment.md) | Magical darkness as region concealment (not just unlit) | Accepted (supersedes part of ADR-0015) |
| [0021](0021-simulation-scope-and-cross-zone-awareness.md) | Simulation scope vs. cross-zone awareness — two independent knobs | Accepted |
| [0022](0022-entity-occupancy-collision-and-locomotion.md) | Entity occupancy — tag-based collision and locomotion | Accepted |
| [0023](0023-time-cost-turn-scheduling-and-time-axes.md) | Time-cost turn scheduling (energy model) and the game-time vs animation-time split | Proposed |
| [0024](0024-dirty-region-redraw-grid-composite-cache.md) | Grid composite cache instead of per-frame full repaint | Accepted |
| [0025](0025-world-owns-its-rng.md) | The world owns its RNG — determinism by construction | Accepted |
