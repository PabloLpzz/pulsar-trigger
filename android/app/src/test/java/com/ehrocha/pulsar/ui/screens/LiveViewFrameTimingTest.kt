/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */
package com.ehrocha.pulsar.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveViewFrameTimingTest {
    @Test fun `FPS uses delivered frames over elapsed time rather than target cadence`() {
        val meter = LiveViewFrameRate(500)
        repeat(8) { meter.frameReceived() }
        assertEquals(8.0, meter.sample(1500), 0.001)
    }

    @Test fun `a stalled feed reports zero on its next window`() {
        val meter = LiveViewFrameRate(0)
        repeat(12) { meter.frameReceived() }
        assertEquals(12.0, meter.sample(1000), 0.001)
        assertEquals(0.0, meter.sample(2000), 0.001)
    }

    @Test fun `delayed sampling includes the actual longer window`() {
        val meter = LiveViewFrameRate(0)
        repeat(10) { meter.frameReceived() }
        assertEquals(5.0, meter.sample(2000), 0.001)
        repeat(3) { meter.frameReceived() }
        assertEquals(6.0, meter.sample(2500), 0.001)
    }

    @Test fun `zero elapsed sample does not lose frames or divide by zero`() {
        val meter = LiveViewFrameRate(1000)
        meter.frameReceived()
        assertEquals(0.0, meter.sample(1000), 0.001)
        assertEquals(1.0, meter.sample(2000), 0.001)
    }

    @Test fun `fast frame spends only the remaining part of a 33ms cycle waiting`() {
        assertEquals(23L, liveViewPollingDelayMs(10, true))
    }

    @Test fun `slow transfer is not penalized by another fixed 150ms wait`() {
        assertEquals(1L, liveViewPollingDelayMs(200, true))
    }

    @Test fun `missing or undecodable frames back off to avoid hot polling`() {
        assertEquals(100L, liveViewPollingDelayMs(5, false))
        assertEquals(100L, liveViewPollingDelayMs(200, false))
    }
}
