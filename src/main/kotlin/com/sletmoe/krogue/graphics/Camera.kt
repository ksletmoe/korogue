package com.sletmoe.krogue.graphics

import asciiPanel.AsciiCharacterData
import com.sletmoe.krogue.algorithms.color.ColorTransformer
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
    private val zone: Zone,
    private val focusProvider: CameraFocusProvider,
    var visibilityConfiguration: VisibilityConfiguration,
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
            write(characterData, coordinate)
        }
    }

    private fun drawTiles(cameraOrigin: Point, buffer: Grid<AsciiCharacterData>) {
        buffer.forEachCoordinate { coordinate ->
            val zoneCoordinate = coordinate + cameraOrigin
            val tile = zone.tiles[zoneCoordinate]

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
        val currentlyVisible = visibilityConfiguration.lineOfSightCalculator.calculateLineOfSight(
            focusProvider(), zone.tiles, visibilityConfiguration.maximumVisibilityDistance
        )
        previouslyVisible = previouslyVisible or currentlyVisible

        buffer.forEachCoordinate { coordinate ->
            val zoneCoordinate = coordinate + cameraOrigin

            if (!currentlyVisible[zoneCoordinate]) {
                buffer[coordinate] = if (
                    visibilityConfiguration.previouslyViewedTilesVisible && previouslyVisible[zoneCoordinate]
                ) {
                    val zoneTile= zone.tiles[zoneCoordinate]
                    AsciiCharacterData(
                        zoneTile.glyph,
                        visibilityConfiguration.previouslyViewedTilesForegroundColorProvider(zoneTile.color),
                        visibilityConfiguration.previouslyViewedTilesBackgroundColorProvider(zoneTile.backgroundColor),
                    )
                } else {
                    AsciiCharacterData(' ', Color.black, Color.black)
                }

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
            visibilityConfiguration: VisibilityConfiguration,
            init: AsciiCamera.() -> Unit
        ): AsciiCamera = initialize(AsciiCamera(ui, zone, focusProvider, visibilityConfiguration), init)
    }
}

data class VisibilityConfiguration(
    var lineOfSightCalculator: LineOfSightCalculator,
    var maximumVisibilityDistance: Int? = null,
    var useLighting: Boolean = false,
    var previouslyViewedTilesVisible: Boolean = false,
    var previouslyViewedTilesForegroundColorProvider: ColorTransformer = { Color.gray },
    var previouslyViewedTilesBackgroundColorProvider: ColorTransformer = { Color.black },
) {
    companion object {
        fun create(
            lineOfSightCalculator: LineOfSightCalculator, init: VisibilityConfiguration.() -> Unit
        ): VisibilityConfiguration = initialize(VisibilityConfiguration(lineOfSightCalculator), init)
    }
}
