package com.sletmoe.krogue.kotile

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.graphics.Color
import com.sletmoe.kotile.display.ascii.AsciiTileWindow
import com.sletmoe.kotile.utilities.Vector2Int
import com.sletmoe.krogue.algorithms.color.toNormalizedRgb
import com.sletmoe.krogue.algorithms.lighting.DiminishingLightValueCalculator
import com.sletmoe.krogue.algorithms.los.LineOfSightCalculator
import com.sletmoe.krogue.algorithms.los.OmnicientLineOfSightCalculator
import com.sletmoe.krogue.algorithms.los.SymmetricShadowCaster
import com.sletmoe.krogue.algorithms.zonegen.randomWalkCave
import com.sletmoe.krogue.components.Behavior
import com.sletmoe.krogue.components.Health
import com.sletmoe.krogue.components.Inventory
import com.sletmoe.krogue.components.Item
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
import com.sletmoe.krogue.events.EntityDamaged
import com.sletmoe.krogue.events.EntityDied
import com.sletmoe.krogue.events.ItemPickedUp
import com.sletmoe.krogue.random.GameRandom
import com.sletmoe.krogue.registry.GameModule
import com.sletmoe.krogue.save.SaveCodec
import com.sletmoe.krogue.systems.BehaviorSystem
import com.sletmoe.krogue.systems.CombatSystem
import com.sletmoe.krogue.systems.HuntPlayerStrategy
import com.sletmoe.krogue.systems.LightingSystem
import com.sletmoe.krogue.systems.MovementSystem
import com.sletmoe.krogue.systems.PickupSystem
import com.sletmoe.krogue.systems.PortalSystem
import com.sletmoe.krogue.systems.WanderStrategy
import com.sletmoe.krogue.ui.BarValue
import com.sletmoe.krogue.ui.BarWidget
import com.sletmoe.krogue.ui.Dialog
import com.sletmoe.krogue.ui.Frame
import com.sletmoe.krogue.ui.InventoryPanel
import com.sletmoe.krogue.ui.LogPanel
import com.sletmoe.krogue.ui.MapPanel
import com.sletmoe.krogue.ui.Menu
import com.sletmoe.krogue.ui.MenuItem
import com.sletmoe.krogue.ui.UiRoot
import com.sletmoe.krogue.ui.WindowSurface
import com.sletmoe.krogue.ui.inset
import com.sletmoe.krogue.ui.splitBottom
import com.sletmoe.krogue.ui.splitRight
import com.sletmoe.krogue.utilities.IntRect
import com.sletmoe.krogue.world.GameWorld
import com.sletmoe.krogue.world.Tile
import com.sletmoe.krogue.world.Zone
import java.io.File
import kotlin.random.Random

/**
 * Kotile-backed roguelike demo. The screen is a [UiRoot] of widgets (ADR-0011): a [MapPanel] with
 * a sidebar stacking a [LogPanel] combat/pickup log over an [InventoryPanel] (both fed by the event
 * bus), over a bottom [Frame]d status strip with the player's HP [BarWidget]. The map draws the
 * current zone with:
 * - FOV via [SymmetricShadowCaster] (toggle to omniscient with SPACE)
 * - Lighting via [DiminishingLightValueCalculator] on the player's lantern
 * - Previously-viewed tile dimming
 * - Arrow key player movement
 * - F5 saves and F9 loads (CBOR via [SaveCodec]; entities + terrain + RNG + fog)
 * - Esc opens a modal system menu (resume/save/load/quit); a game-over dialog appears at 0 HP
 *
 * Occupants are ECS entities in [GameWorld.ecs] (ADR-0007), driven by systems each tick:
 * [BehaviorSystem] (AI) and player input emit [MoveIntent]s; [MovementSystem] resolves
 * them; [PortalSystem] applies zone transitions; [CombatSystem] applies attacks and clears
 * the dead; [LightingSystem] renders the lanterns.
 *
 * The demo has two zones linked by stairs (`>`/`<`). Only the player's zone is simulated
 * and rendered (ADR-0008); the other freezes in place and is restored on return. The [MapPanel]
 * reads the current zone each frame, so a transition needs no renderer rebuild.
 */
