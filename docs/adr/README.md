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
| [0001](0001-split-krogue-kotile.md) | Split krogue (engine) from kotile (renderer) | Accepted |
| [0002](0002-lightweight-component-entity-model.md) | Lightweight component entity model | Accepted |
| [0003](0003-immutable-components.md) | Immutable components | Accepted |
| [0004](0004-index-free-queries.md) | Index-free entity queries | Accepted |
| [0005](0005-world-mutation-chokepoint.md) | Enforced mutation chokepoint | Accepted |
| [0006](0006-tooling-beads-docs-native.md) | Tooling: beads + docs/ADRs + native subagents | Accepted |
| [0007](0007-game-world-composes-ecs-world.md) | Game world composes the ECS world | Accepted |
| [0008](0008-active-only-multi-zone-simulation.md) | Active-only multi-zone simulation | Accepted |
| [0009](0009-save-load-and-registries.md) | Save/load: CBOR, seeded RNG streams, registry/module | Accepted |
| [0010](0010-event-bus.md) | Deferred-dispatch event bus for decoupled notifications | Accepted |
| [0011](0011-krogue-ui-toolkit.md) | A krogue-side TUI widget toolkit; kotile stays a renderer | Accepted |
