package com.example.data

import android.content.Context
import android.content.SharedPreferences

class Prefs(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("notenova_prefs", Context.MODE_PRIVATE)

    var isPremium: Boolean
        get() = prefs.getBoolean("is_premium", false)
        set(value) = prefs.edit().putBoolean("is_premium", value).apply()

    var themeMode: String
        get() = prefs.getString("theme_mode", "system") ?: "system"
        set(value) = prefs.edit().putString("theme_mode", value).apply()

    var appLockPin: String?
        get() = prefs.getString("app_lock_pin", null)
        set(value) = prefs.edit().putString("app_lock_pin", value).apply()

    var isBioLockEnabled: Boolean
        get() = prefs.getBoolean("bio_lock_enabled", false)
        set(value) = prefs.edit().putBoolean("bio_lock_enabled", value).apply()

    var isAutoLockEnabled: Boolean
        get() = prefs.getBoolean("auto_lock_enabled", false)
        set(value) = prefs.edit().putBoolean("auto_lock_enabled", value).apply()

    var defaultCategory: String
        get() = prefs.getString("default_category", "General") ?: "General"
        set(value) = prefs.edit().putString("default_category", value).apply()

    var customVolume: Int
        get() = prefs.getInt("custom_volume", 80)
        set(value) = prefs.edit().putInt("custom_volume", value).apply()

    var isFadeInEnabled: Boolean
        get() = prefs.getBoolean("fade_in_enabled", false)
        set(value) = prefs.edit().putBoolean("fade_in_enabled", value).apply()

    var ringtoneUri: String?
        get() = prefs.getString("ringtone_uri", null)
        set(value) = prefs.edit().putString("ringtone_uri", value).apply()

    var vibrationPattern: String
        get() = prefs.getString("vibration_pattern", "default") ?: "default" // default, pulse, heartbeat
        set(value) = prefs.edit().putString("vibration_pattern", value).apply()
}
