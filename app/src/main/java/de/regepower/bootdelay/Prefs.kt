package de.regepower.bootdelay

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("bootdelay", Context.MODE_PRIVATE)

    var initialDelaySec: Int
        get() = sp.getInt(KEY_INITIAL, 30)
        set(v) = sp.edit().putInt(KEY_INITIAL, v).apply()

    var gapSec: Int
        get() = sp.getInt(KEY_GAP, 10)
        set(v) = sp.edit().putInt(KEY_GAP, v).apply()

    var packages: List<String>
        get() = sp.getString(KEY_PKGS, "").orEmpty().split('\n').filter { it.isNotBlank() }
        set(v) = sp.edit().putString(KEY_PKGS, v.joinToString("\n")).apply()

    private companion object {
        const val KEY_INITIAL = "initial"
        const val KEY_GAP = "gap"
        const val KEY_PKGS = "pkgs"
    }
}
