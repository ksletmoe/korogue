package com.sletmoe.krogue.ui

import com.badlogic.gdx.graphics.Color
import com.sletmoe.krogue.utilities.IntRect
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe

class RegionSurfaceTest : FunSpec({

    test("translates local coordinates to the rect's origin and offsets z") {
        val recording = RecordingSurface(20, 20)
        val region = RegionSurface(recording, IntRect(5, 3, 10, 10), zOffset = 100)

        region.put(2, 1, z = 4, glyph = '@', fg = Color.WHITE, bg = Color.BLACK)

        val cell = recording.puts.single()
        cell.x shouldBe 7 // 5 + 2
        cell.y shouldBe 4 // 3 + 1
        cell.z shouldBe 104 // 100 + 4
    }

    test("reports its own size and clips writes outside the rect") {
        val recording = RecordingSurface(20, 20)
        val region = RegionSurface(recording, IntRect(5, 5, 4, 3))

        region.width shouldBe 4
        region.height shouldBe 3
        region.put(4, 0, 0, '#', Color.WHITE, Color.BLACK) // x == width -> out
        region.put(0, 3, 0, '#', Color.WHITE, Color.BLACK) // y == height -> out
        region.put(-1, 0, 0, '#', Color.WHITE, Color.BLACK) // negative -> out

        recording.puts.shouldBeEmpty()
    }

    test("dim scales rgb but leaves alpha untouched") {
        val recording = RecordingSurface(10, 10)
        val region = RegionSurface(recording, IntRect(0, 0, 10, 10), dim = 0.5f)

        region.put(0, 0, 0, '.', Color(1f, 0.8f, 0.4f, 1f), Color(0.2f, 0.2f, 0.2f, 1f))

        val cell = recording.puts.single()
        cell.fg.r shouldBe (0.5f plusOrMinus 1e-4f)
        cell.fg.g shouldBe (0.4f plusOrMinus 1e-4f)
        cell.fg.a shouldBe 1f
        cell.bg.r shouldBe (0.1f plusOrMinus 1e-4f)
    }

    test("dim of 1.0 passes the original color through unchanged") {
        val recording = RecordingSurface(10, 10)
        val region = RegionSurface(recording, IntRect(0, 0, 10, 10))
        val fg = Color(0.3f, 0.6f, 0.9f, 1f)

        region.put(0, 0, 0, '.', fg, Color.BLACK)

        recording.puts.single().fg shouldBe fg
    }
})
