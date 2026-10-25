/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */
package com.ehrocha.pulsar.focus

import kotlin.math.*

internal data class FocusStar(
    val x: Double, val y: Double, val hfr: Double, val flux: Double, val snr: Double,
)

internal data class StarFocusFrame(
    val width: Int, val height: Int, val stars: List<FocusStar>, val manual: Boolean = false,
)

/** JPEG preview photometry, not an absolute optical focus certification.
 *  Detection uses a reduced grid; centroid, shape and half-flux radius are
 *  measured on original pixels. No Android dependencies or camera commands. */
internal object StarFocusAnalyzer {
    private const val APERTURE = 12
    private const val BACKGROUND_RADIUS = 16

    fun analyze(width: Int, height: Int, argb: IntArray, manual: Pair<Int, Int>? = null): StarFocusFrame {
        require(width > 0 && height > 0 && argb.size.toLong() == width.toLong() * height)
        val luma = IntArray(argb.size) { i ->
            val p = argb[i]
            (((p shr 16) and 255) * 77 + ((p shr 8) and 255) * 150 + (p and 255) * 29) shr 8
        }
        if (manual != null) {
            val star = measure(width, height, argb, luma, manual.first, manual.second, APERTURE)
            return StarFocusFrame(width, height, listOfNotNull(star), manual = true)
        }
        val stride = max(1, ceil(max(width, height) / 640.0).toInt())
        val gw = (width + stride - 1) / stride
        val gh = (height + stride - 1) / stride
        val grid = IntArray(gw * gh)
        val histogram = IntArray(256)
        for (gy in 0 until gh) for (gx in 0 until gw) {
            var sum = 0
            var count = 0
            for (y in gy * stride until min(height, (gy + 1) * stride)) {
                for (x in gx * stride until min(width, (gx + 1) * stride)) {
                    sum += luma[y * width + x]; count++
                }
            }
            val value = sum / count
            grid[gy * gw + gx] = value
            histogram[value]++
        }
        val background = histogramMedian(histogram)
        val deviations = IntArray(256)
        for (i in histogram.indices) deviations[abs(i - background)] += histogram[i]
        val noise = max(1.0, 1.4826 * histogramMedian(deviations))
        val threshold = background + max(8.0, 5.0 * noise)
        val candidates = mutableListOf<Pair<Int, Int>>()
        for (y in 1 until gh - 1) for (x in 1 until gw - 1) {
            val i = y * gw + x
            if (grid[i] < threshold) continue
            var maximum = true
            for (dy in -1..1) for (dx in -1..1) {
                val other = (y + dy) * gw + x + dx
                if (grid[other] > grid[i] || (other < i && grid[other] == grid[i])) maximum = false
            }
            if (maximum) candidates += i to grid[i]
        }
        val stars = mutableListOf<FocusStar>()
        // Bound work in textured/daylight scenes. Only point-like, isolated,
        // unsaturated sources are accepted below, even if manually selected.
        for ((index, _) in candidates.sortedByDescending { it.second }.take(64)) {
            val x = (index % gw) * stride + stride / 2
            val y = (index / gw) * stride + stride / 2
            if (stars.any { hypot(it.x - x, it.y - y) < APERTURE * 2 }) continue
            val star = measure(width, height, argb, luma, x, y, stride + 1) ?: continue
            stars += star
        }
        return StarFocusFrame(width, height, stars.sortedByDescending { it.snr }.take(12))
    }

