package com.sletmoe.korogue.algorithms.geometry

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class DirectionalMissileGlyphTest : FunSpec({

    test("a purely horizontal delta is '-', either direction") {
        directionalMissileGlyph(4, 0) shouldBe '-'
        directionalMissileGlyph(-4, 0) shouldBe '-'
    }

    test("a purely vertical delta is '|', either direction") {
        directionalMissileGlyph(0, 4) shouldBe '|'
        directionalMissileGlyph(0, -4) shouldBe '|'
    }

    test("a same-sign diagonal (top-left<->bottom-right) is '\\'") {
        directionalMissileGlyph(3, 3) shouldBe '\\'
        directionalMissileGlyph(-3, -3) shouldBe '\\'
    }

    test("an opposite-sign diagonal (bottom-left<->top-right) is '/'") {
        directionalMissileGlyph(3, -3) shouldBe '/'
        directionalMissileGlyph(-3, 3) shouldBe '/'
    }

    test("a zero delta falls back to the horizontal glyph") {
        directionalMissileGlyph(0, 0) shouldBe '-'
    }

    test("a non-perfect diagonal still buckets by sign, not exact slope") {
        directionalMissileGlyph(1, 5) shouldBe '\\'
        directionalMissileGlyph(5, -1) shouldBe '/'
    }

    test("custom glyphs are honored") {
        directionalMissileGlyph(4, 0, horizontal = '=') shouldBe '='
        directionalMissileGlyph(0, 4, vertical = '!') shouldBe '!'
        directionalMissileGlyph(3, 3, diagonalDown = 'v') shouldBe 'v'
        directionalMissileGlyph(3, -3, diagonalUp = '^') shouldBe '^'
    }
})
