package com.sletmoe.kotile.tiles

import com.badlogic.gdx.graphics.Color
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class StaticTileTest : FunSpec({
    test("tint defaults to white so the sprite is drawn unmodified") {
        StaticTile(2, 5).tint shouldBe Color.WHITE
    }

    test("an explicit tint is retained") {
        val tile = StaticTile(0, 0, Color.RED)
        tile.tint shouldBe Color.RED
    }
})
