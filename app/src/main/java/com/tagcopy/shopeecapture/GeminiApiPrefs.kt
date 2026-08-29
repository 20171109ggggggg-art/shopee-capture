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
        "保留這張圖片裡的商品本體形狀、顏色、材質完全不變，" +
            "但把商品上看得到的品牌logo、商標、廠牌文字都移除或模糊化處理（用材質本身的顏色填補，" +
            "不要留下明顯的擦除痕跡）。" +
            "如果這張圖片本身是行銷banner（帶有大量疊加的標題文字、標語、勾選圖示、按鈕、價格標籤等），" +
            "把這些疊加在圖片上的文字和圖層全部移除，只保留商品本體，" +
            "重新生成一個乾淨自然的情境背景，讓整張圖看起來像一張正常拍攝的商品照片。" +
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
}
