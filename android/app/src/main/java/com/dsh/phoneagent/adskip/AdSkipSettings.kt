package com.dsh.phoneagent.adskip

import android.content.Context
import android.content.SharedPreferences

/**
 * Ad-skip preferences.
 *
 * Kept separate from the rule data: the master switch must be readable the instant
 * the accessibility service connects, without parsing a few megabytes of rules.
 */
object AdSkipSettings {

    private const val PREFS = "ad_skip"
    private const val KEY_ENABLED = "enabled"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, value).apply()
    }
}
