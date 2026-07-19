package com.sletmoe.korogue.components

import com.sletmoe.korogue.algorithms.color.NormalizedRgb
import com.sletmoe.korogue.ecs.World
import com.sletmoe.kotile.utilities.Vector2Int
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe

class ComponentsTest : FunSpec({
    test("Position exposes its coordinate as a Vector2Int") {
        Position(3, 4).point shouldBe Vector2Int(3, 4)
    }

    test("Position constructs from a Vector2Int equivalently to its Int pair (ADR-0034)") {
        Position(Vector2Int(3, 4)) shouldBe Position(3, 4)
    }

    test("MoveIntent round-trips through its Vector2Int delta form (ADR-0034)") {
        MoveIntent(Vector2Int(-1, 2)) shouldBe MoveIntent(-1, 2)
        MoveIntent(-1, 2).delta shouldBe Vector2Int(-1, 2)
    }

    test("Portal constructs from and exposes a Vector2Int target (ADR-0034)") {
        Portal("cave", Vector2Int(7, 8)) shouldBe Portal("cave", 7, 8)
        Portal("cave", 7, 8).target shouldBe Vector2Int(7, 8)
    }

    test("Health reports alive/dead from current") {
        Health(5, 10).alive.shouldBeTrue()
        Health(0, 10).alive.shouldBeFalse()
        Health(0, 10).dead.shouldBeTrue()
    }

    test("Renderable defaults to the CREATURE layer") {
        Renderable('@', NormalizedRgb(1.0, 1.0, 0.0)).layer shouldBe RenderLayer.CREATURE
    }

    test("RenderLayer z-indices sit above the terrain layer (0)") {
        RenderLayer.CREATURE.zIndex shouldBe 1
        RenderLayer.PLAYER.zIndex shouldBe 2
        RenderLayer.OVERLAY.zIndex shouldBe 3
    }

    test("ZoneMember and Named carry their data") {
        ZoneMember("cave").zoneId shouldBe "cave"
        Named("goblin", "a small foe").description shouldBe "a small foe"
    }

    test("components attach to and read back from an entity") {
        val world = World()
        val e = world.spawn(Position(1, 2), Health(10, 10), Player)
        e.get<Position>() shouldBe Position(1, 2)
        e.has<Player>().shouldBeTrue()
    }
})
