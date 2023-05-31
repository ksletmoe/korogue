package com.sletmoe.krogue.graphics

import java.awt.Dimension
import java.awt.Rectangle

abstract class AsciiSubpanelGroup : AsciiSubpanelComponent() {
    protected var components: MutableList<AsciiSubpanelComponent> = mutableListOf()

    override fun getMinimumSize(containerSize: Dimension): Dimension = getMinimumSizeImpl(containerSize)
    override fun getPreferredSize(containerSize: Dimension): Dimension = getPreferredSizeImpl(containerSize)
    override fun getMaximumSize(containerSize: Dimension): Dimension = getMaximumSizeImpl(containerSize)

    fun addComponent(component: AsciiSubpanelComponent, resizeComponents: Boolean = false) {
        components.add(component)
        if (resizeComponents) {
            resizeComponents()
        }
    }

    fun removeComponent(component: AsciiSubpanelComponent, resizeComponents: Boolean = false) {
        components.removeIf { it == component }
        if (resizeComponents) {
            resizeComponents()
        }
    }

    protected fun componentPreferredDimensionSpecs(containerSize: Dimension): List<ComponentDimensionSpec> =
        components.map { ComponentDimensionSpec(it, it.getPreferredSize(containerSize)) }

    override fun setBoundsImpl(newBounds: Rectangle) {
        val minSize = getMinimumSize(newBounds.size)
        val maxSize = getMaximumSize(newBounds.size)

        if (newBounds.width < minSize.width || newBounds.height < minSize.height) {
            throw RuntimeException("New bounds $newBounds has smaller dimensions than the allowed minimum $minSize")
        } else if (newBounds.width > maxSize.width || newBounds.height > maxSize.height) {
            throw RuntimeException("New bounds $newBounds has larger dimensions than the allowed maximum $maxSize")
        }
        super.setBoundsImpl(newBounds)
    }

    override fun refresh() {
        components.forEach { it.refresh() }
    }

    override fun onNewBounds() {
        resizeComponents()
    }

    abstract fun resizeComponents()
    abstract fun getMinimumSizeImpl(containerSize: Dimension): Dimension
    abstract fun getPreferredSizeImpl(containerSize: Dimension): Dimension
    abstract fun getMaximumSizeImpl(containerSize: Dimension): Dimension

    protected data class ComponentDimensionSpec(val component: AsciiSubpanelComponent, val dimension: Dimension)
}
