package com.tagcopy.shopeecapture

import android.content.Context

/**
 * 【2026-09-05新增】AI改圖要用哪個供應商——使用者在設定畫面「AI改圖服務」選單手動切換，
 * 不做自動判斷/自動備援切換（品質好壞很難用程式準確判斷，切錯了反而更難排查）。
 * 兩邊共用同一份換背景提示詞（getPrompt()/setPrompt()），不分裂成兩份維護；
 * 如果之後發現ChatGPT對這份提示詞有系統性理解落差，再考慮另外調整。
 */
enum class ImageEditProvider(val label: String) {
    GEMINI("Gemini"),
    CHATGPT("ChatGPT");

    companion object {
        fun fromLabel(label: String): ImageEditProvider =
            entries.firstOrNull { it.label == label } ?: GEMINI
    }
}

/**
 * AI換背景（測試功能）的設定：Gemini/OpenAI API Key、是否啟用、換背景提示詞、
 * 目前選用的AI改圖供應商。
 * API Key存在SharedPreferences的私有檔案裡，只有這個App自己能讀到（其他App讀不到）。
 */
object GeminiApiPrefs {
    private const val PREFS_NAME = "gemini_api_prefs"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_PROMPT = "prompt"
    private const val KEY_OPENAI_API_KEY = "openai_api_key"
    private const val KEY_IMAGE_EDIT_PROVIDER = "image_edit_provider"

    val DEFAULT_PROMPT =
        "保留這張圖片裡的商品本體形狀、顏色、材質完全不變，" +
            "但把商品上看得到的品牌logo、商標、廠牌文字都移除或模糊化處理（用材質本身的顏色填補，" +
            "不要留下明顯的擦除痕跡）。" +
            "如果這張圖片本身是行銷banner（帶有大量疊加的標題文字、標語、勾選圖示、按鈕、價格標籤等），" +
            "把這些疊加在圖片上的文字和圖層全部移除，只保留商品本體，" +
            "重新生成一個乾淨自然的情境背景，讓整張圖看起來像一張正常拍攝的商品照片。" +
            "檢查商品在原圖中的擺放姿態是否符合這類商品正常展示/使用時的樣子，" +
            "如果看起來不自然（懸空、無支撐、角度違反物理），依商品類型調整成合理的擺放方式" +
            "——例如衣物用衣架吊掛或平整攤開展示、鞋子立於地面或斜放展示、包袋立放或側放、" +
            "小型電子用品放在桌面或使用情境中、珠寶飾品用展示台或平整擺放，" +
            "其他沒列出來的品類也依常理判斷合適的擺法——並加上柔和寫實的陰影讓畫面看起來穩固自然。" +
            "但如果原圖本身是拆解圖／材質分層展示圖這種刻意呈現結構的手法，" +
            "維持原本的分解排列方式，不用強制擺放。" +
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

    fun getOpenAiApiKey(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_OPENAI_API_KEY, "") ?: ""
    }

    fun setOpenAiApiKey(context: Context, apiKey: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_OPENAI_API_KEY, apiKey.trim())
            .apply()
    }

    fun getImageEditProvider(context: Context): ImageEditProvider {
        val name = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_IMAGE_EDIT_PROVIDER, ImageEditProvider.GEMINI.name)
        return try {
            ImageEditProvider.valueOf(name ?: ImageEditProvider.GEMINI.name)
        } catch (e: Exception) {
            ImageEditProvider.GEMINI
        }
    }

    fun setImageEditProvider(context: Context, provider: ImageEditProvider) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_IMAGE_EDIT_PROVIDER, provider.name)
            .apply()
    }
}
