package com.sletmoe.krogue.utilities

private fun addingOverflows(
    a: Int,
    b: Int,
): Boolean {
    // determine if adding a and b together would result in an integer overflow, returning true if yes, false if no
    val aMaxDelta = Int.MAX_VALUE - a
    return aMaxDelta <= b
}

fun nonOverflowingAdd(
    a: Int,
    b: Int,
): Int {
    // add a and b together and return the result. If a + b would result in an integer overflow, return Int.MAX_VALUE
    // instead
    return if (addingOverflows(a, b)) {
        Int.MAX_VALUE
    } else {
        a + b
    }
}

fun medianOrNull(values: List<Int>): Int? =
    values.sorted().let { sortedVals ->
        return if (sortedVals.isEmpty()) {
            null
        } else if (sortedVals.size % 2 == 0) {
            // need to take the mean of the middle two values
            (sortedVals[sortedVals.size / 2] + sortedVals[(sortedVals.size - 1) / 2]) / 2
        } else {
            sortedVals[sortedVals.size / 2]
        }
    }
