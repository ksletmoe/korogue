package com.sletmoe.krogue.graphics

import java.awt.Dimension
import java.awt.Rectangle

interface Resizable {
    val minimumSize: Dimension
    val preferredSize: Dimension
    val maximumSize: Dimension

    var bounds: Rectangle
}
