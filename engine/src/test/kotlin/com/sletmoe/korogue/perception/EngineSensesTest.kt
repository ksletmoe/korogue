package com.sletmoe.korogue.perception

import com.badlogic.gdx.graphics.Color
import com.sletmoe.korogue.algorithms.lighting.LightValue
import com.sletmoe.korogue.algorithms.los.SymmetricShadowCaster
import com.sletmoe.korogue.components.Health
import com.sletmoe.korogue.components.Position
import com.sletmoe.korogue.components.ZoneMember
import com.sletmoe.korogue.ecs.World
import com.sletmoe.korogue.world.BLANK_TILE
import com.sletmoe.korogue.world.GameWorld
import com.sletmoe.korogue.world.Tile
import com.sletmoe.korogue.world.Zone
import com.sletmoe.kotile.utilities.Grid
import com.sletmoe.kotile.utilities.Vector2Int
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe

/**
 * Unit tests for the engine's built-in [Sense] contributors (ADR-0015, krogue-1my.2): their tags and
 * their reveal behaviour over line-of-sight, lighting, radius, and entity detection. The two-phase
 * reveal/suppress *algorithm* is covered by [StandardPerceptionTest]; this exercises the senses
 * themselves.
 */
class EngineSensesTest : DescribeSpec({

    val wall = Tile("wall", '#', Color.WHITE, Color.BLACK, isWalkable = false, blocksLineOfSight = true)

    /** An n×n zone, fully lit by default (so lighting never hides a cell unless a test darkens it). */
    fun litZone(size: Int = 8): Zone =
        Zone("z", Grid(size, size, BLANK_TILE)).also { it.lightMap.fill(LightValue.FULLBRIGHT) }

    fun world(zone: Zone): GameWorld = GameWorld(World(), mapOf(zone.zoneId to zone), zone.zoneId)

    val los = SymmetricShadowCaster()

    describe("SightSense") {
        it("tags itself visual + light-dependent") {
            SightSense(los).tags shouldBe setOf(PerceptionTags.VISUAL, PerceptionTags.LIGHT_DEPENDENT)
        }

        it("reveals lit, line-of-sight cells and the entities standing on them") {
            val zone = litZone()
            val gw = world(zone)
            val observer = gw.ecs.spawn(Position(4, 4), ZoneMember("z"), Sight())
            val other = gw.ecs.spawn(Position(5, 4), ZoneMember("z"), Health(1, 1))

            val c = SightSense(los).reveal(observer, observer.require<Sight>(), gw)

            c.cells shouldContain Vector2Int(4, 4)
            c.cells shouldContain Vector2Int(5, 4)
            c.entities shouldContainExactly setOf(other.id)
        }

        it("does not reveal an unlit cell even when it is in line-of-sight") {
            val zone = litZone()
            zone.lightMap[2, 4] = null // darken one in-sight cell
            val gw = world(zone)
            val observer = gw.ecs.spawn(Position(4, 4), ZoneMember("z"), Sight())

            val c = SightSense(los).reveal(observer, observer.require<Sight>(), gw)

            c.cells shouldNotContain Vector2Int(2, 4)
            c.cells shouldContain Vector2Int(3, 4) // its lit neighbour is still seen
        }

        it("caps revealed cells at the Sight radius") {
            val zone = litZone()
            val gw = world(zone)
            val observer = gw.ecs.spawn(Position(4, 4), ZoneMember("z"), Sight(radius = 1.0))

            val c = SightSense(los).reveal(observer, observer.require<Sight>(), gw)

            c.cells shouldContain Vector2Int(4, 3) // within radius 1
            c.cells shouldNotContain Vector2Int(4, 0) // beyond radius 1
        }

        it("does not reveal a cell behind a wall") {
            val zone = litZone()
            zone.tiles[4, 2] = wall
            val gw = world(zone)
            val observer = gw.ecs.spawn(Position(4, 4), ZoneMember("z"), Sight())

            SightSense(los).reveal(observer, observer.require<Sight>(), gw).cells shouldNotContain Vector2Int(4, 0)
        }
    }

    describe("DarkvisionSense") {
        it("tags itself visual (not light-dependent)") {
            DarkvisionSense(los).tags shouldBe setOf(PerceptionTags.VISUAL)
        }

        it("reveals in-sight cells regardless of lighting") {
            val zone = Zone("z", Grid(8, 8, BLANK_TILE)) // wholly unlit
            val gw = world(zone)
            val observer = gw.ecs.spawn(Position(4, 4), ZoneMember("z"), Darkvision())

            val c = DarkvisionSense(los).reveal(observer, observer.require<Darkvision>(), gw)

            c.cells shouldContain Vector2Int(4, 4)
            c.cells shouldContain Vector2Int(4, 1)
        }

        it("is still blocked by walls") {
            val zone = Zone("z", Grid(8, 8, BLANK_TILE))
            zone.tiles[4, 2] = wall
            val gw = world(zone)
            val observer = gw.ecs.spawn(Position(4, 4), ZoneMember("z"), Darkvision())

            DarkvisionSense(los)
                .reveal(observer, observer.require<Darkvision>(), gw)
                .cells shouldNotContain Vector2Int(4, 0)
        }
    }

    describe("TrueSightSense") {
        it("tags itself visual and pierces visual") {
            TrueSightSense(los).tags shouldBe setOf(PerceptionTags.VISUAL)
            TrueSightSense(los).pierces shouldBe setOf(PerceptionTags.VISUAL)
        }

        it("reveals in-sight cells regardless of lighting, like darkvision") {
            val zone = Zone("z", Grid(8, 8, BLANK_TILE)) // wholly unlit
            val gw = world(zone)
            val observer = gw.ecs.spawn(Position(4, 4), ZoneMember("z"), TrueSight())

            val c = TrueSightSense(los).reveal(observer, observer.require<TrueSight>(), gw)

            c.cells shouldContain Vector2Int(4, 4)
            c.cells shouldContain Vector2Int(4, 1)
        }
    }

    describe("TremorsenseSense") {
        it("tags itself vibration") {
            TremorsenseSense().tags shouldBe setOf(PerceptionTags.VIBRATION)
        }

        it("reveals living creatures within radius through walls, and no cells") {
            val zone = Zone("z", Grid(16, 16, BLANK_TILE))
            zone.tiles[4, 4] = wall // wall between observer and target does not matter
            val gw = world(zone)
            val observer = gw.ecs.spawn(Position(4, 5), ZoneMember("z"), Tremorsense(radius = 3.0))
            val near = gw.ecs.spawn(Position(4, 3), ZoneMember("z"), Health(1, 1))
            val far = gw.ecs.spawn(Position(4, 15), ZoneMember("z"), Health(1, 1))

            val c = TremorsenseSense().reveal(observer, observer.require<Tremorsense>(), gw)

            c.cells.shouldContainExactly()
            c.entities shouldContain near.id
            c.entities shouldNotContain far.id
        }

        it("does not reveal non-living entities (no Health)") {
            val zone = Zone("z", Grid(8, 8, BLANK_TILE))
            val gw = world(zone)
            val observer = gw.ecs.spawn(Position(4, 4), ZoneMember("z"), Tremorsense())
            val item = gw.ecs.spawn(Position(4, 5), ZoneMember("z")) // a thing, but not alive

            TremorsenseSense().reveal(observer, observer.require<Tremorsense>(), gw).entities shouldNotContain item.id
        }
    }

    describe("TelepathySense") {
        it("tags itself mental") {
            TelepathySense().tags shouldBe setOf(PerceptionTags.MENTAL)
        }

        it("reveals every living creature in the zone when radius is null") {
            val zone = Zone("z", Grid(40, 40, BLANK_TILE))
            val gw = world(zone)
            val observer = gw.ecs.spawn(Position(0, 0), ZoneMember("z"), Telepathy())
            val acrossTheMap = gw.ecs.spawn(Position(39, 39), ZoneMember("z"), Health(1, 1))

            TelepathySense().reveal(observer, observer.require<Telepathy>(), gw).entities shouldContain acrossTheMap.id
        }

        it("excludes creatures in another zone") {
            val zone = Zone("z", Grid(8, 8, BLANK_TILE))
            val gw = GameWorld(World(), mapOf("z" to zone, "other" to Zone("other", Grid(8, 8, BLANK_TILE))), "z")
            val observer = gw.ecs.spawn(Position(0, 0), ZoneMember("z"), Telepathy())
            val elsewhere = gw.ecs.spawn(Position(0, 0), ZoneMember("other"), Health(1, 1))

            TelepathySense().reveal(observer, observer.require<Telepathy>(), gw).entities shouldNotContain elsewhere.id
        }
    }

    describe("a sense") {
        it("contributes nothing for an observer with no Position") {
            val zone = litZone()
            val gw = world(zone)
            val observer = gw.ecs.spawn(ZoneMember("z"), Sight())

            SightSense(los).reveal(observer, observer.require<Sight>(), gw) shouldBe Contribution.EMPTY
        }
    }
})
