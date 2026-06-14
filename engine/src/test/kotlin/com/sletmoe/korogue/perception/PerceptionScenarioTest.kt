package com.sletmoe.korogue.perception

import com.sletmoe.korogue.algorithms.lighting.LightValue
import com.sletmoe.korogue.components.Health
import com.sletmoe.korogue.components.Position
import com.sletmoe.korogue.components.ZoneMember
import com.sletmoe.korogue.ecs.TickContext
import com.sletmoe.korogue.ecs.World
import com.sletmoe.korogue.registry.GameModule
import com.sletmoe.korogue.schedule.Scheduler
import com.sletmoe.korogue.schedule.TimedEffect
import com.sletmoe.korogue.utilities.Grid
import com.sletmoe.korogue.world.BLANK_TILE
import com.sletmoe.korogue.world.GameWorld
import com.sletmoe.korogue.world.Zone
import com.sletmoe.kotile.utilities.Vector2Int
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import kotlin.random.Random

/**
 * End-to-end scenarios for concealment, piercing, and suppression (ADR-0015, krogue-1my.3), driven
 * by the engine's *real* built-in senses, [Invisible]/[Blind] effects, and [StandardPerception] wired
 * by `GameModule.engineDefaults()` — the algorithm and the senses are unit-tested separately
 * ([StandardPerceptionTest], [EngineSensesTest]); this proves the concrete pieces compose.
 */
class PerceptionScenarioTest : DescribeSpec({

    /** The engine default `StandardPerception`, wired to the built-in senses. */
    val model = GameModule.engineDefaults().build().perceptionModels.resolve(StandardPerception.ID)

    /** A fully-lit n×n zone, so light-dependent `Sight` always reveals an in-sight cell. */
    fun litWorld(size: Int = 8): GameWorld {
        val zone = Zone("z", Grid(size, size, BLANK_TILE)).also { it.lightMap.fill(LightValue.FULLBRIGHT) }
        return GameWorld(World(), mapOf(zone.zoneId to zone), zone.zoneId)
    }

    describe("an invisible monster") {
        it("is not seen by Sight, though the floor under it still is") {
            val gw = litWorld()
            val observer = gw.ecs.spawn(Position(4, 4), ZoneMember("z"), Sight())
            val ghost = gw.ecs.spawn(Position(5, 4), ZoneMember("z"), Health(1, 1), Invisible)

            val perceived = model.perceive(observer, gw)

            perceived.entities shouldNotContain ghost.id
            perceived.cells shouldContain Vector2Int(5, 4) // the cell is lit + in sight
        }

        it("is still felt by Tremorsense (a different tag) carried alongside Sight") {
            val gw = litWorld()
            val observer = gw.ecs.spawn(Position(4, 4), ZoneMember("z"), Sight(), Tremorsense())
            val ghost = gw.ecs.spawn(Position(5, 4), ZoneMember("z"), Health(1, 1), Invisible)

            model.perceive(observer, gw).entities shouldContain ghost.id
        }

        it("is seen by TrueSight, which pierces visual concealment") {
            val gw = litWorld()
            val observer = gw.ecs.spawn(Position(4, 4), ZoneMember("z"), TrueSight())
            val ghost = gw.ecs.spawn(Position(5, 4), ZoneMember("z"), Health(1, 1), Invisible)

            model.perceive(observer, gw).entities shouldContain ghost.id
        }
    }

    describe("a blinded observer") {
        it("perceives no cells through Sight while Blind is present") {
            val gw = litWorld()
            val observer = gw.ecs.spawn(Position(4, 4), ZoneMember("z"), Sight(), Blind)

            model.perceive(observer, gw).cells shouldBe emptySet()
        }

        it("is also blinded through TrueSight — pierce defeats concealment, not suppression") {
            val gw = litWorld()
            val observer = gw.ecs.spawn(Position(4, 4), ZoneMember("z"), TrueSight(), Blind)

            model.perceive(observer, gw).cells shouldBe emptySet() // true-sight is still eyesight
        }

        it("still perceives creatures through Tremorsense — a different channel Blind can't reach") {
            val gw = litWorld()
            val observer = gw.ecs.spawn(Position(4, 4), ZoneMember("z"), Sight(), Tremorsense(), Blind)
            val creature = gw.ecs.spawn(Position(5, 4), ZoneMember("z"), Health(1, 1))

            model.perceive(observer, gw).entities shouldContain creature.id
        }
    }

    describe("a transient blindness on a scheduler fuse (krogue-6uq)") {
        it("suppresses sight, then restores it when the fuse removes Blind") {
            val gw = litWorld()
            val observer = gw.ecs.spawn(Position(4, 4), ZoneMember("z"), Sight(), Blind)

            // A fuse-fired effect that lifts the transient blindness by removing the component —
            // the permanent Sight is untouched, so perception returns intact.
            val unblind = TimedEffect { world, _, _ -> world.remove<Blind>(observer.id) }
            val scheduler = Scheduler()
            scheduler.fuse("unblind", afterTurns = 3)

            // Turns 0..2: still blind, no cells.
            for (t in 0..2L) {
                scheduler.advance(gw.ecs, TickContext(t, 0L, Random.Default)) { unblind }
            }
            model.perceive(observer, gw).cells shouldBe emptySet()

            // Turn 3: fuse fires, Blind removed, sight restored.
            scheduler.advance(gw.ecs, TickContext(3L, 0L, Random.Default)) { unblind }

            observer.has<Blind>() shouldBe false
            model.perceive(observer, gw).cells shouldContain Vector2Int(4, 4)
        }
    }
})
