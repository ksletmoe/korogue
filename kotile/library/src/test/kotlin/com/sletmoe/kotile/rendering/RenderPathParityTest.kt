package com.sletmoe.kotile.rendering

import com.sletmoe.kotile.display.ascii.AsciiTile
import com.sletmoe.kotile.display.ascii.AsciiTileWindow
import com.sletmoe.kotile.tiles.SpriteTile
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Guards the symmetry of kotile's two parallel render paths (ADR-0028):
 * [TileRenderer] (sprite) and [AsciiTileWindow] (ascii). The docs treat them as
 * siblings, so a member added to one is expected to be mirrored on the other —
 * or listed below as deliberately path-specific.
 *
 * These reflect over the class objects only; nothing is instantiated, so no
 * OpenGL context is needed and they always run (unlike the
 * [com.sletmoe.kotile.HeadlessGl]-guarded rendering tests).
 *
 * The two paths drifted once already (krogue-0y8): the sprite path had grown
 * `windowWidth`/`windowHeight`/`onResize` against the ascii path's
 * `widthInTiles`/`heightInTiles`/`resize`, and was missing clear/fill/query
 * entirely. This test fails if that starts happening again.
 */
class RenderPathParityTest : FunSpec({

    /**
     * Members that legitimately exist on only one path, with the reason. Anything
     * else that appears on one side and not the other is drift, and fails below.
     */
    val asciiOnly =
        setOf(
            // Glyph-and-color content is the ascii path's whole point; the sprite path
            // has no character concept to write a string of.
            "drawText",
        )
    val spriteOnly = emptySet<String>()

    /**
     * Public, non-synthetic member names, normalised so Kotlin property getters
     * (`getWidthInTiles`) read as the property (`widthInTiles`). `$`-bearing names
     * are Kotlin's generated default-argument bridges, not API.
     */
    fun apiNamesOf(type: Class<*>): Set<String> =
        type.declaredMethods
            .filter { method: Method ->
                Modifier.isPublic(method.modifiers) && !method.isSynthetic && !method.isBridge && '$' !in method.name
            }
            .map { method ->
                method.name
                    .removePrefix("get")
                    .replaceFirstChar { it.lowercase() }
            }
            .toSet()

    val spriteApi = apiNamesOf(TileRenderer::class.java)
    val asciiApi = apiNamesOf(AsciiTileWindow::class.java)

    test("the sprite and ascii paths expose the same vocabulary") {
        (spriteApi - spriteOnly) shouldBe (asciiApi - asciiOnly)
    }

    test("both paths carry the shared render-path vocabulary") {
        val shared =
            listOf(
                "widthInTiles", "heightInTiles", "tileWidthPx", "tileHeightPx", "layout",
                "resize", "drawTile", "fill", "clearTile", "clear", "clearLayer",
                "topTileAt", "render", "asLayer", "dispose",
            )
        spriteApi shouldContainAll shared
        asciiApi shouldContainAll shared
    }

    test("the sprite path does not reintroduce its pre-1.0 names") {
        // Renamed to the ascii path's spelling in krogue-0y8. Named explicitly so a
        // revert is caught by name rather than only as a vocabulary mismatch.
        spriteApi.intersect(setOf("windowWidth", "windowHeight", "onResize")) shouldBe emptySet()
    }

    // Both paths take one sealed supertype (SpriteTile / AsciiTile) so callers don't
    // pick an overload per branch -- and so a new branch doesn't need a new overload.
    // See ADR-0027 for the hierarchies.
    fun drawTileContentTypesOf(type: Class<*>): Set<Class<*>> =
        type.declaredMethods
            .filter { it.name == "drawTile" && Modifier.isPublic(it.modifiers) && !it.isSynthetic && '$' !in it.name }
            .map { it.parameterTypes.last() }
            .toSet()

    test("sprite drawTile takes the sealed SpriteTile, not one overload per branch") {
        drawTileContentTypesOf(TileRenderer::class.java) shouldBe setOf(SpriteTile::class.java)
    }

    test("ascii drawTile takes the sealed AsciiTile, not one overload per branch") {
        drawTileContentTypesOf(AsciiTileWindow::class.java) shouldBe setOf(AsciiTile::class.java)
    }
})
