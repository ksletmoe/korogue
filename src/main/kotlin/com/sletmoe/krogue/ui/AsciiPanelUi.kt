package com.sletmoe.krogue.ui

import asciiPanel.AsciiCharacterData
import asciiPanel.AsciiFont
import asciiPanel.AsciiPanel
import com.sletmoe.krogue.graphics.AsciiSubpanelComponent
import com.sletmoe.krogue.graphics.repaintCharacters
import java.awt.Color
import java.awt.Dimension
import java.awt.Rectangle
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.KeyListener
import java.awt.event.MouseEvent
import java.awt.event.MouseListener
import java.util.LinkedList
import java.util.Queue
import javax.swing.JFrame

class AsciiPanelUi(
    name: String?, size: Dimension, font: AsciiFont? = null
) : JFrame(name), UserInterface, KeyListener, MouseListener {
    private val inputQueue: Queue<InputEvent> = LinkedList()
    val asciiPanel = AsciiPanel(size.width, size.height, font)
    private var contents: AsciiSubpanelComponent? = null
    override val widthInCharacters: Int
        get() = asciiPanel.widthInCharacters
    override val heightInCharacters: Int
        get() = asciiPanel.heightInCharacters
    private val displayBounds: Rectangle
        get() = Rectangle(0, 0, widthInCharacters, heightInCharacters)

    init {
        add(asciiPanel)
//        addComponentListener(
//            object : ComponentAdapter() {
//                override fun componentResized(e: ComponentEvent?) {
//                    terminal.size = e!!.component.size
//                }
//            }
//        )
        addKeyListener(this)
        addMouseListener(this)
        isVisible = true
        defaultCloseOperation = EXIT_ON_CLOSE
        pack() //
        repaint()
    }

    fun setContents(component: AsciiSubpanelComponent) {
        val componentMinSize = component.minimumSize
        if (displayBounds.contains(Rectangle(0, 0, componentMinSize.width, componentMinSize.height))) {
            component.bounds = Rectangle(0, 0, widthInCharacters, heightInCharacters)
            contents = component
            refresh()
        } else {
            throw RuntimeException(
                "AsciiSubpanelComponent with minimumSize $componentMinSize does not fit in AsciiPanel with bounds $displayBounds"
            )
        }
    }

    override fun repaintCharacters(bounds: Rectangle) {
        asciiPanel.repaintCharacters(bounds)
    }

    override fun drawCharacter(character: Char, foregroundColor: Color, backgroundColor: Color, x: Int, y: Int) {
        asciiPanel.write(character, x, y, foregroundColor, backgroundColor)
    }

    override fun drawCharacter(characterData: AsciiCharacterData, x: Int, y: Int) {
        asciiPanel.write(characterData, x, y)
    }

    override fun getInput(): InputEvent? = inputQueue.poll()

    override fun refresh() {
        contents?.refresh()
    }

    override fun keyPressed(e: KeyEvent) {
        inputQueue.add(e)
    }

    override fun keyReleased(e: KeyEvent) {}
    override fun keyTyped(e: KeyEvent) {}
    override fun mouseClicked(e: MouseEvent) {
        inputQueue.add(e)
    }

    override fun mouseEntered(e: MouseEvent) {}
    override fun mouseExited(e: MouseEvent) {}
    override fun mousePressed(e: MouseEvent) {}
    override fun mouseReleased(e: MouseEvent) {}
}
