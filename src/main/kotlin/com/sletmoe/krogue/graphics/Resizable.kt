package com.sletmoe.krogue.graphics

import java.awt.Dimension
import java.awt.Rectangle

interface Resizable {
    fun getMinimumSize(containerSize: Dimension): Dimension

    fun getPreferredSize(containerSize: Dimension): Dimension

    fun getMaximumSize(containerSize: Dimension): Dimension

    var bounds: Rectangle
}
