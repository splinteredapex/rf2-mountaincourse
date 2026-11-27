package com.splinteredapex.logger

import android.os.SystemClock

/** Live values shown on screen. Written by the logger threads, read by the UI every 200 ms. */
object Live {
    @Volatile var recording = false
    @Volatile var sessionName = ""
    @Volatile var startElapsedNs = 0L
    @Volatile var bytesWritten = 0L
    @Volatile var markCount = 0
    @Volatile var lastError = ""

    // Phone GNSS
    @Volatile var fixCount = 0L
    @Volatile var lastFixElapsedNs = 0L
    @Volatile var hAcc = Float.NaN
    @Volatile var vAcc = Float.NaN
    @Volatile var speedMps = Float.NaN
    @Volatile var satsUsed = 0
    @Volatile var satsInView = 0
    @Volatile var rawEpochs = 0L
    @Volatile var rawSignals = 0
    @Volatile var rawCarrierValid = 0
    @Volatile var dualFrequency = false

    /** GNSS UTC minus elapsedRealtime, in ms. Used to stamp events with GPS time. */
    @Volatile var utcOffsetMs = Long.MIN_VALUE

    // Motion sensors (events per second, updated once a second)
    @Volatile var accHz = 0
    @Volatile var gyrHz = 0
    @Volatile var magHz = 0
    @Volatile var baroHz = 0
    @Volatile var rotHz = 0
    @Volatile var pressureHpa = Float.NaN

    // External receiver
    @Volatile var extState = "Not used"
    @Volatile var extFixLabel = ""
    @Volatile var extFixQuality = 0 // 0 none, 1 GNSS, 2 DGNSS, 4 RTK fixed, 5 RTK float
    @Volatile var extHAccM = Float.NaN
    @Volatile var extVAccM = Float.NaN
    @Volatile var extSats = 0
    @Volatile var extRateHz = 0
    @Volatile var extBytes = 0L
    @Volatile var lastGga: String? = null

    // NTRIP
    @Volatile var ntripState = ""
    @Volatile var ntripBytes = 0L

    fun gnssUtcNowMs(): Long? {
        val off = utcOffsetMs
        if (off == Long.MIN_VALUE) return null
        return SystemClock.elapsedRealtime() + off
    }

    fun resetForSession() {
        bytesWritten = 0; markCount = 0; lastError = ""
        fixCount = 0; rawEpochs = 0; rawSignals = 0; rawCarrierValid = 0
        extBytes = 0; ntripBytes = 0
    }
}
