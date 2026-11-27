package com.splinteredapex.logger

import android.os.SystemClock
import android.util.Base64
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * NTRIP v1 client: pulls RTCM3 corrections from a caster and passes them to the receiver.
 * Sends the receiver's position (GGA) every 10 s, which network (VRS) services need.
 * Corrections are also saved to ntrip_rtcm.bin so a run can be re-processed later.
 */
class NtripClient(
    private val prefs: Prefs,
    session: Session,
    private val sink: (ByteArray) -> Unit,
) {
    private val log = session.binary("ntrip_rtcm.bin")
    @Volatile private var running = false
    @Volatile private var socket: Socket? = null
    private var thread: Thread? = null

    fun start() {
        running = true
        thread = Thread({ loop() }, "ntrip").apply { start() }
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
        thread?.join(3000)
        Live.ntripState = ""
    }

    private fun loop() {
        var backoff = 2000L
        while (running) {
            try {
                connectAndStream()
                backoff = 2000L
            } catch (e: Exception) {
                Live.ntripState = "Error: ${e.message ?: e.javaClass.simpleName}"
            }
            runCatching { socket?.close() }
            if (!running) break
            SystemClock.sleep(backoff)
            backoff = (backoff * 2).coerceAtMost(30_000L)
        }
    }

    private fun connectAndStream() {
        val host = prefs.ntripHost.trim()
        val mount = prefs.ntripMount.trim().removePrefix("/")
        if (host.isEmpty() || mount.isEmpty()) throw IllegalStateException("set caster host and mountpoint")
        Live.ntripState = "Connecting to $host"
        val s = Socket()
        socket = s
        s.connect(InetSocketAddress(host, prefs.ntripPort), 10_000)
        s.soTimeout = 15_000
        val auth = Base64.encodeToString("${prefs.ntripUser}:${prefs.ntripPass}".toByteArray(), Base64.NO_WRAP)
        val req = "GET /$mount HTTP/1.0\r\n" +
            "User-Agent: NTRIP SplinteredApexLogger/1.0\r\n" +
            "Accept: */*\r\n" +
            "Authorization: Basic $auth\r\n" +
            "Connection: close\r\n\r\n"
        val os = s.getOutputStream()
        os.write(req.toByteArray())
        Live.lastGga?.let { os.write(it.toByteArray()) }
        os.flush()

        val input = s.getInputStream()
        val status = readLine(input)
        if (!status.contains("200")) throw IllegalStateException("caster said: ${status.take(60)}")
        if (status.startsWith("HTTP")) {
            // Skip headers
            while (true) { if (readLine(input).isEmpty()) break }
        }
        Live.ntripState = "Receiving corrections"
        val buf = ByteArray(4096)
        var lastGgaSent = SystemClock.elapsedRealtime()
        while (running) {
            val n = input.read(buf)
            if (n < 0) throw IllegalStateException("caster closed the connection")
            if (n > 0) {
                val chunk = buf.copyOf(n)
                sink(chunk)
                log.write(chunk, 0, n)
                Live.ntripBytes += n
            }
            val now = SystemClock.elapsedRealtime()
            if (now - lastGgaSent > 10_000) {
                Live.lastGga?.let { os.write(it.toByteArray()); os.flush() }
                lastGgaSent = now
            }
        }
    }

    private fun readLine(input: InputStream): String {
        val sb = StringBuilder()
        while (sb.length < 512) {
            val c = input.read()
            if (c < 0) break
            if (c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
        }
        return sb.toString()
    }
}
