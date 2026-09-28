package com.snoremask.app.bt

import android.content.Context

/**
 * Stores the Bluetooth device the user chose for auto-start. The device MAC
 * address is the stable identifier; the name is kept only for display.
 * Empty address == "None" (auto-start disabled), which is the default.
 */
object BtPrefs {
    private const val PREFS = "snoremask"
    private const val KEY_ADDR = "bt_auto_address"
    private const val KEY_NAME = "bt_auto_name"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Selected device address, or null when set to "None". */
    fun getSelectedAddress(context: Context): String? =
        prefs(context).getString(KEY_ADDR, "")?.ifEmpty { null }

    /** Display name of the selected device, or null. */
    fun getSelectedName(context: Context): String? =
        prefs(context).getString(KEY_NAME, "")?.ifEmpty { null }

    /** Pass null address to disable auto-start ("None"). */
    fun setSelected(context: Context, address: String?, name: String?) {
        prefs(context).edit()
            .putString(KEY_ADDR, address ?: "")
            .putString(KEY_NAME, name ?: "")
            .apply()
    }
}
