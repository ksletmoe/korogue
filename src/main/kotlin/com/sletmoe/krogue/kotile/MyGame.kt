package com.sletmoe.krogue.kotile

import com.badlogic.gdx.Input
import com.badlogic.gdx.graphics.Color
import com.sletmoe.kotile.display.ascii.AsciiTileWindow
import com.sletmoe.kotile.utilities.Vector2Int
import com.sletmoe.krogue.algorithms.color.toNormalizedRgb
import com.sletmoe.krogue.algorithms.lighting.LightCalculators
import com.sletmoe.krogue.algorithms.los.LineOfSightCalculator
import com.sletmoe.krogue.algorithms.los.OmnicientLineOfSightCalculator
import com.sletmoe.krogue.algorithms.los.SymmetricShadowCaster
import com.sletmoe.krogue.algorithms.zonegen.randomWalkCave
import com.sletmoe.krogue.components.Behavior
import com.sletmoe.krogue.components.Health
import com.sletmoe.krogue.components.LightEmitter
import com.sletmoe.krogue.components.MoveIntent
import com.sletmoe.krogue.components.Named
import com.sletmoe.krogue.components.Player
import com.sletmoe.krogue.components.Portal
import com.sletmoe.krogue.components.Position
import com.sletmoe.krogue.components.RenderLayer
import com.sletmoe.krogue.components.Renderable
import com.sletmoe.krogue.components.ZoneMember
import com.sletmoe.krogue.ecs.EntityId
import com.sletmoe.krogue.systems.BehaviorStrategies
import com.sletmoe.krogue.systems.BehaviorSystem
import com.sletmoe.krogue.systems.CombatSystem
import com.sletmoe.krogue.systems.LightingSystem
import com.sletmoe.krogue.systems.MovementSystem
import com.sletmoe.krogue.systems.PortalSystem
import com.sletmoe.krogue.world.GameWorld
import com.sletmoe.krogue.world.Tile
import com.sletmoe.krogue.world.Zone
import kotlin.random.Random

/**
 * Kotile-backed roguelike demo. Renders the current zone through [KotileZoneRenderer] with:
 * - FOV via [SymmetricShadowCaster] (toggle to omniscient with SPACE)
 * - Lighting via [DiminishingLightValueCalculator] on the player's lantern
 * - Previously-viewed tile dimming
 * - Arrow key player movement
 *
 * Occupants are ECS entities in [GameWorld.ecs] (ADR-0007), driven by systems each tick:
 * [BehaviorSystem] (AI) and player input emit [MoveIntent]s; [MovementSystem] resolves
 * them; [PortalSystem] applies zone transitions; [CombatSystem] applies attacks and clears
 * the dead; [LightingSystem] renders the lanterns.
 *
 * The demo has two zones linked by stairs (`>`/`<`). Only the player's zone is simulated
 * and rendered (ADR-0008); the other freezes in place and is restored on return.
 */