    private fun measure(
        width: Int, height: Int, argb: IntArray, luma: IntArray,
        approximateX: Int, approximateY: Int, searchRadius: Int,
    ): FocusStar? {
        var px = approximateX
        var py = approximateY
        var peak = -1
        for (y in max(0, approximateY - searchRadius)..min(height - 1, approximateY + searchRadius)) {
            for (x in max(0, approximateX - searchRadius)..min(width - 1, approximateX + searchRadius)) {
                if (luma[y * width + x] > peak) {
                    peak = luma[y * width + x]; px = x; py = y
                }
            }
        }
        if (px < BACKGROUND_RADIUS || py < BACKGROUND_RADIUS ||
            px >= width - BACKGROUND_RADIUS || py >= height - BACKGROUND_RADIUS) return null
        val sky = mutableListOf<Double>()
        for (dy in -BACKGROUND_RADIUS..BACKGROUND_RADIUS) for (dx in -BACKGROUND_RADIUS..BACKGROUND_RADIUS) {
            val r2 = dx * dx + dy * dy
            if (r2 in 169..256) sky += luma[(py + dy) * width + px + dx].toDouble()
        }
        val background = median(sky)
        val noise = max(1.0, 1.4826 * median(sky.map { abs(it - background) }))
        val snr = (peak - background) / noise
        if (snr < 8) return null
        val threshold = background + max(3.0, 3 * noise)
        val side = APERTURE * 2 + 1
        val visited = BooleanArray(side * side)
        val queue = IntArray(side * side)
        var head = 0
        var tail = 1
        queue[0] = APERTURE * side + APERTURE
        visited[queue[0]] = true
        val members = mutableListOf<Triple<Double, Double, Double>>()
        while (head < tail) {
            val index = queue[head++]
            val dx = index % side - APERTURE
            val dy = index / side - APERTURE
            val imageIndex = (py + dy) * width + px + dx
            if (luma[imageIndex] < threshold) continue
            if (dx * dx + dy * dy >= (APERTURE - 1) * (APERTURE - 1)) return null
            val color = argb[imageIndex]
            if (((color shr 16) and 255) >= 250 || ((color shr 8) and 255) >= 250 || (color and 255) >= 250) return null
            members += Triple((px + dx).toDouble(), (py + dy).toDouble(), luma[imageIndex] - background)
            for (ny in dy - 1..dy + 1) for (nx in dx - 1..dx + 1) {
                if (nx !in -APERTURE..APERTURE || ny !in -APERTURE..APERTURE) continue
                val next = (ny + APERTURE) * side + nx + APERTURE
                if (!visited[next]) { visited[next] = true; queue[tail++] = next }
            }
        }
        if (members.size < 5) return null // Reject isolated hot pixels and tiny noise clusters.
        val flux = members.sumOf { it.third }
        val cx = members.sumOf { it.first * it.third } / flux
        val cy = members.sumOf { it.second * it.third } / flux
        val xx = members.sumOf { (it.first - cx).pow(2) * it.third } / flux
        val yy = members.sumOf { (it.second - cy).pow(2) * it.third } / flux
        val xy = members.sumOf { (it.first - cx) * (it.second - cy) * it.third } / flux
        val discriminant = sqrt((xx - yy).pow(2) + 4 * xy * xy)
        val minor = (xx + yy - discriminant) / 2
        val major = (xx + yy + discriminant) / 2
        if (minor <= 0 || sqrt(major / minor) > 1.8) return null
        val radialFlux = members.map { hypot(it.first - cx, it.second - cy) to it.third }.sortedBy { it.first }
        var accumulated = 0.0
        var previousRadius = 0.0
        var hfr = 0.0
        for ((radius, light) in radialFlux) {
            if (accumulated + light >= flux / 2) {
                hfr = previousRadius + (radius - previousRadius) * ((flux / 2 - accumulated) / light)
                break
            }
            accumulated += light
            previousRadius = radius
        }
        if (hfr !in 0.8..7.5) return null
        return FocusStar(cx, cy, hfr, flux, snr)
    }

    private fun histogramMedian(histogram: IntArray): Int {
        val target = (histogram.sum() + 1) / 2
        var count = 0
        for (i in histogram.indices) { count += histogram[i]; if (count >= target) return i }
        return 0
    }
}

internal fun median(values: List<Double>): Double {
    if (values.isEmpty()) return 0.0
    val sorted = values.sorted()
    return if (sorted.size % 2 == 1) sorted[sorted.size / 2]
    else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
}
