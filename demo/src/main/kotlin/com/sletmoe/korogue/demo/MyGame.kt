package com.sletmoe.korogue.demo

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.Input
import com.badlogic.gdx.graphics.Color
import com.sletmoe.korogue.algorithms.color.toNormalizedRgb
import com.sletmoe.korogue.algorithms.lighting.DiminishingLightValueCalculator
import com.sletmoe.korogue.algorithms.los.LineOfSightCalculator
import com.sletmoe.korogue.algorithms.los.OmnicientLineOfSightCalculator
import com.sletmoe.korogue.algorithms.los.SymmetricShadowCaster
import com.sletmoe.korogue.algorithms.zonegen.randomWalkCave
import com.sletmoe.korogue.components.Behavior
import com.sletmoe.korogue.components.Collision
import com.sletmoe.korogue.components.Health
import com.sletmoe.korogue.components.Inventory
import com.sletmoe.korogue.components.Item
import com.sletmoe.korogue.components.LightEmitter
import com.sletmoe.korogue.components.MoveIntent
import com.sletmoe.korogue.components.Named
import com.sletmoe.korogue.components.Player
import com.sletmoe.korogue.components.Portal
import com.sletmoe.korogue.components.Position
import com.sletmoe.korogue.components.RenderLayer
import com.sletmoe.korogue.components.Renderable
import com.sletmoe.korogue.components.ZoneMember
import com.sletmoe.korogue.ecs.EntityId
import com.sletmoe.korogue.ecs.TickContext
import com.sletmoe.korogue.events.EntityDamaged
import com.sletmoe.korogue.events.EntityDied
import com.sletmoe.korogue.events.ItemPickedUp
import com.sletmoe.korogue.kotile.Game
import com.sletmoe.korogue.kotile.ZoneFog
import com.sletmoe.korogue.loop.GameLoop
import com.sletmoe.korogue.loop.RealTimeLoop
import com.sletmoe.korogue.loop.TurnBasedLoop
import com.sletmoe.korogue.perception.Perceived
import com.sletmoe.korogue.perception.Sight
import com.sletmoe.korogue.perception.SightSense
import com.sletmoe.korogue.perception.StandardPerception
import com.sletmoe.korogue.presentation.EventAnimationQueue
import com.sletmoe.korogue.presentation.VisualEvent
import com.sletmoe.korogue.random.GameRandom
import com.sletmoe.korogue.registry.GameModule
import com.sletmoe.korogue.save.SaveCodec
import com.sletmoe.korogue.schedule.Scheduler
import com.sletmoe.korogue.schedule.SchedulerSystem
import com.sletmoe.korogue.systems.BehaviorSystem
import com.sletmoe.korogue.systems.CombatSystem
import com.sletmoe.korogue.systems.HuntPlayerStrategy
import com.sletmoe.korogue.systems.LightingSystem
import com.sletmoe.korogue.systems.MovementSystem
import com.sletmoe.korogue.systems.PerceptionSystem
import com.sletmoe.korogue.systems.PickupSystem
import com.sletmoe.korogue.systems.PortalSystem
import com.sletmoe.korogue.systems.WanderStrategy
import com.sletmoe.korogue.ui.BarValue
import com.sletmoe.korogue.ui.BarWidget
import com.sletmoe.korogue.ui.Dialog
import com.sletmoe.korogue.ui.Frame
import com.sletmoe.korogue.ui.InventoryPanel
import com.sletmoe.korogue.ui.LogPanel
import com.sletmoe.korogue.ui.MapPanel
import com.sletmoe.korogue.ui.Menu
import com.sletmoe.korogue.ui.MenuItem
import com.sletmoe.korogue.ui.UiRoot
import com.sletmoe.korogue.ui.WindowSurface
import com.sletmoe.korogue.ui.inset
import com.sletmoe.korogue.ui.splitBottom
import com.sletmoe.korogue.ui.splitRight
import com.sletmoe.korogue.utilities.IntRect
import com.sletmoe.korogue.world.GameWorld
import com.sletmoe.korogue.world.Tile
import com.sletmoe.korogue.world.Zone
import com.sletmoe.kotile.display.ascii.AsciiTileWindow
import com.sletmoe.kotile.utilities.Vector2Int
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
 * Advances via a pluggable [GameLoop] (krogue-lhw): **turn-based by default** (the world ticks once
 * per move, so it waits for you), or real-time (`MyGame(turnBased = false)`, the original
 * continuous behavior). See the constructor.
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
    /**
     * Turn-based (default) advances the world once per player move (krogue-lhw); real-time advances
     * every frame as the demo originally did. Real-time keeps the per-tick AI act-chance throttle so
     * creatures don't move at frame rate; turn-based lets them act every turn.
     */
    private val turnBased: Boolean = true,
) : Game() {
    private val startPoint = Vector2Int(10, 10)

    private val symmetricShadowCaster = SymmetricShadowCaster()
    private val omnipresentLos = OmnicientLineOfSightCalculator()

    // The FOV mode the player's sight uses now (SPACE toggles it, krogue-f50's debug feature). It is
    // the LOS behind the `Sight` sense (ADR-0015): instead of MapPanel owning a LOS calculator, the
    // demo overrides the built-in `SightSense` with one reading this swappable delegate, so the
    // toggle still works while visibility is decided in the perception layer, not the renderer.
    private var activeLos: LineOfSightCalculator = symmetricShadowCaster
    private val toggleableLos =
        LineOfSightCalculator { origin, tiles, maxViewDistance ->
            activeLos.calculateLineOfSight(origin, tiles, maxViewDistance)
        }

    // Content (world gen + placement) draws from one stream; gameplay (AI/combat) from
    // another, so the same master seed always yields the same world (ADR-0009).
    private val worldgen: Random = gameRandom.stream("worldgen")

    // Engine defaults plus the demo's overrides: a `SightSense` reading the toggleable FOV above, and
    // — in turn-based mode — AI that acts every turn (overriding the built-in strategies' per-tick act
    // chance, which only makes sense under real-time's per-frame ticking).
    private val gameModule: GameModule =
        GameModule
            .engineDefaults()
            .sense(SightSense.ID, SightSense(toggleableLos))
            .apply {
                if (turnBased) {
                    strategy(WanderStrategy.ID, WanderStrategy(actChance = 1.0))
                    strategy(HuntPlayerStrategy.ID, HuntPlayerStrategy(actChance = 1.0))
                }
            }.build()

    /** The perception model the [PerceptionSystem] caches each tick (ADR-0015): the engine default. */
    private val perceptionModel = gameModule.perceptionModels.resolve(StandardPerception.ID)

    /** Drives world advancement: turn-on-input or continuous (see [turnBased], krogue-lhw). */
    private val gameLoop: GameLoop = if (turnBased) TurnBasedLoop() else RealTimeLoop()

    // Daemon/fuse timers (krogue-6uq). The demo registers no timed effects yet, so this is empty;
    // it's wired through registerSystems and save/load so the Rogue example (krogue-sdh) only needs
    // to register effects (gameModule.effect(...)) and schedule them. Restored in place on load.
    private val scheduler: Scheduler = Scheduler()

    // Save/load (F5/F9). The codec knows every registered component; the save file lives in
    // the working directory so it's easy to find when running the demo.
    private val saveCodec: SaveCodec = SaveCodec(gameModule.components)
    private val saveFile: File = File(SAVE_FILE_NAME)

    private var world: GameWorld = buildWorld()
    private var playerId: EntityId = spawnPlayer()

    private lateinit var ui: UiRoot
    private lateinit var logPanel: LogPanel
    private lateinit var lightingSystem: LightingSystem
    private lateinit var perceptionSystem: PerceptionSystem

    // Presentation-side, wall-clock visual sequences triggered by game events (krogue-wuq):
    // hit-flash/death-fade today, driven from [wireAnimations]. Never touches game state or saves.
    private val animationQueue = EventAnimationQueue()

    // The elapsedMs [drawFrame] is running with this frame; [MapPanel]'s decorate hook reads it to
    // draw [animationQueue] at the right point in its sequence (set once at the top of drawFrame).
    private var animationClockMs: Long = 0L

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
        lightingSystem =
            LightingSystem(world.zones, gameModule.calculators::resolve, activeZones = world::simulatedZones)
        // Perception caches each observer's Perceived (ADR-0015) and runs *after* lighting, since the
        // `Sight` sense reveals only lit cells — so it reads the light map this same tick produced.
        perceptionSystem = PerceptionSystem(world, perceptionModel, activeZones = world::simulatedZones)
        world.ecs
            // First in the pipeline: timed effects (regen/hunger/spawns) resolve at the top of the turn.
            .addSystem(SchedulerSystem(scheduler, gameModule.effects::resolve))
            .addSystem(BehaviorSystem(gameModule.strategies::resolve, activeZones = world::simulatedZones))
            .addSystem(MovementSystem(world.zones))
            .addSystem(PortalSystem(world))
            .addSystem(PickupSystem())
            .addSystem(CombatSystem())
            .addSystem(lightingSystem)
            .addSystem(perceptionSystem)
    }

    /**
     * Computes the light map once so the first frame renders lit terrain. Map visibility is gated on
     * lighting (normally computed during a world tick), but turn-based mode hasn't ticked yet at
     * startup, and a loaded game's light map (derived state) isn't saved — so without this the map
     * is black until the first move. Runs only the lighting system, not a full tick, so monsters
     * don't take a free turn.
     */
    private fun primeLighting() {
        lightingSystem.update(
            world.ecs,
            TickContext(turn = world.ecs.currentTurn, elapsedMs = 0L, random = Random.Default),
        )
    }

    /**
     * Caches the player's `Perceived` once so the first frame draws what they see — and again after a
     * [toggleLos], since perception is now computed on a tick (by [perceptionSystem]) rather than in
     * the renderer. Runs after [primeLighting] so the sight sense sees the freshly-lit cells.
     */
    private fun primePerception() {
        perceptionSystem.update(
            world.ecs,
            TickContext(turn = world.ecs.currentTurn, elapsedMs = 0L, random = Random.Default),
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
        buildUi()
        primeLighting()
        primePerception()
    }

    override fun onTick(deltaMs: Long) {
        if (ui.hasModal) return // a dialog (menu / game over) is up: pause the world
        // A visual sequence (hit-flash/death-fade) is playing: pause the world so the player sees
        // it land before acting again (krogue-wuq). Mirrors the modal-dialog pause above; presentation
        // time (animationQueue.update, in drawFrame) keeps advancing regardless.
        if (animationQueue.isPlaying) return
        // The loop decides whether this frame advances the world: turn-based ticks once per player
        // move (requestTurn from intendMove); real-time ticks at a fixed timestep, accumulating
        // deltaMs so speed is FPS-independent (krogue-k7q). A tick runs all systems (BehaviorSystem
        // -> MovementSystem -> PortalSystem -> PickupSystem -> CombatSystem -> LightingSystem);
        // gameplay randomness draws from its own stream (ADR-0009).
        gameLoop.advance(deltaMs) {
            world.ecs.tick(random = gameRandom.stream("gameplay"))
            if (!gameOverShown && (world.ecs.get(playerId)?.get<Health>()?.dead == true)) openGameOver()
        }
    }

    override fun drawFrame(elapsedMs: Long) {
        animationClockMs = elapsedMs
        animationQueue.update(elapsedMs)
        window.clear()
        ui.render()
        window.render(elapsedMs)
    }

    /**
     * Builds the [UiRoot] and its widgets bound to the current [world]: a [MapPanel], a [LogPanel]
     * sidebar (subscribed to the world's event bus), and a status strip with the HP bar. Called at
     * startup and again after [load] swaps in a new world; the FOV mode lives on [activeLos], so it
     * survives the rebuild without being threaded through.
     */
    private fun buildUi() {
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

        // The MapPanel renders the player's cached `Perceived` (ADR-0015); it defaults its observer
        // to the player, so nothing more to wire here.
        ui.add(
            MapPanel(
                bounds = mapRect,
                gameWorld = world,
                fogFor = { zone -> zoneFog.forZone(zone.zoneId, zone.width, zone.height) },
                decorate = { surface, camera -> animationQueue.render(surface, camera, animationClockMs) },
            ),
        )

        logPanel = LogPanel(logRect, title = "Log")
        ui.add(logPanel)
        ui.add(InventoryPanel(inventoryRect) { playerItems() })
        wireLog()
        wireAnimations()

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

    /**
     * Subscribes [animationQueue] to the current world's event bus (krogue-wuq), the same seam
     * [wireLog] uses: a hit flashes at the target's (snapshotted) position, a death fades the
     * deceased's own glyph/color out. Re-subscribed per [buildUi] like [wireLog].
     *
     * Only enqueues when the player currently perceives the cell (krogue-c0j): combat off-screen
     * or on an unperceived cell would otherwise still gate input on a sequence the player can't
     * see, and — separately — would reveal hidden combat by drawing a flash they shouldn't see.
     */
    private fun wireAnimations() {
        world.ecs.events.subscribe<EntityDamaged> { event ->
            val at = event.position ?: return@subscribe
            if (playerPerceives(at)) animationQueue.enqueue(VisualEvent.HitFlash(at))
        }
        world.ecs.events.subscribe<EntityDied> { event ->
            val at = event.position ?: return@subscribe
            if (playerPerceives(at)) {
                animationQueue.enqueue(
                    VisualEvent.DeathFade(at, event.glyph ?: '%', event.color?.toColor() ?: Color.GRAY),
                )
            }
        }
    }

    private fun playerPerceives(at: Vector2Int): Boolean =
        world.ecs.get(playerId)?.get<Perceived>()?.sees(at.x, at.y) == true

    override fun onKeyDown(keycode: Int) {
        if (ui.handleKey(keycode)) return // a modal/widget consumed it; don't treat as gameplay
        // A visual sequence is playing: any key fast-forwards past it rather than acting as gameplay
        // (krogue-wuq's skip/fast-forward requirement).
        if (animationQueue.isPlaying) {
            animationQueue.skip()
            return
        }
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

    /** Writes the full game state (entities + terrain + RNG + per-zone fog + timers) to [saveFile]. */
    private fun save() {
        val bytes = saveCodec.save(world, gameRandom, zoneFog.snapshot(), scheduler.snapshot())
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
        world = loaded.world
        gameRandom = loaded.random
        zoneFog.restore(loaded.fog)
        scheduler.restore(loaded.schedule)
        playerId = world.ecs.entitiesWith<Player>().first().id
        // A sequence mid-play (krogue-wuq) is animating positions in the world being replaced;
        // its coordinates would otherwise resolve against the loaded zone's camera next frame.
        animationQueue.clear()
        registerSystems()
        buildUi()
        primeLighting()
        primePerception()
        println("Loaded game from ${saveFile.absolutePath}")
    }

    /**
     * Player input is data too: attach a [MoveIntent] that MovementSystem resolves on the next
     * tick, and tell the loop the player took a turn (turn-based advances on it; real-time ignores).
     */
    private fun intendMove(
        dx: Int,
        dy: Int,
    ) {
        world.ecs.set(playerId, MoveIntent(dx, dy))
        gameLoop.requestTurn()
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
            // step onto the portal, don't bump into it
            Collision.PASSABLE,
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
                // Senses the world by sight (ADR-0015), uncapped — limited only by walls and lighting,
                // matching the demo's old uncapped LOS — and an empty Perceived so PerceptionSystem
                // caches the player's view each tick for the renderer.
                Sight(),
                Perceived(),
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
                // step onto the item to pick it up
                Collision.PASSABLE,
            )
        }
    }

    private fun playerItems(): List<String> = world.ecs.get(playerId)?.get<Inventory>()?.items ?: emptyList()

    private fun toggleLos() {
        activeLos = if (activeLos === symmetricShadowCaster) omnipresentLos else symmetricShadowCaster
        // Perception is cached on a tick, so recompute it now for the change to show this frame.
        primePerception()
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
