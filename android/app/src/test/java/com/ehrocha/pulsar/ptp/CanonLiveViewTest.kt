/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */
package com.ehrocha.pulsar.ptp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.OutputStream

/** Exercise the shared USB / PTP-IP operation sequence without a camera.
 *  Literal wire fixtures follow libgphoto2's EOS property record format. */
class CanonLiveViewTest {
    private data class Call(
        val op: Int, val params: IntArray, val data: ByteArray?, val expectDataIn: Boolean,
    )

    private class RecordingWire(vararg responses: Int) : PtpWire {
        val calls = mutableListOf<Call>()
        private val replies = ArrayDeque(responses.toList())
        var failureAt = -1
        var failure: Exception = PtpProtocolException("test", "disconnected")

        override suspend fun transact(
            opCode: Int, params: IntArray, dataOut: ByteArray?, expectDataIn: Boolean,
        ): PtpClient.Response {
            calls += Call(opCode, params, dataOut, expectDataIn)
            if (calls.size == failureAt) throw failure
            check(replies.isNotEmpty()) { "Unexpected operation: 0x%04X".format(opCode) }
            return PtpClient.Response(replies.removeFirst())
        }

        override suspend fun transactStream(
            opCode: Int, params: IntArray, sink: OutputStream, onProgress: (Long, Long) -> Unit,
        ): PtpClient.Response = error("Live view must not use object streaming")

        override fun close() = Unit
    }

    private fun assertEosWrite(call: Call, propertyLowByte: Int, value: Int) {
        assertEquals(0x9110, call.op)
        assertTrue(call.params.isEmpty())
        assertFalse(call.expectDataIn)
        assertArrayEquals(byteArrayOf(
            12, 0, 0, 0, propertyLowByte.toByte(), 0xD1.toByte(), 0, 0,
            value.toByte(), 0, 0, 0,
        ), call.data)
    }

    @Test fun `EOS numeric property record includes length property and uint32 value`() = runTest {
        val wire = RecordingWire(0x2001)
        PtpClient(wire).canonSetDevicePropValue(0xD1B0, 0x12345678)
        assertEquals(1, wire.calls.size)
        val call = wire.calls.single()
        assertEquals(0x9110, call.op)
        assertTrue(call.params.isEmpty())
        assertFalse(call.expectDataIn)
        assertArrayEquals(byteArrayOf(
            12, 0, 0, 0, 0xB0.toByte(), 0xD1.toByte(), 0, 0,
            0x78, 0x56, 0x34, 0x12,
        ), call.data)
    }

    @Test fun `standard live view requests camera and host displays`() = runTest {
        val wire = RecordingWire(0x2001)
        assertTrue(PtpClient(wire).canonSetLiveViewOutput(true).ok)
        val call = wire.calls.single()
        assertEquals(0x1016, call.op)
        assertArrayEquals(intArrayOf(0xD1B0), call.params)
        assertArrayEquals(byteArrayOf(3, 0, 0, 0), call.data)
        assertFalse(call.expectDataIn)
    }

    @Test fun `R50 property rejection enables live view using EOS property records`() = runTest {
        val wire = RecordingWire(0x200A, 0x2001, 0x2001, 0x2001)
        assertTrue(PtpClient(wire).canonSetLiveViewOutput(true).ok)
        assertEquals(listOf(0x1016, 0x9116, 0x9110, 0x9110), wire.calls.map { it.op })
        val events = wire.calls[1]
        assertTrue(events.params.isEmpty())
        assertNull(events.data)
        assertTrue(events.expectDataIn)
        assertEosWrite(wire.calls[2], 0xB1, 1)
        assertEosWrite(wire.calls[3], 0xB0, 3)
    }

    @Test fun `unsupported standard operation also tries EOS`() = runTest {
        val wire = RecordingWire(0x2005, 0x2001, 0x2001, 0x2001)
        assertTrue(PtpClient(wire).canonSetLiveViewOutput(true).ok)
        assertEquals(4, wire.calls.size)
        assertEosWrite(wire.calls.last(), 0xB0, 3)
    }

    @Test fun `optional event and EVF mode rejection does not hide working output`() = runTest {
        val wire = RecordingWire(0x200A, 0x2005, 0x200A, 0x2001)
        assertTrue(PtpClient(wire).canonSetLiveViewOutput(true).ok)
        assertEquals(4, wire.calls.size)
    }

    @Test fun `busy EVF mode still permits output as in Canon preview setup`() = runTest {
        val wire = RecordingWire(0x200A, 0x2001, 0x2019, 0x2001)
        assertTrue(PtpClient(wire).canonSetLiveViewOutput(true).ok)
        assertEosWrite(wire.calls.last(), 0xB0, 3)
    }

    @Test fun `busy standard output does not trigger unsupported-property fallback`() = runTest {
        val wire = RecordingWire(0x2019)
        assertEquals(0x2019, PtpClient(wire).canonSetLiveViewOutput(true).code)
        assertEquals(1, wire.calls.size)
    }

