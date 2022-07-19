package com.sletmoe.krogue.ui

import asciiPanel.AsciiCharacterData
import asciiPanel.AsciiFont
import asciiPanel.AsciiPanel
import com.sletmoe.krogue.graphics.AsciiSubpanelGroup
import com.sletmoe.krogue.graphics.repaintCharacters
import com.sletmoe.krogue.utilities.withLock
import java.awt.Color
import java.awt.Dimension
import java.awt.Rectangle
import java.util.concurrent.locks.ReentrantLock
import javax.swing.JPanel

class ResizableAsciiPanel(dimensionsInCharacters: Dimension, font: AsciiFont, backgroundColor: Color) : JPanel() {
    private var asciiPanel = AsciiPanel(dimensionsInCharacters.width, dimensionsInCharacters.height, font)
    private var subpanelGroup: AsciiSubpanelGroup? = null
    private val resizeMutex = ReentrantLock()

    var backgroundColor: Color
        get() = background
        set(value) {
            background = value
        }

    val widthInCharacters: Int
        get() = asciiPanel.widthInCharacters

    val heightInCharacters: Int
        get() = asciiPanel.heightInCharacters

    val displayBounds: Rectangle
        get() = Rectangle(0, 0, asciiPanel.widthInCharacters, asciiPanel.heightInCharacters)

    init {
        super.setBounds(
            0,
            0,
            dimensionsInCharacters.width * font.width,
            dimensionsInCharacters.height * font.height
        )
        background = backgroundColor

        add(asciiPanel)
    }

    fun setSubpanelGroup(group: AsciiSubpanelGroup) {
        val componentMinSize = group.minimumSize
        if (displayBounds.contains(Rectangle(0, 0, componentMinSize.width, componentMinSize.height))) {
            group.bounds = Rectangle(0, 0, widthInCharacters, heightInCharacters)
            subpanelGroup = group
            subpanelGroup?.refresh()
        } else {
            throw RuntimeException(
                "AsciiSubpanelComponent with minimumSize $componentMinSize does not fit in AsciiPanel with bounds $displayBounds"
            )
        }
    }

    override fun setBounds(newX: Int, newY: Int, newWidth: Int, newHeight: Int) {
        super.setBounds(newX, newY, newWidth, newHeight)

        resizeMutex.withLock {
            remove(asciiPanel)
            val oldAsciiPanel = asciiPanel
            val asciiPanelDimensionsInChars = Dimension(
                newWidth / oldAsciiPanel.asciiFont.width, newHeight / oldAsciiPanel.asciiFont.height
            )
            asciiPanel = AsciiPanel(
                asciiPanelDimensionsInChars.width, asciiPanelDimensionsInChars.height, oldAsciiPanel.asciiFont
            )
            add(asciiPanel)
            val widthPadding = newWidth - asciiPanel.width
            val heightPadding = newHeight - asciiPanel.height

            asciiPanel.setBounds(
                newX + (widthPadding / 2),
                newY + (heightPadding / 2),
                asciiPanel.width,
                asciiPanel.height,
            )
            preferredSize = asciiPanel.size
            subpanelGroup?.let { it.bounds = displayBounds }
        }
    }

    fun refresh() {
        subpanelGroup?.refresh()
    }

    fun repaintCharacters(bounds: Rectangle) {
        asciiPanel.repaintCharacters(bounds)
    }

    fun drawCharacter(character: Char, foregroundColor: Color, backgroundColor: Color, x: Int, y: Int) {
        // if we try to write in the middle of resizing, we'll potentially attempt to write outside of bounds.
        resizeMutex.withLock {
            if (displayBounds.contains(x, y)) {
                asciiPanel.write(character, x, y, foregroundColor, backgroundColor)
            }
        }
    }

    fun drawCharacter(characterData: AsciiCharacterData, x: Int, y: Int) {
        // if we try to write in the middle of resizing, we'll potentially attempt to write outside of bounds.
        resizeMutex.withLock {
            if (displayBounds.contains(x, y)) {
                asciiPanel.write(characterData, x, y)
            }
        }
    }
}
