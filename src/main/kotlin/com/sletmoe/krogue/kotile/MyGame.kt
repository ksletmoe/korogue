package com.sletmoe.krogue.kotile

import com.badlogic.gdx.Input
import com.badlogic.gdx.graphics.Color
import com.sletmoe.kotile.display.ascii.AsciiTileWindow
import com.sletmoe.kotile.utilities.Vector2Int
import com.sletmoe.krogue.algorithms.color.toNormalizedRgb
import com.sletmoe.krogue.algorithms.lighting.LightCalculators
import com.sletmoe.krogue.algorithms.los.OmnicientLineOfSightCalculator
import com.sletmoe.krogue.algorithms.los.SymmetricShadowCaster
import com.sletmoe.krogue.algorithms.zonegen.randomWalkCave
import com.sletmoe.krogue.components.Health
import com.sletmoe.krogue.components.LightEmitter
import com.sletmoe.krogue.components.Named
import com.sletmoe.krogue.components.Player
import com.sletmoe.krogue.components.Position
import com.sletmoe.krogue.components.RenderLayer
import com.sletmoe.krogue.components.Renderable
import com.sletmoe.krogue.components.ZoneMember
import com.sletmoe.krogue.ecs.Entity
import com.sletmoe.krogue.ecs.EntityId
import com.sletmoe.krogue.systems.LightingSystem
import com.sletmoe.krogue.world.GameWorld
import com.sletmoe.krogue.world.Tile
import com.sletmoe.krogue.world.Zone
import kotlin.math.abs
import kotlin.random.Random

/**
 * Kotile-backed roguelike demo. Renders the current zone through [KotileZoneRenderer] with:
 * - FOV via [SymmetricShadowCaster] (toggle to omniscient with SPACE)
 * - Lighting via [DiminishingLightValueCalculator] on the player's lantern
 * - Previously-viewed tile dimming
 * - Arrow key player movement
 *
 * Occupants are ECS entities in [GameWorld.ecs] (ADR-0007). Lighting is now a
 * [LightingSystem] over `LightEmitter` entities (4b-s5). Movement and simple AI are still
 * interim glue in this class, replaced by `MovementSystem`/`CombatSystem` (s6) and a
 * `BehaviorSystem` (s7); the name-based AI goes away with s7.
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

    init {
        populateZone(world.currentZone, numCreatures = 10)
        world.ecs.addSystem(LightingSystem(world.zones))
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
        renderer =
            KotileZoneRenderer(
                zone = world.currentZone,
                world = world.ecs,
                window = window,
                lineOfSightCalculator = symmetricShadowCaster,
                maximumVisibilityDistance = 30.0,
            )
    }

    override fun onTick() {
        // INTERIM (s3): despawn dead non-player creatures; folded into CombatSystem (s6).
        world.ecs
            .entitiesWith<Health, ZoneMember>()
            .filter { it.id != playerId && it.require<Health>().dead }
            .toList()
            .forEach { world.ecs.despawn(it.id) }

        // INTERIM (s3): name-based AI mirroring the old Creature.update; becomes BehaviorSystem (s7).
        updateCreatures()

        // Run registered ECS systems (currently LightingSystem). One tick per frame is
        // interim — 4b-s6 introduces turn structure once movement is system-driven.
        world.ecs.tick()
    }

    override fun drawFrame(elapsedMs: Long) {
        renderer.render(focusPoint = playerPosition().point, elapsedMs = elapsedMs)
    }

    override fun onKeyDown(keycode: Int) {
        when (keycode) {
            Input.Keys.LEFT -> moveEntity(playerId, -1, 0)
            Input.Keys.RIGHT -> moveEntity(playerId, 1, 0)
            Input.Keys.UP -> moveEntity(playerId, 0, -1)
            Input.Keys.DOWN -> moveEntity(playerId, 0, 1)
            Input.Keys.SPACE -> toggleLos()
        }
    }

    // -------------------------------------------------------------------------
    // Setup
    // -------------------------------------------------------------------------

    private fun buildWorld(): GameWorld =
        GameWorld.create {
            zone("Level 1", 200, 200, isCurrentZone = true, random = random) {
                fill(WALL_TILE)
                addFeature(randomWalkCave(startPoint.x, startPoint.y, 6000, GROUND_TILE))
            }
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
            )
        }
    }

    // -------------------------------------------------------------------------
    // INTERIM glue — replaced by ECS systems in s5/s6/s7
    // -------------------------------------------------------------------------

    private fun playerPosition(): Position = world.ecs.get(playerId)!!.require<Position>()

    /**
     * INTERIM (s3): move [id] by ([dx], [dy]) — bump-attack an occupant, else step onto
     * walkable terrain. Becomes MoveIntent + MovementSystem/CombatSystem in 4b-s6.
     */
    private fun moveEntity(
        id: EntityId,
        dx: Int,
        dy: Int,
    ) {
        val entity = world.ecs.get(id) ?: return
        val pos = entity.require<Position>()
        val zoneId = entity.require<ZoneMember>().zoneId
        val destX = pos.x + dx
        val destY = pos.y + dy

        val occupant = world.entityAt(zoneId, destX, destY)
        when {
            occupant != null && occupant.id != id -> attack(occupant)
            world.zones.getValue(zoneId).isWalkable(destX, destY) ->
                world.ecs.set(id, Position(destX, destY))
        }
    }

    /** INTERIM (s3): apply attack damage; becomes CombatSystem (s6). */
    private fun attack(target: Entity) {
        world.ecs.update<Health>(target.id) { it.copy(current = (it.current - ATTACK_DAMAGE).coerceAtLeast(0)) }
    }

    /** INTERIM (s3): name-based AI mirroring the old Creature.update; becomes BehaviorSystem (s7). */
    private fun updateCreatures() {
        val playerPos = playerPosition()
        world.ecs
            .entitiesWith<Position, Named, ZoneMember>()
            .filter { it.id != playerId }
            .toList()
            .forEach { creature ->
                if (random.nextInt(100) <= 98) return@forEach
                when (creature.require<Named>().name) {
                    "sheep" -> {
                        val (dx, dy) = STEPS.random(random)
                        moveEntity(creature.id, dx, dy)
                    }
                    "zombie" -> {
                        val pos = creature.require<Position>()
                        if (pos.point.distanceChebyshev(playerPos.point) <= ZOMBIE_AGGRO_RANGE) {
                            val dx = (playerPos.x - pos.x).coerceIn(-1, 1)
                            val dy = if (dx == 0) (playerPos.y - pos.y).coerceIn(-1, 1) else 0
                            moveEntity(creature.id, dx, dy)
                        }
                    }
                }
            }
    }

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
        private const val ATTACK_DAMAGE = 20
        private const val ZOMBIE_AGGRO_RANGE = 10

        private val STEPS = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)

        private fun Vector2Int.distanceChebyshev(other: Vector2Int): Int = maxOf(abs(x - other.x), abs(y - other.y))

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
