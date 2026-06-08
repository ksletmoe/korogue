package com.sletmoe.krogue.save

import com.badlogic.gdx.graphics.Color
import com.sletmoe.krogue.components.Behavior
import com.sletmoe.krogue.components.Health
import com.sletmoe.krogue.components.MoveIntent
import com.sletmoe.krogue.components.Named
import com.sletmoe.krogue.components.Player
import com.sletmoe.krogue.components.Position
import com.sletmoe.krogue.components.ZoneMember
import com.sletmoe.krogue.random.GameRandom
import com.sletmoe.krogue.registry.GameModule
import com.sletmoe.krogue.systems.HuntPlayerStrategy
import com.sletmoe.krogue.world.GameWorld
import com.sletmoe.krogue.world.Tile
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.ExperimentalSerializationApi
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

    test("rejects an unsupported format version (fail-fast)") {
        val bogus = Cbor.encodeToByteArray(SaveEnvelope(formatVersion = 999, payload = byteArrayOf()))
        shouldThrow<IllegalStateException> { codec.load(bogus) }
    }
})
