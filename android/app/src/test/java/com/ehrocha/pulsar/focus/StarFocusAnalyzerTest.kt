/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */
package com.ehrocha.pulsar.focus

import org.junit.Assert.*
import org.junit.Test
import java.util.Random
import kotlin.math.*

class StarFocusAnalyzerTest {
    private fun image(
        width: Int = 240, height: Int = 180, sigmaX: Double = 2.0, sigmaY: Double = sigmaX,
        amplitude: Double = 140.0, noise: Double = 0.0,
        stars: List<Pair<Double, Double>> = listOf(50.0 to 50.0, 120.0 to 80.0, 180.0 to 125.0),
    ): IntArray {
        val random = Random(42)
        return IntArray(width * height) { i ->
            val x = i % width; val y = i / width
            val signal = stars.sumOf { (cx, cy) ->
                amplitude * exp(-0.5 * (((x - cx) / sigmaX).pow(2) + ((y - cy) / sigmaY).pow(2)))
            }
            val v = (12 + signal + random.nextGaussian() * noise).roundToInt().coerceIn(0, 255)
            (255 shl 24) or (v shl 16) or (v shl 8) or v
        }
    }

    @Test fun `finds multiple point sources with centroid and finite half flux radii`() {
        val stars = StarFocusAnalyzer.analyze(240, 180, image()).stars
        assertEquals(3, stars.size)
        assertTrue(stars.all { it.hfr in 1.8..2.6 && it.flux > 0 && it.snr >= 8 })
        assertTrue(stars.any { hypot(it.x - 120, it.y - 80) < 0.5 })
    }

    @Test fun `narrower stars have smaller half flux radius`() {
        val sharp = StarFocusAnalyzer.analyze(240, 180, image(sigmaX = 1.5)).stars
        val blurry = StarFocusAnalyzer.analyze(240, 180, image(sigmaX = 3.5)).stars
        assertEquals(3, sharp.size); assertEquals(3, blurry.size)
        assertTrue(median(sharp.map { it.hfr }) < median(blurry.map { it.hfr }) * 0.6)
    }

    @Test fun `brightness alone does not masquerade as much better focus`() {
        val bright = StarFocusAnalyzer.analyze(240, 180, image(amplitude = 160.0)).stars
        val dim = StarFocusAnalyzer.analyze(240, 180, image(amplitude = 65.0)).stars
        assertEquals(3, dim.size)
        assertEquals(median(bright.map { it.hfr }), median(dim.map { it.hfr }), 0.25)
    }

    @Test fun `blank sky yields no stars`() {
        assertTrue(StarFocusAnalyzer.analyze(240, 180, image(stars = emptyList())).stars.isEmpty())
    }

    @Test fun `read noise does not become a focus target`() {
        assertTrue(StarFocusAnalyzer.analyze(240, 180, image(stars = emptyList(), noise = 3.0)).stars.isEmpty())
    }

    @Test fun `isolated hot pixels are rejected`() {
        val pixels = image(stars = emptyList())
        for (i in listOf(50 * 240 + 50, 80 * 240 + 120)) pixels[i] = 0xFFC8C8C8.toInt()
        assertTrue(StarFocusAnalyzer.analyze(240, 180, pixels).stars.isEmpty())
    }

    @Test fun `saturated stars are rejected`() {
        assertTrue(StarFocusAnalyzer.analyze(240, 180, image(amplitude = 300.0)).stars.isEmpty())
    }

    @Test fun `clipping in one color channel also invalidates a measurement`() {
        val pixels = image(stars = listOf(120.0 to 80.0))
        pixels[80 * 240 + 120] = 0xFFFF8080.toInt()
        assertTrue(StarFocusAnalyzer.analyze(240, 180, pixels).stars.isEmpty())
    }

    @Test fun `elongated streaks are rejected`() {
        assertTrue(StarFocusAnalyzer.analyze(240, 180, image(sigmaX = 4.5, sigmaY = 1.4)).stars.isEmpty())
    }

    @Test fun `edge clipped stars are not measured as artificially sharp`() {
        assertTrue(StarFocusAnalyzer.analyze(240, 180, image(stars = listOf(5.0 to 80.0))).stars.isEmpty())
    }

    @Test fun `very broad sources exceeding aperture are rejected`() {
        assertTrue(StarFocusAnalyzer.analyze(240, 180, image(sigmaX = 8.0)).stars.isEmpty())
    }

    @Test fun `manual selection finds a valid nearby star but not another region`() {
        val pixels = image()
        val selected = StarFocusAnalyzer.analyze(240, 180, pixels, 123 to 82)
        assertTrue(selected.manual)
        assertEquals(1, selected.stars.size)
        assertTrue(hypot(selected.stars.single().x - 120, selected.stars.single().y - 80) < 0.5)
        assertTrue(StarFocusAnalyzer.analyze(240, 180, pixels, 85 to 125).stars.isEmpty())
    }

    @Test fun `manual selection does not bypass saturation or noise rejection`() {
        assertTrue(StarFocusAnalyzer.analyze(240, 180, image(amplitude = 300.0), 120 to 80).stars.isEmpty())
        assertTrue(StarFocusAnalyzer.analyze(240, 180, image(stars = emptyList()), 120 to 80).stars.isEmpty())
    }

    @Test fun `reduced detection grid still measures original pixel radii`() {
        val frame = StarFocusAnalyzer.analyze(1024, 768, image(1024, 768,
            stars = listOf(200.0 to 200.0, 500.0 to 350.0, 800.0 to 500.0)))
        assertEquals(3, frame.stars.size)
        assertEquals(2.2, median(frame.stars.map { it.hfr }), 0.35)
    }

    @Test fun `moderate noise preserves valid star measurements`() {
        val stars = StarFocusAnalyzer.analyze(240, 180, image(noise = 2.0)).stars
        assertEquals(3, stars.size)
        assertTrue(stars.all { it.hfr in 1.6..2.8 })
    }

    @Test fun `synthetic focus adjustment improves smoothed measurement without changing exposure`() {
        val tracker = StarFocusTracker()
        // Equal approximate total flux: amplitude * sigma^2 remains fixed.
        val blurred = StarFocusAnalyzer.analyze(240, 180, image(sigmaX = 3.0, amplitude = 90.0))
        val sharper = StarFocusAnalyzer.analyze(240, 180, image(sigmaX = 2.0, amplitude = 202.5))
        var before = StarFocusReading()
        repeat(5) { before = tracker.update(blurred) }
        val after = (0 until 5).map { tracker.update(sharper) }
        assertTrue(after.any { it.status == StarFocusStatus.IMPROVING })
        assertTrue(after.last().bestHfr!! < before.bestHfr!! * 0.8)
        val empty = StarFocusAnalyzer.analyze(240, 180, image(stars = emptyList()))
        assertNull(tracker.update(empty).hfr)
    }
}
