package com.sletmoe.korogue.registry

import com.sletmoe.korogue.algorithms.lighting.DiminishingLightValueCalculator
import com.sletmoe.korogue.systems.BehaviorStrategy
import com.sletmoe.korogue.systems.HuntPlayerStrategy
import com.sletmoe.korogue.systems.WanderStrategy
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
})
