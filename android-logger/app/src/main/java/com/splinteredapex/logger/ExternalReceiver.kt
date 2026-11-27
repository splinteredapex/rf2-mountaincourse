package com.splinteredapex.logger

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.InputStream
import java.io.OutputStream
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * External GNSS receiver (e.g. u-blox ZED-F9P) over USB or Bluetooth serial.
 * Every byte received is saved untouched to external_raw.ubx (UBX + NMEA mix, readable by RTKLIB).
 * Parsed fixes go to external_fix.csv.
 */
class ExternalReceiver(
    private val context: Context,
    private val session: Session,
    private val prefs: Prefs,
) {
    private val raw = session.binary("external_raw.ubx")
    private val fixes = session.text(
        "external_fix.csv",
        "t_ns,utc_ms,msg,lat,lon,h_ellipsoid_m,h_msl_m,h_acc_m,v_acc_m,quality,sats,speed_mps,heading_deg"
    )

    @Volatile private var running = false
    private var thread: Thread? = null
    private val writeLock = Any()
    @Volatile private var out: ((ByteArray) -> Unit)? = null
    @Volatile private var closer: (() -> Unit)? = null

    private val parser = StreamParser()
    private var lastPvtNs = 0L
    private var rateCount = 0
    private var rateStartNs = 0L
    private var gstLatStd = Float.NaN
    private var gstLonStd = Float.NaN
    private var gstAltStd = Float.NaN

    fun start() {
        running = true
        thread = Thread({ loop() }, "external-gnss").apply { start() }
    }

    fun stop() {
        running = false
        runCatching { closer?.invoke() }
        thread?.join(3000)
        Live.extState = "Not used"
        Live.extFixLabel = ""
        Live.extFixQuality = 0
        Live.lastGga = null
    }

    /** Sends bytes to the receiver (used for RTCM corrections). Silently dropped while disconnected. */
    fun write(bytes: ByteArray) {
        val o = out ?: return
        synchronized(writeLock) { runCatching { o(bytes) } }
    }

    private fun loop() {
        while (running) {
            try {
                when (prefs.source) {
                    ExternalSource.USB -> runUsb()
                    ExternalSource.BLUETOOTH -> runBluetooth()
                    ExternalSource.NONE -> return
                }
            } catch (e: Exception) {
                Live.extState = "Disconnected: ${e.message ?: e.javaClass.simpleName}"
            }
            out = null
            closer = null
            Live.extFixQuality = 0
            if (running) SystemClock.sleep(2000)
        }
    }

    // ---------------- USB ----------------

    private fun findUsbDriver(um: UsbManager): UsbSerialDriver? {
        UsbSerialProber.getDefaultProber().findAllDrivers(um).firstOrNull()?.let { return it }
        val table = ProbeTable()
            .addProduct(0x1546, 0x01a9, CdcAcmSerialDriver::class.java) // u-blox F9 series
            .addProduct(0x1546, 0x01a8, CdcAcmSerialDriver::class.java) // u-blox M8 series
            .addProduct(0x1546, 0x01a7, CdcAcmSerialDriver::class.java) // u-blox 7
        return UsbSerialProber(table).findAllDrivers(um).firstOrNull()
    }

    private fun runUsb() {
        val um = context.getSystemService(UsbManager::class.java)
        Live.extState = "Looking for USB receiver"
        val driver = findUsbDriver(um) ?: throw IllegalStateException("no USB receiver plugged in")
        if (!um.hasPermission(driver.device)) {
            Live.extState = "Allow USB access on screen"
            if (!requestUsbPermission(um, driver.device)) throw IllegalStateException("USB access not allowed")
        }
        val conn = um.openDevice(driver.device) ?: throw IllegalStateException("could not open USB device")
        val port = driver.ports[0]
        port.open(conn)
        closer = { runCatching { port.close() } }
        runCatching { port.setParameters(prefs.baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE) }
        runCatching { port.dtr = true; port.rts = true }
        out = { b -> port.write(b, 500) }
        Live.extState = "Connected (USB, ${driver.device.productName ?: "serial"})"

        if (prefs.configureUblox) write(Ubx.configure10HzRaw())

        val buf = ByteArray(16384)
        try {
            while (running) {
                val n = port.read(buf, 200)
                if (n > 0) onBytes(buf, n)
            }
        } finally {
            runCatching { port.close() }
        }
    }

    private fun requestUsbPermission(um: UsbManager, device: UsbDevice): Boolean {
        val action = context.packageName + ".USB_PERMISSION"
        val latch = CountDownLatch(1)
        var granted = false
        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                granted = i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                latch.countDown()
            }
        }
        ContextCompat.registerReceiver(context, rx, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(context, 0, Intent(action).setPackage(context.packageName), flags)
            um.requestPermission(device, pi)
            latch.await(60, TimeUnit.SECONDS)
        } finally {
            runCatching { context.unregisterReceiver(rx) }
        }
        return granted
    }

    // ---------------- Bluetooth ----------------

    @SuppressLint("MissingPermission")
    private fun runBluetooth() {
        val addr = prefs.btAddress
        if (addr.isBlank()) throw IllegalStateException("choose a Bluetooth device in Setup")
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: throw IllegalStateException("no Bluetooth on this phone")
        val dev = adapter.getRemoteDevice(addr)
        Live.extState = "Connecting to ${prefs.btName.ifBlank { addr }}"
        val sock: BluetoothSocket = dev.createRfcommSocketToServiceRecord(SPP_UUID)
        closer = { runCatching { sock.close() } }
        runCatching { adapter.cancelDiscovery() }
        sock.connect()
        val input: InputStream = sock.inputStream
        val output: OutputStream = sock.outputStream
        out = { b -> output.write(b); output.flush() }
        Live.extState = "Connected (Bluetooth, ${prefs.btName.ifBlank { addr }})"
        val buf = ByteArray(8192)
        try {
            while (running) {
                val n = input.read(buf)
                if (n < 0) throw IllegalStateException("Bluetooth link closed")
                if (n > 0) onBytes(buf, n)
            }
        } finally {
            runCatching { sock.close() }
        }
    }

    // ---------------- Parsing ----------------

    private fun onBytes(buf: ByteArray, n: Int) {
        raw.write(buf, 0, n)
        Live.extBytes += n
        parser.feed(buf, n, ::onUbx, ::onNmea)
        val now = SystemClock.elapsedRealtimeNanos()
        if (now - lastPvtNs > 3_000_000_000L && now - rateStartNs > 3_000_000_000L) Live.extRateHz = 0
    }

    private fun countRate(tNs: Long) {
        rateCount++
        if (tNs - rateStartNs >= 1_000_000_000L) {
            Live.extRateHz = rateCount
            rateCount = 0
            rateStartNs = tNs
        }
    }

    private fun onUbx(cls: Int, id: Int, p: ByteArray) {
        if (cls != 0x01 || id != 0x07 || p.size < 92) return // NAV-PVT only
        val t = SystemClock.elapsedRealtimeNanos()
        lastPvtNs = t
        countRate(t)
        val valid = p[11].toInt()
        val fixType = u1(p, 20)
        val flags = u1(p, 21)
        val gnssFixOk = flags and 1 != 0
        val diff = flags and 2 != 0
        val carr = (flags shr 6) and 3
        val sats = u1(p, 23)
        val lon = i4(p, 24) * 1e-7
        val lat = i4(p, 28) * 1e-7
        val h = i4(p, 32) / 1000.0
        val hMsl = i4(p, 36) / 1000.0
        val hAcc = u4(p, 40) / 1000.0
        val vAcc = u4(p, 44) / 1000.0
        val gSpeed = i4(p, 60) / 1000.0
        val head = i4(p, 64) * 1e-5
        val quality = when {
            !gnssFixOk || fixType < 2 -> 0
            carr == 2 -> 4
            carr == 1 -> 5
            diff -> 2
            else -> 1
        }
        var utcMs = 0L
        if (valid and 3 == 3) {
            val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            cal.clear()
            cal.set(u2(p, 4), u1(p, 6) - 1, u1(p, 7), u1(p, 8), u1(p, 9), u1(p, 10))
            utcMs = cal.timeInMillis + Math.floorDiv(i4(p, 16), 1_000_000)
        }
        publish(t, utcMs, "PVT", lat, lon, h, hMsl, hAcc, vAcc, quality, sats, gSpeed, head)
        Live.lastGga = Nmea.buildGga(utcMs, lat, lon, hMsl, h - hMsl, quality, sats)
    }

    private fun onNmea(line: String) {
        val body = line.substringBefore('*')
        val f = body.split(',')
        if (f.isEmpty() || f[0].length < 6) return
        when (f[0].substring(3)) {
            "GGA" -> {
                Live.lastGga = line + "\r\n"
                val t = SystemClock.elapsedRealtimeNanos()
                if (t - lastPvtNs < 2_000_000_000L) return // NAV-PVT is better; GGA kept for NTRIP only
                countRate(t)
                val q = f.getOrNull(6)?.toIntOrNull() ?: 0
                val lat = Nmea.latLon(f.getOrNull(2), f.getOrNull(3))
                val lon = Nmea.latLon(f.getOrNull(4), f.getOrNull(5))
                val sats = f.getOrNull(7)?.toIntOrNull() ?: 0
                val msl = f.getOrNull(9)?.toDoubleOrNull()
                val sep = f.getOrNull(11)?.toDoubleOrNull() ?: 0.0
                if (lat == null || lon == null) { publishNoFix(); return }
                val hAcc = if (gstLatStd.isNaN()) Double.NaN else kotlin.math.hypot(gstLatStd.toDouble(), gstLonStd.toDouble())
                publish(
                    t, 0L, "GGA", lat, lon, msl?.plus(sep) ?: Double.NaN, msl ?: Double.NaN,
                    hAcc, gstAltStd.toDouble(), q, sats, Double.NaN, Double.NaN
                )
            }
            "GST" -> {
                gstLatStd = f.getOrNull(6)?.toFloatOrNull() ?: Float.NaN
                gstLonStd = f.getOrNull(7)?.toFloatOrNull() ?: Float.NaN
                gstAltStd = f.getOrNull(8)?.toFloatOrNull() ?: Float.NaN
            }
        }
    }

    private fun publishNoFix() {
        Live.extFixQuality = 0
        Live.extFixLabel = "No fix"
    }

    private fun publish(
        t: Long, utcMs: Long, msg: String, lat: Double, lon: Double, h: Double, hMsl: Double,
        hAcc: Double, vAcc: Double, q: Int, sats: Int, speed: Double, head: Double,
    ) {
        Live.extFixQuality = q
        Live.extFixLabel = qualityLabel(q)
        Live.extHAccM = hAcc.toFloat()
        Live.extVAccM = vAcc.toFloat()
        Live.extSats = sats
        fun n(x: Double, d: Int) = if (x.isNaN()) "" else "%.${d}f".format(Locale.US, x)
        fixes.line(
            "$t,${if (utcMs > 0) utcMs else ""},$msg,${n(lat, 9)},${n(lon, 9)},${n(h, 3)},${n(hMsl, 3)}," +
                "${n(hAcc, 3)},${n(vAcc, 3)},$q,$sats,${n(speed, 3)},${n(head, 2)}"
        )
    }

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        fun qualityLabel(q: Int) = when (q) {
            4 -> "RTK FIXED"
            5 -> "RTK float"
            2 -> "DGNSS"
            1 -> "GNSS only"
            else -> "No fix"
        }

        private fun u1(p: ByteArray, o: Int) = p[o].toInt() and 0xff
        private fun u2(p: ByteArray, o: Int) = u1(p, o) or (u1(p, o + 1) shl 8)
        private fun i4(p: ByteArray, o: Int) = u1(p, o) or (u1(p, o + 1) shl 8) or (u1(p, o + 2) shl 16) or (u1(p, o + 3) shl 24)
        private fun u4(p: ByteArray, o: Int) = i4(p, o).toLong() and 0xffffffffL
    }
}

