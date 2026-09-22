package com.screensmith.android.mqtt

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the setup screen tells someone whose broker did not answer.
 *
 * The message is the whole point of the Test button: a form filled in blind
 * is otherwise answered by whether a screen ever shows a value, which is a
 * long way round to find out about a typo. What the client hands over is a
 * chain of wrappers ending in a Netty class name, and a class name tells the
 * person holding the phone nothing they can act on - while hiding the part
 * that would, which is the address that was refused.
 *
 * Checked here rather than on the phone because it is pure text work: the
 * only part of the probe that does not need a broker at the other end. The
 * strings below are the real ones, copied off the device on 2026-09-22.
 */
class BrokerProbeTest {

    @Test
    fun `a refused connection says which address refused it`() {
        val real = Exception(
            "io.netty.channel.AbstractChannel\$AnnotatedConnectException: Connection refused: /127.0.0.1:1883",
        )
        assertEquals("Connection refused: 127.0.0.1:1883", real.readableReason())
    }

    @Test
    fun `the line that names the address wins over the one that does not`() {
        // The real chain off the phone: the middle link carries the address
        // and the deepest one does not. Taking the deepest was the first
        // rule here, and it answered "Connection refused" - of what?
        val chain = Exception(
            "com.hivemq.client.mqtt.exceptions.ConnectionFailedException",
            Exception(
                "io.netty.channel.AbstractChannel\$AnnotatedConnectException: Connection refused: /192.168.8.107:1883",
                Exception("java.net.ConnectException: Connection refused"),
            ),
        )
        assertEquals("Connection refused: 192.168.8.107:1883", chain.readableReason())
    }

    @Test
    fun `a host that does not resolve says the host`() {
        val deep = RuntimeException("wrapper", IllegalStateException("java.net.UnknownHostException: broker.local"))
        assertEquals("broker.local", deep.readableReason())
    }

    @Test
    fun `a timeout keeps its own words`() {
        assertEquals("no answer within 6s", Exception("no answer within 6s").readableReason())
    }

    @Test
    fun `something with nothing to say is named rather than left blank`() {
        // An exception with no message at all still has to produce a line -
        // an empty one reads as "nothing happened", which is the one thing
        // it does not mean.
        assertEquals("IllegalStateException", IllegalStateException().readableReason())
    }
}
