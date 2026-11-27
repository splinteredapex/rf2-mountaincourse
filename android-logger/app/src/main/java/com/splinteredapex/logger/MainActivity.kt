package com.splinteredapex.logger

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private val ui = Handler(Looper.getMainLooper())
    private var tone: ToneGenerator? = null

    private lateinit var recBig: TextView
    private lateinit var recDetail: TextView
    private lateinit var gnssBig: TextView
    private lateinit var gnssDetail: TextView
    private lateinit var sensBig: TextView
    private lateinit var sensDetail: TextView
    private lateinit var extBig: TextView
    private lateinit var extDetail: TextView
    private lateinit var btnRecord: Button
    private lateinit var settingsBox: View
    private lateinit var syncOverlay: View
    private lateinit var syncClock: TextView
    private lateinit var syncLabel: TextView
    private lateinit var btnBtDevice: Button

    private val good by lazy { getColor(R.color.good) }
    private val warn by lazy { getColor(R.color.warn) }
    private val bad by lazy { getColor(R.color.bad) }
    private val text by lazy { getColor(R.color.text) }

    private val tick = object : Runnable {
        override fun run() { refresh(); ui.postDelayed(this, 200) }
    }
    private var syncFlashUntil = 0L
    private val clockTick = object : Runnable {
        override fun run() { refreshClock(); ui.postDelayed(this, 16) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)

        recBig = findViewById(R.id.recBig); recDetail = findViewById(R.id.recDetail)
        gnssBig = findViewById(R.id.gnssBig); gnssDetail = findViewById(R.id.gnssDetail)
        sensBig = findViewById(R.id.sensBig); sensDetail = findViewById(R.id.sensDetail)
        extBig = findViewById(R.id.extBig); extDetail = findViewById(R.id.extDetail)
        btnRecord = findViewById(R.id.btnRecord)
        settingsBox = findViewById(R.id.settingsBox)
        syncOverlay = findViewById(R.id.syncOverlay)
        syncClock = findViewById(R.id.syncClock)
        syncLabel = findViewById(R.id.syncLabel)
        btnBtDevice = findViewById(R.id.btnBtDevice)

        btnRecord.setOnClickListener { toggleRecording() }
        findViewById<Button>(R.id.btnSync).setOnClickListener { showSync() }
        findViewById<Button>(R.id.btnMark).setOnClickListener { mark() }
        findViewById<Button>(R.id.btnSessions).setOnClickListener { showRuns() }
        syncOverlay.setOnClickListener { hideSync() }
        btnBtDevice.setOnClickListener { chooseBluetooth() }
        bindSettings()
        askPermissions()
    }

    override fun onResume() {
        super.onResume()
        ui.post(tick)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(tick)
    }

    override fun onDestroy() {
        tone?.release()
        super.onDestroy()
    }

    // ---------------- Permissions ----------------

    private fun askPermissions() {
        val want = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) want += Manifest.permission.POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT >= 31) want += Manifest.permission.BLUETOOTH_CONNECT
        val missing = want.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }

    private fun hasLocation() = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    // ---------------- Actions ----------------

    private fun toggleRecording() {
        if (Live.recording) {
            AlertDialog.Builder(this)
                .setTitle("Stop recording?")
                .setPositiveButton("Stop") { _, _ -> LoggerService.send(this, LoggerService.ACTION_STOP) }
                .setNegativeButton("Keep going", null)
                .show()
            return
        }
        if (!hasLocation()) {
            Toast.makeText(this, "Location permission is needed. Allow \"Precise\" location.", Toast.LENGTH_LONG).show()
            askPermissions()
            return
        }
        saveSettings()
        LoggerService.send(this, LoggerService.ACTION_START)
    }

    private fun mark() {
        if (!Live.recording) { toast("Start recording first"); return }
        LoggerService.send(this, LoggerService.ACTION_EVENT, "MARK", "")
        beep()
        toast("Point ${Live.markCount + 1} marked")
    }

    private fun showSync() {
        if (Live.recording) LoggerService.send(this, LoggerService.ACTION_EVENT, "SYNC", "")
        else toast("Not recording: this sync will not be saved")
        syncLabel.text = if (Live.recording) "SYNC ${Live.syncCount + 1}: FILM THIS SCREEN" else "NOT RECORDING"
        syncOverlay.visibility = View.VISIBLE
        syncFlashUntil = SystemClock.elapsedRealtime() + 300
        beep()
        ui.post(clockTick)
    }

    private fun hideSync() {
        syncOverlay.visibility = View.GONE
        ui.removeCallbacks(clockTick)
    }

    private fun beep() {
        if (tone == null) tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 100) }.getOrNull()
        tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 250)
    }

    private fun refreshClock() {
        val flash = SystemClock.elapsedRealtime() < syncFlashUntil
        syncOverlay.setBackgroundColor(if (flash) Color.WHITE else Color.BLACK)
        syncClock.setTextColor(if (flash) Color.BLACK else Color.WHITE)
        val g = Live.gnssUtcNowMs()
        val ms = g ?: System.currentTimeMillis()
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = ms }
        syncClock.text = "%02d:%02d:%02d.%03d".format(
            Locale.US, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND), c.get(Calendar.MILLISECOND)
        )
        findViewById<TextView>(R.id.syncHint).text =
            if (g != null) "GPS time (UTC). Hold in view of the camera for 3 s, then tap."
            else "Phone clock: no GPS fix yet. Tap to close."
    }

    // ---------------- Live status ----------------

    private fun refresh() {
        val rec = Live.recording
        btnRecord.text = if (rec) "Stop recording" else "Start recording"
        settingsBox.alpha = if (rec) 0.4f else 1f
        setEnabledDeep(settingsBox, !rec)
        if (rec) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (rec) {
            val s = (SystemClock.elapsedRealtimeNanos() - Live.startElapsedNs) / 1_000_000_000L
            recBig.text = "● %d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60)
            recBig.setTextColor(getColor(R.color.accent))
            recDetail.text = "${Live.sessionName}\n${"%.1f".format(Live.bytesWritten / 1e6)} MB   syncs ${Live.syncCount}   marks ${Live.markCount}" +
                if (Live.lastError.isNotEmpty()) "\n${Live.lastError}" else ""
        } else {
            recBig.text = "Ready"
            recBig.setTextColor(text)
            recDetail.text = "Film the Camera sync screen at the start and end of every run."
        }

        // Phone GNSS
        val age = (SystemClock.elapsedRealtimeNanos() - Live.lastFixElapsedNs) / 1e9
        if (!rec) {
            gnssBig.text = "Idle"; gnssBig.setTextColor(text); gnssDetail.text = ""
        } else if (Live.fixCount == 0L || age > 5) {
            gnssBig.text = "Searching"; gnssBig.setTextColor(warn)
            gnssDetail.text = "sats ${Live.satsUsed}/${Live.satsInView}"
        } else {
            val h = Live.hAcc
            gnssBig.text = "±%.1f m horizontal".format(h)
            gnssBig.setTextColor(if (h <= 3) good else if (h <= 8) warn else bad)
            val v = if (Live.vAcc.isNaN()) "?" else "%.1f".format(Live.vAcc)
            val spd = if (Live.speedMps.isNaN()) "?" else "%.0f".format(Live.speedMps * 3.6)
            gnssDetail.text = "height ±$v m   ${spd} km/h   sats ${Live.satsUsed}/${Live.satsInView}\n" +
                "raw signals ${Live.rawSignals}   carrier phase ${Live.rawCarrierValid}   " +
                (if (Live.dualFrequency) "L1+L5" else "L1 only")
        }

        // Sensors
        if (!rec) {
            sensBig.text = "Idle"; sensBig.setTextColor(text); sensDetail.text = ""
        } else {
            sensBig.text = "${Live.accHz} Hz"
            sensBig.setTextColor(if (Live.accHz >= 150) good else if (Live.accHz > 0) warn else bad)
            val p = if (Live.pressureHpa.isNaN()) "-" else "%.2f hPa".format(Live.pressureHpa)
            sensDetail.text = "accel ${Live.accHz}  gyro ${Live.gyrHz}  mag ${Live.magHz}  orient ${Live.rotHz}\nbarometer ${Live.baroHz} Hz  $p"
        }

        // External
        if (prefs.source == ExternalSource.NONE) {
            extBig.text = "Not used"; extBig.setTextColor(text)
            extDetail.text = "Phone only. Plug in an RTK receiver for cm-level height."
        } else if (!rec) {
            extBig.text = prefs.source.label; extBig.setTextColor(text)
            extDetail.text = "Connects when recording starts."
        } else {
            val q = Live.extFixQuality
            extBig.text = if (Live.extBytes == 0L) "Connecting" else Live.extFixLabel.ifEmpty { "No data yet" }
            extBig.setTextColor(when (q) { 4 -> good; 5, 2, 1 -> warn; else -> bad })
            val hAcc = if (Live.extHAccM.isNaN()) "?" else "%.3f".format(Live.extHAccM)
            val vAcc = if (Live.extVAccM.isNaN()) "?" else "%.3f".format(Live.extVAccM)
            val nt = if (prefs.ntripEnabled) "\nNTRIP: ${Live.ntripState}  ${Live.ntripBytes / 1024} KB" else "\nNo corrections: RTK needs NTRIP on"
            extDetail.text = "${Live.extState}\n±$hAcc m H  ±$vAcc m V  sats ${Live.extSats}  ${Live.extRateHz} Hz  ${Live.extBytes / 1024} KB$nt"
        }
    }

    private fun setEnabledDeep(v: View, enabled: Boolean) {
        v.isEnabled = enabled
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) setEnabledDeep(v.getChildAt(i), enabled)
    }

    // ---------------- Settings ----------------

    private fun bindSettings() {
        val sp = findViewById<Spinner>(R.id.spSource)
        sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, ExternalSource.entries.map { it.label })
        sp.setSelection(prefs.source.ordinal)
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                prefs.source = ExternalSource.entries[pos]
                updateSourceViews()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        findViewById<CheckBox>(R.id.cbConfigure).apply {
            isChecked = prefs.configureUblox
            setOnCheckedChangeListener { _, c -> prefs.configureUblox = c }
        }
        findViewById<CheckBox>(R.id.cbNtrip).apply {
            isChecked = prefs.ntripEnabled
            setOnCheckedChangeListener { _, c -> prefs.ntripEnabled = c }
        }
        bindText(R.id.etHost, prefs.ntripHost) { prefs.ntripHost = it }
        bindText(R.id.etPort, prefs.ntripPort.toString()) { prefs.ntripPort = it.toIntOrNull() ?: 2101 }
        bindText(R.id.etMount, prefs.ntripMount) { prefs.ntripMount = it }
        bindText(R.id.etUser, prefs.ntripUser) { prefs.ntripUser = it }
        bindText(R.id.etPass, prefs.ntripPass) { prefs.ntripPass = it }
        bindText(R.id.etBaud, prefs.baud.toString()) { prefs.baud = it.toIntOrNull() ?: 38400 }
        bindText(R.id.etNotes, prefs.notes) { prefs.notes = it }
        updateSourceViews()
    }

    private fun bindText(id: Int, value: String, save: (String) -> Unit) {
        val e = findViewById<EditText>(id)
        e.setText(value)
        e.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = save(s?.toString() ?: "")
        })
    }

    private fun saveSettings() { /* settings save as they are typed */ }

    private fun updateSourceViews() {
        val src = prefs.source
        btnBtDevice.visibility = if (src == ExternalSource.BLUETOOTH) View.VISIBLE else View.GONE
        btnBtDevice.text = if (prefs.btName.isNotEmpty()) "Bluetooth: ${prefs.btName}" else "Choose Bluetooth device"
        findViewById<View>(R.id.cbConfigure).visibility = if (src == ExternalSource.USB) View.VISIBLE else View.GONE
        findViewById<View>(R.id.etBaud).visibility = if (src == ExternalSource.USB) View.VISIBLE else View.GONE
    }

    @SuppressLint("MissingPermission")
    private fun chooseBluetooth() {
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 2)
            return
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        val devices = adapter?.bondedDevices?.toList().orEmpty()
        if (devices.isEmpty()) {
            toast("Pair the receiver in Android Bluetooth settings first")
            return
        }
        val names = devices.map { "${it.name ?: "Unknown"}  (${it.address})" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Paired devices")
            .setItems(names) { _, i ->
                prefs.btAddress = devices[i].address
                prefs.btName = devices[i].name ?: devices[i].address
                updateSourceViews()
            }
            .show()
    }

    // ---------------- Runs ----------------

    private fun showRuns() {
        val runs = Session.list(this)
        if (runs.isEmpty()) { toast("No runs recorded yet"); return }
        val labels = runs.map { "${it.name}   ${"%.1f".format(dirSize(it) / 1e6)} MB" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Recorded runs")
            .setItems(labels) { _, i -> runActions(runs[i]) }
            .show()
    }

    private fun runActions(dir: File) {
        val isCurrent = Live.recording && dir.name == Live.sessionName
        AlertDialog.Builder(this)
            .setTitle(dir.name)
            .setItems(arrayOf("Share as .zip (Drive, email, etc.)", "Delete")) { _, which ->
                if (isCurrent) { toast("Stop recording first"); return@setItems }
                if (which == 0) shareRun(dir) else confirmDelete(dir)
            }
            .show()
    }

    private fun confirmDelete(dir: File) {
        AlertDialog.Builder(this)
            .setTitle("Delete ${dir.name}?")
            .setMessage("This cannot be undone.")
            .setPositiveButton("Delete") { _, _ -> dir.deleteRecursively(); toast("Deleted") }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun shareRun(dir: File) {
        toast("Packing ${dir.name}…")
        Thread {
            try {
                val outDir = File(cacheDir, "exports").apply { mkdirs() }
                outDir.listFiles()?.forEach { it.delete() }
                val zip = File(outDir, dir.name + ".zip")
                ZipOutputStream(zip.outputStream().buffered(1 shl 16)).use { z ->
                    dir.listFiles()?.sortedBy { it.name }?.forEach { f ->
                        z.putNextEntry(ZipEntry(dir.name + "/" + f.name))
                        f.inputStream().use { it.copyTo(z, 1 shl 16) }
                        z.closeEntry()
                    }
                }
                val uri = FileProvider.getUriForFile(this, "$packageName.files", zip)
                ui.post {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "application/zip"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, "Course run ${dir.name}")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(send, "Share run"))
                }
            } catch (e: Exception) {
                ui.post { toast("Could not pack run: ${e.message}") }
            }
        }.start()
    }

    private fun dirSize(d: File): Long = d.listFiles()?.sumOf { it.length() } ?: 0L

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
