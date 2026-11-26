/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.stickers.impl.gif

/**
 * A palette of at most 256 opaque colors, and the mapping from any ARGB color to its index.
 *
 * Colors are bucketed in a 5 bits per channel space, the palette is then built with a median
 * cut over those buckets so that the flat areas of a video frame keep their exact colors.
 */
internal class GifPalette(
    val colors: IntArray,
    private val bucketColors: IntArray,
) {
    private val cache = IntArray(BUCKET_COUNT) { UNKNOWN }

    init {
        require(colors.isNotEmpty()) { "A palette needs at least one color" }
        require(colors.size <= MAX_COLORS) { "A GIF palette has at most $MAX_COLORS colors" }
    }

    fun indexOf(color: Int): Int {
        val bucket = bucketOf(color)
        val cached = cache[bucket]
        if (cached != UNKNOWN) return cached
        val target = bucketColors[bucket]
        var bestIndex = 0
        var bestDistance = Int.MAX_VALUE
        for (index in colors.indices) {
            val distance = distance(colors[index], target)
            if (distance < bestDistance) {
                bestDistance = distance
                bestIndex = index
            }
        }
        cache[bucket] = bestIndex
        return bestIndex
    }

    companion object {
        const val MAX_COLORS = 256
        private const val BUCKET_COUNT = 32 * 32 * 32
        private const val UNKNOWN = -1

        fun bucketOf(color: Int): Int {
            return color shr 9 and 0x7C00 or (color shr 6 and 0x03E0) or (color shr 3 and 0x1F)
        }

        private fun distance(first: Int, second: Int): Int {
            val red = (first shr 16 and 0xFF) - (second shr 16 and 0xFF)
            val green = (first shr 8 and 0xFF) - (second shr 8 and 0xFF)
            val blue = (first and 0xFF) - (second and 0xFF)
            return red * red + green * green + blue * blue
        }
    }
}

/**
 * Builds a [GifPalette] with a median cut: the color space of one frame is repeatedly split in
 * two along its widest channel, until there are [maxColors] boxes or no box can be split.
 */
internal object GifQuantizer {
    private const val BUCKET_COUNT = 32 * 32 * 32

    fun quantize(pixels: IntArray, maxColors: Int = GifPalette.MAX_COLORS): GifPalette {
        require(pixels.isNotEmpty()) { "Cannot quantize an empty frame" }
        require(maxColors in 1..GifPalette.MAX_COLORS) { "Invalid palette size $maxColors" }

        val counts = IntArray(BUCKET_COUNT)
        val redSums = IntArray(BUCKET_COUNT)
        val greenSums = IntArray(BUCKET_COUNT)
        val blueSums = IntArray(BUCKET_COUNT)
        for (color in pixels) {
            val bucket = GifPalette.bucketOf(color)
            counts[bucket] += 1
            redSums[bucket] += color shr 16 and 0xFF
            greenSums[bucket] += color shr 8 and 0xFF
            blueSums[bucket] += color and 0xFF
        }

        val usedBuckets = ArrayList<Int>(maxColors * 4)
        for (bucket in 0 until BUCKET_COUNT) {
            if (counts[bucket] > 0) usedBuckets.add(bucket)
        }
        if (usedBuckets.isEmpty()) {
            return GifPalette(intArrayOf(0), IntArray(BUCKET_COUNT))
        }

        val bucketColors = IntArray(BUCKET_COUNT)
        for (bucket in usedBuckets) {
            bucketColors[bucket] = averageColor(counts, redSums, greenSums, blueSums, listOf(bucket))
        }

        val boxes = mutableListOf(Box(usedBuckets.toMutableList()))
        while (boxes.size < maxColors) {
            val box = boxes
                .filter { it.buckets.size > 1 }
                .maxByOrNull { population(it, counts) }
                ?: break
            val (first, second) = split(box, counts)
            boxes.remove(box)
            boxes.add(first)
            boxes.add(second)
        }

        val palette = IntArray(boxes.size) { index ->
            averageColor(counts, redSums, greenSums, blueSums, boxes[index].buckets)
        }
        return GifPalette(palette, bucketColors)
    }

    private fun split(box: Box, counts: IntArray): Pair<Box, Box> {
        var channel = 0
        var widestRange = -1
        for (candidate in 0..2) {
            var minValue = 32
            var maxValue = -1
            for (bucket in box.buckets) {
                val value = channelValue(bucket, candidate)
                minValue = minOf(minValue, value)
                maxValue = maxOf(maxValue, value)
            }
            val range = maxValue - minValue
            if (range > widestRange) {
                widestRange = range
                channel = candidate
            }
        }

        box.buckets.sortWith(compareBy { channelValue(it, channel) })
        val half = (population(box, counts) + 1) / 2
        var accumulated = 0L
        var splitIndex = box.buckets.size - 1
        for (index in box.buckets.indices) {
            accumulated += counts[box.buckets[index]]
            if (accumulated >= half) {
                splitIndex = index + 1
                break
            }
        }
        splitIndex = splitIndex.coerceIn(1, box.buckets.size - 1)
        val first = box.buckets.subList(0, splitIndex).toMutableList()
        val second = box.buckets.subList(splitIndex, box.buckets.size).toMutableList()
        return Box(first) to Box(second)
    }

    private fun population(box: Box, counts: IntArray): Long = box.buckets.sumOf { counts[it].toLong() }

    private fun averageColor(
        counts: IntArray,
        redSums: IntArray,
        greenSums: IntArray,
        blueSums: IntArray,
        buckets: List<Int>,
    ): Int {
        var count = 0L
        var red = 0L
        var green = 0L
        var blue = 0L
        for (bucket in buckets) {
            val bucketCount = counts[bucket].toLong()
            count += bucketCount
            red += redSums[bucket]
            green += greenSums[bucket]
            blue += blueSums[bucket]
        }
        return if (count == 0L) {
            0xFF000000.toInt()
        } else {
            0xFF shl 24 or
                ((red / count).toInt() and 0xFF shl 16) or
                ((green / count).toInt() and 0xFF shl 8) or
                ((blue / count).toInt() and 0xFF)
        }
    }

    private fun channelValue(bucket: Int, channel: Int): Int = when (channel) {
        0 -> bucket shr 10 and 0x1F
        1 -> bucket shr 5 and 0x1F
        else -> bucket and 0x1F
    }

    private data class Box(val buckets: MutableList<Int>)
}
