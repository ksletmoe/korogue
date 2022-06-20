package com.sletmoe.krogue.graphics

import java.awt.Dimension
import java.awt.Rectangle

abstract class AsciiSubpanelGroup : AsciiSubpanelComponent() {
    override val minimumSize: Dimension
        get() = getMinimumSizeImpl()
    override val preferredSize: Dimension
        get() = getPreferredSizeImpl()
    override val maximumSize: Dimension
        get() = getMaximumSizeImpl()

    protected var components: MutableList<AsciiSubpanelComponent> = mutableListOf()

    fun addComponent(component: AsciiSubpanelComponent) {
        components.add(component)
        resizeComponents()
    }

    fun removeComponent(component: AsciiSubpanelComponent) {
        components.removeIf { it == component }
        resizeComponents()
    }

    protected fun componentPreferredDimensionSpecs(): List<ComponentDimensionSpec> =
        components.map { ComponentDimensionSpec(it, Dimension(it.preferredSize)) }

    override fun setBoundsImpl(newBounds: Rectangle) {
        val minSize = minimumSize
        val maxSize = maximumSize

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
    abstract fun getMinimumSizeImpl(): Dimension
    abstract fun getPreferredSizeImpl(): Dimension
    abstract fun getMaximumSizeImpl(): Dimension

    protected data class ComponentDimensionSpec(val component: AsciiSubpanelComponent, val dimension: Dimension)
}
