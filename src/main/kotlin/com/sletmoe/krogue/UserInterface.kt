package com.sletmoe.krogue

import asciiPanel.AsciiPanel
import mu.KotlinLogging
import java.awt.Color
import java.awt.Rectangle
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.KeyListener
import java.awt.event.MouseEvent
import java.awt.event.MouseListener
import java.util.LinkedList
import java.util.Queue
import javax.swing.JFrame

class UserInterface(
    name: String?, screenWidth: Int, screenHeight: Int
) : JFrame(name), KeyListener, MouseListener {
    private val inputQueue: Queue<InputEvent> = LinkedList()
    private val gameViewArea = Rectangle(screenWidth, screenHeight)
    private val terminal = AsciiPanel(screenWidth, screenHeight)

    companion object {
        private val logging = KotlinLogging.logger {}
    }

    init {
        super.add(terminal)
        super.addKeyListener(this)
        super.addMouseListener(this)
        super.setSize(screenWidth * 9, screenHeight * 16)
        super.setVisible(true)
        super.setDefaultCloseOperation(EXIT_ON_CLOSE)
        super.repaint()
    }

    val nextInput: InputEvent?
        get() = inputQueue.poll()

    fun clear() {
        terminal.clear()
    }

    fun refresh() {
        terminal.repaint()
    }

    fun drawChar(glyph: Char, x: Int, y: Int, color: Color?) {
        terminal.write(glyph, x, y, color)
    }

    override fun keyPressed(e: KeyEvent) {
        logging.debug { "received keyPressed event $e" }
        inputQueue.add(e)
    }

    fun drawMessages() {}
    override fun keyReleased(e: KeyEvent) {}
    override fun keyTyped(e: KeyEvent) {}
    override fun mouseClicked(e: MouseEvent) {
        logging.debug { "received mouseClick event $e" }
        inputQueue.add(e)
    }

    override fun mouseEntered(e: MouseEvent) {}
    override fun mouseExited(e: MouseEvent) {}
    override fun mousePressed(e: MouseEvent) {}
    override fun mouseReleased(e: MouseEvent) {}
}
