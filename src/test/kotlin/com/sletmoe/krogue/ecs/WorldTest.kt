package com.sletmoe.krogue.ecs

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.random.Random

// Test components live in TestComponents.kt.

class WorldTest : DescribeSpec({

    describe("entity lifecycle") {
        it("assigns unique, increasing ids") {
            val world = World()
            world.spawn().id shouldBe EntityId(0)
            world.spawn().id shouldBe EntityId(1)
        }

        it("does not reuse ids after despawn") {
            val world = World()
            val a = world.spawn()
            a.id shouldBe EntityId(0)
            world.despawn(a.id)
            // The next id is fresh, not the recycled 0 — important for save/load stability.
            world.spawn().id shouldBe EntityId(1)
        }

        it("spawns with initial components retrievable immediately") {
            val e = World().spawn(Position(3, 4), Health(20, 20))
            e.get<Position>() shouldBe Position(3, 4)
            e.get<Health>() shouldBe Health(20, 20)
        }

        it("get and contains find a spawned entity") {
            val world = World()
            val e = world.spawn()
            world.contains(e.id).shouldBeTrue()
            world.get(e.id) shouldBe e
        }

        it("despawn removes an entity") {
            val world = World()
            val e = world.spawn()
            world.despawn(e.id) shouldBe e
            world.contains(e.id).shouldBeFalse()
            world.get(e.id).shouldBeNull()
            world.despawn(e.id).shouldBeNull()
        }

        it("tracks entity count and spawn order") {
            val world = World()
            val a = world.spawn()
            val b = world.spawn()
            val c = world.spawn()
            world.entityCount shouldBe 3
            world.entities().map { it.id }.toList() shouldContainExactly listOf(a.id, b.id, c.id)
        }
    }

    describe("component mutation seam") {
        it("set attaches and replaces by concrete type") {
            val world = World()
            val e = world.spawn()
            world.set(e.id, Health(50, 100))
            e.get<Health>() shouldBe Health(50, 100)
            world.set(e.id, Health(75, 100))
            e.get<Health>() shouldBe Health(75, 100)
        }

        it("set is a no-op for an unknown entity") {
            World().set(EntityId(5), Health(1, 1))
        }

        it("update reads, transforms, stores, and returns the new value") {
            val world = World()
            val e = world.spawn(Health(100, 100))
            world.update<Health>(e.id) { it.copy(current = it.current - 30) } shouldBe Health(70, 100)
            e.get<Health>() shouldBe Health(70, 100)
        }

        it("update returns null and changes nothing when the component is absent") {
            val world = World()
            val e = world.spawn(Position(0, 0))
            world.update<Health>(e.id) { it.copy(current = 0) }.shouldBeNull()
            e.has<Health>().shouldBeFalse()
        }

        it("update returns null for an unknown entity") {
            World().update<Health>(EntityId(999)) { it.copy(current = 0) }.shouldBeNull()
        }

        it("update writes back under the queried type's key even if transform returns a subtype") {
            val world = World()
            val e = world.spawn(Buff(1))
            world.update<Buff>(e.id) { SuperBuff(it.power + 1) }
            // Stored under Buff::class, so the Buff query still finds it (not orphaned).
            val found = e.get<Buff>()
            found.shouldBeInstanceOf<SuperBuff>()
            found.power shouldBe 2
        }

        it("remove removes and returns the component") {
            val world = World()
            val e = world.spawn(Position(4, 4))
            world.remove<Position>(e.id) shouldBe Position(4, 4)
            e.has<Position>().shouldBeFalse()
            world.remove<Position>(e.id).shouldBeNull()
        }
    }

    describe("queries") {
        it("entitiesWith<A> returns only entities having A") {
            val world = World()
            val withPos = world.spawn(Position(1, 1))
            world.spawn(Health(1, 1))
            world.entitiesWith<Position>().map { it.id }.toList() shouldContainExactly listOf(withPos.id)
        }

        it("entitiesWith<A, B> returns the intersection") {
            val world = World()
            val both = world.spawn(Position(1, 1), Health(1, 1))
            world.spawn(Position(2, 2))
            world.spawn(Health(2, 2))
            world.entitiesWith<Position, Health>().map { it.id }.toList() shouldContainExactly listOf(both.id)
        }

        it("entitiesWith<A, B, C> requires all three") {
            val world = World()
            val all = world.spawn(Position(0, 0), Health(1, 1), Tagged("z"))
            world.spawn(Position(0, 0), Health(1, 1))
            world.entitiesWith<Position, Health, Tagged>().map { it.id }.toList() shouldContainExactly listOf(all.id)
        }

        it("returns a lazy sequence that can be filtered further") {
            val world = World()
            world.spawn(Health(5, 10))
            val alive = world.spawn(Health(8, 10))
            val result = world.entitiesWith<Health>().filter { it.require<Health>().current > 6 }.toList()
            result.map { it.id } shouldContainExactly listOf(alive.id)
        }

        it("is snapshot-safe: spawning during iteration does not throw") {
            val world = World()
            world.spawn(Health(1, 1))
            world.spawn(Health(1, 1))
            var visited = 0
            world.entitiesWith<Health>().forEach { _ ->
                visited++
                if (visited == 1) world.spawn(Health(2, 2)) // structural change mid-iteration
            }
            visited shouldBe 2 // snapshot taken at query creation
            world.entityCount shouldBe 3
        }

        it("is snapshot-safe: despawning during iteration does not throw") {
            val world = World()
            world.spawn(Health(1, 1))
            world.spawn(Health(1, 1))
            world.entitiesWith<Health>().forEach { world.despawn(it.id) }
            world.entityCount shouldBe 0
        }
    }

    describe("systems and tick") {
        it("runs systems in registration order") {
            val world = World()
            val log = mutableListOf<String>()
            world.addSystem { _, _ -> log.add("a") }
            world.addSystem { _, _ -> log.add("b") }
            world.tick()
            log shouldContainExactly listOf("a", "b")
        }

        it("advances the turn counter and exposes it via context") {
            val world = World()
            val seenTurns = mutableListOf<Long>()
            world.addSystem { _, ctx -> seenTurns.add(ctx.turn) }
            world.currentTurn shouldBe 0L
            world.tick()
            world.tick()
            world.currentTurn shouldBe 2L
            seenTurns shouldContainExactly listOf(0L, 1L)
        }

        it("passes elapsedMs and random through the context") {
            val world = World()
            var seen: TickContext? = null
            world.addSystem { _, ctx -> seen = ctx }
            val rng = Random(42)
            world.tick(elapsedMs = 16L, random = rng)
            val captured = requireNotNull(seen)
            captured.elapsedMs shouldBe 16L
            captured.random shouldBe rng
        }

        it("lets a system query and mutate the world") {
            val world = World()
            val e = world.spawn(Health(100, 100))
            world.addSystem { w, _ ->
                w.entitiesWith<Health>().forEach { ent ->
                    w.update<Health>(ent.id) { it.copy(current = it.current - 10) }
                }
            }
            world.tick()
            e.get<Health>() shouldBe Health(90, 100)
        }

        it("exposes registered systems in order") {
            val world = World()
            val s1 = System { _, _ -> }
            val s2 = System { _, _ -> }
            world.addSystem(s1).addSystem(s2)
            world.systems() shouldContainExactly listOf(s1, s2)
        }
    }
})
