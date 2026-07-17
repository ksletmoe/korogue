package com.sletmoe.korogue.registry

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class RegistryTest : FunSpec({

    // Registry is documented as an *immutable* lookup, and the built-in systems now take
    // `Registry<T>` directly (krogue-cjv). Registry.of(Map) must snapshot its source, or a caller
    // that keeps and mutates the map after construction would silently change what resolve()/ids
    // return. (The Map overload originally aliased instead of copying — this guards the fix.)
    test("Registry.of(Map) snapshots its source; later mutation of the caller's map does not leak in") {
        val source = mutableMapOf("a" to 1, "b" to 2)
        val registry = Registry.of(source)

        source["a"] = 99 // mutate an existing entry
        source["c"] = 3 // add after construction
        source.remove("b") // remove after construction

        registry.resolve("a") shouldBe 1 // original value, not 99
        registry.resolve("b") shouldBe 2 // still present despite the removal
        ("c" in registry) shouldBe false // the addition did not leak in
        registry.ids shouldBe setOf("a", "b")
    }

    test("Registry.of(vararg) builds the same lookup") {
        val registry = Registry.of("x" to 10, "y" to 20)

        registry.resolve("x") shouldBe 10
        ("y" in registry) shouldBe true
        ("z" in registry) shouldBe false
        registry.ids shouldBe setOf("x", "y")
    }
})
