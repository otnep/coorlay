package com.coorlay.airplay

import java.net.DatagramSocket
import java.net.SocketAddress
import java.net.SocketException
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NtpClockListenTest {
    @Test
    fun failedBindClosesSocketAndAllowsAnotherListen() {
        val failure = SocketException("bind failed: EADDRNOTAVAIL")
        val rejected = object : DatagramSocket(null as SocketAddress?) {
            override fun bind(address: SocketAddress?) { throw failure }
        }
        val clock = NtpClock()
        try {
            try {
                clock.listen { rejected }
                fail("The bind failure must propagate")
            } catch (error: SocketException) {
                assertSame(failure, error)
            }
            assertTrue("Failed SETUP must release its UDP descriptor", rejected.isClosed)
            assertTrue("A failed bind must reset running", clock.listen() > 0)
        } finally {
            clock.close()
            rejected.close()
        }
    }

    @Test
    fun socketCreationFailureDoesNotLeaveClockRunning() {
        val failure = SocketException("socket failed: EMFILE")
        val clock = NtpClock()
        try {
            try {
                clock.listen { throw failure }
                fail("The creation failure must propagate")
            } catch (error: SocketException) {
                assertSame(failure, error)
            }
            assertTrue(clock.listen() > 0)
        } finally {
            clock.close()
        }
    }
}
