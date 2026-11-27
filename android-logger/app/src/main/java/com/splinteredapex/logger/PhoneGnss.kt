package com.splinteredapex.logger

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssClock
import android.location.GnssMeasurement
import android.location.GnssMeasurementRequest
import android.location.GnssMeasurementsEvent
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.altitude.AltitudeConverter
import android.os.Build
import android.os.SystemClock
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Phone GNSS: 1 Hz fixes (location.csv + track.gpx) and raw measurements in Google GnssLogger
 * "Raw" format (gnss_raw.txt), which pipeline/phone_raw_to_rinex.py turns into RINEX.
 */
class PhoneGnss(private val context: Context, private val session: Session) {

    private val lm = context.getSystemService(LocationManager::class.java)
    private val exec = Executors.newSingleThreadExecutor()
    private var altConverter: AltitudeConverter? = null

    private val locFile = session.text(
        "location.csv",
        "t_ns,utc_ms,lat,lon,h_ellipsoid_m,h_msl_m,h_acc_m,v_acc_m,speed_mps,speed_acc_mps,bearing_deg,bearing_acc_deg,sats_used"
    )
    private val rawFile = session.text("gnss_raw.txt", null)
    private val gpx = session.text("track.gpx", null)

    private val locListener = LocationListener { onLocation(it) }

