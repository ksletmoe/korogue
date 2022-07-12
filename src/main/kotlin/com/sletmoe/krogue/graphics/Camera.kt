package com.sletmoe.krogue.graphics

import asciiPanel.AsciiCharacterData
import com.sletmoe.krogue.algorithms.los.LineOfSightCalculator
import com.sletmoe.krogue.ui.UserInterface
import com.sletmoe.krogue.utilities.Grid
import com.sletmoe.krogue.utilities.initialize
import com.sletmoe.krogue.utilities.or
import com.sletmoe.krogue.utilities.plus
import com.sletmoe.krogue.world.Zone
import java.awt.Color
import java.awt.Point
import java.awt.Rectangle
import kotlin.math.max
import kotlin.math.min

interface Camera {
    fun focus()
    val viewArea: Rectangle
}

typealias CameraFocusProvider = () -> Point

open class AsciiCamera(
    ui: UserInterface,
    private var zone: Zone,
    private val focusProvider: CameraFocusProvider,
    var lineOfSightCalculator: LineOfSightCalculator,
    var maxViewDistance: Int = Int.MAX_VALUE,
) : AsciiSubpanel(ui), Camera {
    private var previouslyVisible = Grid(zone.width, zone.height, false)

    override val viewArea: Rectangle
        get() {
            val cameraOrigin = getCameraOrigin()
            return Rectangle(cameraOrigin.x, cameraOrigin.y, contentBounds.width, contentBounds.height)
        }

    override fun focus() {
        clear()
        val buffer = Grid(contentBounds.width, contentBounds.height, defaultFillCharacter)

        val cameraOrigin = getCameraOrigin()
        drawTiles(cameraOrigin, buffer)
        drawCreatures(cameraOrigin, buffer)
        setVisibility(cameraOrigin, buffer)

        flushBuffer(buffer)
    }

    private fun flushBuffer(buffer: Grid<AsciiCharacterData>) {
        buffer.forEachIndexed { coordinate, characterData ->
            write(
                characterData.character,
                characterData.foregroundColor,
                characterData.backgroundColor,
                coordinate.x,
                coordinate.y,
            )
        }
    }

    private fun drawTiles(cameraOrigin: Point, buffer: Grid<AsciiCharacterData>) {
        buffer.forEachCoordinate { coordinate ->
            val worldTileCoordinate = coordinate + cameraOrigin
            val tile = zone.tiles[worldTileCoordinate]

            buffer[coordinate] = AsciiCharacterData(tile.glyph, tile.color, tile.backgroundColor)
        }
    }

    private fun drawCreatures(cameraOrigin: Point, buffer: Grid<AsciiCharacterData>) {
        zone.creatures.forEach { creature ->
            val subpanelTileX = creature.x - cameraOrigin.x
            val subpanelTileY = creature.y - cameraOrigin.y

            if (
                subpanelTileX in 0..buffer.lastColumnIndex
                && subpanelTileY in 0..buffer.lastRowIndex
            ) {
                val tileBgColor = zone.tiles[creature.x, creature.y].backgroundColor
                buffer[subpanelTileX, subpanelTileY] = AsciiCharacterData(creature.glyph, creature.color, tileBgColor)
            }
        }
    }

    private fun setVisibility(cameraOrigin: Point, buffer: Grid<AsciiCharacterData>) {
        // TODO: calculate subgrid of world, pass that to LOS calculator?
        val currentlyVisible = lineOfSightCalculator.calculateLineOfSight(focusProvider(), zone.tiles, maxViewDistance)
        previouslyVisible = previouslyVisible or currentlyVisible

        buffer.forEachCoordinate { coordinate ->
            val visibilityGridCoordinate = coordinate + cameraOrigin

            if (!currentlyVisible[visibilityGridCoordinate]) {
                buffer[coordinate] = AsciiCharacterData(' ', Color.black, Color.black)
            }
        }
    }

    private fun getCameraOrigin(): Point {
        val focusPoint = focusProvider()
        val cameraOriginX = max(
            0,
            min(focusPoint.x - contentBounds.width / 2, zone.width - contentBounds.width),
        )
        val cameraOriginY = max(
            0,
            min(focusPoint.y - contentBounds.height / 2, zone.height - contentBounds.height),
        )

        return Point(cameraOriginX, cameraOriginY)
    }

    override fun onNewBounds() {
        focus()
    }

    companion object {
        fun create(
            ui: UserInterface,
            zone: Zone,
            focusProvider: CameraFocusProvider,
            lineOfSightCalculator: LineOfSightCalculator,
            init: AsciiCamera.() -> Unit
        ): AsciiCamera = initialize(AsciiCamera(ui, zone, focusProvider, lineOfSightCalculator), init)
    }
}
