package com.tagcopy.shopeecapture

import android.content.Context

/**
 * 筆電生成伺服器設定：App直接呼叫筆電端FastAPI服務（main.py）的網址生成影片，
 * 取代舊版透過Termux執行batch_generate.py的做法——手機不再需要裝Termux。
 * 設計比照 AccountPrefs.kt 同樣的 SharedPreferences 寫法。
 *
 * 【2026-09-03新增】預設值改成目前實際在用的筆電MagicDNS主機名稱（不再是空字串），
 * 新手機裝好App後不用先手動打一次網址就已經是能用的預設值，只是之後筆電換了
 * 主機名稱記得回來這裡（DEFAULT_SERVER_URL）一併更新程式碼裡的預設值。
 */
object ServerPrefs {
    private const val PREFS_NAME = "server_prefs"
    private const val KEY_URL = "server_url"
    const val DEFAULT_SERVER_URL = "http://win-ha61d1nqgju.tail37aca2.ts.net:8000"

    fun getServerUrl(context: Context): String {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return sp.getString(KEY_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
    }

    /** 設定伺服器網址，自動去除結尾多餘的斜線，避免拼URL時出現雙斜線。 */
    fun setServerUrl(context: Context, url: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_URL, url.trim().trimEnd('/'))
            .apply()
    }

    fun isConfigured(context: Context): Boolean = getServerUrl(context).isNotBlank()
}
