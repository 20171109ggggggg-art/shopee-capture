package com.tagcopy.shopeecapture

import android.content.Context

/**
 * AI換背景（測試功能）的設定：Gemini API Key、是否啟用、換背景提示詞。
 * API Key存在SharedPreferences的私有檔案裡，只有這個App自己能讀到（其他App讀不到）。
 */
object GeminiApiPrefs {
    private const val PREFS_NAME = "gemini_api_prefs"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_PROMPT = "prompt"

    val DEFAULT_PROMPT =
        "保留這張圖片裡的商品本體完全不變（形狀、顏色、文字、logo都不能改），" +
            "根據商品的種類和用途，生成一個自然貼合的使用情境背景" +
            "（例如廚房家電配廚房、保養品配梳妝台、3C用品配書桌辦公室），" +
            "如果原本背景已經合適就保留類似的氛圍稍微優化即可，" +
            "光線自然柔和，不要加上任何文字。"

    fun getApiKey(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_API_KEY, "") ?: ""
    }

    fun setApiKey(context: Context, apiKey: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_API_KEY, apiKey.trim())
            .apply()
    }

    fun isEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }

    fun getPrompt(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PROMPT, DEFAULT_PROMPT) ?: DEFAULT_PROMPT
    }

    fun setPrompt(context: Context, prompt: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_PROMPT, prompt)
            .apply()
    }

    // 【2026-08-29新增】擷取一件商品通常會抓到10幾張圖，但不需要每張都送去AI改圖（花錢又沒必要，
    // 後製影片也不會用到那麼多張）。這裡設定「前幾張」要送去AI改圖，其餘的維持原圖，
    // 存檔張數、檔名順序都不變，只有前N張的內容被替換成AI改過的版本。
    private const val KEY_EDIT_COUNT = "edit_count"
    const val DEFAULT_EDIT_COUNT = 3

    fun getEditCount(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_EDIT_COUNT, DEFAULT_EDIT_COUNT)
    }

    fun setEditCount(context: Context, count: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putInt(KEY_EDIT_COUNT, count.coerceAtLeast(0))
            .apply()
    }
}
