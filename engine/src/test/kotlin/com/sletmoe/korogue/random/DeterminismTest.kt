package com.sletmoe.korogue.random

import com.badlogic.gdx.graphics.Color
import com.sletmoe.korogue.algorithms.zonegen.randomWalkCave
import com.sletmoe.korogue.components.Behavior
import com.sletmoe.korogue.components.Position
import com.sletmoe.korogue.components.ZoneMember
import com.sletmoe.korogue.registry.GameModule
import com.sletmoe.korogue.save.SaveCodec
import com.sletmoe.korogue.systems.BehaviorSystem
import com.sletmoe.korogue.systems.MovementSystem
import com.sletmoe.korogue.systems.WanderStrategy
import com.sletmoe.korogue.utilities.Grid
import com.sletmoe.korogue.world.GameWorld
import com.sletmoe.korogue.world.Tile
import com.sletmoe.korogue.world.Zone
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.random.Random

/**
 * The end-to-end proof of ADR-0009's headline guarantee — "same master seed ⇒ same world + loot",
 * and a save that resumes exactly — now that the world owns its RNG (ADR-0025, krogue-33i).
 *
 * The point of these tests is as much *what they don't do* as what they do: no test here threads an
 * RNG into [GameWorld.Builder.zone] or `World.tick`, because the API no longer lets a caller supply
 * one there. Reproducibility falls out of construction. Before krogue-33i these same calls would
 * have silently drawn from `Random.Default` and every assertion below would be a coin flip.
 */
