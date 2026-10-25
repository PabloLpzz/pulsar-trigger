/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */
package com.ehrocha.pulsar.ui.screens

/** Count decoded preview updates, sampled against a monotonic clock.
 *  Both sampling and frame delivery run on the UI coroutine dispatcher. */
internal class LiveViewFrameRate(startMillis: Long) {
    private var windowStart = startMillis
    private var frames = 0

    fun frameReceived() { frames++ }

    fun sample(nowMillis: Long): Double {
        val elapsed = nowMillis - windowStart
        if (elapsed <= 0) return 0.0
        val rate = frames * 1000.0 / elapsed
        frames = 0
        windowStart = nowMillis
        return rate
    }
}

/** Aim for at most ~30 requests/s, including transfer and decode time.
 *  Yield even on slow requests; back off on missing/undecodable frames. */
internal fun liveViewPollingDelayMs(elapsedMillis: Long, receivedFrame: Boolean): Long =
    (33L - elapsedMillis).coerceAtLeast(if (receivedFrame) 1L else 100L)
