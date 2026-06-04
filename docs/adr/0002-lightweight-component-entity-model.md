# ADR-0002: Lightweight component entity model

- **Status:** Accepted
- **Date:** 2026-05-25

## Context

krogue is an extensible engine others build games on, so its entity architecture is a
keystone. The main options were pure ECS (archetype/column storage, e.g. Fleks), an
OOP inheritance hierarchy, or a lightweight component model. Expected scale is hundreds
to low thousands of entities. Roguelikes need heavy runtime mutability (status effects,
buffs, polymorph).

## Decision

A **lightweight component model** ("Option B"):

- An `Entity` is a thin container of components keyed by concrete type (one per type).
- Components are mostly immutable data classes; systems are plain classes/functions
  that query entities by component set.
- Typed access via Kotlin reified generics (`get<T>()`, `entitiesWith<A, B>()`).
- Code-first: consumers extend by writing Kotlin. A data-driven (JSON/YAML) content
  layer is explicitly deferred and out of scope for v1.

## Consequences

- Runtime composition (add/remove components) is trivial — the roguelike pattern.
- No ECS dependency or archetype machinery; appropriate for the stated scale.
- Consumers add content as code (new components/systems). Drives the rest of Phase 4.

## Alternatives considered

- **Pure ECS (Fleks/archetypes)** — rejected: cache-locality benefits are irrelevant at
  hundreds of entities; adds a dependency and conceptual overhead we don't need.
- **OOP inheritance** — rejected: rigid for roguelike composition; the placeholder
  `Creature.update` branching on `name == "sheep"/"zombie"` is exactly the smell it
  produces. Behavior becomes a component instead.
