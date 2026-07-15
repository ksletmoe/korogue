package com.sletmoe.korogue.systems

import com.badlogic.gdx.graphics.Color
import com.sletmoe.korogue.components.AttackIntent
import com.sletmoe.korogue.components.BumpResponse
import com.sletmoe.korogue.components.Collision
import com.sletmoe.korogue.components.Facing
import com.sletmoe.korogue.components.Locomotion
import com.sletmoe.korogue.components.MoveIntent
import com.sletmoe.korogue.components.MovementTags
import com.sletmoe.korogue.components.Position
import com.sletmoe.korogue.components.ZoneMember
import com.sletmoe.korogue.ecs.World
import com.sletmoe.korogue.world.BLANK_TILE
import com.sletmoe.korogue.world.Tile
import com.sletmoe.korogue.world.Zone
import com.sletmoe.kotile.utilities.Grid
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

private val WALL = Tile("wall", '#', Color.GRAY, Color.BLACK, isWalkable = false, blocksLineOfSight = true)

// Terrain a walker cannot enter but a flyer/swimmer can: blocks only {walk} (krogue-xeb).
private val WATER =
    Tile(
        "water",
        '~',
        Color.BLUE,
        Color.BLACK,
        isWalkable = false,
        blocksLineOfSight = false,
        blocks = setOf(MovementTags.WALK),
    )

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

    test("a MoveIntent onto a non-blocking (PASSABLE) occupant steps onto its cell, no attack") {
        val zone = Zone("z", Grid(5, 5, BLANK_TILE))
        val world = worldFor(zone)
        world.spawn(Position(3, 2), ZoneMember("z"), Collision.PASSABLE)
        val mover = world.spawn(Position(2, 2), ZoneMember("z"), MoveIntent(1, 0)).id

        world.tick()

        world.get(mover)!!.require<Position>() shouldBe Position(3, 2)
        world.get(mover)!!.get<AttackIntent>().shouldBeNull()
    }

    test("a solid non-attackable occupant (bump = BLOCK) stops the mover without attacking") {
        val zone = Zone("z", Grid(5, 5, BLANK_TILE))
        val world = worldFor(zone)
        world.spawn(Position(3, 2), ZoneMember("z"), Collision(bump = BumpResponse.BLOCK))
        val mover = world.spawn(Position(2, 2), ZoneMember("z"), MoveIntent(1, 0)).id

        world.tick()

        world.get(mover)!!.require<Position>() shouldBe Position(2, 2)
        world.get(mover)!!.get<MoveIntent>().shouldBeNull()
        world.get(mover)!!.get<AttackIntent>().shouldBeNull()
    }

    test("a flyer passes a walk-only blocker and lands on its cell, while a walker is blocked") {
        val zone = Zone("z", Grid(5, 5, BLANK_TILE))
        val walkOnly = Collision(blocks = setOf(MovementTags.WALK), bump = BumpResponse.BLOCK)

        // A flyer: WALK is blocked but FLY is not, so it enters the cell.
        val flyWorld = worldFor(zone)
        flyWorld.spawn(Position(3, 2), ZoneMember("z"), walkOnly)
        val flyer =
            flyWorld.spawn(
                Position(2, 2),
                ZoneMember("z"),
                Locomotion(setOf(MovementTags.FLY)),
                MoveIntent(1, 0),
            ).id
        flyWorld.tick()
        flyWorld.get(flyer)!!.require<Position>() shouldBe Position(3, 2)

        // A default walker: WALK is blocked (and bump = BLOCK), so it stays put.
        val walkWorld = worldFor(zone)
        walkWorld.spawn(Position(3, 2), ZoneMember("z"), walkOnly)
        val walker = walkWorld.spawn(Position(2, 2), ZoneMember("z"), MoveIntent(1, 0)).id
        walkWorld.tick()
        walkWorld.get(walker)!!.require<Position>() shouldBe Position(2, 2)
    }

    test("a flyer crosses water terrain that blocks {walk}, while a walker cannot enter it") {
        val tiles = Grid(5, 5, BLANK_TILE)
        tiles[3, 2] = WATER

        // A flyer: water blocks {walk} but not {fly}, so the terrain is passable.
        val flyWorld = worldFor(Zone("z", tiles))
        val flyer =
            flyWorld.spawn(
                Position(2, 2),
                ZoneMember("z"),
                Locomotion(setOf(MovementTags.FLY)),
                MoveIntent(1, 0),
            ).id
        flyWorld.tick()
        flyWorld.get(flyer)!!.require<Position>() shouldBe Position(3, 2)

        // A default walker: water blocks {walk}, so it is a terrain no-op (no attack).
        val walkWorld = worldFor(Zone("z", tiles))
        val walker = walkWorld.spawn(Position(2, 2), ZoneMember("z"), MoveIntent(1, 0)).id
        walkWorld.tick()
        walkWorld.get(walker)!!.require<Position>() shouldBe Position(2, 2)
        walkWorld.get(walker)!!.get<AttackIntent>().shouldBeNull()
    }

    test("a horizontal MoveIntent sets Facing to match its direction (krogue-csc)") {
        val zone = Zone("z", Grid(5, 5, BLANK_TILE))
        val world = worldFor(zone)
        val right = world.spawn(Position(2, 2), ZoneMember("z"), MoveIntent(1, 0)).id
        val left = world.spawn(Position(2, 3), ZoneMember("z"), MoveIntent(-1, 0)).id

        world.tick()

        world.get(right)!!.require<Facing>() shouldBe Facing.RIGHT
        world.get(left)!!.require<Facing>() shouldBe Facing.LEFT
    }

    test("Facing persists across a tick where the entity has no MoveIntent (krogue-csc)") {
        val zone = Zone("z", Grid(5, 5, BLANK_TILE))
        val world = worldFor(zone)
        val id = world.spawn(Position(2, 2), ZoneMember("z"), MoveIntent(1, 0)).id
        world.tick()
        world.get(id)!!.require<Facing>() shouldBe Facing.RIGHT

        world.tick() // no MoveIntent this tick

        world.get(id)!!.require<Facing>() shouldBe Facing.RIGHT
    }

    test("a purely vertical MoveIntent leaves Facing unchanged (krogue-csc)") {
        val zone = Zone("z", Grid(5, 5, BLANK_TILE))
        val world = worldFor(zone)
        val id = world.spawn(Position(2, 2), ZoneMember("z"), MoveIntent(-1, 0)).id
        world.tick()
        world.get(id)!!.require<Facing>() shouldBe Facing.LEFT

        world.set(id, MoveIntent(0, 1))
        world.tick()

        world.get(id)!!.require<Facing>() shouldBe Facing.LEFT
    }

    test("a blocked or attacking MoveIntent still sets Facing from its attempted direction") {
        val zone = Zone("z", Grid(5, 5, BLANK_TILE))
        val world = worldFor(zone)
        world.spawn(Position(3, 2), ZoneMember("z"), Collision(bump = BumpResponse.BLOCK))
        val blocked = world.spawn(Position(2, 2), ZoneMember("z"), MoveIntent(1, 0)).id

        val target = world.spawn(Position(3, 3), ZoneMember("z")).id
        val attacker = world.spawn(Position(2, 3), ZoneMember("z"), MoveIntent(1, 0)).id

        world.tick()

        world.get(blocked)!!.require<Facing>() shouldBe Facing.RIGHT
        world.get(attacker)!!.require<Facing>() shouldBe Facing.RIGHT
        world.get(attacker)!!.require<AttackIntent>().targetId shouldBe target
    }
})