    private val statusCb = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            for (i in 0 until status.satelliteCount) if (status.usedInFix(i)) used++
            Live.satsUsed = used
            Live.satsInView = status.satelliteCount
        }
    }

    private val measCb = object : GnssMeasurementsEvent.Callback() {
        override fun onGnssMeasurementsReceived(event: GnssMeasurementsEvent) = onMeasurements(event)
    }

    @SuppressLint("MissingPermission")
    fun start() {
        rawFile.line("# SplinteredApex Course Logger, GnssLogger-compatible raw file")
        rawFile.line("# Raw," + RAW_COLS)
        rawFile.line("#")
        gpx.line("""<?xml version="1.0" encoding="UTF-8"?>""")
        gpx.line("""<gpx version="1.1" creator="SplinteredApex Course Logger" xmlns="http://www.topografix.com/GPX/1/1">""")
        gpx.line("<trk><name>${session.name}</name><trkseg>")

        if (Build.VERSION.SDK_INT >= 34) altConverter = runCatching { AltitudeConverter() }.getOrNull()

        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, exec, locListener)
        lm.registerGnssStatusCallback(exec, statusCb)
        if (Build.VERSION.SDK_INT >= 31) {
            // Full tracking turns off duty cycling, needed for continuous carrier phase.
            val req = GnssMeasurementRequest.Builder().setFullTracking(true).build()
            lm.registerGnssMeasurementsCallback(req, exec, measCb)
        } else {
            lm.registerGnssMeasurementsCallback(exec, measCb)
        }
    }

    fun stop() {
        runCatching { lm.removeUpdates(locListener) }
        runCatching { lm.unregisterGnssStatusCallback(statusCb) }
        runCatching { lm.unregisterGnssMeasurementsCallback(measCb) }
        exec.shutdown()
        runCatching { exec.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS) }
        gpx.line("</trkseg></trk></gpx>")
    }

    private fun onLocation(loc: Location) {
        val conv = altConverter
        if (conv != null && Build.VERSION.SDK_INT >= 34 && loc.hasAltitude()) {
            runCatching { conv.addMslAltitudeToLocation(context, loc) }
        }
        val tNs = loc.elapsedRealtimeNanos
        Live.utcOffsetMs = loc.time - tNs / 1_000_000L
        Live.fixCount++
        Live.lastFixElapsedNs = tNs
        Live.hAcc = if (loc.hasAccuracy()) loc.accuracy else Float.NaN
        Live.vAcc = if (loc.hasVerticalAccuracy()) loc.verticalAccuracyMeters else Float.NaN
        Live.speedMps = if (loc.hasSpeed()) loc.speed else Float.NaN

        val msl = if (Build.VERSION.SDK_INT >= 34 && loc.hasMslAltitude()) f(loc.mslAltitudeMeters) else ""
        locFile.line(
            listOf(
                tNs, loc.time,
                "%.9f".format(Locale.US, loc.latitude), "%.9f".format(Locale.US, loc.longitude),
                if (loc.hasAltitude()) f(loc.altitude) else "", msl,
                if (loc.hasAccuracy()) f(loc.accuracy.toDouble()) else "",
                if (loc.hasVerticalAccuracy()) f(loc.verticalAccuracyMeters.toDouble()) else "",
                if (loc.hasSpeed()) f(loc.speed.toDouble()) else "",
                if (loc.hasSpeedAccuracy()) f(loc.speedAccuracyMetersPerSecond.toDouble()) else "",
                if (loc.hasBearing()) f(loc.bearing.toDouble()) else "",
                if (loc.hasBearingAccuracy()) f(loc.bearingAccuracyDegrees.toDouble()) else "",
                Live.satsUsed
            ).joinToString(",")
        )
        val ele = if (loc.hasAltitude()) "<ele>${f(loc.altitude)}</ele>" else ""
        gpx.line(
            "<trkpt lat=\"%.9f\" lon=\"%.9f\">%s<time>%s</time></trkpt>"
                .format(Locale.US, loc.latitude, loc.longitude, ele, Session.isoUtc(loc.time))
        )
    }

    private fun onMeasurements(event: GnssMeasurementsEvent) {
        val c = event.clock
        val utc = System.currentTimeMillis()
        var carrier = 0
        val freqs = HashSet<Long>()
        val sb = StringBuilder(4096)
        for (m in event.measurements) {
            sb.setLength(0)
            sb.append("Raw,")
            appendRow(sb, utc, c, m)
            rawFile.line(sb.toString())
            if ((m.accumulatedDeltaRangeState and GnssMeasurement.ADR_STATE_VALID) != 0) carrier++
            if (m.hasCarrierFrequencyHz()) freqs += (m.carrierFrequencyHz / 1e6).toLong()
        }
        Live.rawEpochs++
        Live.rawSignals = event.measurements.size
        Live.rawCarrierValid = carrier
        Live.dualFrequency = freqs.any { it in 1170..1180 }
    }

    private fun appendRow(sb: StringBuilder, utc: Long, c: GnssClock, m: GnssMeasurement) {
        val v = ArrayList<String>(37)
        v += utc.toString()
        v += c.timeNanos.toString()
        v += if (c.hasLeapSecond()) c.leapSecond.toString() else ""
        v += if (c.hasTimeUncertaintyNanos()) d(c.timeUncertaintyNanos) else ""
        v += if (c.hasFullBiasNanos()) c.fullBiasNanos.toString() else ""
        v += if (c.hasBiasNanos()) d(c.biasNanos) else ""
        v += if (c.hasBiasUncertaintyNanos()) d(c.biasUncertaintyNanos) else ""
        v += if (c.hasDriftNanosPerSecond()) d(c.driftNanosPerSecond) else ""
        v += if (c.hasDriftUncertaintyNanosPerSecond()) d(c.driftUncertaintyNanosPerSecond) else ""
        v += c.hardwareClockDiscontinuityCount.toString()
        v += m.svid.toString()
        v += d(m.timeOffsetNanos)
        v += m.state.toString()
        v += m.receivedSvTimeNanos.toString()
        v += m.receivedSvTimeUncertaintyNanos.toString()
        v += d(m.cn0DbHz)
        v += d(m.pseudorangeRateMetersPerSecond)
        v += d(m.pseudorangeRateUncertaintyMetersPerSecond)
        v += m.accumulatedDeltaRangeState.toString()
        v += d(m.accumulatedDeltaRangeMeters)
        v += d(m.accumulatedDeltaRangeUncertaintyMeters)
        v += if (m.hasCarrierFrequencyHz()) d(m.carrierFrequencyHz.toDouble()) else ""
        v += "" // CarrierCycles (deprecated)
        v += "" // CarrierPhase (deprecated)
        v += "" // CarrierPhaseUncertainty (deprecated)
        v += m.multipathIndicator.toString()
        v += if (m.hasSnrInDb()) d(m.snrInDb) else ""
        v += m.constellationType.toString()
        @Suppress("DEPRECATION")
        v += if (m.hasAutomaticGainControlLevelDb()) d(m.automaticGainControlLevelDb) else ""
        if (Build.VERSION.SDK_INT >= 30) {
            v += if (m.hasBasebandCn0DbHz()) d(m.basebandCn0DbHz) else ""
            v += if (m.hasFullInterSignalBiasNanos()) d(m.fullInterSignalBiasNanos) else ""
            v += if (m.hasFullInterSignalBiasUncertaintyNanos()) d(m.fullInterSignalBiasUncertaintyNanos) else ""
            v += if (m.hasSatelliteInterSignalBiasNanos()) d(m.satelliteInterSignalBiasNanos) else ""
            v += if (m.hasSatelliteInterSignalBiasUncertaintyNanos()) d(m.satelliteInterSignalBiasUncertaintyNanos) else ""
        } else {
            repeat(5) { v += "" }
        }
        v += if (m.hasCodeType()) m.codeType else ""
        v += if (c.hasElapsedRealtimeNanos()) c.elapsedRealtimeNanos.toString() else ""
        sb.append(v.joinToString(","))
    }

    companion object {
        const val RAW_COLS = "utcTimeMillis,TimeNanos,LeapSecond,TimeUncertaintyNanos,FullBiasNanos,BiasNanos," +
            "BiasUncertaintyNanos,DriftNanosPerSecond,DriftUncertaintyNanosPerSecond,HardwareClockDiscontinuityCount," +
            "Svid,TimeOffsetNanos,State,ReceivedSvTimeNanos,ReceivedSvTimeUncertaintyNanos,Cn0DbHz," +
            "PseudorangeRateMetersPerSecond,PseudorangeRateUncertaintyMetersPerSecond,AccumulatedDeltaRangeState," +
            "AccumulatedDeltaRangeMeters,AccumulatedDeltaRangeUncertaintyMeters,CarrierFrequencyHz,CarrierCycles," +
            "CarrierPhase,CarrierPhaseUncertainty,MultipathIndicator,SnrInDb,ConstellationType,AgcDb,BasebandCn0DbHz," +
            "FullInterSignalBiasNanos,FullInterSignalBiasUncertaintyNanos,SatelliteInterSignalBiasNanos," +
            "SatelliteInterSignalBiasUncertaintyNanos,CodeType,ChipsetElapsedRealtimeNanos"

        private fun f(x: Double) = "%.3f".format(Locale.US, x)
        private fun d(x: Double) = x.toString()
    }
}
