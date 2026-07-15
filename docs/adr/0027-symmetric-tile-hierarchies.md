# ADR-0027: Symmetric sprite and ASCII tile hierarchies

- **Status:** Accepted
- **Date:** 2026-07-15

## Context

kotile has two parallel sealed tile hierarchies — one for sprite cells, one for
ASCII cells — that model the same four roles. Their names had drifted apart:

| Role | Sprite path | ASCII path |
| --- | --- | --- |
| Sealed base (storable cell content) | `SpriteTileEntry` | `AnimatableAsciiTile` |
| Static branch | `StaticTile` | `AsciiTileDescriptor` |
| Open, time-driven branch | `Tile` | *(none — base was closed)* |
| Built-in frame-based impl | `AnimatedSpriteTile` | `AnimatedAsciiTile` |

Every corresponding pair was named on a different scheme. The base named the
container on one side and an animatability property on the other; the static
branch was a `Tile` on one side and a `Descriptor` on the other. Worst of all,
the *animated* sprite branch was called plain `Tile` — the most generic name
available — while sitting under a base called `SpriteTileEntry`, beside a sibling
called `StaticTile`, and colliding conceptually with the engine's own
`world.Tile` (terrain). It read like a base type rather than the animated
specialization it was.

The two paths also differed structurally, not just in naming: a consumer could
implement `Tile` and supply their own time-driven sprite, but the ASCII base was
sealed with exactly two implementations, so an equivalent custom ASCII cell was
impossible.

These are all public types in `com.sletmoe:kotile`. Renaming them after the 1.0
publish is a breaking change, so the reconciliation had to happen now.

## Decision

**Both hierarchies use one naming scheme, with identical shape:**

```
SpriteTile (sealed base)        AsciiTile (sealed base)
├─ StaticSpriteTile (data)      ├─ StaticAsciiTile (data)
└─ DynamicSpriteTile (iface)    └─ DynamicAsciiTile (iface)
   └─ AnimatedSpriteTile           └─ AnimatedAsciiTile
```

Base named for what it holds; branches split on whether appearance depends on
time; the built-in frame-cycling class keeps its existing name under the open
branch.

`DynamicAsciiTile` is new, closing the extensibility gap: consumers can now write
a time-driven ASCII cell (the branch is open even though the base stays sealed),
exactly as they always could for sprites.

The ASCII base's `descriptorAt` is renamed `resolveAt`, and
`AsciiTileWindow.topDescriptorAt` to `topTileAt` — "descriptor" named a type that
no longer exists.

Dispatch still differs, and that is inherent rather than drift: an ASCII cell
resolves itself via `resolveAt`, while a `StaticSpriteTile` is resolved by the
renderer against a `TileSheet` (the tile carries only sheet coordinates).
`StaticAsciiTile` therefore plays double duty as both the static branch and the
resolved output of `resolveAt`.

## Consequences

Intuition transfers between the two paths: same roles, same names, same shape.
The `Tile` discoverability trap is gone, along with its collision with the
engine's terrain `Tile`.

Per-frame repaint tracking now keys on the `DynamicSpriteTile` /
`DynamicAsciiTile` *branch* rather than on a concrete class. This fixed a latent
bug the new open branch would otherwise have exposed: `AsciiTileWindow` tested
`is AnimatedAsciiTile`, so a consumer's own dynamic tile would have painted once
and then frozen. Tests now pin the branch membership both hierarchies' redraw
tracking depends on, since re-parenting either animated class would silently
freeze animation with nothing else failing.

This is a breaking rename of public kotile types, taken deliberately before 1.0.
Downstream cost proved near zero: the korogue engine hides the ASCII hierarchy
behind `TileSurface`, so the Rogue example needed no changes at all — only
`WindowSurface` and the demos touch these names.

## Alternatives considered

- **`Animated*` as the open branch interface** (rename `Tile` ->
  `AnimatedSpriteTile` as an interface) — would force the well-named concrete
  classes to be renamed (`FrameAnimatedSpriteTile`), churning the most-used types
  in the demos. "Animated" also overstates the contract: the branch only promises
  appearance is a function of time, and frame cycling is one way to satisfy that,
  not the only one.
- **Standardize on `Descriptor` for the static branch** (rename `StaticTile` ->
  `SpriteTileDescriptor`) — "descriptor" is jargon for what is simply a tile, and
  it fits the sprite path poorly, where the type is sheet coordinates rather than
  a resolved appearance.
- **Rename only, leaving the ASCII base closed** — keeps the diff strictly
  mechanical, but the paths would still differ in extensibility, so intuition
  would transfer for naming and then break on the first attempt to write a custom
  ASCII cell.