class DeterminismTest : FunSpec({

    val module = GameModule.engineDefaults().build()
    val codec = SaveCodec(module.components)
    val floor = Tile("floor", '.', Color.GRAY, Color.BLACK, isWalkable = true, blocksLineOfSight = false)
    val wall = Tile("wall", '#', Color.DARK_GRAY, Color.BLACK, isWalkable = false, blocksLineOfSight = true)

    // Systems are code, not save state, so a loaded world is re-wired exactly as a new one is —
    // the same thing a consuming game does after SaveCodec.load (see the demo's registerSystems).
    fun wire(world: GameWorld) {
        world.ecs
            .addSystem(BehaviorSystem(module.strategies::resolve))
            .addSystem(MovementSystem(world.zones))
    }

    /**
     * A playable world from [seed] alone: carved terrain plus wanderers, both drawn from the
     * world's own worldgen stream. Note the absence of any `random =` argument.
     */
    fun newGame(seed: Long): GameWorld {
        val world =
            GameWorld.create(GameRandom.fromSeed(seed)) {
                zone("cave", 30, 30, isCurrentZone = true) {
                    fill(wall)
                    addFeature(randomWalkCave(15, 15, length = 400, groundTile = floor))
                }
            }
        val rng = world.random.stream(GameRandom.WORLDGEN)
        val zone = world.currentZone
        repeat(8) {
            var x: Int
            var y: Int
            do {
                x = rng.nextInt(zone.width)
                y = rng.nextInt(zone.height)
            } while (!zone.tiles[x, y].isWalkable || world.entityAt(zone.zoneId, x, y) != null)
            world.ecs.spawn(Position(x, y), ZoneMember(zone.zoneId), Behavior(WanderStrategy.ID))
        }
        wire(world)
        return world
    }

    /** Every wanderer's id and cell — the observable outcome of N ticks of RNG-driven AI. */
    fun positions(world: GameWorld): String =
        world.ecs
            .entities()
            .map { e -> "${e.id.value}@${e.require<Position>().x},${e.require<Position>().y}" }
            .joinToString("|")

    /** A carved cave, as glyphs — the observable outcome of the worldgen draws. */
    fun glyphs(tiles: Grid<Tile>): String =
        buildString {
            for (y in 0 until tiles.height) for (x in 0 until tiles.width) append(tiles[x, y].glyph)
        }

    fun terrain(world: GameWorld): String = glyphs(world.currentZone.tiles)

    /** Carve a cave off [rng] the way a zone generator does — for comparing stream positions. */
    fun carve(rng: Random): String =
        glyphs(
            Zone
                .create("probe", 30, 30, rng) {
                    fill(wall)
                    addFeature(randomWalkCave(15, 15, length = 400, groundTile = floor))
                }.tiles,
        )

    test("the same master seed replays the same world and the same play, with no RNG passed by the caller") {
        val a = newGame(2026)
        val b = newGame(2026)

        terrain(a) shouldBe terrain(b)
        positions(a) shouldBe positions(b) // same cave ⇒ same spawn cells

        repeat(20) { a.ecs.tick() }
        repeat(20) { b.ecs.tick() }

        positions(a) shouldBe positions(b)
    }

    test("a different master seed diverges") {
        // Guards the tests above against passing vacuously: if worldgen or the AI had stopped
        // drawing randomness at all, "identical" would be trivially true. It must be earned.
        terrain(newGame(1)) shouldNotBe terrain(newGame(2))

        val a = newGame(1).also { w -> repeat(20) { w.ecs.tick() } }
        val b = newGame(2).also { w -> repeat(20) { w.ecs.tick() } }
        positions(a) shouldNotBe positions(b)
    }

    test("a save resumes mid-stream: 10 ticks, save, load, 10 more == 20 uninterrupted") {
        val straight = newGame(7)
        val atSpawn = positions(straight)
        repeat(20) { straight.ecs.tick() }
        // Ticking must actually draw and move, or "resumed == straight" below proves nothing.
        positions(straight) shouldNotBe atSpawn

        val interrupted = newGame(7)
        repeat(10) { interrupted.ecs.tick() }
        val resumed = codec.load(codec.save(interrupted)).world
        wire(resumed)
        repeat(10) { resumed.ecs.tick() }

        // Exact resume, not merely "a valid game": the gameplay stream picks up at the draw it was
        // on, so the wanderers walk the same path they would have without the save/load.
        positions(resumed) shouldBe positions(straight)
        resumed.ecs.currentTurn shouldBe straight.ecs.currentTurn
    }

    test("gameplay never shifts world gen: a played-out world generates the same next level") {
        // ADR-0009's promise that has to survive the game evolving — "same seed ⇒ same world"
        // *however* combat changes. It holds only because the engine wires tick() to the GAMEPLAY
        // stream and generation to WORLDGEN. Were tick() drawing from the world-gen stream (or
        // were they one stream), 50 turns of AI and combat draws would move the next cave.
        fun nextCaveAfter(ticks: Int): String {
            val world = newGame(5)
            repeat(ticks) { world.ecs.tick() }
            return carve(world.random.stream(GameRandom.WORLDGEN))
        }

        nextCaveAfter(50) shouldBe nextCaveAfter(0)
    }

    test("zone() draws the world's worldgen stream rather than forking a private one") {
        // Rogue's LevelFactory leans on exactly this: it runs its own generator off
        // world.random.stream(WORLDGEN) first and lets zone() carry on from where that left off.
        // If zone() forked a private stream, advancing WORLDGEN beforehand could not move the
        // terrain it produces — so this asserts the shared position, not just a shared seed.
        fun caveAfterPreDraws(preDraws: Int): String {
            val random = GameRandom.fromSeed(11)
            repeat(preDraws) { random.stream(GameRandom.WORLDGEN).nextInt() }
            val world =
                GameWorld.create(random) {
                    zone("cave", 30, 30, isCurrentZone = true) {
                        fill(wall)
                        addFeature(randomWalkCave(15, 15, length = 400, groundTile = floor))
                    }
                }
            return terrain(world)
        }

        caveAfterPreDraws(1) shouldNotBe caveAfterPreDraws(0)
    }

    test("a loaded world carries its RNG, so the codec cannot be handed a mismatched one") {
        val world = newGame(99)
        repeat(5) { world.ecs.tick() }

        val loaded = codec.load(codec.save(world)).world

        loaded.random.masterSeed shouldBe 99
        // The loaded gameplay stream sits exactly where the original's does.
        loaded.random.stream(GameRandom.GAMEPLAY).nextLong() shouldBe
            world.random.stream(GameRandom.GAMEPLAY).nextLong()
    }
})
