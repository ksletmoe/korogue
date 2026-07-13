package com.sletmoe.korogue.systems

import com.badlogic.gdx.graphics.Color
import com.sletmoe.korogue.algorithms.color.toNormalizedRgb
import com.sletmoe.korogue.components.AttackIntent
import com.sletmoe.korogue.components.Health
import com.sletmoe.korogue.components.Named
import com.sletmoe.korogue.components.Player
import com.sletmoe.korogue.components.Position
import com.sletmoe.korogue.components.RenderLayer
import com.sletmoe.korogue.components.Renderable
import com.sletmoe.korogue.ecs.World
import com.sletmoe.korogue.events.EntityDamaged
import com.sletmoe.korogue.events.EntityDied
import com.sletmoe.kotile.utilities.Vector2Int
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

class CombatSystemTest : FunSpec({

    fun worldWithCombat(): World = World().addSystem(CombatSystem(damage = 30))

    test("an AttackIntent reduces the target's health by the damage and is consumed") {
        val world = worldWithCombat()
        val target = world.spawn(Health(100, 100)).id
        val attacker = world.spawn(AttackIntent(target)).id

        world.tick()

        world.get(target)!!.require<Health>().current shouldBe 70
        world.get(attacker)!!.get<AttackIntent>().shouldBeNull()
    }

    test("health floors at zero and a dead non-player entity is despawned") {
        val world = worldWithCombat()
        val target = world.spawn(Health(20, 100)).id
        world.spawn(AttackIntent(target))

        world.tick()

        world.get(target).shouldBeNull() // 20 - 30 -> 0 -> dead -> despawned
    }

    test("a dead player is spared (death handling is out of scope)") {
        val world = worldWithCombat()
        val player = world.spawn(Health(10, 100), Player).id
        world.spawn(AttackIntent(player))

        world.tick()

        world.get(player).shouldNotBeNull().require<Health>().current shouldBe 0
    }

    test("publishes EntityDamaged for each hit, with attacker, amount, remaining health and position") {
        val world = worldWithCombat()
        val damaged = mutableListOf<EntityDamaged>()
        world.events.subscribe<EntityDamaged> { damaged.add(it) }
        val target = world.spawn(Health(100, 100), Position(3, 4)).id
        val attacker = world.spawn(AttackIntent(target)).id

        world.tick()

        damaged.shouldNotBeNull().single().let {
            it.target shouldBe target
            it.attacker shouldBe attacker
            it.amount shouldBe 30
            it.remainingHealth shouldBe 70
            it.position shouldBe Vector2Int(3, 4)
        }
    }

    test("EntityDamaged has a null position when the target carries no Position") {
        val world = worldWithCombat()
        val damaged = mutableListOf<EntityDamaged>()
        world.events.subscribe<EntityDamaged> { damaged.add(it) }
        val target = world.spawn(Health(100, 100)).id
        world.spawn(AttackIntent(target))

        world.tick()

        damaged.single().position.shouldBeNull()
    }

    test("publishes EntityDied with the snapshotted name, position, glyph and color when a non-player dies") {
        val world = worldWithCombat()
        val deaths = mutableListOf<EntityDied>()
        world.events.subscribe<EntityDied> { deaths.add(it) }
        val color = Color.GREEN.toNormalizedRgb()
        val target =
            world
                .spawn(
                    Health(20, 100),
                    Named("goblin"),
                    Position(5, 6),
                    Renderable('g', color, RenderLayer.CREATURE),
                ).id
        world.spawn(AttackIntent(target))

        world.tick()

        deaths.single().let {
            it.entity shouldBe target
            it.name shouldBe "goblin"
            it.position shouldBe Vector2Int(5, 6)
            it.glyph shouldBe 'g'
            it.color shouldBe color
        }
        world.get(target).shouldBeNull()
    }

    test("EntityDied has null position/glyph/color when the deceased carried no Position/Renderable") {
        val world = worldWithCombat()
        val deaths = mutableListOf<EntityDied>()
        world.events.subscribe<EntityDied> { deaths.add(it) }
        val target = world.spawn(Health(20, 100)).id
        world.spawn(AttackIntent(target))

        world.tick()

        deaths.single().let {
            it.position.shouldBeNull()
            it.glyph.shouldBeNull()
            it.color.shouldBeNull()
        }
    }

    test("a spared player emits no EntityDied") {
        val world = worldWithCombat()
        val deaths = mutableListOf<EntityDied>()
        world.events.subscribe<EntityDied> { deaths.add(it) }
        val player = world.spawn(Health(10, 100), Player).id
        world.spawn(AttackIntent(player))

        world.tick()

        deaths shouldBe emptyList()
    }
})
