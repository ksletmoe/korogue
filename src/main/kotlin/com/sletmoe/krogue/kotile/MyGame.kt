package com.sletmoe.krogue.kotile

import com.badlogic.gdx.Input
import com.badlogic.gdx.graphics.Color
import com.sletmoe.kotile.display.ascii.AsciiTileWindow
import com.sletmoe.krogue.algorithms.lighting.DiminishingLightValueCalculator
import com.sletmoe.krogue.algorithms.los.OmnicientLineOfSightCalculator
import com.sletmoe.krogue.algorithms.los.SymmetricShadowCaster
import com.sletmoe.krogue.algorithms.zonegen.randomWalkCave
import com.sletmoe.krogue.world.Creature
import com.sletmoe.krogue.world.LightSource
import com.sletmoe.krogue.world.Tile
import com.sletmoe.krogue.world.World
import com.sletmoe.krogue.world.ZonalPosition
import com.sletmoe.krogue.world.Zone
import java.awt.Point
import kotlin.random.Random

/**
 * Kotile-backed roguelike demo. Renders the current zone through [KotileZoneRenderer] with:
 * - FOV via [SymmetricShadowCaster] (toggle to omniscient with SPACE)
 * - Lighting via [DiminishingLightValueCalculator] on the player's lantern
 * - Previously-viewed tile dimming
 * - Arrow key player movement
 */
class MyGame(
    private val random: Random = Random.Default,
) : Game() {
    private val startPoint = Point(10, 10)

    private val symmetricShadowCaster = SymmetricShadowCaster()
    private val omnipresentLos = OmnicientLineOfSightCalculator()

    private val world: World = buildWorld()

    private val player: Creature =
        Creature(
            ZonalPosition(world.currentZone, startPoint.x, startPoint.y),
            "You",
            '@',
            Color.YELLOW,
        ).also { p ->
            p.lightSource =
                LightSource(
                    p.position.copy(),
                    "Lantern",
                    Color(1f, 1f, 150f / 255f, 1f),
                    15.0,
                    DiminishingLightValueCalculator(),
                )
            world.currentZone.addCreature(p)
            // Initial light map calculation.
            world.currentZone.recalculateLightMap()
        }

    private lateinit var renderer: KotileZoneRenderer

    // -------------------------------------------------------------------------
    // Game overrides
    // -------------------------------------------------------------------------

    override fun buildWindow(): AsciiTileWindow =
        AsciiTileWindow.create {
            widthInTiles = WINDOW_W
            heightInTiles = WINDOW_H
            fitToWindow = true
        }

    override fun create() {
        super.create()
        renderer =
            KotileZoneRenderer(
                zone = world.currentZone,
                window = window,
                lineOfSightCalculator = symmetricShadowCaster,
                maximumVisibilityDistance = 30.0,
            )
    }

    override fun onTick() {
        // Remove dead creatures.
        world.currentZone.creatures
            .filter { it.dead && it !== player }
            .forEach { world.currentZone.removeCreature(it) }

        // Update non-player creatures.
        world.currentZone.creatures
            .filter { it !== player }
            .forEach { it.update(world.currentZone) }
    }

    override fun drawFrame(elapsedMs: Long) {
        renderer.render(
            focusPoint = player.position.point,
            player = player,
            elapsedMs = elapsedMs,
        )
    }

    override fun onKeyDown(keycode: Int) {
        when (keycode) {
            Input.Keys.LEFT -> player.moveInZone(-1, 0)
            Input.Keys.RIGHT -> player.moveInZone(1, 0)
            Input.Keys.UP -> player.moveInZone(0, -1)
            Input.Keys.DOWN -> player.moveInZone(0, 1)
            Input.Keys.SPACE -> toggleLos()
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private fun toggleLos() {
        renderer.losCalculator =
            if (renderer.losCalculator === symmetricShadowCaster) {
                omnipresentLos
            } else {
                symmetricShadowCaster
            }
    }

    private fun buildWorld(): World {
        val world =
            World.create {
                zone("Level 1", 200, 200, isCurrentZone = true, random = random) {
                    fill(WALL_TILE)
                    addFeature(randomWalkCave(startPoint.x, startPoint.y, 6000, GROUND_TILE))
                }
            }
        populateZone(world.currentZone, 10)
        return world
    }

    private fun populateZone(
        zone: Zone,
        numCreatures: Int,
    ) {
        repeat(numCreatures) {
            var rx: Int
            var ry: Int
            do {
                rx = random.nextInt(zone.width)
                ry = random.nextInt(zone.height)
            } while (!zone.tiles[rx, ry].isWalkable)

            val creature =
                if (random.nextBoolean()) {
                    Creature(ZonalPosition(zone, rx, ry), "zombie", 'z', Color.GREEN, "aggressive")
                } else {
                    Creature(ZonalPosition(zone, rx, ry), "sheep", 's', Color.WHITE, "docile")
                }
            zone.addCreature(creature)
        }
    }

    companion object {
        private const val WINDOW_W = 80
        private const val WINDOW_H = 40

        private val WALL_TILE
            get() =
                Tile(
                    "stone wall",
                    '#',
                    color = Color.GRAY,
                    backgroundColor = Color.BLACK,
                    isWalkable = false,
                    blocksLineOfSight = true,
                )

        private val GROUND_TILE =
            Tile(
                "stone floor",
                '.',
                color = Color.LIGHT_GRAY,
                backgroundColor = Color.BLACK,
                isWalkable = true,
                blocksLineOfSight = false,
            )
    }
}
