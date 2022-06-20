package com.sletmoe.krogue.graphics

import java.awt.Rectangle

val MAX_BOUNDS = Rectangle(0, 0, Int.MAX_VALUE, Int.MAX_VALUE)

abstract class AsciiSubpanelComponent(private var _bounds: Rectangle = MAX_BOUNDS) : Resizable {
    override var bounds: Rectangle
        get() = _bounds
        set(value) {
            setBoundsImpl(value)
        }

    abstract fun refresh()
    abstract fun onNewBounds()

    protected open fun setBoundsImpl(newBounds: Rectangle) {
        _bounds = newBounds
        onNewBounds()
    }
}

