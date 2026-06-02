package com.sletmoe.krogue.ecs

import kotlin.reflect.KClass

/** Stable, unique identity for an [Entity] within a [World]. Never reused once issued. */
@JvmInline
value class EntityId(val value: Long)

/**
 * A thin container of [Component]s keyed by concrete type — krogue's unit of game
 * state. An entity holds at most one component of each concrete class.
 *
 * Entities are minted by [World.spawn] and are READ-ONLY to callers: all mutation
 * flows through [World] ([World.set]/[World.update]/[World.remove]) so there is a
 * single seam onto which change tracking / save-state deltas can later be hooked.
 *
 * Components are looked up by their CONCRETE class: `get<Health>()` finds a value
 * stored as `Health`, not a subclass of it. Don't rely on polymorphic lookup.
 */
class Entity internal constructor(
    val id: EntityId,
    initial: Iterable<Component> = emptyList(),
) {
    @PublishedApi
    internal val byType = HashMap<KClass<out Component>, Component>()

    init {
        for (component in initial) byType[component::class] = component
    }

    /** Every component currently attached, in no particular order. */
    val components: Collection<Component>
        get() = byType.values

    /** The component of type [T], or null if absent. */
    inline fun <reified T : Component> get(): T? = byType[T::class] as T?

    /** The component of type [T], or throws if absent. Use when presence is an invariant. */
    inline fun <reified T : Component> require(): T =
        get<T>() ?: error("Entity $id is missing required component ${T::class.simpleName}")

    /** True if a component of type [T] is attached. */
    inline fun <reified T : Component> has(): Boolean = byType.containsKey(T::class)

    override fun toString(): String = "Entity(${id.value}, ${byType.keys.map { it.simpleName }})"
}
