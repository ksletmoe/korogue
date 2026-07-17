package com.sletmoe.korogue.systems

import com.badlogic.gdx.graphics.Color
import com.sletmoe.korogue.components.AttackIntent
import com.sletmoe.korogue.components.Behavior
import com.sletmoe.korogue.components.MoveIntent
import com.sletmoe.korogue.components.Player
import com.sletmoe.korogue.components.Position
import com.sletmoe.korogue.components.RangedAttacker
import com.sletmoe.korogue.components.ZoneMember
import com.sletmoe.korogue.ecs.World
import com.sletmoe.korogue.events.RangedAttackFired
import com.sletmoe.korogue.registry.Registry
import com.sletmoe.korogue.world.BLANK_TILE
import com.sletmoe.korogue.world.GameWorld
import com.sletmoe.korogue.world.Tile
import com.sletmoe.korogue.world.Zone
import com.sletmoe.kotile.utilities.Grid
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

private val WALL = Tile("wall", '#', Color.GRAY, Color.BLACK, isWalkable = false, blocksLineOfSight = true)

class RangedAttackSystemTest : FunSpec({

    fun worldFor(zone: Zone): World {
        val gw = GameWorld(World(), mapOf(zone.zoneId to zone), zone.zoneId)
        return gw.ecs.addSystem(RangedAttackSystem(gw))
    }

    test("fires at a distant player in a clear line of sight") {
        val zone = Zone("z", Grid(10, 10, BLANK_TILE))
        val world = worldFor(zone)
        world.spawn(Player, Position(6, 2), ZoneMember("z"))
        val archer = world.spawn(Position(2, 2), ZoneMember("z"), RangedAttacker(range = 6)).id

        var fired: RangedAttackFired? = null
        world.events.subscribe<RangedAttackFired> { fired = it }
        world.tick()

        world.get(archer)!!.require<AttackIntent>()
        val event = fired!!
        event.from shouldBe Position(2, 2).point
        event.to shouldBe Position(6, 2).point
        event.path.last() shouldBe Position(6, 2).point
    }

    test("does not fire at an adjacent player -- that's melee's job") {
        val zone = Zone("z", Grid(10, 10, BLANK_TILE))
        val world = worldFor(zone)
        world.spawn(Player, Position(3, 2), ZoneMember("z"))
        val archer = world.spawn(Position(2, 2), ZoneMember("z"), RangedAttacker(range = 6)).id

        world.tick()

        world.get(archer)!!.get<AttackIntent>().shouldBeNull()
    }

    test("does not fire beyond its range") {
        val zone = Zone("z", Grid(10, 10, BLANK_TILE))
        val world = worldFor(zone)
        world.spawn(Player, Position(9, 2), ZoneMember("z"))
        val archer = world.spawn(Position(2, 2), ZoneMember("z"), RangedAttacker(range = 4)).id

        world.tick()

        world.get(archer)!!.get<AttackIntent>().shouldBeNull()
    }

    test("does not fire when a wall blocks the line of sight") {
        val tiles = Grid(10, 10, BLANK_TILE)
        tiles[4, 2] = WALL
        val zone = Zone("z", tiles)
        val world = worldFor(zone)
        world.spawn(Player, Position(6, 2), ZoneMember("z"))
        val archer = world.spawn(Position(2, 2), ZoneMember("z"), RangedAttacker(range = 6)).id

        world.tick()

        world.get(archer)!!.get<AttackIntent>().shouldBeNull()
    }

    test("does not fire across zones") {
        val zone = Zone("z", Grid(10, 10, BLANK_TILE))
        val world = worldFor(zone)
        world.spawn(Player, Position(6, 2), ZoneMember("other"))
        val archer = world.spawn(Position(2, 2), ZoneMember("z"), RangedAttacker(range = 6)).id

        world.tick()

        world.get(archer)!!.get<AttackIntent>().shouldBeNull()
    }

    test("BehaviorSystem does not also move an entity RangedAttackSystem just fired") {
        val zone = Zone("z", Grid(10, 10, BLANK_TILE))
        val gw = GameWorld(World(), mapOf(zone.zoneId to zone), zone.zoneId)
        val world =
            gw.ecs
                .addSystem(RangedAttackSystem(gw))
                .addSystem(
                    BehaviorSystem(gw, Registry.of("x" to BehaviorStrategy { _, _, _ -> MoveIntent(1, 0) })),
                )
        world.spawn(Player, Position(6, 2), ZoneMember("z"))
        val archer =
            world.spawn(
                Behavior("x"),
                Position(2, 2),
                ZoneMember("z"),
                RangedAttacker(range = 6),
            ).id

        world.tick()

        world.get(archer)!!.require<AttackIntent>()
        world.get(archer)!!.require<Position>() shouldBe Position(2, 2) // did not also move
    }
})
