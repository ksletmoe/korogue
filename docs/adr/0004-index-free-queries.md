# ADR-0004: Index-free entity queries

- **Status:** Accepted
- **Date:** 2026-06-01

## Context

`World.entitiesWith<A, B>()` must find entities having a given set of components. The
obvious "real ECS" approach maintains a per-component-type index so queries are fast.
Expected scale is hundreds to low thousands of entities.

## Decision

**No component index.** Queries scan all entities (over a snapshot) and filter by
`has<T>()`. If queries ever become a measured bottleneck at much larger scale, a
component-type index can be introduced *behind the same query methods* without changing
any caller.

## Consequences

- O(n) per query, which is negligible at the stated scale.
- Deletes an entire class of bugs: there is no index that can desync from entity state.
- Makes runtime component add/remove trivially safe (nothing to keep in sync).
- Retains a clean upgrade path if scale ever demands it (the methods are the seam).

## Alternatives considered

- **Maintain a `Map<ComponentType, Set<EntityId>>` index now** — rejected as premature:
  it buys speed we don't need and costs a desync-bug surface we'd rather not own. The
  decision is explicitly reversible later if profiling justifies it.
