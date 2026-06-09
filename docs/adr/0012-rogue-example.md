# ADR-0012: A faithful Rogue 5.4.4 example, ported under BSD-3-Clause with attribution

- **Status:** Accepted
- **Date:** 2026-06-08

## Context

korogue is a reusable roguelike engine; the demo (`MyGame`) exercises it but isn't a real game.
To validate the engine end-to-end — and to surface what's still missing — we will build a
**faithful clone of the original Rogue** as a shipped example (krogue-sdh). We have the genuine
**Rogue 5.4.4** C source (the Toy/Arnold/Wichman game, in the Epyx/A.I. Design lineage maintained
by Nicholas J. Kisseberth) as ground truth for mechanics and attribution.

The forces:

- **Legal basis.** Rogue 5.4.4 is licensed **BSD-3-Clause** (`LICENSE.TXT`):
  *Copyright (C) 1980–1983, 1985, 1999 Michael Toy, Ken Arnold and Glenn Wichman*, with portions
  (state.c, mdport.c) © Nicholas J. Kisseberth. BSD-3 permits source/binary redistribution of
  derivatives provided we **retain the copyright notice, the conditions, and the disclaimer**, and
  **do not use the authors' names to endorse** the derivative. So a faithful port + redistribution
  is allowed — attribution is the obligation, which matches our intent to give full credit.
- **Faithfulness.** The value is in *fidelity*: the actual tables and formulas (26 monsters, item
  probabilities, the e_levels experience curve, AC/strength/to-hit math, daemons/fuses timing,
  amulet-at-26). We port those, we don't approximate them.
- **Engine cleanliness.** The engine must stay game-agnostic (ADR-0001). Rogue content lives in an
  example, consuming korogue's public surface (registries/components/systems, the UI toolkit) — so
  the example doubles as the reference "how to build a game on korogue".
- **Two renderers.** Rogue is ASCII, but we want a graphical/tileset rendering too, selectable by
  config, sharing one set of game logic.

## Decision

Build the example as a **faithful Kotlin reimplementation of Rogue 5.4.4 on korogue**, redistributed
under the original **BSD-3-Clause**:

- **Attribution.** Ship Rogue's original `LICENSE.TXT` and a `NOTICE` crediting Michael Toy, Ken
  Arnold and Glenn Wichman (and Kisseberth for the 5.4.x maintenance) in the example, retaining the
  copyright notice, conditions, and disclaimer. No wording implies the authors endorse korogue.
- **Location.** A dedicated example package, `com.sletmoe.korogue.examples.rogue` (a separate Gradle
  module is a candidate once korogue is published — it would prove the example consumes only the
  public API). Kept apart from the engine and from the existing `kotile`/`MyGame` demo.
- **Fidelity from source.** Mechanics, tables, and formulas are ported from the 5.4.4 source as the
  reference (`rooms.c`, `monsters.c`, `things.c`, `fight.c`, `daemons.c`, `chase.c`, …), translated
  into korogue's ECS (components/systems/registries) rather than C globals.
- **Engine features promoted from Rogue's needs.** Two general capabilities Rogue requires are added
  to the **engine**, not the example: a **turn-on-input loop** (krogue-lhw) and a **timed-effects
  scheduler** (Rogue's daemons-and-fuses: one-shot fuses and recurring daemons keyed to turns).
- **Two renderers via config.** The same game logic renders either as **ASCII** (CP437 through the
  UI toolkit, ADR-0011) or with a **graphical tileset**, chosen by configuration. The `TileSurface`
  seam and `Renderable` are where the tileset backend plugs in; a suitably-licensed tileset must be
  sourced.

## Consequences

- The engine gains a turn loop and a scheduler — broadly useful, and the example is the forcing
  function that justifies them (validating "build a real game to find the gaps").
- A large body of work; decomposed into sub-issues under the krogue-sdh epic (package + license,
  level gen, player/stats, item model + generation/identification, item effects, monsters + AI,
  combat, hunger, commands/UI, win-lose/score, tileset renderer).
- The example demonstrates the consumer extension path (registries, components, systems, UI), which
  also pressure-tests korogue's public API ahead of a 1.0.
- We carry an attribution obligation (LICENSE + NOTICE in the example) — small and one-time.
- ASCII-first; the tileset backend is additive behind the surface seam, not a rewrite.

## Alternatives considered

- **A loose, "inspired-by" roguelike** instead of a faithful port. Rejected: far less valuable as
  both an engine validation and a tribute; fidelity is the point, and the BSD license makes a true
  port legal.
- **Transpiling / directly porting the C** (globals, `union thing`, ncurses). Rejected: it would
  fight korogue's ECS/immutable-component design and idiomatic Kotlin; we port *mechanics*, not code
  structure.
- **Putting the example in the engine's main source / the existing demo.** Rejected: the engine
  must stay game-agnostic, and a separate example better proves the consumer story.
- **Keeping the turn loop + scheduler inside the example.** Rejected: both are general engine
  concerns other games will want; the example is just their first consumer.
