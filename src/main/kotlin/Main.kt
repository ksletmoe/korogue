import asciiPanel.AsciiCharacterData
import asciiPanel.AsciiFont
import com.sletmoe.krogue.Game
import com.sletmoe.krogue.algorithms.color.multiplicationTransformer
import com.sletmoe.krogue.algorithms.lighting.DiminishingLightValueCalculator
import com.sletmoe.krogue.algorithms.los.OmnicientLineOfSightCalculator
import com.sletmoe.krogue.algorithms.los.SymmetricShadowCaster
import com.sletmoe.krogue.algorithms.zonegen.randomWalkCave
import com.sletmoe.krogue.graphics.AsciiCamera
import com.sletmoe.krogue.graphics.AsciiDisplay
import com.sletmoe.krogue.graphics.AsciiSubpanelHorizontalGroup
import com.sletmoe.krogue.graphics.AsciiSubpanelVerticalGroup
import com.sletmoe.krogue.graphics.Borders
import com.sletmoe.krogue.graphics.VisibilityConfiguration
import com.sletmoe.krogue.ui.AsciiPanelUi
import com.sletmoe.krogue.utilities.plus
import com.sletmoe.krogue.world.Creature
import com.sletmoe.krogue.world.LightSource
import com.sletmoe.krogue.world.Tile
import com.sletmoe.krogue.world.World
import com.sletmoe.krogue.world.ZonalPosition
import com.sletmoe.krogue.world.Zone
import kotlinx.coroutines.runBlocking
import java.awt.Color
import java.awt.Dimension
import java.awt.Point
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import kotlin.math.ceil
import kotlin.random.Random

