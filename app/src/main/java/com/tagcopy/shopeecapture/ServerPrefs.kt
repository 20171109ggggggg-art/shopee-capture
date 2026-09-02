package com.tagcopy.shopeecapture

import android.content.Context

/**
 * 筆電生成伺服器設定：App直接呼叫筆電端FastAPI服務（main.py）的網址生成影片，
 * 取代舊版透過Termux執行batch_generate.py的做法——手機不再需要裝Termux。
 * 設計比照 AccountPrefs.kt 同樣的 SharedPreferences 寫法。
 */
object ServerPrefs {
    private const val PREFS_NAME = "server_prefs"
    private const val KEY_URL = "server_url"

    fun getServerUrl(context: Context): String {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return sp.getString(KEY_URL, "") ?: ""
    }

    /** 設定伺服器網址，自動去除結尾多餘的斜線，避免拼URL時出現雙斜線。 */
    fun setServerUrl(context: Context, url: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_URL, url.trim().trimEnd('/'))
            .apply()
    }

    fun isConfigured(context: Context): Boolean = getServerUrl(context).isNotBlank()
}
