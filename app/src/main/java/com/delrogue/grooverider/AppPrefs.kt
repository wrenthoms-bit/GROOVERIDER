package com.delrogue.grooverider

import android.content.Context

/** Small device settings (spec 7): onboarding state and the like. Plain
 * SharedPreferences -- nothing here needs DataStore's structured schema. */
object AppPrefs {
    private const val FILE = "grooverider_prefs"
    private const val KEY_ONBOARDED = "onboarded"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isOnboarded(context: Context): Boolean = prefs(context).getBoolean(KEY_ONBOARDED, false)

    fun setOnboarded(context: Context) {
        prefs(context).edit().putBoolean(KEY_ONBOARDED, true).apply()
    }
}
