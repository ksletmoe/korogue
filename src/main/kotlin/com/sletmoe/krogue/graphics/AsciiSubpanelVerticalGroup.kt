package com.sletmoe.krogue.graphics

import com.sletmoe.krogue.utilities.initialize
import com.sletmoe.krogue.utilities.medianOrNull
import com.sletmoe.krogue.utilities.nonOverflowingSumOf
import java.awt.Dimension
import java.awt.Rectangle
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class AsciiSubpanelVerticalGroup : AsciiSubpanelGroup() {
    override fun getMinimumSizeImpl(): Dimension {
        return Dimension(
            components.maxOfOrNull { it.minimumSize.width } ?: 0,
            components.nonOverflowingSumOf { it.minimumSize.height },
        )
    }

    override fun getPreferredSizeImpl(): Dimension {
        val medianPreferredWidth = medianOrNull(components.map { it.preferredSize.width }) ?: 0
        return Dimension(
            // not all elements will be of the same width. We want to aim for the median preferred width, but need
            // to be sure what we pick is greater than the highest min width
            max(medianPreferredWidth, minimumSize.width),
            components.nonOverflowingSumOf { it.preferredSize.height },
        )
    }

    override fun getMaximumSizeImpl(): Dimension {
        var maxHeight = Int.MAX_VALUE
        if (components.isNotEmpty()) {
            maxHeight = components.nonOverflowingSumOf { it.maximumSize.height }
        }
        return Dimension(
            components.maxOfOrNull { it.maximumSize.width } ?: Int.MAX_VALUE,
            maxHeight,
        )
    }

    override fun resizeComponents() {
        // set up new dimension specs for each component. These will start off set to each component's preferredSize
        val componentDimensionSpecs = componentPreferredDimensionSpecs()
        // set each component's width to the min of its preferred width and our width
        componentDimensionSpecs.forEach { it.dimension.width = min(it.component.preferredSize.width, bounds.width) }

        val heightDelta = preferredSize.height - bounds.height

        if (heightDelta > 0) {
            // we need to scale components down.
            var trimmedChars = 0
            var trimableComponentDimSpecs = componentDimensionSpecs

            while (trimableComponentDimSpecs.isNotEmpty()) {
                // we want to trim each component equally, if possible.
                val trimVal = heightDelta / components.size

                trimableComponentDimSpecs = trimableComponentDimSpecs.filter { componentDimSpec ->
                    // if heightDelta % components.size != 0, we can't truly trim each component exactly evenly. When we
                    // get down to (heightDelta - trimmedChars) < trimableComponents, we'll need to be sure we aren't
                    // trimming more than we should. Calculate the maxTrimVal here such that we don't trim too much
                    val maxTrimVal = min(
                        componentDimSpec.dimension.height - componentDimSpec.component.minimumSize.height,
                        heightDelta - trimmedChars,
                    )
                    val componentTrimVal = min(trimVal, maxTrimVal)
                    componentDimSpec.dimension.height -= componentTrimVal
                    trimmedChars += componentTrimVal

                    // we still have leeway to trim more off of this
                    maxTrimVal > componentTrimVal
                }
            }
        } else if (heightDelta < 0) {
            // need to scale up
            var expandedChars = 0
            var expandableComponentDimSpecs = componentDimensionSpecs

            while (expandableComponentDimSpecs.isNotEmpty()) {
                val expandVal = abs(heightDelta) / components.size

                expandableComponentDimSpecs = expandableComponentDimSpecs.filter { componentDimSpec ->
                    val maxExpandVal = min(
                        componentDimSpec.component.maximumSize.height - componentDimSpec.dimension.height,
                        abs(heightDelta) - expandedChars,
                    )
                    val componentExpandVal = min(expandVal, maxExpandVal)
                    componentDimSpec.dimension.height += componentExpandVal
                    expandedChars += componentExpandVal

                    // we still have leeway to add to this
                    maxExpandVal > componentExpandVal
                }
            }
        } // else we got lucky, and all the preferred widths add up exactly to our bounds. No width adjustments needed

        // now go layout the components with the sizes we calculated
        var y = bounds.y
        componentDimensionSpecs.forEach { componentDimensionSpec ->
            componentDimensionSpec.component.bounds = Rectangle(
                bounds.x,
                y,
                componentDimensionSpec.dimension.width,
                componentDimensionSpec.dimension.height,
            )
            y += componentDimensionSpec.dimension.height
        }
    }

    companion object {
        fun create(
            init: AsciiSubpanelGroup.() -> Unit,
        ): AsciiSubpanelVerticalGroup = initialize(AsciiSubpanelVerticalGroup(), init)
    }
}
