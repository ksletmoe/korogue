package com.sletmoe.krogue.test.utilities

import io.kotest.inspectors.forAll

inline fun IntRange.forAll(fn: (Int) -> Unit): IntRange = apply { toList().forAll(fn) }