/** Splits a mixed serial stream into UBX frames and NMEA sentences; RTCM and noise are skipped. */
class StreamParser {
    private var state = 0
    private var cls = 0
    private var id = 0
    private var len = 0
    private var payload = ByteArray(0)
    private var pos = 0
    private var ckA = 0
    private var ckB = 0
    private val nmea = StringBuilder(128)

    fun feed(buf: ByteArray, n: Int, onUbx: (Int, Int, ByteArray) -> Unit, onNmea: (String) -> Unit) {
        for (i in 0 until n) {
            val b = buf[i].toInt() and 0xff
            when (state) {
                0 -> when (b) {
                    0xB5 -> state = 1
                    '$'.code -> { nmea.setLength(0); nmea.append('$'); state = 10 }
                }
                1 -> state = if (b == 0x62) 2 else 0
                2 -> { cls = b; ckA = b; ckB = ckA; state = 3 }
                3 -> { id = b; add(b); state = 4 }
                4 -> { len = b; add(b); state = 5 }
                5 -> {
                    len = len or (b shl 8); add(b)
                    if (len > 8192) { state = 0 } else {
                        payload = ByteArray(len); pos = 0
                        state = if (len == 0) 7 else 6
                    }
                }
                6 -> { payload[pos++] = b.toByte(); add(b); if (pos == len) state = 7 }
                7 -> state = if (b == (ckA and 0xff)) 8 else 0
                8 -> { if (b == (ckB and 0xff)) onUbx(cls, id, payload); state = 0 }
                10 -> {
                    if (b == '\n'.code || b == '\r'.code) {
                        if (nmea.length > 6) onNmea(nmea.toString())
                        state = 0
                    } else if (b < 0x20 || b > 0x7e || nmea.length > 120) {
                        state = 0
                    } else nmea.append(b.toChar())
                }
            }
        }
    }

