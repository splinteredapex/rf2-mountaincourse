package com.splinteredapex.logger

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread

/**
 * Accelerometer, gyro, magnetometer, barometer and orientation at the fastest rate the phone allows
 * (about 200 to 400 Hz on a Pixel). Written gzipped to sensors.csv.gz.
 * Columns: t_ns, sensor, x, y, z, w (w only for the orientation quaternion).
 * Units: ACC m/s^2, GYR rad/s, MAG uT, BARO hPa (in x), GRV unit quaternion (x, y, z, w).
 * Axes are the phone's own: x to the right of the screen, y to the top, z out of the screen.
 */
class MotionSensors(context: Context, session: Session) : SensorEventListener {

    private val sm = context.getSystemService(SensorManager::class.java)
    private val out = session.text("sensors.csv.gz", "t_ns,sensor,x,y,z,w", gzip = true)
    private val meta = session.text("sensors_info.csv", "sensor,name,vendor,max_range,resolution,min_delay_us")
    private val thread = HandlerThread("sensors")
    private val counts = IntArray(5)
    private var lastRateNs = 0L
    private val sb = StringBuilder(128)

    private val wanted = listOf(
        Sensor.TYPE_ACCELEROMETER to "ACC",
        Sensor.TYPE_GYROSCOPE to "GYR",
        Sensor.TYPE_MAGNETIC_FIELD to "MAG",
        Sensor.TYPE_PRESSURE to "BARO",
        Sensor.TYPE_GAME_ROTATION_VECTOR to "GRV",
    )

    fun start() {
        thread.start()
        val h = Handler(thread.looper)
        for ((type, tag) in wanted) {
            val s = sm.getDefaultSensor(type) ?: continue
            meta.line("$tag,${s.name.replace(',', ' ')},${s.vendor.replace(',', ' ')},${s.maximumRange},${s.resolution},${s.minDelay}")
            sm.registerListener(this, s, SensorManager.SENSOR_DELAY_FASTEST, h)
        }
        meta.flush()
    }

    fun stop() {
        sm.unregisterListener(this)
        thread.quitSafely()
        runCatching { thread.join(2000) }
        Live.accHz = 0; Live.gyrHz = 0; Live.magHz = 0; Live.baroHz = 0; Live.rotHz = 0
    }

    override fun onSensorChanged(e: SensorEvent) {
        val idx: Int
        val tag: String
        when (e.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> { idx = 0; tag = "ACC" }
            Sensor.TYPE_GYROSCOPE -> { idx = 1; tag = "GYR" }
            Sensor.TYPE_MAGNETIC_FIELD -> { idx = 2; tag = "MAG" }
            Sensor.TYPE_PRESSURE -> { idx = 3; tag = "BARO"; Live.pressureHpa = e.values[0] }
            Sensor.TYPE_GAME_ROTATION_VECTOR -> { idx = 4; tag = "GRV" }
            else -> return
        }
        counts[idx]++
        val v = e.values
        sb.setLength(0)
        sb.append(e.timestamp).append(',').append(tag).append(',').append(v[0])
        if (v.size >= 3 && idx != 3) sb.append(',').append(v[1]).append(',').append(v[2]) else sb.append(",,")
        if (idx == 4 && v.size >= 4) sb.append(',').append(v[3]) else sb.append(',')
        out.line(sb.toString())

        if (e.timestamp - lastRateNs >= 1_000_000_000L) {
            if (lastRateNs != 0L) {
                Live.accHz = counts[0]; Live.gyrHz = counts[1]; Live.magHz = counts[2]
                Live.baroHz = counts[3]; Live.rotHz = counts[4]
            }
            counts.fill(0)
            lastRateNs = e.timestamp
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
}
