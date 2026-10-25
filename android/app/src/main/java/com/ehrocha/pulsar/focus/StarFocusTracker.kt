/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */
package com.ehrocha.pulsar.focus

import kotlin.math.*

internal enum class StarFocusStatus { SEARCHING, SAMPLING, UNSTABLE, IMPROVING, NEAR_BEST, WORSENING }

internal data class StarFocusReading(
    val stars: List<FocusStar> = emptyList(),
    val hfr: Double? = null,
    val bestHfr: Double? = null,
    val status: StarFocusStatus = StarFocusStatus.SEARCHING,
    val manual: Boolean = false,
)

/** Compare the SAME stars at the SAME image scale, smoothing five samples.
 *  A lost cohort, mode/size change, or a large flux change resets the best.
 *  A lower HFR is only a relative improvement, not proof of perfect focus. */
internal class StarFocusTracker {
    private var cohort = emptyList<FocusStar>()
    private var referenceFlux = emptyList<Double>()
    private val samples = ArrayDeque<Double>()
    private var best: Double? = null
    private var misses = 0
    private var geometry: Triple<Int, Int, Boolean>? = null

    fun reset() {
        cohort = emptyList(); referenceFlux = emptyList(); samples.clear(); best = null; misses = 0; geometry = null
    }

    fun update(frame: StarFocusFrame): StarFocusReading {
        val nextGeometry = Triple(frame.width, frame.height, frame.manual)
        if (geometry != nextGeometry) { reset(); geometry = nextGeometry }
        val minimum = if (frame.manual) 1 else 3
        if (cohort.isEmpty()) {
            if (frame.stars.size < minimum) return StarFocusReading(stars = frame.stars, manual = frame.manual)
            cohort = frame.stars.take(if (frame.manual) 1 else 5)
            referenceFlux = cohort.map { it.flux }
        }
        val available = frame.stars.toMutableList()
        val matched = cohort.mapNotNull { reference ->
            val nearest = available.minByOrNull { hypot(it.x - reference.x, it.y - reference.y) }
            if (nearest != null && hypot(nearest.x - reference.x, nearest.y - reference.y) <= 10.0) {
                available.remove(nearest); nearest
            } else null
        }
        if (matched.size != cohort.size) {
            samples.clear()
            misses++
            if (misses >= 3) { reset(); return update(frame) }
            return StarFocusReading(stars = matched, manual = frame.manual)
        }
        // Keep exposure changes from masquerading as an optical improvement.
        val fluxRatio = median(matched.zip(referenceFlux).map { (now, reference) -> now.flux / reference })
        if (fluxRatio !in 0.65..1.5) {
            samples.clear(); best = null
            referenceFlux = matched.map { it.flux }
        }
        cohort = matched
        misses = 0
        samples.addLast(median(matched.map { it.hfr }))
        while (samples.size > 5) samples.removeFirst()
        if (samples.size < 5) return StarFocusReading(stars = matched, manual = frame.manual, status = StarFocusStatus.SAMPLING)
        val hfr = median(samples.toList())
        val scatter = 1.4826 * median(samples.map { abs(it - hfr) }) / hfr
        if (scatter > 0.12) return StarFocusReading(matched, hfr, best, StarFocusStatus.UNSTABLE, frame.manual)
        val previousBest = best
        val status = when {
            previousBest == null -> StarFocusStatus.NEAR_BEST
            hfr < previousBest * 0.97 -> StarFocusStatus.IMPROVING
            hfr > previousBest * 1.08 -> StarFocusStatus.WORSENING
            else -> StarFocusStatus.NEAR_BEST
        }
        best = min(best ?: hfr, hfr)
        return StarFocusReading(matched, hfr, best, status, frame.manual)
    }
}