class MyGame(
    title: String,
    targetFps: Int,
    gameWindowSizeRowsCols: Dimension,
    font: AsciiFont,
    private val random: Random = Random.Default,
) : Game(targetFps, AsciiPanelUi(title, gameWindowSizeRowsCols, font)) {
    private val firstZoneStartPoint = Point(10, 10)

    private val topBar = AsciiDisplay.create(ui) {
        minimumSizeProvider = { Dimension(80, 3) }
        preferredSizeProvider = { Dimension(Int.MAX_VALUE, 5) }
        maximumSizeProvider = { Dimension(Int.MAX_VALUE, 5) }
        border = Borders.singleLine(Color.blue, Color.black)
    }
    private val sideBar = AsciiDisplay.create(ui) {
        minimumSizeProvider = { Dimension(16, 16) }
        preferredSizeProvider = { Dimension((ui.widthInCharacters * 0.15).toInt(), Int.MAX_VALUE) }
        maximumSizeProvider = { Dimension(20, Int.MAX_VALUE) }
        border = Borders.singleLine(Color.blue, Color.black) {
            topRightCorner = AsciiCharacterData(Char(194), Color.blue, Color.black)
            bottomRightCorner = AsciiCharacterData(Char(193), Color.blue, Color.black)
        }
    }

    private val world = buildWorld(random)
    private val symmetricShadowCaster = SymmetricShadowCaster()
    private val omnipresentLosCalculator = OmnicientLineOfSightCalculator()

    private val player = Creature(
        ZonalPosition(world.currentZone, firstZoneStartPoint.x, firstZoneStartPoint.y),
        "You",
        '@',
        Color.yellow,
    )

    init {
        player.lightSource = LightSource(
            player.position.copy(),
            "Lantern",
            Color(255, 255, 150),
            15.0,
            lightValueCalculator = DiminishingLightValueCalculator()
        )

        world.currentZone.addCreature(player)
    }

    private val camera = AsciiCamera.create(
        ui,
        world.currentZone,
        focusProvider = { player.position.point },
        VisibilityConfiguration.create(symmetricShadowCaster) {
            maximumVisibilityDistance = 30.0
            previouslyViewedTilesVisible = true
            previouslyViewedTilesForegroundColorProvider = multiplicationTransformer(Color.blue)
            useLighting = true
        },
    ) {
        minimumSizeProvider = { Dimension(60, 20) }
        preferredSizeProvider = { Dimension(ceil(ui.widthInCharacters * 0.85).toInt(), Int.MAX_VALUE) }
        border = Borders.singleLine(Color.blue, Color.black).withoutLeft()
    }

    init {
        ui.setSubpanelGroup(
            AsciiSubpanelVerticalGroup.create {
                addComponent(topBar)
                addComponent(
                    AsciiSubpanelHorizontalGroup.create {
                        addComponent(sideBar)
                        addComponent(camera)
                    }
                )
            }
        )
    }

    private fun buildWorld(random: Random): World {
        val world = World.create {
            zone("Level 1", 200, 200, isCurrentZone = true, random = random) {
                fill(wallTile)
                addFeature(randomWalkCave(firstZoneStartPoint.x, firstZoneStartPoint.y, 6000, groundTile))
            }
        }

        populateZone(world.currentZone, 10)

        return world
    }

    private fun populateZone(zone: Zone, numCreatures: Int) {
        repeat(numCreatures) {
            var rndX = 0
            var rndY = 0

            do {
                rndX = random.nextInt(zone.width)
                rndY = random.nextInt(zone.height)
            } while (!zone.tiles[rndX, rndY].isWalkable)

            val creatureType = random.nextInt(2)

            val creature = if (creatureType == 0) {
                createCreature("zombie", zone, rndX, rndY)
            } else {
                createCreature("sheep", zone, rndX, rndY)
            }

            zone.addCreature(creature)
        }
    }

    private fun createCreature(type: String, zone: Zone, x: Int, y: Int): Creature {
        return when (type) {
            "zombie" -> {
                Creature(ZonalPosition(zone, x, y), "zombie", 'z', Color.green, "aggressive")
            }
            "sheep" -> {
                Creature(ZonalPosition(zone, x, y), "sheep", 's', Color.white, "docile")
            }
            else -> {
                throw RuntimeException()
            }
        }
    }

    override fun onTick(ticks: Long) {
        updateWorld()
        updateTopBar()
        updateSideBar()
        camera.focus()
    }

    private fun onCreatureDeath(creature: Creature) {
        logging.debug { "Creature $creature died" }
    }

    override fun onInput(inputEvent: InputEvent) {
        if (inputEvent is KeyEvent) {
            when (inputEvent.keyCode) {
                KeyEvent.VK_LEFT -> player.moveInZone(-1, 0)
                KeyEvent.VK_RIGHT -> player.moveInZone(1, 0)
                KeyEvent.VK_UP -> player.moveInZone(0, -1)
                KeyEvent.VK_DOWN -> player.moveInZone(0, 1)
                KeyEvent.VK_SPACE -> toggleLos()
            }
        } else if (inputEvent is MouseEvent) {
            //
        }
    }

    private fun updateWorld() {
        world.currentZone.creatures.filter { it.dead }.forEach { deadCreature ->
            onCreatureDeath(deadCreature)
            world.currentZone.removeCreature(deadCreature)
        }

        world.currentZone.creatures.filter { creature -> creature != player }.forEach { creature ->
            creature.update(world.currentZone)
        }
    }

    private fun updateTopBar() {
        topBar.fill(' ', Color.black, Color.black)
        topBar.write("My Game", Color.white, Color.black, startingAt = Point(1, 1))
    }

    private fun updateSideBar() {
        sideBar.fill(' ', Color.black, Color.black)
        val cameraViewArea = camera.viewArea
        val creaturesInView = world.currentZone.creatures
            .filter { cameraViewArea.contains(it.position.point) }
            .sortedBy { player.position.point.distanceSq(it.position.point) }
        val creatureNameColumnWidth = creaturesInView.map { it.name.length }.plus("Creature".length).max() + 1

        val sideBarInfoStartingPoint = Point(1, 1)

        // write sidebar header
        val creatureNameColumnHeader = "Creature".padEnd(creatureNameColumnWidth)
        sideBar.write("${creatureNameColumnHeader}HP", Color.white, Color.black, sideBarInfoStartingPoint)

        creaturesInView.forEachIndexed { idx, creature ->
            val labelStartingPoint = sideBarInfoStartingPoint + Point(0, idx + 2)
            sideBar.write(
                creature.name.padEnd(creatureNameColumnWidth),
                creature.color,
                Color.black,
                labelStartingPoint
            )
            sideBar.write(
                creature.health.toString(),
                healthTextColor(creature.health, creature.maxHp),
                Color.black,
                labelStartingPoint + Point(creatureNameColumnWidth, 0),
            )
        }
    }

    private fun healthTextColor(health: Int, maxHealth: Int): Color {
        val healthPercent = health.toDouble() / maxHealth.toDouble()
        return when {
            healthPercent > 0.8 -> Color.green
            healthPercent > .25 -> Color.yellow
            else -> Color.red
        }
    }

    private fun toggleLos() {
        if (camera.visibilityConfiguration.lineOfSightCalculator == symmetricShadowCaster) {
            camera.visibilityConfiguration.lineOfSightCalculator = omnipresentLosCalculator
        } else {
            camera.visibilityConfiguration.lineOfSightCalculator = symmetricShadowCaster
        }
    }

    companion object {
        private val wallTile: Tile
            get() = Tile(
                "stone wall",
                '#',
                color = Color.gray,
                backgroundColor = Color.BLACK,
                isWalkable = false,
                blocksLineOfSight = true,
            )

        private val groundTile = Tile(
            "stone floor",
            '.',
            color = Color.lightGray,
            backgroundColor = Color.black,
            isWalkable = true,
            blocksLineOfSight = false,
        )
    }
}

fun main(args: Array<String>) = runBlocking {
    val font = AsciiFont.CP437_12x12
    val game = MyGame("Krogue", 60, Dimension(120, 48), font)
    game.run()
}
