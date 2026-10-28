package org.sunsetware.phocid.bridge

import android.content.Context
import java.security.SecureRandom

object TokenStore {
    private const val PREFS = "bridge"
    private const val KEY_TOKEN = "token"
    private const val HEX = "0123456789abcdef"

    fun getOrCreate(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_TOKEN, null)?.let {
            return it
        }
        return regenerate(context)
    }

    fun regenerate(context: Context): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        val token =
            buildString(bytes.size * 2) {
                bytes.forEach { byte ->
                    val value = byte.toInt() and 0xff
                    append(HEX[value ushr 4])
                    append(HEX[value and 0x0f])
                }
            }
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TOKEN, token)
            .apply()
        return token
    }
}
