package com.sletmoe.krogue.systems

import com.sletmoe.krogue.components.AttackIntent
import com.sletmoe.krogue.components.Health
import com.sletmoe.krogue.components.Player
import com.sletmoe.krogue.ecs.World
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
})
