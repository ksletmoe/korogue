package com.sletmoe.korogue.ui

import com.badlogic.gdx.Input
import com.badlogic.gdx.graphics.Color
import com.sletmoe.korogue.utilities.IntRect
import com.sletmoe.korogue.utilities.Paginator
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class PagedTextListTest : FunSpec({

    val width = 30
    val height = 3

    /** A continue-prompt footer shown only while more pages follow — a stand-in for a consumer's choice. */
    fun continuePrompt(p: Paginator<String>): String? = if (p.hasNext) "--more--" else null

    fun list(
        rows: List<String>,
        footer: ((Paginator<String>) -> String?)? = ::continuePrompt,
        onDismiss: (() -> Unit)? = null,
    ) = PagedTextList(IntRect(0, 0, width, height), rows, footer = footer, onDismiss = onDismiss)

    /** The non-empty visible rows of [list], top to bottom, trimmed of padding. */
    fun PagedTextList.visibleRows(): List<String> {
        val surface = RecordingSurface(width, height)
        draw(surface)
        return (0 until height).map { y ->
            (0 until width).mapNotNull { x -> surface.top(x, y)?.glyph }.joinToString("").trimEnd()
        }.filter { it.isNotEmpty() }
    }

    test("with a footer, the bottom row is reserved so a page holds height-1 rows") {
        val l = list(listOf("r0", "r1", "r2", "r3", "r4")) // height 3, footer -> 2 rows/page
        l.pages.pageCount shouldBe 3
        l.visibleRows() shouldBe listOf("r0", "r1", "--more--")
    }

    test("with no footer the full box height is used") {
        val l = list(listOf("a", "b", "c"), footer = null)
        l.pages.pageCount shouldBe 1
        l.visibleRows() shouldBe listOf("a", "b", "c")
    }

    test("the footer text is whatever the caller's lambda returns") {
        val l = PagedTextList(IntRect(0, 0, width, height), listOf("r0", "r1", "r2"), footer = { "page ${it.page + 1}/${it.pageCount}" })
        l.visibleRows() shouldBe listOf("r0", "r1", "page 1/2")
    }

    test("advance and back keys turn the page; the last page's footer can blank out") {
        val l = list(listOf("r0", "r1", "r2", "r3", "r4"))
        l.handleKey(Input.Keys.SPACE) shouldBe true
        l.pages.page shouldBe 1
        l.visibleRows() shouldBe listOf("r2", "r3", "--more--")
        l.handleKey(Input.Keys.SPACE)
        l.visibleRows() shouldBe listOf("r4") // last page: continuePrompt returns null
        l.handleKey(Input.Keys.PAGE_UP)
        l.pages.page shouldBe 1
    }

    test("advancing past the last page invokes onDismiss without overrunning") {
        var dismissed = 0
        val l = list(listOf("r0", "r1", "r2", "r3", "r4"), onDismiss = { dismissed++ })
        repeat(2) { l.handleKey(Input.Keys.SPACE) } // to last page
        l.pages.isLastPage shouldBe true
        l.handleKey(Input.Keys.SPACE)
        l.pages.page shouldBe 2
        dismissed shouldBe 1
    }

    test("unbound keys are not consumed") {
        list(listOf("r0", "r1")).handleKey(Input.Keys.LEFT) shouldBe false
    }

    test("rows render in the caller's foreground color") {
        val l = PagedTextList(IntRect(0, 0, width, height), listOf("r0"), fg = Color.GREEN, footer = null)
        val surface = RecordingSurface(width, height)
        l.draw(surface)
        surface.top(0, 0)!!.fg shouldBe Color.GREEN
    }
})
