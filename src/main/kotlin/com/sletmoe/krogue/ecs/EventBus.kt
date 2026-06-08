package com.sletmoe.krogue.ecs

import kotlin.reflect.KClass

/**
 * Marker for anything publishable on an [EventBus]. Concrete events are small,
 * immutable, self-contained value objects (see the game-side `events` package):
 * because an event is delivered *after* the producing tick, the entity it describes
 * may already be gone, so an event must carry the data its observers need rather
 * than an id to look up.
 */
interface Event

/**
 * A handle to one subscription; [cancel] removes the handler. Idempotent — cancelling
 * twice is harmless. Returned by [EventBus.subscribe] / [EventBus.subscribeAll].
 */
fun interface Subscription {
    fun cancel()
}

/**
 * A minimal typed pub/sub bus that decouples producers (systems) from observers
 * (UI, logging, audio, death handling): neither needs to know about the other.
 *
 * **Publishing enqueues; [dispatch] delivers.** [publish] only appends to an internal
 * queue; nothing runs until [dispatch] drains it in FIFO order. [World.tick] dispatches
 * once after all systems have run, so observers always see a consistent end-of-tick world
 * — never a half-updated one mid-system. Events published *by a handler during dispatch*
 * are drained in the same pass, so a cascade resolves within the tick that started it.
 *
 * **Keyed by concrete class.** A handler registered for `T` receives events whose runtime
 * class is exactly `T`, mirroring how [Component]s are keyed (no polymorphic delivery).
 * Use [subscribeAll] for a catch-all observer such as a logger.
 *
 * **Observers, not mutators.** Handlers are meant to observe; the bus makes no ordering
 * guarantee against world mutation, and the world may already have moved on (e.g. a dead
 * entity despawned) by the time an event is delivered. Drive world changes through systems
 * and components, not handlers.
 *
 * Subscriber order is registration order; the queue is FIFO — dispatch is deterministic.
 */
class EventBus {
    private val handlers = LinkedHashMap<KClass<out Event>, MutableList<(Event) -> Unit>>()
    private val anyHandlers = ArrayList<(Event) -> Unit>()
    private val queue = ArrayDeque<Event>()

    /** Registers [handler] for events of concrete type [T]. Returns a [Subscription] to [cancel] it. */
    fun <T : Event> subscribe(
        type: KClass<T>,
        handler: (T) -> Unit,
    ): Subscription {
        @Suppress("UNCHECKED_CAST")
        val erased = handler as (Event) -> Unit
        val list = handlers.getOrPut(type) { ArrayList() }
        list.add(erased)
        return Subscription { list.remove(erased) }
    }

    /** Reified convenience for [subscribe]. */
    inline fun <reified T : Event> subscribe(noinline handler: (T) -> Unit): Subscription = subscribe(T::class, handler)

    /** Registers [handler] for *every* event, regardless of type — e.g. a combat log or debug trace. */
    fun subscribeAll(handler: (Event) -> Unit): Subscription {
        anyHandlers.add(handler)
        return Subscription { anyHandlers.remove(handler) }
    }

    /** Enqueues [event] for the next [dispatch]. Does not run any handler. */
    fun publish(event: Event) {
        queue.addLast(event)
    }

    /**
     * Delivers queued events to their handlers in FIFO order, then to the catch-all
     * handlers, draining the queue completely (including events a handler publishes
     * mid-dispatch). A no-op when the queue is empty.
     */
    fun dispatch() {
        while (queue.isNotEmpty()) {
            val event = queue.removeFirst()
            // Snapshot per delivery so a handler may (un)subscribe without disturbing this pass.
            handlers[event::class]?.toList()?.forEach { it(event) }
            if (anyHandlers.isNotEmpty()) anyHandlers.toList().forEach { it(event) }
        }
    }

    /** Number of events awaiting [dispatch]. Mostly for tests/diagnostics. */
    val pending: Int
        get() = queue.size
}
