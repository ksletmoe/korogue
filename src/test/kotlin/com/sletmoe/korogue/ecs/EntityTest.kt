package com.sletmoe.korogue.ecs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

// Test components live in TestComponents.kt. Entity is read-only to callers, so all
// construction here goes through World.spawn; mutation is covered in WorldTest.

class EntityTest : DescribeSpec({
    fun entity(vararg components: Component): Entity = World().spawn(components.asList())

    describe("Entity component access (read-only)") {
        it("reports a missing component as absent") {
            val e = entity()
            e.has<Position>().shouldBeFalse()
            e.get<Position>().shouldBeNull()
        }

        it("require throws for a missing component") {
            shouldThrow<IllegalStateException> { entity().require<Position>() }
        }

        it("retrieves components it was spawned with") {
            val e = entity(Position(2, 3))
            e.has<Position>().shouldBeTrue()
            e.get<Position>() shouldBe Position(2, 3)
            e.require<Position>() shouldBe Position(2, 3)
        }

        it("keeps components of different types independently") {
            val e = entity(Position(1, 1), Health(10, 10), Name("goblin"))
            e.get<Position>() shouldBe Position(1, 1)
            e.get<Health>() shouldBe Health(10, 10)
            e.get<Name>() shouldBe Name("goblin")
        }

        it("holds at most one component per concrete type — last wins") {
            val e = entity(Health(50, 100), Health(75, 100))
            e.get<Health>() shouldBe Health(75, 100)
        }

        it("exposes all attached components") {
            val e = entity(Position(0, 0), Name("x"))
            e.components.shouldContainExactlyInAnyOrder(Position(0, 0), Name("x"))
        }
    }
})
