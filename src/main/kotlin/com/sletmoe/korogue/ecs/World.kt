package com.sletmoe.korogue.ecs

import kotlin.random.Random

/**
 * Owns every [Entity] and [System] and drives them via [tick].
 *
 * **Queries scan all entities** — there is no component index. At korogue's scale
 * (hundreds to low thousands of entities) an O(n) scan per query is negligible,
 * and going index-free removes a whole class of index-desync bugs. If queries
 * ever become hot at much larger scale, a component-type index can be introduced
 * behind these same methods without changing any caller.
 *
 * **Query results are snapshots.** [entities] and [entitiesWith] capture the
 * entity set when the sequence is created, so a system may freely [spawn] /
 * [despawn] (or mutate components) while iterating a query without a
 * `ConcurrentModificationException`; such structural changes simply are not
 * reflected in an already-running iteration.
 *
 * **Mutation flows through [World].** [Entity] is read-only to callers; component
 * changes go through [set] / [update] / [remove], giving a single seam onto which
 * change tracking / save-state deltas can later be hooked.
 */
class World {
    private var nextId = 0L
    private val entitiesById = LinkedHashMap<EntityId, Entity>()
    private val systems = ArrayList<System>()
    private var turn = 0L

    /**
     * The world's pub/sub bus (Phase 4c). Systems [EventBus.publish] notifications while
     * they run; subscribers register here. Transient runtime state — handlers are code and
     * the queue is in-flight, so the bus is not part of save state. [tick] drains it after
     * every system has run.
     */
    val events: EventBus = EventBus()

    // -------------------------------------------------------------------------
    // Persistence (4f) — module-internal hooks for the save codec.
    // -------------------------------------------------------------------------

    /** The id the next [spawn] will use; persisted so loaded games never reissue an id. */
    internal val nextEntityId: Long get() = nextId

    /**
     * Replaces all entities with [entities] and restores the [turn] and [nextId] counters.
     * Systems are left intact. Used by the save codec to rebuild a world from disk.
     */
    internal fun restore(
        turn: Long,
        nextId: Long,
        entities: List<Entity>,
    ) {
        entitiesById.clear()
        for (entity in entities) entitiesById[entity.id] = entity
        this.turn = turn
        this.nextId = nextId
    }

    // -------------------------------------------------------------------------
    // Entity lifecycle
    // -------------------------------------------------------------------------

    /** Creates an entity with the given initial components and registers it. */
    fun spawn(vararg components: Component): Entity = spawn(components.asList())

    /** Creates an entity with the given initial components and registers it. */
    fun spawn(components: List<Component>): Entity {
        val entity = Entity(EntityId(nextId++), components)
        entitiesById[entity.id] = entity
        return entity
    }

    /** Removes the entity with [id]; returns it if it existed. */
    fun despawn(id: EntityId): Entity? = entitiesById.remove(id)

    /** The entity with [id], or null. */
    fun get(id: EntityId): Entity? = entitiesById[id]

    /** True if an entity with [id] exists. */
    fun contains(id: EntityId): Boolean = entitiesById.containsKey(id)

    /** Number of live entities. */
    val entityCount: Int
        get() = entitiesById.size

    /** A snapshot of all entities, in spawn order. Safe to spawn/despawn while iterating. */
    fun entities(): Sequence<Entity> = entitiesById.values.toList().asSequence()

    // -------------------------------------------------------------------------
    // Component mutation (the single seam)
    // -------------------------------------------------------------------------

    /**
     * Attaches [component] to entity [id], replacing any existing component of its
     * concrete class. No-op if [id] is unknown. Note this keys by the component's
     * concrete runtime class; [update] keys by the queried type.
     */
    fun set(
        id: EntityId,
        component: Component,
    ) {
        val entity = entitiesById[id] ?: return
        entity.byType[component::class] = component
    }

    /**
     * Reads the component of type [T] on [id], applies [transform], and stores the
     * result **under [T]'s key** (not the returned value's runtime class, so a
     * transform returning a subtype can't orphan the entry). Returns the new value,
     * or null if the entity or the component was absent (a no-op). Canonical way to
     * "change" immutable component state:
     *
     *     world.update<Health>(id) { it.copy(current = it.current - damage) }
     */
    inline fun <reified T : Component> update(
        id: EntityId,
        transform: (T) -> T,
    ): T? {
        val entity = get(id) ?: return null
        val current = entity.get<T>() ?: return null
        val updated = transform(current)
        entity.byType[T::class] = updated
        return updated
    }

    /** Removes the component of type [T] from [id]; returns it if present, else null. */
    inline fun <reified T : Component> remove(id: EntityId): T? {
        val entity = get(id) ?: return null
        return entity.byType.remove(T::class) as T?
    }

    // -------------------------------------------------------------------------
    // Queries
    // -------------------------------------------------------------------------

    // The overloads differ only by reified type arity, which erases to identical JVM
    // signatures — @JvmName keeps the generated methods distinct. Kotlin still
    // resolves call sites by the number of type arguments. (Java callers, should any
    // exist, would see the mangled names; these queries are Kotlin-only ergonomics.)

    /** Entities that have a component of type [A]. */
    @JvmName("entitiesWith1")
    inline fun <reified A : Component> entitiesWith(): Sequence<Entity> = entities().filter { it.has<A>() }

    /** Entities that have components of both [A] and [B]. */
    @JvmName("entitiesWith2")
    inline fun <reified A : Component, reified B : Component> entitiesWith(): Sequence<Entity> =
        entities().filter { it.has<A>() && it.has<B>() }

    /** Entities that have components of all of [A], [B] and [C]. */
    @JvmName("entitiesWith3")
    inline fun <reified A : Component, reified B : Component, reified C : Component> entitiesWith(): Sequence<Entity> =
        entities().filter { it.has<A>() && it.has<B>() && it.has<C>() }

    // -------------------------------------------------------------------------
    // Systems
    // -------------------------------------------------------------------------

    /** Registers [system]; systems run in registration order. Returns this for chaining. */
    fun addSystem(system: System): World {
        systems.add(system)
        return this
    }

    /** The registered systems, in execution order. */
    fun systems(): List<System> = systems.toList()

    /** The current turn counter (number of completed [tick]s). */
    val currentTurn: Long
        get() = turn

    /**
     * Runs every registered system once in registration order, drains the [events] bus to
     * its subscribers, then advances the turn counter. Events are dispatched after all
     * systems have run, so subscribers observe a consistent end-of-tick world.
     */
    fun tick(
        elapsedMs: Long = 0L,
        random: Random = Random.Default,
    ) {
        val ctx = TickContext(turn = turn, elapsedMs = elapsedMs, random = random)
        for (system in systems) system.update(this, ctx)
        events.dispatch()
        turn++
    }
}
