package com.kalibox.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.kalibox.app.util.AppLog

class Prefs(ctx: Context) {
    private val sp: SharedPreferences = ctx.getSharedPreferences("kalibox", Context.MODE_PRIVATE)

    /** VNC password lives in EncryptedSharedPreferences only. */
    private val secure: SharedPreferences? = try {
        val key = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            ctx, "kalibox_secure", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        AppLog.e("Prefs", "EncryptedSharedPreferences unavailable", e)
        null
    }

    var legalAccepted: Boolean
        get() = sp.getBoolean("legal", false)
        set(v) = sp.edit().putBoolean("legal", v).apply()
    var desktopInstalled: Boolean
        get() = sp.getBoolean("desktop_installed", false)
        set(v) = sp.edit().putBoolean("desktop_installed", v).apply()
    var noSeccomp: Boolean
        get() = sp.getBoolean("no_seccomp", false)
        set(v) = sp.edit().putBoolean("no_seccomp", v).apply()
    var rootMode: Boolean
        get() = sp.getBoolean("root_mode", false)
        set(v) = sp.edit().putBoolean("root_mode", v).apply()
    var fontSize: Int
        get() = sp.getInt("font_size", 14)
        set(v) = sp.edit().putInt("font_size", v).apply()
    var colorScheme: String
        get() = sp.getString("color_scheme", "Kali dark") ?: "Kali dark"
        set(v) = sp.edit().putString("color_scheme", v).apply()
    /** "720p", "1080p" or "match" */
    var resolution: String
        get() = sp.getString("resolution", "720p") ?: "720p"
        set(v) = sp.edit().putString("resolution", v).apply()
    var touchpad: Boolean
        get() = sp.getBoolean("touchpad", false)
        set(v) = sp.edit().putBoolean("touchpad", v).apply()
    var vncPassword: String?
        get() = secure?.getString("vnc", null)
        set(v) { secure?.edit()?.putString("vnc", v)?.apply() }
    val secureStorageOk: Boolean get() = secure != null
}
