package com.tvplayer.app
import android.content.Context

object DohSettings {
    private const val PREFS = "doh_settings"
    private const val KEY_ENABLED = "doh_enabled"
    private const val DEFAULT = true

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, DEFAULT)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
        CrashLogger.info("DoH", "Тумблер сохранён: " + (if (enabled) "🟢 ВКЛ" else "🔴 ВЫКЛ"))
    }
}