    private fun add(b: Int) {
        ckA = (ckA + b) and 0xff
        ckB = (ckB + ckA) and 0xff
    }
}

object Ubx {
    private fun frame(cls: Int, id: Int, payload: ByteArray): ByteArray {
        val out = ByteArray(payload.size + 8)
        out[0] = 0xB5.toByte(); out[1] = 0x62
        out[2] = cls.toByte(); out[3] = id.toByte()
        out[4] = (payload.size and 0xff).toByte(); out[5] = (payload.size shr 8).toByte()
        System.arraycopy(payload, 0, out, 6, payload.size)
        var a = 0; var b = 0
        for (i in 2 until payload.size + 6) { a = (a + (out[i].toInt() and 0xff)) and 0xff; b = (b + a) and 0xff }
        out[out.size - 2] = a.toByte(); out[out.size - 1] = b.toByte()
        return out
    }

    /**
     * UBX-CFG-VALSET to RAM only (lost at power-off, nothing permanent changes):
     * 10 Hz navigation, NAV-PVT + RXM-RAWX + RXM-SFRBX + NMEA GGA on USB, RTCM3 input on USB.
     */
    fun configure10HzRaw(): ByteArray {
        val kv = java.io.ByteArrayOutputStream()
        fun key(k: Long) { for (s in 0..3) kv.write(((k shr (8 * s)) and 0xff).toInt()) }
        fun u1(k: Long, v: Int) { key(k); kv.write(v) }
        fun u2(k: Long, v: Int) { key(k); kv.write(v and 0xff); kv.write(v shr 8) }
        u2(0x30210001, 100)  // CFG-RATE-MEAS = 100 ms
        u1(0x20910009, 1)    // CFG-MSGOUT-UBX_NAV_PVT_USB
        u1(0x209102a7, 1)    // CFG-MSGOUT-UBX_RXM_RAWX_USB
        u1(0x20910234, 1)    // CFG-MSGOUT-UBX_RXM_SFRBX_USB
        u1(0x209100bd, 1)    // CFG-MSGOUT-NMEA_ID_GGA_USB
        u1(0x10770004, 1)    // CFG-USBINPROT-RTCM3X
        val payload = byteArrayOf(0, 1, 0, 0) + kv.toByteArray() // version 0, layer RAM
        return frame(0x06, 0x8A, payload)
    }
}

