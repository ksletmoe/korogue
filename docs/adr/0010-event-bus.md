# ADR-0010: Deferred-dispatch event bus for decoupled notifications

- **Status:** Accepted
- **Date:** 2026-06-08

## Context

Systems already coordinate *within* a tick through **intent components**: `Behavior`
writes a `MoveIntent`, `MovementSystem` consumes it and may write an `AttackIntent`,
`CombatSystem` consumes that. This is a deliberate, ordered pipeline — each step reads
the previous step's components — and it works well for the turn's mechanics.

It does not, however, serve *cross-cutting observers*. A combat-log pane, audio, analytics,
fog-of-war memory, and player-death/game-over handling (krogue-4zi) all want to react to
things that *happened* — "an entity died", "the player changed zones" — without the
producing system knowing they exist, and without every such concern being wedged into the
mechanics pipeline as another system that re-scans for state changes. Phase 4c (krogue-4c)
called for "pub/sub for decoupled systems" to fill this gap.

The forces:

- **Determinism.** The rest of the engine is deterministic and snapshot-oriented (seeded
  RNG streams, query snapshots, immutable components). Event delivery must not introduce
  order-dependence or let an observer see a half-updated, mid-system world.
- **No new coupling.** Producers must not reference consumers; consumers must not have to
  know which system emits what.
- **Transient, not state.** Save/load (ADR-0009) serializes entities + terrain + RNG.
  Subscribers are code and queued events are in-flight work — neither belongs in save state.

## Decision

Add a generic, engine-side **`EventBus`** (`com.sletmoe.krogue.ecs`) with a
**publish-enqueues / dispatch-delivers** model, owned by `World` and drained once per tick:

- `Event` is a marker interface; `EventBus.publish(event)` only appends to an internal FIFO
  queue. Nothing runs until `dispatch()` drains it.
- `World` owns one `EventBus` as `World.events`. `World.tick()` runs all systems, **then**
  calls `events.dispatch()`, then advances the turn. So observers always see a consistent
  end-of-tick world, never a partially-updated one between systems.
- Handlers are **keyed by concrete event class** (`subscribe<T>`), mirroring how components
  are keyed by concrete class (ADR-0002) — no polymorphic delivery. `subscribeAll` exists
  for catch-all observers (a logger/debug trace). `subscribe` returns a `Subscription` whose
  `cancel()` is idempotent.
- Dispatch drains the queue **completely**, including events a handler publishes mid-dispatch,
  so a cascade resolves within the tick that started it. Delivery order is deterministic:
  FIFO over the queue, registration order among a type's handlers, typed handlers before
  catch-all.
- **Events are immutable, self-contained snapshots.** Because delivery is deferred to end of
  tick, the entity an event describes may already be gone (e.g. a despawned corpse), so an
  event carries the data observers need (e.g. `EntityDied` carries the snapshotted name)
  rather than an id to look up.
- The bus is **transient**: not touched by the save codec or `World.restore`.

Game-side concrete events live in `com.sletmoe.krogue.events` (`EntityDamaged`, `EntityDied`,
`ZoneChanged`), keeping the `ecs` core game-agnostic (ADR-0001). `CombatSystem` emits
`EntityDamaged`/`EntityDied`; `PortalSystem` emits `ZoneChanged`.

## Consequences

- New observers (combat-log UI, audio, player-death handling per krogue-4zi, per-zone fog
  memory per krogue-ro8) attach as subscribers without editing the systems that produce the
  events, and without joining the mechanics pipeline.
- Intent components remain the tool for *intra-tick mechanics* (ordered, consumed, drive the
  next system); the bus is for *notifications* (fan-out, observe-only, delivered after the
  tick). Keeping the two distinct avoids turning either into a catch-all.
- Handlers are observers: the bus makes no ordering guarantee against world mutation, and the
  world may have moved on by delivery time. World changes must still flow through systems and
  components (ADR-0005), not handlers.
- Deferred dispatch means an observer cannot influence the tick that produced the event (it
  reacts the following tick at the earliest). That is the price of consistent, deterministic
  delivery — and the right default for notifications.
- The demo currently has producers but no runtime subscriber; the events are infrastructure
  the above features will consume. They are exercised by tests today.

## Alternatives considered

- **Immediate (synchronous) dispatch** — `publish` invokes handlers inline. Rejected: an
  observer would see a half-updated world mid-system, and re-entrant publishes make ordering
  subtle. Deferring to end-of-tick keeps delivery consistent and deterministic.
- **Just add more systems / intent components** for every cross-cutting concern. Rejected:
  forces each observer to re-scan for state changes and to be ordered into the mechanics
  pipeline — exactly the coupling the bus removes. Intents stay for mechanics; the bus is for
  notifications.
- **Polymorphic / supertype delivery** (subscribe to `Event` or a base type). Rejected for
  the default path as inconsistent with concrete-class component keying and harder to reason
  about; the narrow real need (one observer wanting everything) is met by `subscribeAll`.
- **Host-driven dispatch** (the game loop calls `dispatch()` itself). Rejected: tying it to
  `World.tick()` makes "events describe a completed tick" a structural guarantee rather than a
  caller convention, and means a forgotten `dispatch()` can't silently swallow a tick's events.
