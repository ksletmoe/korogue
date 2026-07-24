package com.sletmoe.kotile.display.ascii

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe

/**
 * Pure (GL-free) tests for the CP437 → Unicode mapping the freetype glyph source
 * uses. Runs everywhere; locks the table entries a vector source depends on to
 * rasterise the right glyph per code-page slot (krogue-9x7.2).
 */
class Cp437Test : FunSpec({

    test("printable ASCII slots map to themselves") {
        for (slot in 0x20..0x7E) {
            Cp437.toUnicode(slot) shouldBe slot
        }
    }

    test("the CP437 special glyphs map to their Unicode code points") {
        // Spot-check the ones a roguelike actually leans on.
        Cp437.toUnicode(1) shouldBe 0x263A // ☺ white smiling face
        Cp437.toUnicode(3) shouldBe 0x2665 // ♥ heart
        Cp437.toUnicode(0x7F) shouldBe 0x2302 // ⌂ house
        Cp437.toUnicode(176) shouldBe 0x2591 // ░ light shade
        Cp437.toUnicode(178) shouldBe 0x2593 // ▓ dark shade
        Cp437.toUnicode(179) shouldBe 0x2502 // │ box vertical
        Cp437.toUnicode(196) shouldBe 0x2500 // ─ box horizontal
        Cp437.toUnicode(201) shouldBe 0x2554 // ╔ box double down-right
        Cp437.toUnicode(219) shouldBe 0x2588 // █ full block
        Cp437.toUnicode(224) shouldBe 0x03B1 // α greek alpha
        Cp437.toUnicode(241) shouldBe 0x00B1 // ± plus-minus
    }

    test("slots 0 and 255 are blank (space)") {
        // CP437 leaves both blank; mapping them to a rendered glyph would be wrong.
        Cp437.toUnicode(0) shouldBe 0x20
        Cp437.toUnicode(255) shouldBe 0x20
    }

    test("out-of-range slots yield -1") {
        Cp437.toUnicode(-1) shouldBe -1
        Cp437.toUnicode(256) shouldBe -1
        Cp437.toUnicode(9999) shouldBe -1
    }

    test("the repertoire covers the glyphs the font must supply") {
        val cps = Cp437.repertoire.codePoints().toArray().toList()
        // The distinctive CP437 ranges a code-oriented font often omits (why Ubuntu Mono was chosen).
        cps shouldContain 0x2588 // full block
        cps shouldContain 0x2500 // box horizontal
        cps shouldContain 0x03B1 // greek alpha
        // Distinct code points only (space is shared by slots 0/32/255), so fewer than 256.
        cps.size shouldBe cps.toSet().size
        cps.size shouldBeGreaterThan 200
    }
})
