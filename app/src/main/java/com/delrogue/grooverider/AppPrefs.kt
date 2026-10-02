package com.delrogue.grooverider

import android.content.Context

/** Small device settings (spec 7): onboarding state and the like. Plain
 * SharedPreferences -- nothing here needs DataStore's structured schema. */
object AppPrefs {
    private const val FILE = "grooverider_prefs"
    private const val KEY_ONBOARDED = "onboarded"
    private const val KEY_FACTORY_CONTENT = "factory_content_version"
    private const val KEY_OPEN_ON_DEFAULT = "open_on_default_preset"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isOnboarded(context: Context): Boolean = prefs(context).getBoolean(KEY_ONBOARDED, false)

    fun setOnboarded(context: Context) {
        prefs(context).edit().putBoolean(KEY_ONBOARDED, true).apply()
    }

    /** Which set of bundled sources and presets has been put in the library (0 = none yet). */
    fun factoryContentVersion(context: Context): Int = prefs(context).getInt(KEY_FACTORY_CONTENT, 0)

    fun setFactoryContentVersion(context: Context, version: Int) {
        prefs(context).edit().putInt(KEY_FACTORY_CONTENT, version).apply()
    }

    /** Set by onboarding: the main screen should open playing the default preset, once. */
    fun setOpenOnDefaultPreset(context: Context, pending: Boolean) {
        prefs(context).edit().putBoolean(KEY_OPEN_ON_DEFAULT, pending).apply()
    }

    fun takeOpenOnDefaultPreset(context: Context): Boolean {
        val pending = prefs(context).getBoolean(KEY_OPEN_ON_DEFAULT, false)
        if (pending) setOpenOnDefaultPreset(context, false)
        return pending
    }
}
