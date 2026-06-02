package com.sletmoe.krogue.ecs

/**
 * Marker for anything attachable to an [Entity]. Components are keyed by concrete
 * runtime class, so an entity holds at most one component of each type.
 *
 * Conventions (enforced by discipline, not the compiler):
 * - Components are IMMUTABLE data classes. To change state, produce a new value
 *   and replace the old one via [World.update]. Immutability keeps entity state
 *   snapshottable — the basis for change tracking, undo, and save/load — and
 *   avoids reference-aliasing bugs.
 * - Components are DEEPLY immutable: no mutable fields, and no mutable types
 *   inside (e.g. store colour as NormalizedRgb or a packed int, never a mutable
 *   libGDX Color).
 * - Components hold only serializable data. For things that are not naturally
 *   serializable (behaviour/strategy objects, light calculators), store a stable
 *   string id and resolve it through a registry at load time rather than the
 *   object itself. The registry itself is deferred to the save/load phase; only
 *   the id-shaped fields are a 4a-onward convention.
 */
interface Component
