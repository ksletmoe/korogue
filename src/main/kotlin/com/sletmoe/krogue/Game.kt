package com.sletmoe.krogue

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import mu.KotlinLogging
import java.awt.Color
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import kotlin.random.Random
import kotlin.system.measureNanoTime
import kotlin.time.Duration.Companion.nanoseconds

class Game(
    title: String,
    targetFps: Int,
    windowWidthCols: Int = 80,
    windowWidthRows: Int = 24,
    random: Random = Random.Default,
) {
    companion object {
        protected val logging = KotlinLogging.logger {}
        private val wallTile = Tile(
            "stone wall", '#', color = Color.white, backgroundColor = Color.black, isBlocked = true
        )
        private val groundTile = Tile(
            "stone floor", '.', color = Color.white, backgroundColor = Color.black, isBlocked = false
        )
    }

    private var isRunning = false
    private val ui = UserInterface(title, screenWidth = windowWidthCols, screenHeight = windowWidthRows)
    private val player = Character(10, 10, "You", '@', Color.green)
    private val nanosecondsPerFrame = 1_000_000_000 / targetFps
    private val world = World.builder(windowWidthCols, windowWidthRows, random)
        .withPlayableCharacter(player)
        .fill(wallTile)
        .withRandomWalkCave(10, 10, 6000, groundTile)
        .build()

    private fun processInput() {
        val event = ui.nextInput

        if (event is KeyEvent) {
            when (event.keyCode) {
                KeyEvent.VK_LEFT -> player.move(-1, 0)
                KeyEvent.VK_RIGHT -> player.move(1, 0)
                KeyEvent.VK_UP -> player.move(0, -1)
                KeyEvent.VK_DOWN -> player.move(0, 1)
            }
        } else if (event is MouseEvent) {
            //
        }
    }

    private fun render() {
        ui.clear()
        ui.drawChar(player.glyph, player.x, player.y, player.color)
        ui.refresh()
    }

    suspend fun run() = coroutineScope {
        isRunning = true
        while (isRunning) {
            val elapsedFrameNanoseconds = measureNanoTime {
                processInput()
                render()
            }

            val remainingFrameNanoseconds = nanosecondsPerFrame - elapsedFrameNanoseconds
            if (remainingFrameNanoseconds > 0) {
                delay(remainingFrameNanoseconds.nanoseconds)
            }
        }
    }
}
