package com.sletmoe.krogue.utilities

fun <T> initialize(
    instance: T,
    init: T.() -> Unit,
): T {
    instance.init()
    return instance
}
