# ADR-0021: Simulation scope vs. cross-zone awareness — two independent knobs

- **Status:** Accepted
- **Date:** 2026-07-02

## Context

ADR-0008 established **active-only simulation**: only the current zone ticks each
frame, so a 50-level dungeon doesn't simulate every zone every frame. The seam
for this already exists — `GameWorld.simulatedZones(): Set<String>` (default
`{currentZoneId}`), which every zone-scoped system iterates
(`activeZones = world::simulatedZones`).

The visible gap (krogue-s67): a monster chasing the player **freezes at a zone
transition** — you step through a portal into the next zone and your pursuer
stops, because its zone is no longer simulated. The driving use case is
"current + adjacent" zones so pursuit continues across a boundary.

But the requirement is broader and must stay **generic** (ADR-0014: pluggable
policy, fixed mechanism): a consumer should be able to pick *whatever* set of
simulated zones it wants — the engine shouldn't cap it (simulate all 50 if you're
willing to pay for it). And separately: a monster five zones away should not, by
default, know where the player is — yet a consumer that *wants* omniscient
cross-zone pursuit should be able to build it.

The key realization is that these are **two orthogonal concerns** that the naive
"just widen simulatedZones" conflates:

- **Simulation scope** — which zones *tick* (their systems run, their monsters
  act). A performance/scope choice.
- **Cross-zone awareness** — what an actor *perceives or targets* across a zone
  boundary. A behaviour choice.

Simulating a zone makes its monsters act; it does **not** imply they know about
the player elsewhere. Conflating the two is what makes "far-away monsters
magically track you" or "you can't simulate more than the current zone" feel
baked in.

## Decision

Keep them separate; make each a pluggable policy with a lean default, and no
engine-imposed limit.

**Knob 1 — Simulation scope.** Generalise `simulatedZones` from an override into
a consumer-supplied policy `() -> Set<String>`. Built-ins:
- `CurrentZoneOnly` (**default**, = today's behaviour),
- `CurrentPlusAdjacent` (current + its portal-neighbours),
- and the consumer may return *any* set, including all zones — the engine imposes
  no cap; the perf trade-off is the consumer's.

**The set is live, not a snapshot.** It is a provider re-evaluated continuously
(systems already call `world::simulatedZones` each tick), so it tracks state:
under `CurrentPlusAdjacent`, crossing a transition changes `currentZoneId` and the
adjacency set **recomputes automatically** — the zone you left may drop out and the
one you entered brings its new neighbours in. Policies must therefore be cheap to
evaluate (portal-neighbour lookup is small; cache per current-zone if needed) and
must not assume a fixed set across ticks.

**Knob 2 — Cross-zone awareness / targeting.** A separate policy on the AI side,
**independent of what's simulated**. Default: **same-zone only** — an actor
perceives and targets within its own zone (perception, ADR-0015, is already
zone-scoped, so this falls out for free). A consumer opts into cross-zone pursuit
by supplying a targeting policy that widens reach. So a monster five zones away is
unaware **by default**, even if that zone happens to be simulated; awareness is a
deliberate opt-in, tunable in reach.

**Mechanic A — Zone-adjacency provider.** `neighborsOf(zoneId): Set<String>`,
default derived from `Portal`s (the distinct `targetZoneId`s of a zone's portal
cells). Overridable for non-portal adjacency (e.g. an overworld grid). N-hop is
just repeated application, so "current + 2 hops" is *composable* — the engine
hardcodes no distance.

**Mechanic B — Atomic boundary-crossing movement.** `PortalSystem` today
transitions only the player. Generalise crossing so *any* entity that crosses a
boundary updates `ZoneMember` and `Position` **together, atomically** (a
`GameWorld.relocate(entity, zoneId, x, y)`-style helper). Required so a chasing
monster actually follows through the portal.

**How the driving use case composes** (all defaults swappable):
`CurrentPlusAdjacent` scope + a cross-zone HuntPlayer policy that paths toward the
portal leading to the player's zone + portal-derived adjacency + atomic crossing
= monsters chase across transitions.

**Sane cross-zone targeting = pursue the exit, not the player.** Because
perception is zone-scoped, the realistic default for cross-zone chase is "head
for the portal the player fled through / that leads to the player's zone," not
omniscient tracking of the player's exact cell. A consumer wanting true
omniscient cross-zone AI can supply that policy — again, the engine isn't the
limit — but the built-in stays portal-pathing.

## Consequences

- The two knobs compose freely: simulate many zones but keep monsters locally
  aware (default), or simulate few but allow cross-zone pursuit — any combination.
- **Zones freeze and resume cleanly as the live set changes.** Entities persist in
  the ECS regardless of whether their zone is simulated (ADR-0007) — a
  de-simulated zone's monsters simply stop ticking with their state intact, and
  resume from it when the zone re-enters the set (walk away from a pursuer, then
  back, and it picks up where it froze). No save/despawn dance is needed; "not
  simulated" is purely "systems skip it this tick." One edge to honour: a boundary
  crossing must land the entity in a zone that is (or immediately becomes) in the
  set for it to keep acting — with `CurrentPlusAdjacent`, crossing toward the
  player always does, since the destination is the current zone or its neighbour.
- Fully **headless-testable** (pure simulation/AI logic; no GL): player in zone A,
  monster in adjacent zone B, tick, assert it advances toward and crosses the
  portal.
- **Rogue doesn't need it** — its monsters don't follow between dungeon levels, so
  the example stays on `CurrentZoneOnly`. This is a generic engine capability for
  connected-zone games (overworlds, rooms-as-zones, mansions).
- Extends ADR-0008 (active-only simulation) and ADR-0016 (dynamic zones);
  interacts with ADR-0015 (perception is the same-zone awareness default).

## Alternatives considered

- **Just widen `simulatedZones` to current+adjacent** — rejected as the *whole*
  answer: it conflates scope with awareness (simulated monsters would need cross-
  zone targeting to do anything useful, and there'd be no knob to keep far
  monsters unaware). It's one *policy* (Knob 1 = `CurrentPlusAdjacent`), not the
  design.
- **Awareness derived from simulation scope** (aware iff simulated) — rejected:
  removes the ability to simulate broadly while keeping monsters locally aware,
  and bakes in "simulated ⇒ omniscient."
- **A fixed adjacency radius / a hard simulated-zone cap in the engine** —
  rejected: the engine shouldn't be the limit; radius/cap are consumer policy.
- **Cross-zone perception** (senses that reveal into other zones) — rejected as
  the default: unrealistic and expensive; cross-zone pursuit is better modelled as
  AI pathing to the exit. A game may still add such a sense (ADR-0015 senses are
  open) if it wants.
