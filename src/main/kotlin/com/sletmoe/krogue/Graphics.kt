package com.sletmoe.krogue

import asciiPanel.AsciiPanel
import java.awt.Point
import kotlin.math.max
import kotlin.math.min

interface Camera {
    fun lookAt(world: World, xFocus: Int, yFocus: Int)
}

class AsciiCamera(
    private val terminal: AsciiPanel,
    private val screenWidth: Int,
    private val screenHeight: Int,
    private val mapWidth: Int,
    private val mapHeight: Int
) : Camera {
    override fun lookAt(world: World, xFocus: Int, yFocus: Int) {
        val origin = getCameraOrigin(xFocus, yFocus);

        (0 until mapWidth).forEach { x ->
            (0 until mapHeight).forEach { y ->
                val tile = world.tileAt(origin.x + x, origin.y + y)
                terminal.write(tile.glyph, x, y, tile.color, tile.backgroundColor)
            }
        }

        terminal.write(world.playableCharacter.glyph, world.playableCharacter.x, world.playableCharacter.y)

        int spx;
        int spy;
        for(Creature entity : world.creatures)
        {
            spx = entity.getX() - origin.x;
            spy = entity.getY() - origin.y;

            if ((spx >= 0 && spx < screenWidth) && (spy >= 0 && spy < screenHeight)) {
                terminal.write(entity.getGlyph(), spx, spy, entity.getColor(), world.getTile(entity.getX(), entity.getY()).getBackgroundColor());
            }
        }
    }
    }

    fun getCameraOrigin(focusX: Int, focusY: Int): Point {
        val originX = max(0, min(focusX - screenWidth / 2, mapWidth - screenWidth))
        val originY = max(0, min(focusY - screenHeight / 2, mapHeight - screenHeight))

        return Point(originX, originY)
    }
}
