# ADR-0014: Extensibility ethos — pluggable policy, fixed mechanism

- **Status:** Accepted
- **Date:** 2026-06-13

## Context

korogue already makes its *behavioural* concerns pluggable: ADR-0009 introduced `GameModule`
registries where the engine ships defaults and a consuming game registers its own implementations
by string id — AI (`BehaviorStrategy`: `wander`, `hunt-player`), lighting (`LightValueCalculator`:
`diminishing`, `global`), timed effects (`TimedEffect`), and serializable components. Line-of-sight
(`LineOfSightCalculator`) is an interface with two shipped implementations. So "ship defaults, let
people plug their own" is already the spine of the design — but it was never written down as a
principle, so each new concern (perception, combat, pathfinding, loot, …) risks re-deciding it
ad hoc, and it's unclear *where* the line sits: not everything should be pluggable.

Building the Rogue example (ADR-0012) as a real external consumer pressured this. The example
wants the engine to be unopinionated about *what kind of roguelike* you're building (Rogue's
binary lit/dark rooms vs. a continuous-light dungeon vs. no light at all), while a first-time
reader still needs an obvious simple path. The forces:

- **Roguelikes vary enormously** in their rules — FOV models, AI, lighting, combat math, hunger,
  identification, loot. An engine that hardcodes any of these policies excludes whole sub-genres.
- **But pluggability is not free.** Every interface is a concept to learn and a place the "happy
  path" can fragment. An engine that is *all* seams and no defaults is as unusable as one that is
  all concrete and no seams.
- **Mechanism is not policy.** Some parts of the engine are infrastructure — the ECS core, the
  mutation chokepoint (ADR-0005), the save envelope/versioning (ADR-0009), the event bus
  (ADR-0010). Making those "pluggable" buys nothing and costs comprehensibility.

## Decision

Adopt one cross-cutting principle, **pluggable policy / fixed mechanism**, realised through the
existing `GameModule` registry seam (ADR-0009):

1. **Behavioural *policy* is an interface with engine-shipped defaults and game-registered
   overrides, resolved by string id.** A concern is "policy" if it's a decision a *game designer*
   would reasonably make differently: AI, field-of-view/perception, lighting, combat resolution,
   pathfinding, loot generation, hunger. Each such concern gets: an interface, one or more default
   implementations registered under ids in `engineDefaults()`, and a `module.<concern>(id, impl)`
   builder so a game can add or override.

2. **Engine *mechanism* stays fixed.** The ECS (entities/components/systems/queries), the
   `World` mutation seam, the save envelope and format-versioning, the event bus, and the registry
   machinery itself are not extension points. They are the substrate every policy is written
   against.

3. **Strong defaults keep the simple path simple.** A basic game calls `GameModule.engineDefaults()`
   and configures behaviour by *data* — attaching components, tuning constructor parameters — and
   never implements an interface. Pluggability is an escape hatch, not a tax. A reader who never
   needs a custom policy never meets one.

4. **Two granularities of extension, where a concern supports them.** Coarse: *replace* a whole
   policy (a different `PerceptionModel`, a different `LineOfSightCalculator`). Fine: *add a
   contributor* to a default policy that's built to compose (register a new "sense" the default
   perception model picks up; register a new AI strategy). The fine grain is what lets a game add
   *one* new mechanic without reimplementing the surrounding policy — the test a closed
   configuration struct fails.

5. **Behaviour is resolved by id; components stay data (ADR-0003).** Entities reference behaviour
   by id (`Behavior("hunt-player")`, a sense's id) rather than carrying code. The registry resolves
   id → implementation. This keeps components serializable and the behaviour table in one place.

The litmus test for a new concern: *"would a game designer plausibly want this to work
differently?"* → make it a registered policy. *"Is this how the engine is built rather than how a
game plays?"* → keep it fixed.

## Consequences

- **Consistency.** New concerns (starting with perception, ADR-0015) follow one familiar shape
  instead of inventing a bespoke extension mechanism each time. Contributors to the engine and
  authors of games learn the pattern once.
- **The example proves the seams.** The Rogue port consumes defaults for most concerns and
  overrides only where it genuinely differs — which is exactly how it validates that the seams
  work and stay ergonomic, ahead of a 1.0 public API.
- **Learnability is preserved by defaults, not by fewer seams.** The cost of many interfaces is
  paid down by `engineDefaults()` being a complete, runnable baseline.
- **Cost: discipline required at design time.** Each concern must decide *policy vs. mechanism* and,
  if policy, whether it needs the fine-grained (composable) extension or only wholesale
  replacement. That judgement is the work this ADR exists to anchor.
- **Not a mandate to make everything pluggable.** "Where it makes sense" is load-bearing.
  Over-abstraction is a failure mode this ADR explicitly guards against: absent a plausible reason
  a game would vary it, a thing stays concrete until a second consumer proves otherwise.

## Alternatives considered

- **Leave it implicit (status quo).** Rejected: each new concern re-litigates the question, and the
  policy/mechanism line drifts. A stated principle is cheap and keeps the engine coherent.
- **Make everything — including mechanism — pluggable.** Rejected: pluggable ECS internals, save
  format, or event bus add surface area and concepts with no game-design payoff, and undermine the
  guarantees those layers exist to provide.
- **Configuration flags/enums instead of interfaces.** Rejected: flags can only select among
  behaviours the engine already anticipated. They cannot express a genuinely novel policy (a new
  sense, an unforeseen AI), which is the whole point — see the closed-struct failure mode in
  ADR-0015.
- **A plugin/DI framework.** Rejected: the lightweight string-id registry (ADR-0009) already does
  the job with no new dependency or lifecycle; a framework would be mechanism for its own sake.
