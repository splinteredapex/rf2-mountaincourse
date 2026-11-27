package com.splinteredapex.logger

import android.content.Context

enum class ExternalSource(val label: String) {
    NONE("None: phone only"),
    USB("USB (u-blox F9P etc.)"),
    BLUETOOTH("Bluetooth serial (SPP)");
}

class Prefs(context: Context) {
    private val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var source: ExternalSource
        get() = runCatching { ExternalSource.valueOf(p.getString("source", "NONE")!!) }.getOrDefault(ExternalSource.NONE)
        set(v) = p.edit().putString("source", v.name).apply()
    var btAddress: String
        get() = p.getString("btAddress", "") ?: ""
        set(v) = p.edit().putString("btAddress", v).apply()
    var btName: String
        get() = p.getString("btName", "") ?: ""
        set(v) = p.edit().putString("btName", v).apply()
    var baud: Int
        get() = p.getInt("baud", 38400)
        set(v) = p.edit().putInt("baud", v).apply()
    var configureUblox: Boolean
        get() = p.getBoolean("configureUblox", true)
        set(v) = p.edit().putBoolean("configureUblox", v).apply()
    var ntripEnabled: Boolean
        get() = p.getBoolean("ntripEnabled", false)
        set(v) = p.edit().putBoolean("ntripEnabled", v).apply()
    var ntripHost: String
        get() = p.getString("ntripHost", "") ?: ""
        set(v) = p.edit().putString("ntripHost", v).apply()
    var ntripPort: Int
        get() = p.getInt("ntripPort", 2101)
        set(v) = p.edit().putInt("ntripPort", v).apply()
    var ntripMount: String
        get() = p.getString("ntripMount", "") ?: ""
        set(v) = p.edit().putString("ntripMount", v).apply()
    var ntripUser: String
        get() = p.getString("ntripUser", "") ?: ""
        set(v) = p.edit().putString("ntripUser", v).apply()
    var ntripPass: String
        get() = p.getString("ntripPass", "") ?: ""
        set(v) = p.edit().putString("ntripPass", v).apply()
    var notes: String
        get() = p.getString("notes", "") ?: ""
        set(v) = p.edit().putString("notes", v).apply()
}
