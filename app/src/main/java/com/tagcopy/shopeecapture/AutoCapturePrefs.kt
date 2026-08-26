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

    /**
     * 讀取一個「可能是null（不限制）」的Double欄位，並正確分辨兩種情況：
     * 1. 這個key從來沒被save()寫過（例如App第一次安裝、使用者從沒碰過這個欄位）→ 回傳呼叫端給的預設值
     * 2. 這個key曾經被save()寫過，且當時使用者把欄位清空代表「不限制」→ 回傳null，不套用預設值
     * 舊版只用Float.NaN當作「null」的標記，沒辦法分辨這兩種情況（都讀到NaN），
     * 導致使用者主動清空成「不限制」後，只要畫面重新讀取一次就會被預設值蓋回去。
     * 用sp.contains(key)先判斷這個key有沒有真的被寫過，兩種情況才分得開。
     */
    private fun getDoubleOrNullWithDefault(
        sp: android.content.SharedPreferences, key: String, default: Double?
    ): Double? {
        if (!sp.contains(key)) return default
        val v = sp.getFloat(key, Float.NaN)
        return if (v.isNaN()) null else v.toDouble()
    }

    private fun putDoubleOrNull(editor: android.content.SharedPreferences.Editor, key: String, value: Double?) {
        editor.putFloat(key, value?.toFloat() ?: Float.NaN)
    }

    /** Int版本，邏輯跟getDoubleOrNullWithDefault完全對應，見上方註解 */
    private fun getIntOrNullWithDefault(
        sp: android.content.SharedPreferences, key: String, default: Int?
    ): Int? {
        if (!sp.contains(key)) return default
        val v = sp.getInt(key, Int.MIN_VALUE)
        return if (v == Int.MIN_VALUE) null else v
    }

    private fun putIntOrNull(editor: android.content.SharedPreferences.Editor, key: String, value: Int?) {
        editor.putInt(key, value ?: Int.MIN_VALUE)
    }

    fun load(context: Context): AutoCaptureConfig {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val filter = ProductFilterConfig(
            minCommissionPercent = getDoubleOrNullWithDefault(sp, KEY_MIN_COMMISSION, 5.0),
            maxCommissionPercent = getDoubleOrNullWithDefault(sp, KEY_MAX_COMMISSION, null),
            minPrice = getDoubleOrNullWithDefault(sp, KEY_MIN_PRICE, 50.0),
            maxPrice = getDoubleOrNullWithDefault(sp, KEY_MAX_PRICE, null),
            minSoldCount = getIntOrNullWithDefault(sp, KEY_MIN_SOLD, 10),
            maxSoldCount = getIntOrNullWithDefault(sp, KEY_MAX_SOLD, null),
            minPromoterCount = getIntOrNullWithDefault(sp, KEY_MIN_PROMOTER, null),
            maxPromoterCount = getIntOrNullWithDefault(sp, KEY_MAX_PROMOTER, null)
        )
        return AutoCaptureConfig(
            targetCount = sp.getInt(KEY_TARGET_COUNT, 20),
            minDelayMs = sp.getLong(KEY_MIN_DELAY, 5000L),
            maxDelayMs = sp.getLong(KEY_MAX_DELAY, 8000L),
            filter = filter,
            timeLimitMs = sp.getLong(KEY_TIME_LIMIT, -1L).let { if (it <= 0L) null else it },
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
