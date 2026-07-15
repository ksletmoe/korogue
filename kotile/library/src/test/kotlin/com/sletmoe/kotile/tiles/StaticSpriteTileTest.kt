package com.sletmoe.kotile.tiles

import com.badlogic.gdx.graphics.Color
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class StaticSpriteTileTest : FunSpec({
    test("tint defaults to white so the sprite is drawn unmodified") {
        StaticSpriteTile(2, 5).tint shouldBe Color.WHITE
    }

    test("an explicit tint is retained") {
        val tile = StaticSpriteTile(0, 0, Color.RED)
        tile.tint shouldBe Color.RED
    }

    test("flipX and flipY default to false") {
        val tile = StaticSpriteTile(0, 0)
        tile.flipX shouldBe false
        tile.flipY shouldBe false
    }

    test("an explicit flipX/flipY is retained") {
        val tile = StaticSpriteTile(0, 0, flipX = true, flipY = true)
        tile.flipX shouldBe true
        tile.flipY shouldBe true
    }
})
