package com.sletmoe.korogue.ecs

import com.sletmoe.korogue.random.GameRandom
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf

// Test components live in TestComponents.kt.

// Two stages with a real dependency (SECOND runsAfter FIRST), for exercising the pipeline
// re-validation that removeSystem/clearSystems trigger without pulling in the built-in stages.
internal enum class TestStage : PipelineStage {
    FIRST,
    SECOND,
    ;

    override val id: String get() = name
    override val runsAfter: Set<PipelineStage> get() = if (this == SECOND) setOf(FIRST) else emptySet()
}

internal class StagedSystem(override val stage: PipelineStage) : Staged {
    override fun update(
        world: World,
        ctx: TickContext,
    ) = Unit
}

internal object WorldTestPing : Event

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

        it("passes elapsedMs through the context, alongside the world's own gameplay stream") {
            val world = World(GameRandom.fromSeed(42))
            var seen: TickContext? = null
            world.addSystem { _, ctx -> seen = ctx }
            world.tick(elapsedMs = 16L)
            val captured = requireNotNull(seen)
            captured.elapsedMs shouldBe 16L
            // Systems get the world's seeded stream — not an RNG the caller had to supply.
            captured.random shouldBe world.random.stream(GameRandom.GAMEPLAY)
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

        it("removeSystem drops a system by reference and stops it running") {
            val world = World()
            val log = mutableListOf<String>()
            val a = System { _, _ -> log.add("a") }
            val b = System { _, _ -> log.add("b") }
            world.addSystem(a).addSystem(b)

            world.removeSystem(a) shouldBe true
            world.systems() shouldContainExactly listOf(b)
            world.tick()
            log shouldContainExactly listOf("b")
        }

        it("removeSystem returns false for a system that was never registered") {
            val world = World()
            world.addSystem { _, _ -> }
            world.removeSystem { _, _ -> } shouldBe false
        }

        it("removeSystem re-validates the surviving pipeline before the next tick") {
            // Removing the stage a survivor depends on makes that constraint vacuous, not violated:
            // the world must still tick cleanly rather than reject the now-shorter pipeline.
            val world = World()
            val dependency = StagedSystem(TestStage.FIRST)
            val dependent = StagedSystem(TestStage.SECOND) // SECOND runsAfter FIRST
            world.addSystem(dependency).addSystem(dependent)
            world.validateSystemOrder() // legal as wired

            world.removeSystem(dependency) shouldBe true
            shouldNotThrowAny { world.tick() }
        }

        it("clearSystems removes every system but leaves entities and events intact") {
            val world = World()
            val entity = world.spawn(Health(100, 100))
            var delivered = false
            world.events.subscribe<WorldTestPing> { delivered = true }
            world.addSystem { _, _ -> }
            world.addSystem { _, _ -> }

            world.clearSystems()
            world.systems().shouldBeEmpty()

            // A tick with no systems is still a real turn: it drains events and advances the counter.
            world.events.publish(WorldTestPing)
            world.tick()
            delivered shouldBe true
            world.currentTurn shouldBe 1L
            world.get(entity.id) shouldNotBe null
        }
    }

    // The out-of-band seam (ADR-0025): running one system without taking a turn. It exists so
    // callers priming derived state at startup (light maps, perception caches) don't have to
    // invent a TickContext — and an invented one meant an invented, unseeded RNG.
    describe("tickContext") {
        it("hands out the world's own gameplay stream, so an out-of-band run is seeded too") {
            val world = World(GameRandom.fromSeed(42))
            world.tickContext().random shouldBe world.random.stream(GameRandom.GAMEPLAY)
        }

        it("reports the turn about to run and passes elapsedMs through") {
            val world = World()
            world.tick()
            world.tick()
            val ctx = world.tickContext(elapsedMs = 16L)
            ctx.turn shouldBe 2L // the turn tick() would process next, matching what tick() passes
            ctx.elapsedMs shouldBe 16L
        }

        it("defaults elapsedMs to zero") {
            World().tickContext().elapsedMs shouldBe 0L
        }

        it("neither advances the turn nor runs systems — the whole point of priming") {
            val world = World()
            var ran = 0
            world.addSystem { _, _ -> ran++ }

            repeat(3) { world.tickContext() }

            ran shouldBe 0
            world.currentTurn shouldBe 0L
            // …and the world still ticks normally afterwards.
            world.tick()
            ran shouldBe 1
            world.currentTurn shouldBe 1L
        }

        it("shares the gameplay stream with tick, so priming and ticking can't desync") {
            // Both draw the same sequence: a system primed out of band consumes from the same
            // stream a later tick continues, rather than replaying draws or forking a parallel one.
            val world = World(GameRandom.fromSeed(7))
            val primed = world.tickContext().random.nextLong()

            var duringTick: Long? = null
            world.addSystem { _, ctx -> duringTick = ctx.random.nextLong() }
            world.tick()

            duringTick shouldNotBe primed // the stream advanced; it did not restart

            // Against a fresh stream off the same seed: priming took the first draw, and the
            // tick picked up at the second — one continuous sequence across both entry points.
            val expected = GameRandom.fromSeed(7).stream(GameRandom.GAMEPLAY)
            expected.nextLong() shouldBe primed
            expected.nextLong() shouldBe duringTick
        }
    }
})
