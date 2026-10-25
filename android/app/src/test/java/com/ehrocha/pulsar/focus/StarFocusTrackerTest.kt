/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */
package com.ehrocha.pulsar.focus

import org.junit.Assert.*
import org.junit.Test

class StarFocusTrackerTest {
    private fun frame(hfr: Double = 2.0, count: Int = 3, shift: Double = 0.0,
        flux: Double = 1000.0, width: Int = 640, manual: Boolean = false) = StarFocusFrame(
        width, 480, (0 until count).map { FocusStar(100.0 + it * 50 + shift, 100.0, hfr, flux, 50.0) }, manual,
    )

    @Test fun `automatic mode requires multiple stars before comparing focus`() {
        val tracker = StarFocusTracker()
        repeat(8) {
            val reading = tracker.update(frame(count = 2))
            assertNull(reading.hfr); assertNull(reading.bestHfr)
            assertEquals(StarFocusStatus.SEARCHING, reading.status)
        }
    }

    @Test fun `manual single star still needs five samples`() {
        val tracker = StarFocusTracker()
        repeat(4) { assertNull(tracker.update(frame(count = 1, manual = true)).hfr) }
        assertEquals(2.0, tracker.update(frame(count = 1, manual = true)).hfr!!, 0.001)
    }

    @Test fun `a single noisy radius does not dominate a stable median`() {
        val tracker = StarFocusTracker()
        repeat(4) { tracker.update(frame()) }
        val reading = tracker.update(frame(hfr = 7.0))
        assertEquals(2.0, reading.hfr!!, 0.001)
        assertEquals(2.0, reading.bestHfr!!, 0.001)
    }

    @Test fun `unstable measurements cannot establish a best focus`() {
        val tracker = StarFocusTracker()
        val readings = listOf(1.0, 2.0, 4.0, 7.0, 9.0).map { tracker.update(frame(it)) }
        assertEquals(StarFocusStatus.UNSTABLE, readings.last().status)
        assertNull(readings.last().bestHfr)
    }

    @Test fun `narrower tracked stars improve the recorded best`() {
        val tracker = StarFocusTracker()
        repeat(5) { tracker.update(frame(3.0)) }
        val readings = (0 until 5).map { tracker.update(frame(2.0)) }
        assertTrue(readings.any { it.status == StarFocusStatus.IMPROVING })
        assertEquals(2.0, readings.last().bestHfr!!, 0.001)
    }

    @Test fun `worse focus is reported against a retained session best`() {
        val tracker = StarFocusTracker()
        repeat(5) { tracker.update(frame()) }
        val readings = (0 until 5).map { tracker.update(frame(3.0)) }
        assertEquals(StarFocusStatus.WORSENING, readings.last().status)
        assertEquals(2.0, readings.last().bestHfr!!, 0.001)
    }

    @Test fun `changing the field resets comparison instead of claiming improvement`() {
        val tracker = StarFocusTracker()
        repeat(5) { tracker.update(frame(3.0)) }
        repeat(3) { assertNull(tracker.update(frame(1.0, shift = 30.0)).hfr) }
        val readings = (0 until 5).map { tracker.update(frame(1.0, shift = 30.0)) }
        assertFalse(readings.any { it.status == StarFocusStatus.IMPROVING })
        assertEquals(1.0, readings.last().bestHfr!!, 0.001)
    }

    @Test fun `image scale change invalidates old pixel radius comparisons`() {
        val tracker = StarFocusTracker()
        repeat(5) { tracker.update(frame()) }
        val reading = tracker.update(frame(1.0, width = 320))
        assertNull(reading.hfr); assertNull(reading.bestHfr)
        assertEquals(StarFocusStatus.SAMPLING, reading.status)
    }

    @Test fun `exposure change restarts smoothing and best`() {
        val tracker = StarFocusTracker()
        repeat(5) { tracker.update(frame()) }
        val reading = tracker.update(frame(1.7, flux = 2000.0))
        assertNull(reading.hfr); assertNull(reading.bestHfr)
    }

    @Test fun `missing stars never retain a stale good focus status`() {
        val tracker = StarFocusTracker()
        repeat(5) { tracker.update(frame()) }
        val reading = tracker.update(frame(count = 0))
        assertNull(reading.hfr)
        assertEquals(StarFocusStatus.SEARCHING, reading.status)
    }

    @Test fun `gradual gain changes are compared with baseline rather than only last frame`() {
        val tracker = StarFocusTracker()
        repeat(5) { tracker.update(frame()) }
        tracker.update(frame(flux = 1100.0))
        tracker.update(frame(flux = 1300.0))
        val reading = tracker.update(frame(flux = 1600.0))
        assertNull(reading.hfr); assertNull(reading.bestHfr)
    }

    @Test fun `switching to a manual star starts a new comparison`() {
        val tracker = StarFocusTracker()
        repeat(5) { tracker.update(frame()) }
        val reading = tracker.update(frame(1.0, count = 1, manual = true))
        assertNull(reading.hfr); assertNull(reading.bestHfr)
        assertEquals(StarFocusStatus.SAMPLING, reading.status)
    }

    @Test fun `small drift and star ordering do not change cohort identity`() {
        val tracker = StarFocusTracker()
        repeat(4) { tracker.update(frame(shift = it.toDouble())) }
        val incoming = frame(shift = 4.0)
        val reading = tracker.update(incoming.copy(stars = incoming.stars.reversed()))
        assertEquals(2.0, reading.hfr!!, 0.001)
        assertEquals(3, reading.stars.size)
    }

    @Test fun `reset clears old comparison when user selects another star`() {
        val tracker = StarFocusTracker()
        repeat(5) { tracker.update(frame()) }
        tracker.reset()
        val reading = tracker.update(frame())
        assertNull(reading.bestHfr); assertNull(reading.hfr)
    }
}
