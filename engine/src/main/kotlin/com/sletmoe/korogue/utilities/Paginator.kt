package com.sletmoe.korogue.utilities

/**
 * A headless, generic page model: splits [items] into pages of at most [pageSize] and tracks which
 * page is current, with forward/back navigation. Pure logic — no rendering, no input handling, no
 * libGDX — so it can drive any presentation (a [PagedTextList], a custom widget, or a test) and the
 * caller stays in full control of how a page looks and which keys turn it.
 *
 * The caller chooses [pageSize] (e.g. a box height minus rows reserved for a footer). There is
 * always at least one page, even when [items] is empty.
 */
class Paginator<T>(
    private val items: List<T>,
    pageSize: Int,
) {
    /** Maximum items shown per page; coerced to at least 1 so navigation always makes progress. */
    val pageSize: Int = pageSize.coerceAtLeast(1)

    /** Total number of pages (>= 1). */
    val pageCount: Int = if (items.isEmpty()) 1 else (items.size + this.pageSize - 1) / this.pageSize

    /** Index of the current page, in `[0, pageCount)`. */
    var page: Int = 0
        private set

    /** The items on the current page (may be shorter than [pageSize] on the last page). */
    val currentPage: List<T>
        get() =
            items.subList(
                (page * pageSize).coerceAtMost(items.size),
                ((page + 1) * pageSize).coerceAtMost(items.size),
            )

    val isFirstPage: Boolean get() = page == 0
    val isLastPage: Boolean get() = page == pageCount - 1
    val hasNext: Boolean get() = !isLastPage
    val hasPrevious: Boolean get() = !isFirstPage

    /** Advances to the next page if there is one; returns true if it moved, false if already at the end. */
    fun next(): Boolean =
        if (hasNext) {
            page++
            true
        } else {
            false
        }

    /** Steps back one page if there is one; returns true if it moved, false if already at the start. */
    fun previous(): Boolean =
        if (hasPrevious) {
            page--
            true
        } else {
            false
        }

    /** Jumps to [index], clamped into `[0, pageCount)`. */
    fun toPage(index: Int) {
        page = index.coerceIn(0, pageCount - 1)
    }
}