    @Test fun `general error retries host only without mistaking it for an unsupported operation`() = runTest {
        val wire = RecordingWire(0x2002, 0x2002)
        assertEquals(0x2002, PtpClient(wire).canonSetLiveViewOutput(true).code)
        assertEquals(listOf(0x1016, 0x1016), wire.calls.map { it.op })
    }

    @Test fun `EOS output rejection remains a failed live view attempt`() = runTest {
        val wire = RecordingWire(0x200A, 0x2001, 0x2001, 0x200A, 0x200A, 0x200A)
        assertEquals(0x200A, PtpClient(wire).canonSetLiveViewOutput(true).code)
        assertEquals(6, wire.calls.size)
    }

    @Test fun `EOS output busy is preserved so it is not marked unsupported`() = runTest {
        val wire = RecordingWire(0x200A, 0x2001, 0x2001, 0x2019)
        assertEquals(0x2019, PtpClient(wire).canonSetLiveViewOutput(true).code)
    }

    @Test fun `EOS body rejecting combined display falls back to working host output`() = runTest {
        val wire = RecordingWire(0x200A, 0x2001, 0x2001, 0x201C, 0x200A, 0x2001)
        assertTrue(PtpClient(wire).canonSetLiveViewOutput(true).ok)
        assertEquals(listOf(0x1016, 0x9116, 0x9110, 0x9110, 0x1016, 0x9110), wire.calls.map { it.op })
        assertEosWrite(wire.calls[3], 0xB0, 3)
        assertArrayEquals(byteArrayOf(2, 0, 0, 0), wire.calls[4].data)
        assertEosWrite(wire.calls[5], 0xB0, 2)
    }

    @Test fun `standard body rejecting combined display can still enable host only`() = runTest {
        val wire = RecordingWire(0x201C, 0x2001)
        assertTrue(PtpClient(wire).canonSetLiveViewOutput(true).ok)
        assertEquals(listOf(0x1016, 0x1016), wire.calls.map { it.op })
        assertArrayEquals(byteArrayOf(3, 0, 0, 0), wire.calls[0].data)
        assertArrayEquals(byteArrayOf(2, 0, 0, 0), wire.calls[1].data)
    }

    @Test fun `unsupported combined output still succeeds when EOS accepts host only`() = runTest {
        val wire = RecordingWire(0x200A, 0x2001, 0x2001, 0x200A, 0x200A, 0x2001)
        assertTrue(PtpClient(wire).canonSetLiveViewOutput(true).ok)
        assertEosWrite(wire.calls.last(), 0xB0, 2)
    }

    @Test fun `EOS initialization also runs when only the host-only attempt needs it`() = runTest {
        val wire = RecordingWire(0x201C, 0x200A, 0x2001, 0x2001, 0x2001)
        assertTrue(PtpClient(wire).canonSetLiveViewOutput(true).ok)
        assertEquals(listOf(0x1016, 0x1016, 0x9116, 0x9110, 0x9110), wire.calls.map { it.op })
        assertEosWrite(wire.calls[3], 0xB1, 1)
        assertEosWrite(wire.calls[4], 0xB0, 2)
    }

    @Test fun `rejected camera output falls back to disabling host output`() = runTest {
        val wire = RecordingWire(0x200A, 0x201C, 0x200A, 0x2001)
        assertTrue(PtpClient(wire).canonSetLiveViewOutput(false).ok)
        assertEquals(listOf(0x1016, 0x9110, 0x1016, 0x9110), wire.calls.map { it.op })
        assertEosWrite(wire.calls[1], 0xB0, 1)
        assertEosWrite(wire.calls[3], 0xB0, 0)
    }

    @Test fun `standard stop restores the camera display without enabling EVF`() = runTest {
        val wire = RecordingWire(0x2001)
        assertTrue(PtpClient(wire).canonSetLiveViewOutput(false).ok)
        assertEquals(1, wire.calls.size)
        assertArrayEquals(byteArrayOf(1, 0, 0, 0), wire.calls.single().data)
    }

    @Test fun `EOS stop restores camera output without fetching events or setting mode`() = runTest {
        val wire = RecordingWire(0x200A, 0x2001)
        assertTrue(PtpClient(wire).canonSetLiveViewOutput(false).ok)
        assertEquals(listOf(0x1016, 0x9110), wire.calls.map { it.op })
        assertEosWrite(wire.calls.last(), 0xB0, 1)
    }

    @Test fun `wire failure during EOS setup propagates instead of declaring unsupported`() = runTest {
        val wire = RecordingWire(0x200A).apply { failureAt = 2 }
        try {
            PtpClient(wire).canonSetLiveViewOutput(true)
            fail("Expected wire failure")
        } catch (e: PtpProtocolException) {
            assertSame(wire.failure, e)
        }
        assertEquals(2, wire.calls.size)
    }

    @Test fun `cancellation during EOS setup propagates`() = runTest {
        val wire = RecordingWire(0x200A).apply {
            failureAt = 2
            failure = CancellationException("cancelled")
        }
        try {
            PtpClient(wire).canonSetLiveViewOutput(true)
            fail("Expected cancellation")
        } catch (e: CancellationException) {
            assertSame(wire.failure, e)
        }
        assertEquals(2, wire.calls.size)
    }
}
