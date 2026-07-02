package com.sletmoe.korogue.perception

import com.sletmoe.korogue.components.Position
import com.sletmoe.korogue.components.ZoneMember
import com.sletmoe.korogue.ecs.Entity
import com.sletmoe.korogue.ecs.World
import com.sletmoe.korogue.registry.GameModule
import com.sletmoe.korogue.utilities.Grid
import com.sletmoe.korogue.world.BLANK_TILE
import com.sletmoe.korogue.world.GameWorld
import com.sletmoe.korogue.world.Zone
import com.sletmoe.kotile.utilities.Vector2Int
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe

/**
 * Unit tests for [StandardPerception]'s two-phase reveal/suppress model (ADR-0015), driven by
 * fixture senses/suppressors/concealments so the algorithm is exercised independently of the engine's
 * built-in senses (krogue-1my.2) and concealments (krogue-1my.3).
 */
class StandardPerceptionTest : DescribeSpec({

    // --- fixtures ---------------------------------------------------------------------------------

    // Two distinct sense-component types: an entity holds at most one component per concrete type, so
    // each real sense (Sight, Tremorsense, …) is its own type. The fixtures mirror that.
    data class FakeSenseComponent(override val senseId: String) : SenseComponent

    data class FakeSenseComponent2(override val senseId: String) : SenseComponent

    /** A contributor that reveals a fixed [cells]/[entities] set, with declared [tags]/[pierces]. */
    class FakeSense(
        override val tags: Set<String>,
        override val pierces: Set<String> = emptySet(),
        private val cells: Set<Vector2Int> = emptySet(),
        private val entities: Set<com.sletmoe.korogue.ecs.EntityId> = emptySet(),
    ) : Sense {
        override fun reveal(
            observer: Entity,
            sense: SenseComponent,
            world: GameWorld,
        ): Contribution = Contribution(cells, entities)
    }

    data class FakeSuppressor(override val negatesTags: Set<String>) : Suppressor

    data class FakeConcealment(override val concealsFromTags: Set<String>) : Concealment

    fun gameWorld(world: World = World()): GameWorld {
        val zone = Zone("z", Grid(8, 8, BLANK_TILE))
        return GameWorld(world, mapOf(zone.zoneId to zone), "z")
    }

    fun perception(vararg senses: Pair<String, Sense>): StandardPerception {
        var builder = GameModule.engineDefaults()
        senses.forEach { (id, sense) -> builder = builder.sense(id, sense) }
        return builder.build().perceptionModels.resolve(StandardPerception.ID) as StandardPerception
    }

    // --- tests ------------------------------------------------------------------------------------

    describe("StandardPerception") {
        it("perceives nothing when the observer has no senses") {
            val gw = gameWorld()
            val observer = gw.ecs.spawn(ZoneMember("z"))

            val perceived = perception().perceive(observer, gw)

            perceived.zoneId shouldBe "z"
            perceived.cells.shouldContainExactly()
            perceived.entities.shouldContainExactly()
        }

        it("returns an empty result with no zone for an observer that has no ZoneMember") {
            val gw = gameWorld()
            val observer = gw.ecs.spawn(FakeSenseComponent("sight"))

            perception("sight" to FakeSense(setOf("visual"), cells = setOf(Vector2Int(1, 1))))
                .perceive(observer, gw)
                .cells
                .shouldContainExactly()
        }

        it("unions the cells and entities of every sense the observer carries") {
            val gw = gameWorld()
            val target = gw.ecs.spawn(Position(2, 2), ZoneMember("z"))
            val observer =
                gw.ecs.spawn(ZoneMember("z"), FakeSenseComponent("sight"), FakeSenseComponent2("tremor"))

            val model =
                perception(
                    "sight" to FakeSense(setOf("visual"), cells = setOf(Vector2Int(1, 1)), entities = setOf(target.id)),
                    "tremor" to FakeSense(setOf("vibration"), cells = setOf(Vector2Int(2, 2))),
                )
            val perceived = model.perceive(observer, gw)

            perceived.cells shouldContainExactly setOf(Vector2Int(1, 1), Vector2Int(2, 2))
            perceived.entities shouldContainExactly setOf(target.id)
        }

        it("drops a sense's contribution when an observer suppressor negates one of its tags") {
            val gw = gameWorld()
            val observer =
                gw.ecs.spawn(ZoneMember("z"), FakeSenseComponent("sight"), FakeSuppressor(setOf("visual")))

            val perceived =
                perception("sight" to FakeSense(setOf("visual", "light-dependent"), cells = setOf(Vector2Int(1, 1))))
                    .perceive(observer, gw)

            perceived.cells.shouldContainExactly() // blinded: sight contributes nothing
        }

        it("drops a suppressed sense even when it pierces the negated tag (pierce is concealment-only)") {
            val gw = gameWorld()
            val observer =
                gw.ecs.spawn(ZoneMember("z"), FakeSenseComponent("truesight"), FakeSuppressor(setOf("visual")))

            val perceived =
                perception(
                    "truesight" to
                        FakeSense(setOf("visual"), pierces = setOf("visual"), cells = setOf(Vector2Int(3, 3))),
                ).perceive(observer, gw)

            perceived.cells.shouldContainExactly() // suppression is absolute on its channel; pierces doesn't save it
        }

        it("keeps a suppressed-channel-adjacent sense whose tags the suppressor doesn't negate") {
            val gw = gameWorld()
            val observer =
                gw.ecs.spawn(ZoneMember("z"), FakeSenseComponent("tremor"), FakeSuppressor(setOf("visual")))

            val perceived =
                perception("tremor" to FakeSense(setOf("vibration"), cells = setOf(Vector2Int(3, 3))))
                    .perceive(observer, gw)

            perceived.cells shouldContainExactly setOf(Vector2Int(3, 3)) // different channel survives blindness
        }

        it("does not perceive a target concealed from a matching sense (cell still revealed)") {
            val gw = gameWorld()
            val target = gw.ecs.spawn(Position(4, 4), ZoneMember("z"), FakeConcealment(setOf("visual")))
            val observer = gw.ecs.spawn(ZoneMember("z"), FakeSenseComponent("sight"))

            val perceived =
                perception(
                    "sight" to FakeSense(setOf("visual"), cells = setOf(Vector2Int(4, 4)), entities = setOf(target.id)),
                ).perceive(observer, gw)

            perceived.entities.shouldNotContain(target.id) // invisible to sight
            perceived.cells.shouldContain(Vector2Int(4, 4)) // but the floor under it is still seen
        }

        it("perceives a concealed target through a sense that pierces the concealment") {
            val gw = gameWorld()
            val target = gw.ecs.spawn(Position(4, 4), ZoneMember("z"), FakeConcealment(setOf("visual")))
            val observer = gw.ecs.spawn(ZoneMember("z"), FakeSenseComponent("seeinvisible"))

            val perceived =
                perception(
                    "seeinvisible" to
                        FakeSense(setOf("visual"), pierces = setOf("visual"), entities = setOf(target.id)),
                ).perceive(observer, gw)

            perceived.entities.shouldContain(target.id)
        }

        it("perceives a concealed target via a second sense whose tag the concealment doesn't cover") {
            val gw = gameWorld()
            val target = gw.ecs.spawn(Position(4, 4), ZoneMember("z"), FakeConcealment(setOf("visual")))
            val observer =
                gw.ecs.spawn(ZoneMember("z"), FakeSenseComponent("sight"), FakeSenseComponent2("tremor"))

            val perceived =
                perception(
                    "sight" to FakeSense(setOf("visual"), entities = setOf(target.id)),
                    "tremor" to FakeSense(setOf("vibration"), entities = setOf(target.id)),
                ).perceive(observer, gw)

            perceived.entities.shouldContain(target.id) // tremorsense sees the invisible creature
        }

        // --- region (environmental) concealment: magical darkness (ADR-0020) --------------------

        it("region concealment hides a magically-dark cell from a matching visual sense") {
            val gw = gameWorld()
            gw.zones.getValue("z").concealment[4, 4] = setOf("visual")
            val observer = gw.ecs.spawn(ZoneMember("z"), FakeSenseComponent("dv"))

            val perceived =
                perception("dv" to FakeSense(setOf("visual"), cells = setOf(Vector2Int(4, 4), Vector2Int(5, 5))))
                    .perceive(observer, gw)

            perceived.cells.shouldNotContain(Vector2Int(4, 4)) // magically dark
            perceived.cells.shouldContain(Vector2Int(5, 5)) // ordinary cell still seen
        }

        it("a piercing sense sees through region concealment (truesight vs magical darkness)") {
            val gw = gameWorld()
            gw.zones.getValue("z").concealment[4, 4] = setOf("visual")
            val observer = gw.ecs.spawn(ZoneMember("z"), FakeSenseComponent("ts"))

            val perceived =
                perception("ts" to FakeSense(setOf("visual"), pierces = setOf("visual"), cells = setOf(Vector2Int(4, 4))))
                    .perceive(observer, gw)

            perceived.cells.shouldContain(Vector2Int(4, 4))
        }

        it("region concealment does not hide a cell from a non-matching (non-visual) sense") {
            val gw = gameWorld()
            gw.zones.getValue("z").concealment[4, 4] = setOf("visual")
            val observer = gw.ecs.spawn(ZoneMember("z"), FakeSenseComponent("tremor"))

            val perceived =
                perception("tremor" to FakeSense(setOf("vibration"), cells = setOf(Vector2Int(4, 4))))
                    .perceive(observer, gw)

            perceived.cells.shouldContain(Vector2Int(4, 4)) // vibration isn't a visual channel
        }

        it("a creature in magical darkness is unseen by a visual sense but felt by a non-visual one") {
            val gw = gameWorld()
            gw.zones.getValue("z").concealment[4, 4] = setOf("visual")
            val target = gw.ecs.spawn(Position(4, 4), ZoneMember("z"))

            // visual sense alone: the creature's cell is region-hidden, so it's not perceived.
            val visualOnly = gw.ecs.spawn(ZoneMember("z"), FakeSenseComponent("dv"))
            perception("dv" to FakeSense(setOf("visual"), cells = setOf(Vector2Int(4, 4)), entities = setOf(target.id)))
                .perceive(visualOnly, gw)
                .entities
                .shouldNotContain(target.id)

            // add tremorsense: the creature is felt through the dark.
            val observer =
                gw.ecs.spawn(ZoneMember("z"), FakeSenseComponent("dv"), FakeSenseComponent2("tremor"))
            perception(
                "dv" to FakeSense(setOf("visual"), cells = setOf(Vector2Int(4, 4)), entities = setOf(target.id)),
                "tremor" to FakeSense(setOf("vibration"), entities = setOf(target.id)),
            ).perceive(observer, gw)
                .entities
                .shouldContain(target.id)
        }

        it("real Darkvision is blocked by magical darkness while TrueSight pierces it (acceptance)") {
            val gw = gameWorld() // 8x8 of transparent BLANK_TILE, so LOS is unobstructed
            gw.zones.getValue("z").concealment[3, 3] = setOf(PerceptionTags.VISUAL)
            val target = gw.ecs.spawn(Position(3, 3), ZoneMember("z"))

            val darkvision = gw.ecs.spawn(Position(0, 0), ZoneMember("z"), Darkvision())
            val dv = perception().perceive(darkvision, gw)
            dv.sees(3, 3) shouldBe false
            dv.sees(target.id) shouldBe false

            val truesight = gw.ecs.spawn(Position(7, 7), ZoneMember("z"), TrueSight())
            val ts = perception().perceive(truesight, gw)
            ts.sees(3, 3) shouldBe true
            ts.sees(target.id) shouldBe true
        }
    }
})
