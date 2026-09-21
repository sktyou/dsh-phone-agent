package com.dsh.phoneagent

import android.content.Context
import android.content.SharedPreferences

/**
 * MCP access switch.
 *
 * Off by default. The control channel itself stays available — the console and any
 * script already using it keep working — but the MCP endpoints, which hand out the
 * server file and its configuration, are closed. That is the part worth gating: a
 * downloadable, pre-configured bridge is what turns "the port is open" into "anyone
 * on this LAN can drive my phone in two minutes".
 */
object McpSettings {

    private const val PREFS = "mcp"
    private const val KEY_ENABLED = "enabled"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, value).apply()
    }
}
