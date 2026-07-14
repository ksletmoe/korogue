package com.sletmoe.korogue.registry

import com.sletmoe.korogue.algorithms.lighting.DiminishingLightValueCalculator
import com.sletmoe.korogue.algorithms.zonegen.ZoneGenContext
import com.sletmoe.korogue.algorithms.zonegen.ZoneGenerator
import com.sletmoe.korogue.components.Facing
import com.sletmoe.korogue.components.RangedAttacker
import com.sletmoe.korogue.ecs.Entity
import com.sletmoe.korogue.perception.Contribution
import com.sletmoe.korogue.perception.DarkvisionSense
import com.sletmoe.korogue.perception.Perceived
import com.sletmoe.korogue.perception.PerceptionModel
import com.sletmoe.korogue.perception.Sense
import com.sletmoe.korogue.perception.SenseComponent
import com.sletmoe.korogue.perception.SightSense
import com.sletmoe.korogue.perception.StandardPerception
import com.sletmoe.korogue.perception.TelepathySense
import com.sletmoe.korogue.perception.TremorsenseSense
import com.sletmoe.korogue.perception.TrueSightSense
import com.sletmoe.korogue.systems.BehaviorStrategy
import com.sletmoe.korogue.systems.HuntPlayerStrategy
import com.sletmoe.korogue.systems.WanderStrategy
import com.sletmoe.korogue.world.GameWorld
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class GameModuleTest : FunSpec({

    test("engine defaults resolve the built-in strategies and calculators") {
        val module = GameModule.engineDefaults().build()
        module.strategies.resolve(WanderStrategy.ID).shouldBeInstanceOf<WanderStrategy>()
        module.strategies.resolve(HuntPlayerStrategy.ID).shouldBeInstanceOf<HuntPlayerStrategy>()
        module.calculators
            .resolve(DiminishingLightValueCalculator.ID)
            .shouldBeInstanceOf<DiminishingLightValueCalculator>()
    }

    test("engine defaults register RangedAttacker and Facing so they survive save/load (krogue-4tn)") {
        val module = GameModule.engineDefaults().build()
        module.components.isRegistered(RangedAttacker()) shouldBe true
        module.components.isRegistered(Facing.LEFT) shouldBe true
    }

    test("a consumer can register and resolve its own strategy") {
        val custom = BehaviorStrategy { _, _, _ -> null }
        val module = GameModule.engineDefaults().strategy("patrol", custom).build()

        module.strategies.resolve("patrol") shouldBe custom
        ("patrol" in module.strategies) shouldBe true
    }

    test("resolving an unknown id fails loudly") {
        val module = GameModule.engineDefaults().build()
        shouldThrow<IllegalStateException> { module.strategies.resolve("nope") }
    }

    test("engine defaults register StandardPerception as the default perception model") {
        val module = GameModule.engineDefaults().build()
        module.perceptionModels.resolve(StandardPerception.ID).shouldBeInstanceOf<StandardPerception>()
    }

    test("engine defaults register the built-in senses") {
        val module = GameModule.engineDefaults().build()
        module.senses.ids shouldBe
            setOf(SightSense.ID, DarkvisionSense.ID, TrueSightSense.ID, TremorsenseSense.ID, TelepathySense.ID)
        module.senses.resolve(SightSense.ID).shouldBeInstanceOf<SightSense>()
        module.senses.resolve(DarkvisionSense.ID).shouldBeInstanceOf<DarkvisionSense>()
        module.senses.resolve(TrueSightSense.ID).shouldBeInstanceOf<TrueSightSense>()
        module.senses.resolve(TremorsenseSense.ID).shouldBeInstanceOf<TremorsenseSense>()
        module.senses.resolve(TelepathySense.ID).shouldBeInstanceOf<TelepathySense>()
    }

    test("a consumer can register and resolve its own sense, wired into the default model") {
        val sense =
            object : Sense {
                override val tags = setOf("heat")

                override fun reveal(
                    observer: Entity,
                    sense: SenseComponent,
                    world: GameWorld,
                ): Contribution = Contribution.EMPTY
            }
        val module = GameModule.engineDefaults().sense("heat", sense).build()

        module.senses.resolve("heat") shouldBe sense
    }

    test("a consumer can override the default perception model") {
        val custom = PerceptionModel { _, _ -> Perceived() }
        val module = GameModule.engineDefaults().perceptionModel(StandardPerception.ID, custom).build()

        module.perceptionModels.resolve(StandardPerception.ID) shouldBe custom
    }

    test("engine defaults register no built-in generators (world-gen generators are game-specific)") {
        val module = GameModule.engineDefaults().build()
        module.generators.ids shouldBe emptySet()
    }

    test("a consumer can register and resolve its own world-gen generator") {
        val generator = ZoneGenerator { ctx: ZoneGenContext -> ctx.spawn(0, 0) }
        val module = GameModule.engineDefaults().generator("cave", generator).build()

        module.generators.resolve("cave") shouldBe generator
        ("cave" in module.generators) shouldBe true
    }

    test("resolving an unknown generator id fails loudly") {
        val module = GameModule.engineDefaults().build()
        shouldThrow<IllegalStateException> { module.generators.resolve("nope") }
    }
})
