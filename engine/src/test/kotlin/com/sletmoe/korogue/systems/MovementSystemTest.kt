package com.sletmoe.korogue.systems

import com.badlogic.gdx.graphics.Color
import com.sletmoe.korogue.components.AttackIntent
import com.sletmoe.korogue.components.MoveIntent
import com.sletmoe.korogue.components.Position
import com.sletmoe.korogue.components.ZoneMember
import com.sletmoe.korogue.ecs.World
import com.sletmoe.korogue.utilities.Grid
import com.sletmoe.korogue.world.BLANK_TILE
import com.sletmoe.korogue.world.Tile
import com.sletmoe.korogue.world.Zone
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

private val WALL = Tile("wall", '#', Color.GRAY, Color.BLACK, isWalkable = false, blocksLineOfSight = true)

class MovementSystemTest : FunSpec({

    fun worldFor(zone: Zone): World = World().addSystem(MovementSystem(mapOf(zone.zoneId to zone)))

    test("a MoveIntent onto walkable, empty terrain moves the entity and is consumed") {
        val zone = Zone("z", Grid(5, 5, BLANK_TILE))
        val world = worldFor(zone)
        val id = world.spawn(Position(2, 2), ZoneMember("z"), MoveIntent(1, 0)).id

        world.tick()

        world.get(id)!!.require<Position>() shouldBe Position(3, 2)
        world.get(id)!!.get<MoveIntent>().shouldBeNull()
    }

    test("a MoveIntent into a wall is consumed without moving and without attacking") {
        val tiles = Grid(5, 5, BLANK_TILE)
        tiles[3, 2] = WALL
        val world = worldFor(Zone("z", tiles))
        val id = world.spawn(Position(2, 2), ZoneMember("z"), MoveIntent(1, 0)).id

        world.tick()

        world.get(id)!!.require<Position>() shouldBe Position(2, 2)
        world.get(id)!!.get<MoveIntent>().shouldBeNull()
        world.get(id)!!.get<AttackIntent>().shouldBeNull()
    }

    test("a MoveIntent into an occupied cell emits an AttackIntent instead of moving") {
        val zone = Zone("z", Grid(5, 5, BLANK_TILE))
        val world = worldFor(zone)
        val target = world.spawn(Position(3, 2), ZoneMember("z")).id
        val attacker = world.spawn(Position(2, 2), ZoneMember("z"), MoveIntent(1, 0)).id

        world.tick()

        world.get(attacker)!!.require<Position>() shouldBe Position(2, 2)
        world.get(attacker)!!.get<MoveIntent>().shouldBeNull()
        world.get(attacker)!!.require<AttackIntent>().targetId shouldBe target
    }
})
