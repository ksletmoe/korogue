package com.sletmoe.krogue

import com.sletmoe.krogue.ui.AsciiPanelUi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import mu.KotlinLogging
import java.awt.event.InputEvent
import kotlin.system.measureNanoTime
import kotlin.time.Duration.Companion.nanoseconds

abstract class Game(
    targetFps: Int,
    protected val ui: AsciiPanelUi,
) {
    protected val logging = KotlinLogging.logger {}
    private var stop = false
    private val nanosecondsPerFrame = 1_000_000_000 / targetFps
    private var _ticks: Long = 0
    val ticks: Long
        get() = _ticks

    // overrideable callbacks
    protected open fun onInput(inputEvent: InputEvent) {}
    protected open fun onTick(ticks: Long) {}
    protected open fun onStartup() {}
    protected open fun onShutdown() {}

    // internal game engine logic

    private fun processInput() {
        val input = ui.getInput()
        if (input != null) {
            onInput(input)
        }
    }

    private fun tick() {
        _ticks += 1
        onTick(_ticks)
    }

    private fun render() {
        ui.refresh()
    }

    suspend fun run() = coroutineScope {
        stop = false
        onStartup()
        while (!stop) {
            val elapsedFrameNanoseconds = measureNanoTime {
                processInput()
                tick()
                render()
            }

            val remainingFrameNanoseconds = nanosecondsPerFrame - elapsedFrameNanoseconds
            if (remainingFrameNanoseconds > 0) {
                delay(remainingFrameNanoseconds.nanoseconds)
            }
        }

        onShutdown()
    }

    fun stop() {
        stop = true
    }
}
