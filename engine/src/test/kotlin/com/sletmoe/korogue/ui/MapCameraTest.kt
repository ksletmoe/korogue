package com.sletmoe.korogue.ui

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * [MapCamera] owns the origin clamp and zone<->screen conversion [MapPanel] used to inline, so the
 * panel and any overlay drawing into the same viewport share one source of truth. These pin the
 * exact clamping the panel relied on.
 */
class MapCameraTest : FunSpec({

    test("centres the viewport on the focus cell away from the zone edges") {
        val camera =
            MapCamera.centeredOn(
                focusX = 10,
                focusY = 10,
                width = 6,
                height = 6,
                zoneWidth = 40,
                zoneHeight = 40,
            )

        camera.originX shouldBe 7 // 10 - 6/2
        camera.originY shouldBe 7
    }

    test("slides the window back inside the zone so it never shows past the far edge") {
        val camera =
            MapCamera.centeredOn(
                focusX = 39,
                focusY = 39,
                width = 6,
                height = 6,
                zoneWidth = 40,
                zoneHeight = 40,
            )

        camera.originX shouldBe 34 // clamped to zoneWidth - width
        camera.originY shouldBe 34
    }

    test("pins to the origin near the near edge and when the zone is smaller than the viewport") {
        MapCamera.centeredOn(0, 0, 6, 6, 40, 40).let {
            it.originX shouldBe 0
            it.originY shouldBe 0
        }
        MapCamera.centeredOn(2, 2, 6, 6, 4, 4).let {
            it.originX shouldBe 0 // zoneWidth - width is negative -> max(0, ...) pins to 0
            it.originY shouldBe 0
        }
    }

    test("converts between zone and screen coordinates and back") {
        val camera = MapCamera.centeredOn(10, 10, 6, 6, 40, 40) // origin (7, 7)

        camera.zoneX(0) shouldBe 7
        camera.zoneY(2) shouldBe 9
        camera.screenX(7) shouldBe 0
        camera.screenY(9) shouldBe 2
        camera.zoneX(camera.screenX(12)) shouldBe 12 // round-trips
    }

    test("containsScreen accepts in-viewport cells and rejects out-of-bounds ones") {
        val camera = MapCamera.centeredOn(10, 10, 6, 6, 40, 40)

        camera.containsScreen(0, 0) shouldBe true
        camera.containsScreen(5, 5) shouldBe true
        camera.containsScreen(6, 0) shouldBe false // width is exclusive
        camera.containsScreen(-1, 0) shouldBe false
    }
})
