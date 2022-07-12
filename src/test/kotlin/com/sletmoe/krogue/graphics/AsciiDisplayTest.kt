package com.sletmoe.krogue.graphics

import asciiPanel.AsciiCharacterData
import com.sletmoe.krogue.test.utilities.KrogueArb
import com.sletmoe.krogue.test.utilities.UserInterfaceStub
import com.sletmoe.krogue.test.utilities.haveCharacterData
import com.sletmoe.krogue.utilities.lastX
import com.sletmoe.krogue.utilities.lastY
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.inspectors.forAll
import io.kotest.matchers.should
import io.kotest.property.Arb
import io.kotest.property.arbitrary.positiveInt
import io.kotest.property.checkAll
import java.awt.Color
import java.awt.Rectangle

class AsciiDisplayTest : FunSpec({
    test("fill") {
        checkAll(KrogueArb.asciiCharacterData, Arb.positiveInt(max = 50), Arb.positiveInt(max = 50)) { characterData, width, height ->
            val ui = UserInterfaceStub(width, height)
            val asciiDisplay = AsciiDisplay(ui)
            asciiDisplay.fill(characterData)

            ui.asciiPanel.characters.flatten().forAll { it should haveCharacterData(characterData) }
        }
    }

    test("writing characters") {
        checkAll(KrogueArb.asciiCharacterDataGrid) { charDataGrid ->
            val ui = UserInterfaceStub(charDataGrid.width, charDataGrid.height)
            val asciiDisplay = AsciiDisplay(ui)

            charDataGrid.forEachIndexed { coordinate, charData ->
                asciiDisplay.write(
                    charData.character,
                    charData.foregroundColor,
                    charData.backgroundColor,
                    coordinate.x,
                    coordinate.y,
                )
            }

            charDataGrid.forEachIndexed { coordinate, charData ->
                ui.asciiPanel.characters[coordinate.x][coordinate.y] should haveCharacterData(charData)
            }
        }
    }
    
    context("with a border") {
        data class BorderTestSpec(
            val description: String, val border: AsciiBorder, val displayFillCharacter: AsciiCharacterData
        )
        val displayFillCharacter = AsciiCharacterData('#', Color.blue, Color.black)

        withData<BorderTestSpec>(
            { it.description },
            listOf(
                BorderTestSpec(
                    "fully enclosing the display",
                    Borders.dashed(Color.white, Color.white),
                    displayFillCharacter,
                ),
                BorderTestSpec(
                    "without a top edge",
                    Borders.dashed(Color.white, Color.white).withoutTop(),
                    displayFillCharacter,
                ),
                BorderTestSpec(
                    "without a right edge",
                    Borders.dashed(Color.white, Color.white).withoutRight(),
                    displayFillCharacter,
                ),
                BorderTestSpec(
                    "without a bottom edge",
                    Borders.dashed(Color.white, Color.white).withoutBottom(),
                    displayFillCharacter,
                ),
                BorderTestSpec(
                    "without a left edge",
                    Borders.dashed(Color.white, Color.white).withoutLeft(),
                    displayFillCharacter,
                ),
            )
        ) { (_, border, fillCharacter) ->
            val emptyCharacter = AsciiCharacterData(' ', Color.white, Color.black)

            val ui = UserInterfaceStub(20, 20)
            ui.asciiPanel.clear(emptyCharacter.character, emptyCharacter.foregroundColor, emptyCharacter.backgroundColor)

            val display = AsciiDisplay.create(ui) {
                defaultFillCharacter = emptyCharacter
                bounds = Rectangle(2, 2, 16, 16)
                this.border = border
            }
            display.fill(fillCharacter)

            checkAll(KrogueArb.coordinates(Rectangle(0, 0, ui.widthInCharacters, ui.heightInCharacters))) { point ->
                val x = point.x
                val y = point.y

                val panelCharacter = ui.asciiPanel.characters[x][y]
                if (display.bounds.contains(x, y)) {
                    when (x) {
                        display.bounds.x -> {
                            when (y) {
                                display.bounds.y -> {
                                    panelCharacter should haveCharacterData(border.topLeftCorner ?: fillCharacter)
                                }
                                display.bounds.lastY -> {
                                    panelCharacter should haveCharacterData(border.bottomLeftCorner ?: fillCharacter)
                                }
                                else -> {
                                    panelCharacter should haveCharacterData(border.leftEdge ?: fillCharacter)
                                }
                            }
                        }
                        display.bounds.lastX -> {
                            when (y) {
                                display.bounds.y -> {
                                    panelCharacter should haveCharacterData(border.topRightCorner ?: fillCharacter)
                                }
                                display.bounds.lastY -> {
                                    panelCharacter should haveCharacterData(border.bottomRightCorner ?: fillCharacter)
                                }
                                else -> {
                                    panelCharacter should haveCharacterData(border.rightEdge ?: fillCharacter)
                                }
                            }
                        }
                        else -> {
                            when (y) {
                                display.bounds.y -> panelCharacter should haveCharacterData(border.topEdge ?: fillCharacter)
                                display.bounds.lastY -> panelCharacter should haveCharacterData(border.bottomEdge ?: fillCharacter)
                                else -> panelCharacter should haveCharacterData(fillCharacter)
                            }
                        }
                    }
                } else {
                    panelCharacter should haveCharacterData(emptyCharacter)
                }
            }
        }
    }
})
