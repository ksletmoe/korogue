package com.sletmoe.korogue.save

import com.badlogic.gdx.graphics.Color
import com.sletmoe.korogue.components.Behavior
import com.sletmoe.korogue.components.Health
import com.sletmoe.korogue.components.MoveIntent
import com.sletmoe.korogue.components.Named
import com.sletmoe.korogue.components.Player
import com.sletmoe.korogue.components.Position
import com.sletmoe.korogue.components.ZoneMember
import com.sletmoe.korogue.random.GameRandom
import com.sletmoe.korogue.registry.GameModule
import com.sletmoe.korogue.schedule.Scheduler
import com.sletmoe.korogue.schedule.SchedulerState
import com.sletmoe.korogue.systems.HuntPlayerStrategy
import com.sletmoe.korogue.utilities.Grid
import com.sletmoe.korogue.world.GameWorld
import com.sletmoe.korogue.world.Tile
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.encodeToByteArray

@OptIn(ExperimentalSerializationApi::class)
class SaveCodecTest : FunSpec({

    val codec = SaveCodec(GameModule.engineDefaults().build().components)
    val floor = Tile("floor", '.', Color.GRAY, Color.BLACK, isWalkable = true, blocksLineOfSight = false)

    test("round-trips entities, terrain, currentZoneId, turn, RNG, and the id counter") {
        val rng = GameRandom.fromSeed(42)
        val world =
            GameWorld.create {
                zone("a", 4, 4, isCurrentZone = true) { fill(floor) }
                zone("b", 3, 3) { fill(floor) }
            }
        val player = world.ecs.spawn(Position(1, 1), ZoneMember("a"), Health(80, 100), Player).id
        val zombie =
            world.ecs.spawn(
                Position(2, 2),
                ZoneMember("a"),
                Named("zombie"),
                Behavior(HuntPlayerStrategy.ID),
            ).id
        // A transient MoveIntent is not registered, so it should be dropped on save.
        val mover = world.ecs.spawn(Position(0, 0), ZoneMember("a"), MoveIntent(1, 0)).id
        repeat(3) { world.ecs.tick() }
        repeat(5) { rng.stream("gameplay").nextInt() }

        val loaded = codec.load(codec.save(world, rng))

        loaded.world.currentZoneId shouldBe "a"
        loaded.world.ecs.currentTurn shouldBe 3
        loaded.world.ecs.get(player)!!.require<Health>() shouldBe Health(80, 100)
        loaded.world.ecs.get(player)!!.has<Player>() shouldBe true
        loaded.world.ecs.get(zombie)!!.require<Behavior>().strategyId shouldBe HuntPlayerStrategy.ID
        loaded.world.ecs.get(mover)!!.has<MoveIntent>() shouldBe false // transient dropped
        loaded.world.ecs.get(mover)!!.require<Position>() shouldBe Position(0, 0)

        // Terrain round-trips.
        loaded.world.zones.getValue("a").tiles[1, 1].glyph shouldBe '.'
        loaded.world.zones.getValue("b").width shouldBe 3

        // RNG resumes exactly: the loaded stream continues where the original left off at save.
        loaded.random.masterSeed shouldBe 42
        loaded.random.stream("gameplay").nextInt() shouldBe rng.stream("gameplay").nextInt()

        // The id counter is preserved (loaded world won't reissue an existing id).
        loaded.world.ecs.nextEntityId shouldBe world.ecs.nextEntityId
    }

    test("round-trips per-zone fog-of-war memory") {
        val world =
            GameWorld.create {
                zone("a", 4, 4, isCurrentZone = true) { fill(floor) }
                zone("b", 3, 2) { fill(floor) }
            }
        val fogA =
            Grid(4, 4, false).apply {
                this[0, 0] = true
                this[3, 3] = true
                this[1, 2] = true
            }
        val fogB = Grid(3, 2, false).apply { this[2, 1] = true }

        val loaded = codec.load(codec.save(world, GameRandom.fromSeed(1), mapOf("a" to fogA, "b" to fogB)))

        loaded.fog.keys shouldBe setOf("a", "b")
        val a = loaded.fog.getValue("a")
        a.width shouldBe 4
        a.height shouldBe 4
        a[0, 0] shouldBe true
        a[3, 3] shouldBe true
        a[1, 2] shouldBe true
        a[2, 2] shouldBe false // unexplored stays unexplored
        loaded.fog.getValue("b")[2, 1] shouldBe true
    }

    test("round-trips terrain with mixed tiles, long runs, and a decorated one-off, preserving every Tile field") {
        val wall = Tile("stone wall", '#', Color.GRAY, Color.BLACK, isWalkable = false, blocksLineOfSight = true)
        val altar =
            Tile("altar", '_', Color.GOLD, Color.MAROON, isWalkable = true, blocksLineOfSight = false, description = "a stone altar")
        fun expectedAt(
            x: Int,
            y: Int,
        ): Tile =
            when {
                x == 4 && y == 3 -> altar // a one-off tile breaking a run mid-stream
                y == 1 && x in 1..8 -> floor // a mixed run inside an otherwise all-wall row
                y == 3 && x in 1..8 -> floor
                else -> wall // long identical runs: whole rows of wall
            }

        val world =
            GameWorld.create {
                zone("cave", 10, 6, isCurrentZone = true) {
                    fill(wall)
                    for (x in 1..8) setTile(x, 1, floor)
                    for (x in 1..8) setTile(x, 3, floor)
                    setTile(4, 3, altar)
                }
            }

        val loaded = codec.load(codec.save(world, GameRandom.fromSeed(7)))
        val tiles = loaded.world.zones.getValue("cave").tiles

        for (y in 0 until 6) {
            for (x in 0 until 10) {
                val expected = expectedAt(x, y)
                val actual = tiles[x, y]
                actual.name shouldBe expected.name
                actual.glyph shouldBe expected.glyph
                Color.rgba8888(actual.color) shouldBe Color.rgba8888(expected.color)
                Color.rgba8888(actual.backgroundColor) shouldBe Color.rgba8888(expected.backgroundColor)
                actual.isWalkable shouldBe expected.isWalkable
                actual.blocksLineOfSight shouldBe expected.blocksLineOfSight
                actual.description shouldBe expected.description
            }
        }
    }

    test("compacts a realistic zone's terrain to a small fraction of the naive per-cell size") {
        val wall = Tile("stone wall", '#', Color.GRAY, Color.BLACK, isWalkable = false, blocksLineOfSight = true)
        val width = 200
        val height = 200
        // A carved-out rectangular room, like randomWalkCave leaves behind: contiguous floor
        // runs rather than scattered single cells, so RLE gets exercised alongside the palette.
        fun tileAt(
            x: Int,
            y: Int,
        ): Tile = if (x in 20 until 180 && y in 20 until 180) floor else wall
        val world =
            GameWorld.create {
                zone("big", width, height, isCurrentZone = true) {
                    fill(wall)
                    for (y in 20 until 180) for (x in 20 until 180) setTile(x, y, floor)
                }
            }

        val savedBytes = codec.save(world, GameRandom.fromSeed(1))

        // Baseline: what the pre-krogue-yox codec wrote for this zone — one full Tile (name,
        // two colors, two booleans) per cell, no interning/RLE. Reproduced here with the same
        // CBOR settings so the comparison is apples-to-apples; this is the exact shape the bead
        // complains about (~168 bytes/tile * 40k cells/zone in the real 200x200 demo zones).
        val flatTiles = (0 until height).flatMap { y -> (0 until width).map { x -> tileAt(x, y) } }
        val naiveZoneBytes = Cbor.encodeToByteArray(ListSerializer(Tile.serializer()), flatTiles)

        // Measured on this 200x200 zone (2 palette entries, a few hundred RLE runs): naive
        // terrain alone is ~3.3 MB (~83 bytes/tile); the whole palette+RLE save (terrain +
        // RNG/entities/schedule envelope) is ~15 KB — a ~215x reduction. Threshold below is a
        // conservative 50x floor, well under the measured ratio.
        savedBytes.size shouldBeLessThan naiveZoneBytes.size / 50
    }

    test("a save without fog loads with empty fog (additive default, no version bump)") {
        val world = GameWorld.create { zone("a", 2, 2, isCurrentZone = true) { fill(floor) } }

        val loaded = codec.load(codec.save(world, GameRandom.fromSeed(1)))

        loaded.fog shouldBe emptyMap()
    }

    test("round-trips the daemon/fuse schedule so timers resume after load") {
        val world = GameWorld.create { zone("a", 2, 2, isCurrentZone = true) { fill(floor) } }
        val scheduler =
            Scheduler().apply {
                fuse("regen", afterTurns = 5)
                daemon("hunger", everyTurns = 10)
            }

        val loaded = codec.load(codec.save(world, GameRandom.fromSeed(1), schedule = scheduler.snapshot()))

        loaded.schedule shouldBe scheduler.snapshot()
        // Restore into a fresh scheduler and confirm the timers are live.
        val resumed = Scheduler().apply { restore(loaded.schedule) }
        resumed.size shouldBe 2
    }

    test("a save without a schedule loads with an empty one (additive default)") {
        val world = GameWorld.create { zone("a", 2, 2, isCurrentZone = true) { fill(floor) } }

        val loaded = codec.load(codec.save(world, GameRandom.fromSeed(1)))

        loaded.schedule shouldBe SchedulerState()
    }

    test("rejects an unsupported format version (fail-fast)") {
        val bogus = Cbor.encodeToByteArray(SaveEnvelope(formatVersion = 999, payload = byteArrayOf()))
        shouldThrow<IllegalStateException> { codec.load(bogus) }
    }
})
