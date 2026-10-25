/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */
package com.ehrocha.pulsar.ptp

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** USB framing tests: a rejected EOS GetEvent has no DATA phase. Consume
 *  its RESPONSE exactly once so subsequent EOS property writes stay aligned. */
class BulkPtpWireResponseTest {
    private fun container(type: Int, code: Int, txId: Int, data: ByteArray = byteArrayOf()): ByteArray =
        ByteBuffer.allocate(12 + data.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(12 + data.size).putShort(type.toShort()).putShort(code.toShort())
            .putInt(txId).put(data).array()

    private class UsbFixture(vararg replies: ByteArray) {
        val incoming = ArrayDeque(replies.toList())
        val sent = mutableListOf<ByteArray>()
        private val connection = mockk<UsbDeviceConnection>()
        private val bulkIn = mockk<UsbEndpoint>()
        private val bulkOut = mockk<UsbEndpoint>()
        val wire: BulkPtpWire

        init {
            every { connection.bulkTransfer(bulkOut, any(), any(), any()) } answers {
                val size = thirdArg<Int>()
                sent += secondArg<ByteArray>().copyOf(size)
                size
            }
            every { connection.bulkTransfer(bulkIn, any(), any(), any()) } answers {
                check(incoming.isNotEmpty()) { "Unexpected extra USB read" }
                val reply = incoming.removeFirst()
                reply.copyInto(secondArg<ByteArray>())
                reply.size
            }
            wire = BulkPtpWire(connection, bulkIn, bulkOut)
        }
    }

    @Test fun `data-in rejection returns camera response without reading twice`() = runTest {
        val usb = UsbFixture(container(3, 0x2005, 1))
        val result = usb.wire.transact(0x9116, expectDataIn = true)
        assertEquals(0x2005, result.code)
        assertNull(result.data)
        assertTrue(usb.incoming.isEmpty())
    }

    @Test fun `ordinary data-in still consumes data then response`() = runTest {
        val data = byteArrayOf(4, 3, 2, 1)
        val usb = UsbFixture(container(2, 0x9116, 1, data), container(3, 0x2001, 1))
        val result = usb.wire.transact(0x9116, expectDataIn = true)
        assertTrue(result.ok)
        assertArrayEquals(data, result.data)
        assertTrue(usb.incoming.isEmpty())
    }

    @Test fun `response without data preserves uint32 response parameters`() = runTest {
        val usb = UsbFixture(container(3, 0x200A, 1, byteArrayOf(0xB0.toByte(), 0xD1.toByte(), 0, 0)))
        val result = usb.wire.transact(0x9116, expectDataIn = true)
        assertEquals(0x200A, result.code)
        assertArrayEquals(intArrayOf(0xD1B0), result.params)
    }

    @Test fun `rejected USB GetEvent does not prevent EOS fallback from enabling live view`() = runTest {
        val usb = UsbFixture(
            container(3, 0x200A, 1), // standard EVF output rejected
            container(3, 0x2005, 2), // optional GetEvent has no DATA
            container(3, 0x2001, 3), // EOS EVFMode
            container(3, 0x2001, 4), // EOS EVFOutput
        )
        assertTrue(PtpClient(usb.wire).canonSetLiveViewOutput(true).ok)
        assertTrue(usb.incoming.isEmpty())
        // Standard command+data, GetEvent command, two EOS command+data pairs.
        assertEquals(7, usb.sent.size)
        val outputData = ByteBuffer.wrap(usb.sent.last()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(24, outputData.int)
        assertEquals(2, outputData.short.toInt())
        assertEquals(0x9110, outputData.short.toInt() and 0xFFFF)
        assertEquals(4, outputData.int)
        assertEquals(12, outputData.int)
        assertEquals(0xD1B0, outputData.int)
        assertEquals(2, outputData.int)
    }

    @Test fun `early response with wrong transaction id is rejected`() = runTest {
        val usb = UsbFixture(container(3, 0x2005, 99))
        try {
            usb.wire.transact(0x9116, expectDataIn = true)
            fail("Expected transaction mismatch")
        } catch (e: PtpProtocolException) {
            assertTrue(e.message!!.contains("transaction id mismatch"))
        }
    }

    @Test fun `truncated response parameters are a protocol error`() = runTest {
        val response = container(3, 0x2005, 1)
        ByteBuffer.wrap(response).order(ByteOrder.LITTLE_ENDIAN).putInt(16)
        val usb = UsbFixture(response)
        try {
            usb.wire.transact(0x9116, expectDataIn = true)
            fail("Expected invalid response length")
        } catch (e: PtpProtocolException) {
            assertTrue(e.message!!.contains("invalid response length"))
        }
    }
}
