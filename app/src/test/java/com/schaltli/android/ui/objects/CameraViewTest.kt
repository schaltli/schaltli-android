package com.schaltli.android.ui.objects

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * The Camera's rules (the designer's docs/2026-11-09-camera.md): to
 * Content-Length or to the end, nothing over 256 KB, Basic from the project's
 * fields, fitted whole and never enlarged, live for a second. Against a real
 * socket, the way a camera answers.
 */
class CameraViewTest {
    @Test
    fun readsToContentLengthAndNoFurther() {
        val stream = ByteArrayInputStream(ByteArray(100) { it.toByte() })
        val body = CameraFetch.readBody(stream, 40)
        assertEquals(40, body!!.size)
        // The rest stays for the next answer on a kept connection.
        assertEquals(60, stream.available())
    }

    @Test
    fun withoutContentLengthToTheEnd() {
        assertEquals(1234, CameraFetch.readBody(ByteArrayInputStream(ByteArray(1234)), -1)!!.size)
    }

    @Test
    fun over256KbIsNoPictureWithOrWithoutTheHeader() {
        val big = CameraFetch.MAX_BYTES + 1
        assertNull(CameraFetch.readBody(ByteArrayInputStream(ByteArray(big)), big.toLong()))
        assertNull(CameraFetch.readBody(ByteArrayInputStream(ByteArray(big)), -1))
        assertEquals(CameraFetch.MAX_BYTES, CameraFetch.readBody(ByteArrayInputStream(ByteArray(CameraFetch.MAX_BYTES)), -1)!!.size)
    }

    @Test
    fun aBodyEndingEarlyIsNoPicture() {
        assertNull(CameraFetch.readBody(ByteArrayInputStream(ByteArray(10)), 20))
    }

    @Test
    fun basicOnlyWithCredentials() {
        assertNull(CameraFetch.basicAuth("", ""))
        // RFC 7617's own example.
        assertEquals("Basic QWxhZGRpbjpvcGVuIHNlc2FtZQ==", CameraFetch.basicAuth("Aladdin", "open sesame"))
    }

    @Test
    fun fittedWholeCentredNeverEnlarged() {
        // A 320x240 picture in a 320x240 box: exactly.
        assertArrayEquals(intArrayOf(10, 20, 320, 240), CameraFetch.fit(320, 240, 10, 20, 320, 240))
        // A smaller picture stays its size, centred.
        assertArrayEquals(intArrayOf(80, 60, 160, 120), CameraFetch.fit(160, 120, 0, 0, 320, 240))
        // VGA into 160x120: halved.
        assertArrayEquals(intArrayOf(0, 0, 160, 120), CameraFetch.fit(640, 480, 0, 0, 160, 120))
        // A wide picture in a 4:3 box: whole, with bands above and below.
        assertArrayEquals(intArrayOf(0, 30, 320, 180), CameraFetch.fit(1280, 720, 0, 0, 320, 240))
    }

    @Test
    fun sampledDownOnlyWhileItStillFillsWhatIsDrawn() {
        // 1080p into 320x240 units at 3 pixels a unit: 960 wide drawn, 1920 sent - halved once.
        assertEquals(2, CameraFetch.sampleSize(1920, 1080, 320, 240, 3f))
        // At one pixel a unit, down to 480 wide.
        assertEquals(4, CameraFetch.sampleSize(1920, 1080, 320, 240, 1f))
        assertEquals(1, CameraFetch.sampleSize(320, 240, 320, 240, 3f))
    }

    @Test
    fun liveForASecondAndNotBeforeTheFirst() {
        assertFalse(CameraFetch.isLive(0, 5000))
        assertTrue(CameraFetch.isLive(4001, 5000))
        assertFalse(CameraFetch.isLive(4000, 5000))
    }

    /** A camera answering [answers] in turn, one connection each. */
    private fun camera(vararg answers: ByteArray, seen: MutableList<String> = mutableListOf()): String {
        val server = ServerSocket(0)
        thread(isDaemon = true) {
            for (answer in answers) {
                server.accept().use { client ->
                    val reader = client.getInputStream().bufferedReader()
                    val head = StringBuilder()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        head.append(line).append('\n')
                    }
                    synchronized(seen) { seen.add(head.toString()) }
                    client.getOutputStream().write(answer)
                    client.getOutputStream().flush()
                }
            }
            server.close()
        }
        return "http://127.0.0.1:${server.localPort}/shot.jpg"
    }

    private fun answer(status: String, body: ByteArray, length: Boolean = true): ByteArray =
        ("HTTP/1.1 $status\r\nContent-Type: image/jpeg\r\n" +
            (if (length) "Content-Length: ${body.size}\r\n" else "") +
            "Connection: close\r\n\r\n").toByteArray() + body

    @Test
    fun aPictureWithTheProjectsCredentials() {
        val seen = mutableListOf<String>()
        val url = camera(answer("200 OK", ByteArray(500) { 7 }), seen = seen)
        val body = CameraFetch.fetch(url, CameraFetch.basicAuth("hilcam", "s3cret"))
        assertEquals(500, body!!.size)
        assertTrue(seen.single().contains("Authorization: Basic aGlsY2FtOnMzY3JldA=="))
    }

    @Test
    fun withoutContentLengthToTheConnectionsEnd() {
        assertEquals(700, CameraFetch.fetch(camera(answer("200 OK", ByteArray(700), length = false)), null)!!.size)
    }

    @Test
    fun aRefusalIsNoPictureAndSaysWhy() {
        assertNull(CameraFetch.fetch(camera(answer("401 Unauthorized", "no".toByteArray())), null))
        assertEquals("HTTP 401", CameraStats.lastFailure)
    }

    @Test
    fun anOversizedPictureIsNoPicture() {
        val big = ByteArray(CameraFetch.MAX_BYTES + 10)
        assertNull(CameraFetch.fetch(camera(answer("200 OK", big)), null))
        assertNull(CameraFetch.fetch(camera(answer("200 OK", big, length = false)), null))
    }

    @Test
    fun noCameraIsNoPictureNotACrash() {
        val port = ServerSocket(0).use { it.localPort }
        assertNull(CameraFetch.fetch("http://127.0.0.1:$port/shot.jpg", null))
    }

    @Test
    fun theStatsNameNoCredentials() {
        CameraFetch.fetch(camera(answer("401 Unauthorized", ByteArray(0))), CameraFetch.basicAuth("hilcam", "s3cret"))
        val report = CameraStats.report().toString()
        assertFalse(report.contains("s3cret"))
        assertFalse(report.contains("hilcam"))
    }
}
