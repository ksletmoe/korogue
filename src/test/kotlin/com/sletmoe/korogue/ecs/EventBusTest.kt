package com.sletmoe.korogue.ecs

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

private data class Pinged(val n: Int) : Event

private data class Ponged(val s: String) : Event

class EventBusTest : FunSpec({

    test("publish enqueues without running handlers; dispatch delivers and drains") {
        val bus = EventBus()
        val seen = mutableListOf<Int>()
        bus.subscribe<Pinged> { seen.add(it.n) }

        bus.publish(Pinged(1))
        bus.publish(Pinged(2))
        bus.pending shouldBe 2
        seen shouldBe emptyList() // nothing runs until dispatch

        bus.dispatch()

        seen shouldContainExactly listOf(1, 2)
        bus.pending shouldBe 0
    }

    test("handlers receive only events of their exact concrete type") {
        val bus = EventBus()
        val pings = mutableListOf<Int>()
        val pongs = mutableListOf<String>()
        bus.subscribe<Pinged> { pings.add(it.n) }
        bus.subscribe<Ponged> { pongs.add(it.s) }

        bus.publish(Pinged(7))
        bus.publish(Ponged("a"))
        bus.dispatch()

        pings shouldContainExactly listOf(7)
        pongs shouldContainExactly listOf("a")
    }

    test("subscribeAll receives every event, in publish order, after typed handlers") {
        val bus = EventBus()
        val order = mutableListOf<String>()
        bus.subscribe<Pinged> { order.add("typed-${it.n}") }
        bus.subscribeAll { order.add("any-$it") }

        bus.publish(Pinged(1))
        bus.publish(Ponged("x"))
        bus.dispatch()

        order shouldContainExactly listOf("typed-1", "any-Pinged(n=1)", "any-Ponged(s=x)")
    }

    test("multiple handlers for one type fire in registration order") {
        val bus = EventBus()
        val order = mutableListOf<String>()
        bus.subscribe<Pinged> { order.add("first") }
        bus.subscribe<Pinged> { order.add("second") }

        bus.publish(Pinged(1))
        bus.dispatch()

        order shouldContainExactly listOf("first", "second")
    }

    test("cancelling a subscription stops further delivery and is idempotent") {
        val bus = EventBus()
        val seen = mutableListOf<Int>()
        val sub = bus.subscribe<Pinged> { seen.add(it.n) }

        bus.publish(Pinged(1))
        bus.dispatch()
        sub.cancel()
        sub.cancel() // idempotent
        bus.publish(Pinged(2))
        bus.dispatch()

        seen shouldContainExactly listOf(1)
    }

    test("events published by a handler are drained in the same dispatch") {
        val bus = EventBus()
        val seen = mutableListOf<String>()
        bus.subscribe<Pinged> { ping ->
            seen.add("ping-${ping.n}")
            if (ping.n < 3) bus.publish(Pinged(ping.n + 1)) // cascade
        }

        bus.publish(Pinged(1))
        bus.dispatch()

        seen shouldContainExactly listOf("ping-1", "ping-2", "ping-3")
        bus.pending shouldBe 0
    }

    test("a handler may subscribe during dispatch without disturbing the current pass") {
        val bus = EventBus()
        val seen = mutableListOf<String>()
        bus.subscribe<Pinged> {
            seen.add("a")
            bus.subscribe<Pinged> { seen.add("late") } // must not fire for this same event
        }

        bus.publish(Pinged(1))
        bus.dispatch()

        seen shouldContainExactly listOf("a")
    }

    test("dispatch on an empty queue is a no-op") {
        val bus = EventBus()
        bus.subscribe<Pinged> { error("should not run") }
        bus.dispatch()
        bus.pending shouldBe 0
    }
})
