package com.tagcopy.shopeecapture

import android.content.Context

object AutoCapturePrefs {
    private const val PREFS_NAME = "auto_capture_prefs"
    private const val KEY_TARGET_COUNT = "target_count"
    private const val KEY_MIN_DELAY = "min_delay_ms"
    private const val KEY_MAX_DELAY = "max_delay_ms"

    private const val KEY_MIN_COMMISSION = "min_commission"
    private const val KEY_MAX_COMMISSION = "max_commission"
    private const val KEY_MIN_PRICE = "min_price"
    private const val KEY_MAX_PRICE = "max_price"
    private const val KEY_MIN_SOLD = "min_sold"
    private const val KEY_MAX_SOLD = "max_sold"
    private const val KEY_MIN_PROMOTER = "min_promoter"
    private const val KEY_MAX_PROMOTER = "max_promoter"
    private const val KEY_TIME_LIMIT = "time_limit_ms"
    private const val KEY_MAX_ATTEMPTS_ENABLED = "max_attempts_enabled"
    private const val KEY_TIME_LIMIT_ENABLED = "time_limit_enabled"

    private fun getDoubleOrNull(sp: android.content.SharedPreferences, key: String): Double? {
        val v = sp.getFloat(key, Float.NaN)
        return if (v.isNaN()) null else v.toDouble()
    }

    private fun putDoubleOrNull(editor: android.content.SharedPreferences.Editor, key: String, value: Double?) {
        editor.putFloat(key, value?.toFloat() ?: Float.NaN)
    }

    private fun getIntOrNull(sp: android.content.SharedPreferences, key: String): Int? {
        val v = sp.getInt(key, Int.MIN_VALUE)
        return if (v == Int.MIN_VALUE) null else v
    }

    private fun putIntOrNull(editor: android.content.SharedPreferences.Editor, key: String, value: Int?) {
        editor.putInt(key, value ?: Int.MIN_VALUE)
    }

    fun load(context: Context): AutoCaptureConfig {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val filter = ProductFilterConfig(
            minCommissionPercent = getDoubleOrNull(sp, KEY_MIN_COMMISSION) ?: 1.0,
            maxCommissionPercent = getDoubleOrNull(sp, KEY_MAX_COMMISSION),
            minPrice = getDoubleOrNull(sp, KEY_MIN_PRICE) ?: 50.0,
            maxPrice = getDoubleOrNull(sp, KEY_MAX_PRICE),
            minSoldCount = getIntOrNull(sp, KEY_MIN_SOLD) ?: 10,
            maxSoldCount = getIntOrNull(sp, KEY_MAX_SOLD),
            minPromoterCount = getIntOrNull(sp, KEY_MIN_PROMOTER),
            maxPromoterCount = getIntOrNull(sp, KEY_MAX_PROMOTER) ?: 100
        )
        return AutoCaptureConfig(
            targetCount = sp.getInt(KEY_TARGET_COUNT, 10),
            minDelayMs = sp.getLong(KEY_MIN_DELAY, 3000L),
            maxDelayMs = sp.getLong(KEY_MAX_DELAY, 6000L),
            filter = filter,
            timeLimitMs = sp.getLong(KEY_TIME_LIMIT, 600000L).let { if (it <= 0L) null else it },
            maxAttemptsLimitEnabled = sp.getBoolean(KEY_MAX_ATTEMPTS_ENABLED, true),
            timeLimitEnabled = sp.getBoolean(KEY_TIME_LIMIT_ENABLED, true)
        )
    }

    fun save(context: Context, config: AutoCaptureConfig) {
        val editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        editor.putInt(KEY_TARGET_COUNT, config.targetCount)
        editor.putLong(KEY_MIN_DELAY, config.minDelayMs)
        editor.putLong(KEY_MAX_DELAY, config.maxDelayMs)
        editor.putLong(KEY_TIME_LIMIT, config.timeLimitMs ?: -1L)
        editor.putBoolean(KEY_MAX_ATTEMPTS_ENABLED, config.maxAttemptsLimitEnabled)
        editor.putBoolean(KEY_TIME_LIMIT_ENABLED, config.timeLimitEnabled)
        putDoubleOrNull(editor, KEY_MIN_COMMISSION, config.filter.minCommissionPercent)
        putDoubleOrNull(editor, KEY_MAX_COMMISSION, config.filter.maxCommissionPercent)
        putDoubleOrNull(editor, KEY_MIN_PRICE, config.filter.minPrice)
        putDoubleOrNull(editor, KEY_MAX_PRICE, config.filter.maxPrice)
        putIntOrNull(editor, KEY_MIN_SOLD, config.filter.minSoldCount)
        putIntOrNull(editor, KEY_MAX_SOLD, config.filter.maxSoldCount)
        putIntOrNull(editor, KEY_MIN_PROMOTER, config.filter.minPromoterCount)
        putIntOrNull(editor, KEY_MAX_PROMOTER, config.filter.maxPromoterCount)
        editor.apply()
    }
}
