package com.sletmoe.krogue.graphics

import com.sletmoe.krogue.utilities.initialize
import com.sletmoe.krogue.utilities.medianOrNull
import com.sletmoe.krogue.utilities.nonOverflowingSumOf
import java.awt.Dimension
import java.awt.Rectangle
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class AsciiSubpanelHorizontalGroup : AsciiSubpanelGroup() {
    override fun getMinimumSizeImpl(containerSize: Dimension): Dimension {
        return Dimension(
            components.nonOverflowingSumOf { it.getMinimumSize(containerSize).width },
            components.maxOfOrNull { it.getMinimumSize(containerSize).height } ?: 0,
        )
    }

    override fun getPreferredSizeImpl(containerSize: Dimension): Dimension {
        val medianPreferredHeight = medianOrNull(components.map { it.getPreferredSize(containerSize).height }) ?: 0
        return Dimension(
            components.nonOverflowingSumOf { it.getPreferredSize(containerSize).width },
            // not all elements will be of the same height. We want to aim for the median preferred height, but need
            // to be sure what we pick is greater than the highest min height
            max(medianPreferredHeight, getMinimumSize(containerSize).height),
        )
    }

    override fun getMaximumSizeImpl(containerSize: Dimension): Dimension {
        var maxWidth = Int.MAX_VALUE
        if (components.isNotEmpty()) {
            maxWidth = components.nonOverflowingSumOf { it.getMaximumSize(containerSize).width }
        }
        return Dimension(
            maxWidth,
            components.maxOfOrNull { it.getMaximumSize(containerSize).height } ?: Int.MAX_VALUE,
        )
    }

    override fun resizeComponents() {
        // set up new dimension specs for each component. These will start off set to each component's preferredSize
        val componentDimensionSpecs = componentPreferredDimensionSpecs(size)
        // set each component's height to the min of its preferred height and our height
        componentDimensionSpecs.forEach { componentDimSpec ->
            componentDimSpec.dimension.height =
                min(
                    componentDimSpec.component.getMaximumSize(size).height, size.height,
                )
        }

        val widthDelta = getPreferredSize(size).width - size.width

        if (widthDelta > 0) {
            // we need to scale components down.
            var trimmedChars = 0
            var trimableComponentDimSpecs = componentDimensionSpecs

            while (trimableComponentDimSpecs.isNotEmpty()) {
                val trimVal = widthDelta / components.size

                trimableComponentDimSpecs =
                    trimableComponentDimSpecs.filter { componentDimSpec ->
                        // if widthDelta % components.size != 0, we can't truly trim each component exactly evenly. When we
                        // get down to (widthDelta - trimmedChars) < trimableComponents, we'll need to be sure we aren't
                        // trimming more than we should. Calculate the maxTrimVal here such that we don't trim too much
                        val maxTrimVal =
                            min(
                                componentDimSpec.dimension.width -
                                    componentDimSpec.component.getMinimumSize(size).width,
                                widthDelta - trimmedChars,
                            )
                        val componentTrimVal = min(trimVal, maxTrimVal)
                        componentDimSpec.dimension.width -= componentTrimVal
                        trimmedChars += componentTrimVal

                        // we still have leeway to trim more off of this
                        maxTrimVal > componentTrimVal
                    }
            }
        } else if (widthDelta < 0) {
            // need to scale up
            var expandedChars = 0
            var expandableComponentDimSpecs = componentDimensionSpecs

            while (expandableComponentDimSpecs.isNotEmpty()) {
                val expandVal = abs(widthDelta) / components.size

                expandableComponentDimSpecs =
                    expandableComponentDimSpecs.filter { componentDimSpec ->
                        val maxExpandVal =
                            min(
                                componentDimSpec.component.getMaximumSize(size).width -
                                    componentDimSpec.dimension.width,
                                abs(widthDelta) - expandedChars,
                            )
                        val componentExpandVal = min(expandVal, maxExpandVal)
                        componentDimSpec.dimension.width += componentExpandVal
                        expandedChars += componentExpandVal

                        // we still have leeway to add to this
                        maxExpandVal > componentExpandVal
                    }
            }
        } // else we got lucky, and all the preferred widths add up exactly to our bounds. No width adjustments needed

        // now go layout the components with the sizes we calculated
        var x = bounds.x
        componentDimensionSpecs.forEach { componentDimensionSpec ->
            componentDimensionSpec.component.bounds =
                Rectangle(
                    x,
                    bounds.y,
                    componentDimensionSpec.dimension.width,
                    componentDimensionSpec.dimension.height,
                )
            x += componentDimensionSpec.dimension.width
        }
    }

    companion object {
        fun create(init: AsciiSubpanelGroup.() -> Unit): AsciiSubpanelHorizontalGroup =
            initialize(AsciiSubpanelHorizontalGroup(), init)
    }
}
