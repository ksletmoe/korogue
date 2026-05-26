package com.sletmoe.krogue.graphics

import asciiPanel.AsciiCharacterData
import com.sletmoe.krogue.utilities.initialize
import java.awt.Color

class AsciiBorder(
    var topEdge: AsciiCharacterData? = null,
    var topRightCorner: AsciiCharacterData? = null,
    var rightEdge: AsciiCharacterData? = null,
    var bottomRightCorner: AsciiCharacterData? = null,
    var bottomEdge: AsciiCharacterData? = null,
    var bottomLeftCorner: AsciiCharacterData? = null,
    var leftEdge: AsciiCharacterData? = null,
    var topLeftCorner: AsciiCharacterData? = null,
) {
    val leftBorderWidth: Int
        get() = if (leftEdge != null) 1 else 0

    val rightBorderWidth: Int
        get() = if (rightEdge != null) 1 else 0

    val addedWidth: Int
        get() = leftBorderWidth + rightBorderWidth

    val topEdgeHeight: Int
        get() = if (topEdge != null) 1 else 0

    val bottomEdgeHeight: Int
        get() = if (bottomEdge != null) 1 else 0

    val addedHeight: Int
        get() = topEdgeHeight + bottomEdgeHeight

    fun withoutTop(): AsciiBorder =
        AsciiBorder(
            rightEdge = rightEdge,
            bottomRightCorner = bottomRightCorner,
            bottomEdge = bottomEdge,
            bottomLeftCorner = bottomLeftCorner,
            leftEdge = leftEdge,
            // if we've got a left or right border it's gonna look weird if we don't extend the sides up to the top of the
            // content. So do that here
            topLeftCorner = leftEdge,
            topRightCorner = rightEdge,
        )

    fun withoutRight(): AsciiBorder =
        AsciiBorder(
            topLeftCorner = topLeftCorner,
            topEdge = topEdge,
            bottomEdge = bottomEdge,
            bottomLeftCorner = bottomLeftCorner,
            leftEdge = leftEdge,
            // same as above, if we have a top or bottom border, extend it in place of the right corners
            topRightCorner = topEdge,
            bottomRightCorner = bottomEdge,
        )

    fun withoutBottom(): AsciiBorder =
        AsciiBorder(
            topLeftCorner = topLeftCorner,
            topEdge = topEdge,
            topRightCorner = topRightCorner,
            rightEdge = rightEdge,
            leftEdge = leftEdge,
            // same as above, if we have left or right edges, extend them in place of the bottom corners
            bottomLeftCorner = leftEdge,
            bottomRightCorner = rightEdge,
        )

    fun withoutLeft(): AsciiBorder =
        AsciiBorder(
            topEdge = topEdge,
            topRightCorner = topRightCorner,
            rightEdge = rightEdge,
            bottomRightCorner = bottomRightCorner,
            bottomEdge = bottomEdge,
            // same as above, if we have top or bottom edges, extend them in place of the left corners
            topLeftCorner = topEdge,
            bottomLeftCorner = bottomEdge,
        )

    class UniformColorBuilder(private val foregroundColor: Color, private val backgroundColor: Color) {
        var topEdge: Char? = null
        var topRightCorner: Char? = null
        var rightEdge: Char? = null
        var bottomRightCorner: Char? = null
        var bottomEdge: Char? = null
        var bottomLeftCorner: Char? = null
        var leftEdge: Char? = null
        var topLeftCorner: Char? = null

        private fun toAsciiCharacterData(character: Char): AsciiCharacterData =
            AsciiCharacterData(character, foregroundColor, backgroundColor)

        fun setVerticalEdges(character: Char) {
            leftEdge = character
            rightEdge = character
        }

        fun setHorizontalEdges(character: Char) {
            topEdge = character
            bottomEdge = character
        }

        fun setCorners(character: Char) {
            topRightCorner = character
            bottomRightCorner = character
            bottomLeftCorner = character
            topLeftCorner = character
        }

        fun build(): AsciiBorder {
            return AsciiBorder(
                topEdge?.let { toAsciiCharacterData(it) },
                topRightCorner?.let { toAsciiCharacterData(it) },
                rightEdge?.let { toAsciiCharacterData(it) },
                bottomRightCorner?.let { toAsciiCharacterData(it) },
                bottomEdge?.let { toAsciiCharacterData(it) },
                bottomLeftCorner?.let { toAsciiCharacterData(it) },
                leftEdge?.let { toAsciiCharacterData(it) },
                topLeftCorner?.let { toAsciiCharacterData(it) },
            )
        }
    }

    companion object {
        fun create(init: AsciiBorder.() -> Unit): AsciiBorder = initialize(AsciiBorder(), init)

        fun withColors(
            foregroundColor: Color,
            backgroundColor: Color,
            init: UniformColorBuilder.() -> Unit,
        ): AsciiBorder {
            val uniformColorBuilder = UniformColorBuilder(foregroundColor, backgroundColor)
            initialize(uniformColorBuilder, init)
            return uniformColorBuilder.build()
        }
    }
}

object Borders {
    fun dashed(
        foregroundColor: Color,
        backgroundColor: Color,
        init: AsciiBorder.() -> Unit = {},
    ): AsciiBorder {
        val border =
            AsciiBorder.withColors(foregroundColor, backgroundColor) {
                setVerticalEdges('|')
                setHorizontalEdges('-')
                setCorners('+')
            }
        return initialize(border, init)
    }

    fun singleLine(
        foregroundColor: Color,
        backgroundColor: Color,
        init: AsciiBorder.() -> Unit = {},
    ): AsciiBorder {
        val border =
            AsciiBorder.withColors(foregroundColor, backgroundColor) {
                setVerticalEdges(Char(179))
                setHorizontalEdges(Char(196))
                topLeftCorner = Char(218)
                topRightCorner = Char(191)
                bottomLeftCorner = Char(192)
                bottomRightCorner = Char(217)
            }
        return initialize(border, init)
    }

    fun doubleLine(
        foregroundColor: Color,
        backgroundColor: Color,
        init: AsciiBorder.() -> Unit = {},
    ): AsciiBorder {
        val border =
            AsciiBorder.withColors(foregroundColor, backgroundColor) {
                setVerticalEdges(Char(186))
                setHorizontalEdges(Char(205))
                topLeftCorner = Char(201)
                topRightCorner = Char(187)
                bottomLeftCorner = Char(200)
                bottomRightCorner = Char(188)
            }
        return initialize(border, init)
    }
}
