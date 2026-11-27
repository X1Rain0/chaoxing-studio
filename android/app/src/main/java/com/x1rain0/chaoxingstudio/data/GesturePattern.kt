package com.x1rain0.chaoxingstudio.data

/** Appends a 1..9 gesture dot, inserting the midpoint when a straight segment skips it. */
internal fun appendGesturePoint(path: List<Int>, point: Int): List<Int> {
    if (point !in 1..9 || point in path) return path
    val previous = path.lastOrNull() ?: return listOf(point)
    val fromRow = (previous - 1) / 3
    val fromColumn = (previous - 1) % 3
    val toRow = (point - 1) / 3
    val toColumn = (point - 1) % 3
    val rowDelta = toRow - fromRow
    val columnDelta = toColumn - fromColumn
    val midpoint = if (kotlin.math.abs(rowDelta) == 2 && kotlin.math.abs(columnDelta) == 2) {
        (fromRow + rowDelta / 2) * 3 + fromColumn + columnDelta / 2 + 1
    } else if (kotlin.math.abs(rowDelta) == 2 && columnDelta == 0) {
        (fromRow + rowDelta / 2) * 3 + fromColumn + 1
    } else if (rowDelta == 0 && kotlin.math.abs(columnDelta) == 2) {
        fromRow * 3 + fromColumn + columnDelta / 2 + 1
    } else {
        null
    }
    return if (midpoint != null && midpoint !in path) path + midpoint + point else path + point
}

internal fun gestureCode(path: List<Int>): String = path.filter { it in 1..9 }.distinct().joinToString("")

/** Converts TalkBack-friendly keypad input through the same midpoint rules as the canvas. */
internal fun gesturePathFromDigits(input: String): List<Int> {
    var path = emptyList<Int>()
    input.take(9).forEach { char ->
        val point = char.digitToIntOrNull() ?: return@forEach
        if (point in 1..9) path = appendGesturePoint(path, point)
    }
    return path
}

/** Returns the closest dot when a pointer is close enough to one of the nine targets. */
internal fun gesturePointAt(
    x: Float,
    y: Float,
    width: Float,
    height: Float,
    thresholdFraction: Float = 0.19f,
): Int? {
    if (width <= 0f || height <= 0f || x !in 0f..width || y !in 0f..height) return null
    val insetX = width / 6f
    val insetY = height / 6f
    val stepX = width / 3f
    val stepY = height / 3f
    var bestPoint = 0
    var bestDistance = Float.MAX_VALUE
    for (point in 1..9) {
        val row = (point - 1) / 3
        val column = (point - 1) % 3
        val dx = x - (insetX + column * stepX)
        val dy = y - (insetY + row * stepY)
        val distance = dx * dx + dy * dy
        if (distance < bestDistance) {
            bestDistance = distance
            bestPoint = point
        }
    }
    val threshold = minOf(stepX, stepY) * thresholdFraction
    return bestPoint.takeIf { bestDistance <= threshold * threshold }
}
