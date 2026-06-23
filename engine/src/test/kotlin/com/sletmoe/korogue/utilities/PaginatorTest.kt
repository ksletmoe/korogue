package com.sletmoe.korogue.utilities

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class PaginatorTest : FunSpec({

    test("splits items into pages of at most pageSize") {
        val p = Paginator((0..4).toList(), pageSize = 2)
        p.pageCount shouldBe 3
        p.currentPage shouldBe listOf(0, 1)
    }

    test("an exact multiple does not leave a trailing empty page") {
        Paginator((0..3).toList(), pageSize = 2).pageCount shouldBe 2
    }

    test("an empty list still has one (empty) page") {
        val p = Paginator(emptyList<String>(), pageSize = 3)
        p.pageCount shouldBe 1
        p.currentPage shouldBe emptyList()
    }

    test("next/previous walk the pages and report whether they moved") {
        val p = Paginator((0..4).toList(), pageSize = 2)
        p.isFirstPage shouldBe true
        p.next() shouldBe true
        p.currentPage shouldBe listOf(2, 3)
        p.next() shouldBe true
        p.currentPage shouldBe listOf(4) // short last page
        p.isLastPage shouldBe true
        p.next() shouldBe false // already at the end
        p.page shouldBe 2
        p.previous() shouldBe true
        p.page shouldBe 1
    }

    test("toPage clamps into range; pageSize is coerced to at least 1") {
        val p = Paginator((0..4).toList(), pageSize = 0)
        p.pageSize shouldBe 1
        p.pageCount shouldBe 5
        p.toPage(99)
        p.page shouldBe 4
        p.toPage(-3)
        p.page shouldBe 0
    }
})
