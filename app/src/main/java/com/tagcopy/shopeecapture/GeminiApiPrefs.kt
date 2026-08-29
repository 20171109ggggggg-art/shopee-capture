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
            "只把背景換成乾淨明亮的居家風格背景，光線自然柔和，不要加上任何文字。"

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