class MyGame(
    private var gameRandom: GameRandom = GameRandom.random(),
) : Game() {
    private val startPoint = Vector2Int(10, 10)

    private val symmetricShadowCaster = SymmetricShadowCaster()
    private val omnipresentLos = OmnicientLineOfSightCalculator()

    // Content (world gen + placement) draws from one stream; gameplay (AI/combat) from
    // another, so the same master seed always yields the same world (ADR-0009).
    private val worldgen: Random = gameRandom.stream("worldgen")

    // Engine defaults are enough for the demo; a richer game would register its own
    // strategies/calculators here (and, from 4f-s3, component serializers).
    private val gameModule: GameModule = GameModule.engineDefaults().build()

    // Save/load (F5/F9). The codec knows every registered component; the save file lives in
    // the working directory so it's easy to find when running the demo.
    private val saveCodec: SaveCodec = SaveCodec(gameModule.components)
    private val saveFile: File = File(SAVE_FILE_NAME)

    private var world: GameWorld = buildWorld()
    private var playerId: EntityId = spawnPlayer()

    private lateinit var ui: UiRoot
    private lateinit var mapPanel: MapPanel
    private lateinit var logPanel: LogPanel

    /** The open modal dialog (system menu / game over), or null. Tracked so it can be closed. */
    private var modalDialog: Dialog? = null
    private var gameOverShown = false

    /**
     * Per-zone fog-of-war memory, owned here (not the [MapPanel]) so it survives a save/load
     * world swap. The map reads the current zone's grid each frame and accumulates into it, so
     * explored areas stay remembered across zone transitions (krogue-ro8).
     */
    private val zoneFog = ZoneFog()

    init {
        spawnPortals()
        world.zones.values.forEach { populateZone(it, numCreatures = 10) }
        world.zones.values.forEach { spawnItems(it, numItems = 6) }
        registerSystems()
    }

    /**
     * Registers the gameplay systems on [world]'s ECS, in run order: decide AI moves, resolve
     * movement, apply zone transitions, pick up items, resolve combat, then recompute lighting.
     * Called for the initial world and again after [load] swaps in a fresh (system-less) world.
     */
    private fun registerSystems() {
        world.ecs
            .addSystem(BehaviorSystem(gameModule.strategies::resolve, activeZones = world::simulatedZones))
            .addSystem(MovementSystem(world.zones))
            .addSystem(PortalSystem(world))
            .addSystem(PickupSystem())
            .addSystem(CombatSystem())
            .addSystem(
                LightingSystem(world.zones, gameModule.calculators::resolve, activeZones = world::simulatedZones),
            )
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
        buildUi(symmetricShadowCaster)
    }

    override fun onTick() {
        if (ui.hasModal) return // a dialog (menu / game over) is up: pause the world
        // Advance the world one tick: BehaviorSystem -> MovementSystem -> PortalSystem ->
        // CombatSystem -> LightingSystem. Gameplay randomness (AI/combat) draws from its
        // own stream. One tick per frame is interim; a turn-on-input loop can come later.
        world.ecs.tick(random = gameRandom.stream("gameplay"))
        if (!gameOverShown && (world.ecs.get(playerId)?.get<Health>()?.dead == true)) openGameOver()
    }

    override fun drawFrame(elapsedMs: Long) {
        window.clear()
        ui.render()
        window.render(elapsedMs)
    }

    /**
     * Builds the [UiRoot] and its widgets bound to the current [world]: a [MapPanel], a [LogPanel]
     * sidebar (subscribed to the world's event bus), and a status strip with the HP bar. Called at
     * startup and again after [load] swaps in a new world; [los] carries the FOV mode across.
     */
    private fun buildUi(los: LineOfSightCalculator) {
        // A fresh UiRoot has no dialogs; clear the modal/game-over tracking to match.
        modalDialog = null
        gameOverShown = false
        ui = UiRoot(WindowSurface(window))
        // Layout: a full-width status strip along the bottom, then the rest split into the map
        // (left) and a message-log sidebar (right).
        val (topRect, statusRect) =
            IntRect(0, 0, window.widthInTiles, window.heightInTiles).splitBottom(STATUS_ROWS)
        val (mapRect, sidebarRect) = topRect.splitRight(SIDEBAR_WIDTH)
        // The sidebar stacks the message log (top) over the inventory panel (bottom).
        val (logRect, inventoryRect) = sidebarRect.splitBottom(INVENTORY_ROWS)

        mapPanel =
            MapPanel(
                bounds = mapRect,
                gameWorld = world,
                fogFor = { zone -> zoneFog.forZone(zone.zoneId, zone.width, zone.height) },
                losCalculator = los,
            )
        ui.add(mapPanel)

        logPanel = LogPanel(logRect, title = "Log")
        ui.add(logPanel)
        ui.add(InventoryPanel(inventoryRect) { playerItems() })
        wireLog()

        // A bottom status strip: a bordered frame with the player's HP bar inside it.
        ui.add(Frame(statusRect, title = "Status"))
        val inner = statusRect.inset(1)
        ui.add(
            BarWidget(
                bounds = IntRect(inner.x, inner.y, inner.width, 1),
                label = "HP",
                filled = Color.FOREST,
                empty = Color.MAROON,
            ) { playerHealth() },
        )
    }

    /** The player's current/max hit points for the HP bar, or 0/0 if the player is gone. */
    private fun playerHealth(): BarValue {
        val health = world.ecs.get(playerId)?.get<Health>() ?: return BarValue(0, 0)
        return BarValue(health.current, health.max)
    }

    /**
     * Subscribes the [logPanel] to the current world's event bus (ADR-0010) — the bus's first real
     * consumer (combat + pickups). Re-subscribed per [buildUi] because a load swaps in a new world
     * (and bus).
     */
    private fun wireLog() {
        world.ecs.events.subscribe<EntityDamaged> { event ->
            logPanel.append("${nameOf(event.attacker)} hits ${nameOf(event.target)} for ${event.amount}")
        }
        world.ecs.events.subscribe<EntityDied> { event ->
            logPanel.append("${event.name ?: "Something"} dies")
        }
        world.ecs.events.subscribe<ItemPickedUp> { event ->
            logPanel.append("You pick up a ${event.name}")
        }
    }

    private fun nameOf(id: EntityId): String = world.ecs.get(id)?.get<Named>()?.name ?: "something"

    override fun onKeyDown(keycode: Int) {
        if (ui.handleKey(keycode)) return // a modal/widget consumed it; don't treat as gameplay
        when (keycode) {
            Input.Keys.LEFT -> intendMove(-1, 0)
            Input.Keys.RIGHT -> intendMove(1, 0)
            Input.Keys.UP -> intendMove(0, -1)
            Input.Keys.DOWN -> intendMove(0, 1)
            Input.Keys.SPACE -> toggleLos()
            Input.Keys.F5 -> save()
            Input.Keys.F9 -> load()
            Input.Keys.ESCAPE -> openSystemMenu()
        }
    }

    // -------------------------------------------------------------------------
    // Dialogs (system menu / game over) — UI toolkit modals
    // -------------------------------------------------------------------------

    /** The pause / system menu (Esc): resume, save, load, or quit. Cancellable with Esc. */
    private fun openSystemMenu() {
        // "Load" runs load(), which rebuilds the UI and so clears this dialog on its own.
        openDialog(
            title = "Menu",
            cancellable = true,
            items =
                listOf(
                    MenuItem("Resume") { closeModal() },
                    MenuItem("Save") {
                        save()
                        closeModal()
                    },
                    MenuItem("Load") { load() },
                    MenuItem("Quit") { Gdx.app.exit() },
                ),
        )
    }

    /** Shown when the player reaches 0 HP (krogue-4zi). Not cancellable — load a save or quit. */
    private fun openGameOver() {
        gameOverShown = true
        openDialog(
            title = "You died",
            cancellable = false,
            items =
                listOf(
                    MenuItem("Load last save") { load() },
                    MenuItem("Quit") { Gdx.app.exit() },
                ),
        )
    }

    private fun openDialog(
        title: String,
        cancellable: Boolean,
        items: List<MenuItem>,
    ) {
        closeModal()
        val rect = centeredRect(DIALOG_WIDTH, items.size + DIALOG_CHROME_ROWS)
        val inner = IntRect(0, 0, rect.width, rect.height).inset(1)
        val menu = Menu(IntRect(0, 0, inner.width, inner.height), items)
        val dialog = Dialog(rect, title, menu, onCancel = if (cancellable) ({ closeModal() }) else null)
        ui.add(dialog, modal = true)
        modalDialog = dialog
    }

    private fun closeModal() {
        modalDialog?.let { ui.remove(it) }
        modalDialog = null
    }

    private fun centeredRect(
        w: Int,
        h: Int,
    ): IntRect {
        val width = w.coerceAtMost(window.widthInTiles)
        val height = h.coerceAtMost(window.heightInTiles)
        return IntRect((window.widthInTiles - width) / 2, (window.heightInTiles - height) / 2, width, height)
    }

    // -------------------------------------------------------------------------
    // Save / load (F5 / F9)
    // -------------------------------------------------------------------------

    /** Writes the full game state (entities + terrain + RNG + per-zone fog) to [saveFile]. */
    private fun save() {
        val bytes = saveCodec.save(world, gameRandom, zoneFog.snapshot())
        saveFile.writeBytes(bytes)
        println("Saved ${bytes.size} bytes to ${saveFile.absolutePath}")
    }

    /**
     * Replaces the running game with the state in [saveFile]: swaps in the loaded world, RNG,
     * and fog, re-registers systems on the fresh ECS, re-resolves the player entity, and rebuilds
     * the UI bound to the new world (keeping the current LOS mode). No-op with a message if there
     * is no save.
     */
    private fun load() {
        if (!saveFile.exists()) {
            println("No save file at ${saveFile.absolutePath}")
            return
        }
        val loaded = saveCodec.load(saveFile.readBytes())
        val los = mapPanel.losCalculator
        world = loaded.world
        gameRandom = loaded.random
        zoneFog.restore(loaded.fog)
        playerId = world.ecs.entitiesWith<Player>().first().id
        registerSystems()
        buildUi(los)
        println("Loaded game from ${saveFile.absolutePath}")
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
            zone(ZONE_1, 200, 200, isCurrentZone = true, random = worldgen) {
                fill(WALL_TILE)
                addFeature(randomWalkCave(startPoint.x, startPoint.y, 6000, GROUND_TILE))
            }
            zone(ZONE_2, 200, 200, random = worldgen) {
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
            x = worldgen.nextInt(zone.width)
            y = worldgen.nextInt(zone.height)
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
                Inventory(),
                // The player carries a lantern; LightingSystem renders it each tick.
                LightEmitter(
                    Color(1f, 1f, 150f / 255f, 1f).toNormalizedRgb(),
                    15.0,
                    DiminishingLightValueCalculator.ID,
                ),
            ).id

    private fun populateZone(
        zone: Zone,
        numCreatures: Int,
    ) {
        repeat(numCreatures) {
            var rx: Int
            var ry: Int
            do {
                rx = worldgen.nextInt(zone.width)
                ry = worldgen.nextInt(zone.height)
            } while (!zone.tiles[rx, ry].isWalkable || world.entityAt(zone.zoneId, rx, ry) != null)

            val zombie = worldgen.nextBoolean()
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
                Behavior(if (zombie) HuntPlayerStrategy.ID else WanderStrategy.ID),
            )
        }
    }

    /** Scatters [numItems] collectible items (non-blocking) across [zone] for the inventory demo. */
    private fun spawnItems(
        zone: Zone,
        numItems: Int,
    ) {
        repeat(numItems) {
            val cell = randomWalkableCell(zone)
            val (glyph, name) = ITEM_KINDS.random(worldgen)
            world.ecs.spawn(
                Position(cell.x, cell.y),
                ZoneMember(zone.zoneId),
                Renderable(glyph, Color.GOLD.toNormalizedRgb(), RenderLayer.CREATURE),
                Item(name),
            )
        }
    }

    private fun playerItems(): List<String> = world.ecs.get(playerId)?.get<Inventory>()?.items ?: emptyList()

    private fun toggleLos() {
        mapPanel.losCalculator =
            if (mapPanel.losCalculator === symmetricShadowCaster) {
                omnipresentLos
            } else {
                symmetricShadowCaster
            }
    }

    companion object {
        private const val WINDOW_W = 80
        private const val WINDOW_H = 40
        private const val STATUS_ROWS = 3
        private const val SIDEBAR_WIDTH = 24
        private const val INVENTORY_ROWS = 12
        private const val DIALOG_WIDTH = 24

        /** (glyph, name) for the demo's collectible items. */
        private val ITEM_KINDS =
            listOf(
                '!' to "potion",
                '?' to "scroll",
                '=' to "ring",
                '$' to "gold piece",
            )
        private const val DIALOG_CHROME_ROWS = 2 // top + bottom border around the menu rows
        private const val ZONE_1 = "Level 1"
        private const val ZONE_2 = "Level 2"
        private const val SAVE_FILE_NAME = "krogue-save.cbor"

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
