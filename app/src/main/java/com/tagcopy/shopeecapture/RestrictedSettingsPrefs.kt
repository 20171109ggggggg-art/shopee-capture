package com.tagcopy.shopeecapture

import android.content.Context

/**
 * Android 13+ 對「側載安裝（非Google Play商店來源）」的App會預設鎖住部分敏感設定
 * （最常見的症狀：去無障礙設定頁面想開啟本App的服務，選項是灰色的點不動，或者
 * 系統偷偷把它自動關掉），要先去「應用程式詳情頁→右上角選單（⋮）→允許受限制的設定」
 * 手動點開才能繼續。這個開關系統沒有提供任何公開API可以讀取目前狀態（Google刻意
 * 不開放查詢，避免被自動化繞過這層安全機制），所以這裡只能讓使用者自己勾選回報
 * 「我已經設定過了」，用SharedPreferences記住這個自我回報的狀態——這不是真正的
 * 系統狀態查詢結果，純粹是使用者自己的操作記錄，用來讓設定頁面的checklist看起來完整。
 */
object RestrictedSettingsPrefs {
    private const val PREFS_NAME = "restricted_settings_prefs"
    private const val KEY_CONFIRMED = "confirmed"

    fun isConfirmed(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_CONFIRMED, false)
    }

    fun setConfirmed(context: Context, confirmed: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_CONFIRMED, confirmed)
            .apply()
    }
}