object Nmea {
    fun latLon(v: String?, hemi: String?): Double? {
        if (v.isNullOrBlank() || hemi.isNullOrBlank()) return null
        val dot = v.indexOf('.')
        if (dot < 3) return null
        val deg = v.substring(0, dot - 2).toDoubleOrNull() ?: return null
        val min = v.substring(dot - 2).toDoubleOrNull() ?: return null
        val x = deg + min / 60.0
        return if (hemi == "S" || hemi == "W") -x else x
    }

    fun buildGga(utcMs: Long, lat: Double, lon: Double, msl: Double, sep: Double, q: Int, sats: Int): String {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = if (utcMs > 0) utcMs else System.currentTimeMillis()
        val time = "%02d%02d%02d.%02d".format(
            Locale.US, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), cal.get(Calendar.SECOND),
            cal.get(Calendar.MILLISECOND) / 10
        )
        fun dm(x: Double, degDigits: Int): String {
            val a = abs(x); val d = a.toInt(); val m = (a - d) * 60.0
            return "%0${degDigits}d%010.7f".format(Locale.US, d, m)
        }
        val body = "GPGGA,$time,${dm(lat, 2)},${if (lat >= 0) "N" else "S"},${dm(lon, 3)},${if (lon >= 0) "E" else "W"}," +
            "$q,%02d,1.0,%.3f,M,%.3f,M,,".format(Locale.US, sats, msl, sep)
        var ck = 0
        for (c in body) ck = ck xor c.code
        return "$" + body + "*" + "%02X".format(ck) + "\r\n"
    }
}