class MyGame(
    private val random: Random = Random.Default,
) : Game() {
    private val startPoint = Vector2Int(10, 10)

    private val symmetricShadowCaster = SymmetricShadowCaster()
    private val omnipresentLos = OmnicientLineOfSightCalculator()

    private val world: GameWorld = buildWorld()
    private val playerId: EntityId = spawnPlayer()

    private lateinit var renderer: KotileZoneRenderer
    private lateinit var renderedZoneId: String

    init {
        spawnPortals()
        world.zones.values.forEach { populateZone(it, numCreatures = 10) }
        // Systems run in registration order each tick: decide AI moves, resolve movement,
        // apply zone transitions, resolve combat, then recompute lighting.
        world.ecs
            .addSystem(BehaviorSystem(activeZones = world::simulatedZones))
            .addSystem(MovementSystem(world.zones))
            .addSystem(PortalSystem(world))
            .addSystem(CombatSystem())
            .addSystem(LightingSystem(world.zones, activeZones = world::simulatedZones))
    }

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
        renderer = buildRenderer(symmetricShadowCaster)
        renderedZoneId = world.currentZoneId
    }

    override fun onTick() {
        // Advance the world one tick: BehaviorSystem -> MovementSystem -> PortalSystem ->
        // CombatSystem -> LightingSystem. One tick per frame is interim; a turn-on-input
        // loop can come later.
        world.ecs.tick()
    }

    override fun drawFrame(elapsedMs: Long) {
        if (world.currentZoneId != renderedZoneId) {
            // The player changed zones (PortalSystem): rebuild the renderer for the new
            // active zone, preserving the LOS mode. (Fog-of-war does not yet persist per zone.)
            renderer = buildRenderer(renderer.losCalculator)
            renderedZoneId = world.currentZoneId
        }
        renderer.render(focusPoint = playerPosition().point, elapsedMs = elapsedMs)
    }

    private fun buildRenderer(los: LineOfSightCalculator): KotileZoneRenderer =
        KotileZoneRenderer(
            zone = world.currentZone,
            world = world.ecs,
            window = window,
            lineOfSightCalculator = los,
            maximumVisibilityDistance = 30.0,
        )

    override fun onKeyDown(keycode: Int) {
        when (keycode) {
            Input.Keys.LEFT -> intendMove(-1, 0)
            Input.Keys.RIGHT -> intendMove(1, 0)
            Input.Keys.UP -> intendMove(0, -1)
            Input.Keys.DOWN -> intendMove(0, 1)
            Input.Keys.SPACE -> toggleLos()
        }
    }

    /** Player input is data too: attach a [MoveIntent] that MovementSystem resolves next tick. */
    private fun intendMove(
        dx: Int,
        dy: Int,
    ) {
        world.ecs.set(playerId, MoveIntent(dx, dy))
    }

    // -------------------------------------------------------------------------
    // Setup
    // -------------------------------------------------------------------------

    private fun buildWorld(): GameWorld =
        GameWorld.create {
            zone(ZONE_1, 200, 200, isCurrentZone = true, random = random) {
                fill(WALL_TILE)
                addFeature(randomWalkCave(startPoint.x, startPoint.y, 6000, GROUND_TILE))
            }
            zone(ZONE_2, 200, 200, random = random) {
                fill(WALL_TILE)
                addFeature(randomWalkCave(startPoint.x, startPoint.y, 6000, GROUND_TILE))
            }
        }

    /** Stairs linking the two demo zones: `>` down to ZONE_2, `<` back up to ZONE_1. You
     * arrive at the destination zone's start point, away from its return stairs. */
    private fun spawnPortals() {
        spawnPortal(ZONE_1, glyph = '>', target = ZONE_2)
        spawnPortal(ZONE_2, glyph = '<', target = ZONE_1)
    }

    private fun spawnPortal(
        zoneId: String,
        glyph: Char,
        target: String,
    ) {
        val cell = randomWalkableCell(world.zones.getValue(zoneId))
        world.ecs.spawn(
            Position(cell.x, cell.y),
            ZoneMember(zoneId),
            Renderable(glyph, Color.CYAN.toNormalizedRgb(), RenderLayer.CREATURE),
            Portal(target, startPoint.x, startPoint.y),
        )
    }

    private fun randomWalkableCell(zone: Zone): Vector2Int {
        var x: Int
        var y: Int
        do {
            x = random.nextInt(zone.width)
            y = random.nextInt(zone.height)
        } while (
            !zone.tiles[x, y].isWalkable ||
            (x == startPoint.x && y == startPoint.y) ||
            world.entityAt(zone.zoneId, x, y) != null
        )
        return Vector2Int(x, y)
    }

    private fun spawnPlayer(): EntityId =
        world.ecs
            .spawn(
                Position(startPoint.x, startPoint.y),
                ZoneMember(world.currentZoneId),
                Renderable('@', Color.YELLOW.toNormalizedRgb(), RenderLayer.PLAYER),
                Health(100, 100),
                Named("You"),
                Player,
                // The player carries a lantern; LightingSystem renders it each tick.
                LightEmitter(Color(1f, 1f, 150f / 255f, 1f).toNormalizedRgb(), 15.0, LightCalculators.DIMINISHING),
            ).id

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
            } while (!zone.tiles[rx, ry].isWalkable || world.entityAt(zone.zoneId, rx, ry) != null)

            val zombie = random.nextBoolean()
            world.ecs.spawn(
                Position(rx, ry),
                ZoneMember(zone.zoneId),
                Renderable(
                    if (zombie) 'z' else 's',
                    (if (zombie) Color.GREEN else Color.WHITE).toNormalizedRgb(),
                    RenderLayer.CREATURE,
                ),
                Health(100, 100),
                Named(if (zombie) "zombie" else "sheep", if (zombie) "aggressive" else "docile"),
                Behavior(if (zombie) BehaviorStrategies.HUNT_PLAYER else BehaviorStrategies.WANDER),
            )
        }
    }

    private fun playerPosition(): Position = world.ecs.get(playerId)!!.require<Position>()

    private fun toggleLos() {
        renderer.losCalculator =
            if (renderer.losCalculator === symmetricShadowCaster) {
                omnipresentLos
            } else {
                symmetricShadowCaster
            }
    }

    companion object {
        private const val WINDOW_W = 80
        private const val WINDOW_H = 40
        private const val ZONE_1 = "Level 1"
        private const val ZONE_2 = "Level 2"

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
