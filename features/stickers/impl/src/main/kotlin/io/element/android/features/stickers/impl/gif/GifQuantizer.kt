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
 *
 * An instance is bound to one encoder: its large bucket arrays are reused from frame to frame so
 * that encoding a long animation does not allocate them again for every frame.
 */
internal class GifQuantizer {
    private val counts = IntArray(BUCKET_COUNT)
    private val redSums = IntArray(BUCKET_COUNT)
    private val greenSums = IntArray(BUCKET_COUNT)
    private val blueSums = IntArray(BUCKET_COUNT)
    private val bucketColors = IntArray(BUCKET_COUNT)
    private val usedBuckets = ArrayList<Int>(INITIAL_BUCKET_CAPACITY)
    private val boxes = ArrayList<Box>(GifPalette.MAX_COLORS)

    fun quantize(pixels: IntArray, maxColors: Int = GifPalette.MAX_COLORS): GifPalette =
        quantize(pixels, pixels.size, maxColors)

    fun quantize(pixels: IntArray, pixelCount: Int, maxColors: Int): GifPalette {
        require(pixelCount in 1..pixels.size) { "Cannot quantize $pixelCount pixels" }
        require(maxColors in 1..GifPalette.MAX_COLORS) { "Invalid palette size $maxColors" }

        counts.fill(0)
        redSums.fill(0)
        greenSums.fill(0)
        blueSums.fill(0)
        for (index in 0 until pixelCount) {
            val color = pixels[index]
            val bucket = GifPalette.bucketOf(color)
            counts[bucket] += 1
            redSums[bucket] += color shr 16 and 0xFF
            greenSums[bucket] += color shr 8 and 0xFF
            blueSums[bucket] += color and 0xFF
        }

        usedBuckets.clear()
        var red = 0L
        var green = 0L
        var blue = 0L
        for (bucket in 0 until BUCKET_COUNT) {
            val count = counts[bucket]
            if (count > 0) {
                usedBuckets.add(bucket)
                bucketColors[bucket] = averageColor(count.toLong(), redSums[bucket].toLong(), greenSums[bucket].toLong(), blueSums[bucket].toLong())
                red += redSums[bucket]
                green += greenSums[bucket]
                blue += blueSums[bucket]
            }
        }
        if (usedBuckets.isEmpty()) {
            // Only reachable with a zero pixel count guard above, kept so that a palette is always usable.
            return GifPalette(intArrayOf(OPAQUE_BLACK), IntArray(BUCKET_COUNT))
        }

        boxes.clear()
        boxes.add(Box(buckets = usedBuckets.toMutableList(), population = pixelCount.toLong(), red = red, green = green, blue = blue))
        while (boxes.size < maxColors) {
            var selected: Box? = null
            for (box in boxes) {
                if (box.buckets.size > 1) {
                    val current = selected
                    if (current == null || box.population > current.population) selected = box
                }
            }
            val box = selected ?: break
            val (first, second) = split(box)
            boxes.remove(box)
            boxes.add(first)
            boxes.add(second)
        }

        val palette = IntArray(boxes.size) { index ->
            val box = boxes[index]
            averageColor(box.population, box.red, box.green, box.blue)
        }
        return GifPalette(palette, bucketColors.copyOf())
    }

    private fun split(box: Box): Pair<Box, Box> {
        val channel = widestChannel(box.buckets)
        box.buckets.sortWith(compareBy { channelValue(it, channel) })

        val half = (box.population + 1) / 2
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

        val firstBuckets = box.buckets.subList(0, splitIndex).toMutableList()
        val secondBuckets = box.buckets.subList(splitIndex, box.buckets.size).toMutableList()
        var red = 0L
        var green = 0L
        var blue = 0L
        var population = 0L
        for (bucket in firstBuckets) {
            population += counts[bucket]
            red += redSums[bucket]
            green += greenSums[bucket]
            blue += blueSums[bucket]
        }
        val first = Box(firstBuckets, population, red, green, blue)
        val second = Box(
            buckets = secondBuckets,
            population = box.population - population,
            red = box.red - red,
            green = box.green - green,
            blue = box.blue - blue,
        )
        return first to second
    }

    private fun widestChannel(buckets: List<Int>): Int {
        var channel = 0
        var widestRange = -1
        for (candidate in 0..2) {
            var minValue = 32
            var maxValue = -1
            for (bucket in buckets) {
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
        return channel
    }

    private fun averageColor(count: Long, red: Long, green: Long, blue: Long): Int {
        return if (count == 0L) {
            OPAQUE_BLACK
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

    private data class Box(
        val buckets: MutableList<Int>,
        val population: Long,
        val red: Long,
        val green: Long,
        val blue: Long,
    )

    private companion object {
        const val BUCKET_COUNT = 32 * 32 * 32
        const val INITIAL_BUCKET_CAPACITY = 1024
        const val OPAQUE_BLACK = 0xFF000000.toInt()
    }
}
