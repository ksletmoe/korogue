package com.sletmoe.krogue.graphics

import asciiPanel.AsciiCharacterData
import asciiPanel.AsciiPanel
import com.sletmoe.krogue.utilities.translated
import java.awt.Color
import java.awt.Dimension
import java.awt.Rectangle
import kotlin.math.min

object DefaultSubpanelSizes {
    val MINIMUM: Dimension
        get() = Dimension(1, 1)
    val PREFERRED: Dimension
        get() = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
    val MAXIMUM: Dimension
        get() = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
}

abstract class AsciiSubpanel(
    private val asciiPanel: AsciiPanel,
    private var _border: AsciiBorder? = null,
    var defaultFillCharacter: AsciiCharacterData = AsciiCharacterData(' ', Color.white, Color.black),
    override var minimumSize: Dimension = DefaultSubpanelSizes.MINIMUM,
    override var preferredSize: Dimension = DefaultSubpanelSizes.PREFERRED,
    override var maximumSize: Dimension = DefaultSubpanelSizes.MAXIMUM,
) : AsciiSubpanelComponent() {
    override var bounds: Rectangle
        get() = writableBounds()
        set(value) {
            super.bounds = value
        }

    var border: AsciiBorder?
        get() = _border
        set(value) {
            _border = value
            // adding a border potentially changes our content bounds
            onNewBounds()
        }

    protected val contentBounds: Rectangle
        get() {
            val contentArea = bounds

            if (border != null) {
                contentArea.x += border!!.leftBorderWidth
                contentArea.y += border!!.topEdgeHeight
                contentArea.width -= border!!.addedWidth
                contentArea.height -= border!!.addedHeight
            }

            return contentArea
        }

    protected val relativeContentBounds: Rectangle
        get() {
            val contentArea = contentBounds
            return contentArea.translated(-contentArea.x, -contentArea.y)
        }

    private fun drawBorder() {
        if (border == null) {
            return
        }

        (0 until bounds.width).forEach { x ->
            (0 until bounds.height).forEach { y ->
                val characterToWrite: AsciiCharacterData? = when (x) {
                    0 -> {
                        when (y) {
                            0 -> {
                                border?.topLeftCorner
                            }
                            bounds.height - 1 -> {
                                border?.bottomLeftCorner
                            }
                            else -> {
                                border?.leftEdge
                            }
                        }
                    }
                    bounds.width - 1 -> {
                        when (y) {
                            0 -> {
                                border?.topRightCorner
                            }
                            bounds.height - 1 -> {
                                border?.bottomRightCorner
                            }
                            else -> {
                                border?.rightEdge
                            }
                        }
                    }
                    else -> {
                        when (y) {
                            0 -> border?.topEdge
                            bounds.height - 1 -> border?.bottomEdge
                            else -> null
                        }
                    }
                }

                asciiPanel.write(characterToWrite ?: defaultFillCharacter, bounds.x + x, bounds.y + y)
            }
        }
    }

    fun clear() {
        fill(defaultFillCharacter)
    }

    fun fill(character: Char, foregroundColor: Color, backgroundColor: Color) {
        drawBorder()
        (0 until contentBounds.width).forEach { x ->
            (0 until contentBounds.height).forEach { y ->
                write(character, foregroundColor, backgroundColor, x, y)
            }
        }
    }

    fun fill(characterData: AsciiCharacterData) {
        fill(characterData.character, characterData.foregroundColor, characterData.backgroundColor)
    }

    override fun refresh() {
        asciiPanel.repaintCharacters(bounds)
    }

    private fun writableBounds(): Rectangle {
        // upon instantiation, before this object is told its bounds by its container, we might have bounds of INT_MAX
        // width and height. In this case, we really just want the bounds that are actually writable. For our purposes,
        // this is the intersection of our bounds and our panel's bounds
        return Rectangle(
            super.bounds.x,
            super.bounds.y,
            min(super.bounds.width, asciiPanel.widthInCharacters - super.bounds.x),
            min(super.bounds.height, asciiPanel.heightInCharacters - super.bounds.y),
        )
    }

    open fun write(character: Char, foregroundColor: Color, backgroundColor: Color, x: Int, y: Int) {
        asciiPanel.write(character, contentBounds.x + x, contentBounds.y + y, foregroundColor, backgroundColor)
    }
}
