import asciiPanel.AsciiCharacterData
import asciiPanel.AsciiFont
import com.sletmoe.krogue.Game
import com.sletmoe.krogue.algorithms.los.LineOfSightCalculator
import com.sletmoe.krogue.algorithms.los.SymmetricShadowCaster
import com.sletmoe.krogue.algorithms.zonegen.randomWalkCave
import com.sletmoe.krogue.graphics.AsciiCamera
import com.sletmoe.krogue.graphics.AsciiDisplay
import com.sletmoe.krogue.graphics.AsciiSubpanelHorizontalGroup
import com.sletmoe.krogue.graphics.AsciiSubpanelVerticalGroup
import com.sletmoe.krogue.graphics.Borders
import com.sletmoe.krogue.ui.AsciiPanelUi
import com.sletmoe.krogue.utilities.Grid
import com.sletmoe.krogue.utilities.plus
import com.sletmoe.krogue.world.Creature
import com.sletmoe.krogue.world.Tile
import com.sletmoe.krogue.world.World
import kotlinx.coroutines.runBlocking
import java.awt.Color
import java.awt.Dimension
import java.awt.Point
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import kotlin.random.Random

class MyGame(
    title: String,
    targetFps: Int,
    gameWindowSizeRowsCols: Dimension,
    font: AsciiFont,
    private val random: Random = Random.Default,
) : Game(targetFps, AsciiPanelUi(title, gameWindowSizeRowsCols, font)) {
    private val player = Creature(10, 10, "You", '@', Color.yellow)

    private val topBar = AsciiDisplay.create(ui) {
        minimumSize = Dimension(80, 3)
        preferredSize = Dimension(Int.MAX_VALUE, 5)
        maximumSize = Dimension(Int.MAX_VALUE, 5)
        border = Borders.singleLine(Color.blue, Color.black)
    }
    private val sideBar = AsciiDisplay.create(ui) {
        minimumSize = Dimension(20, 20)
        preferredSize = Dimension((ui.widthInCharacters * 0.2).toInt(), Int.MAX_VALUE)
        maximumSize = Dimension(60, Int.MAX_VALUE)
        border = Borders.singleLine(Color.blue, Color.black) {
            topRightCorner = AsciiCharacterData(Char(194), Color.blue, Color.black)
            bottomRightCorner = AsciiCharacterData(Char(193), Color.blue, Color.black)
        }
    }

    private val world = buildWorld(random)
    private val symmetricShadowCaster = SymmetricShadowCaster()
    private val omnipresentLosCalculator = object : LineOfSightCalculator {
        override fun calculateLineOfSight(origin: Point, tiles: Grid<Tile>, maxViewDistance: Int): Grid<Boolean> {
            return Grid(tiles.width, tiles.height, true)
        }
    }

    private val camera = AsciiCamera.create(
        ui, world.currentZone, focusProvider = { Point(player.x, player.y) }, symmetricShadowCaster
    ) {
        minimumSize = Dimension(60, 20)
        preferredSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
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
        return World.create {
            zone("Level 1", 200, 200, isCurrentZone = true, random = random) {
                fill(wallTile)
                addCreature(player)
                addFeature(randomWalkCave(player.x, player.y, 6000, groundTile))
                populateZone(10)
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
                KeyEvent.VK_LEFT -> player.move(world.currentZone, -1, 0)
                KeyEvent.VK_RIGHT -> player.move(world.currentZone, 1, 0)
                KeyEvent.VK_UP -> player.move(world.currentZone, 0, -1)
                KeyEvent.VK_DOWN -> player.move(world.currentZone, 0, 1)
                KeyEvent.VK_SPACE -> toggleLos()
            }
        } else if (inputEvent is MouseEvent) {
            //
        }
    }

    private fun updateWorld() {
        world.currentZone.creatures.filter { it.dead }.forEach { onCreatureDeath(it) }

        world.currentZone.creatures = world.currentZone.creatures.filter { it.alive }
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
            .filter { cameraViewArea.contains(it.x, it.y) }
            .sortedBy { player.position.distanceSq(it.position) }
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
        if (camera.lineOfSightCalculator == symmetricShadowCaster) {
            camera.lineOfSightCalculator = omnipresentLosCalculator
        } else {
            camera.lineOfSightCalculator = symmetricShadowCaster
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
