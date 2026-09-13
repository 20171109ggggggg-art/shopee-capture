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
 * 【2026-09-07新增】AI辨識選圖（從候選圖裡挑出最適合當商品主圖的幾張）原本寫死只能
 * 用Gemini，因為OpenAI沒有對應的圖片評分/選圖API。這次改成用OpenAI的Chat Completions
 * API（視覺輸入）自己組一個功能等效的版本（見OpenAiImageSelector），讓使用者可以在
 * 設定畫面切換，測試Gemini/ChatGPT對「選圖」這件事的判斷差異——跟AI改圖是各自獨立的
 * 供應商設定，互不影響。
 */
enum class ImageSelectProvider(val label: String) {
    GEMINI("Gemini"),
    CHATGPT("ChatGPT");

    companion object {
        fun fromLabel(label: String): ImageSelectProvider =
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
    private const val KEY_IMAGE_SELECT_PROVIDER = "image_select_provider"

    val DEFAULT_PROMPT =
        "保留這張圖片裡的商品本體形狀、顏色、材質完全不變，" +
            "商品上原有的品牌logo、商標、廠牌文字，請盡量完整保留、清晰可辨識，不要無故移除或模糊化。" +
            "但logo通常面積小、筆畫細，如果因為改變背景或姿態導致logo在生成過程中變得模糊、扭曲、" +
            "筆畫斷裂殘缺或難以辨識，這種「看起來奇怪、不完整、半模糊」的失敗結果比完全沒有logo更糟——" +
            "遇到這種情況，請乾脆把這個logo完全移除乾淨（用材質本身的顏色或紋理自然填補，不留痕跡），" +
            "最終結果只能是「清晰完整保留」或「乾淨移除不留痕跡」兩種狀態其中一種，不能是中間那種" +
            "殘缺不上不下的結果。" +
            "商品本體的實際造型、外觀圖案、表面紋路必須跟原圖保持一致，" +
            "特別注意商品上外露的獨立零件（例如天線、按鈕、接口、旋鈕、支架/腳架等），" +
            "數量、粗細、清晰度都必須跟原圖完全相符，不能有零件消失、數量變少、變淡" +
            "（近乎透明看不清楚）或無中生有增加的情況——請在生成前後逐一清點這些零件確認數量一致。" +
            "不能因為改變背景、姿態或後製處理而讓商品變形、走樣或喪失原本可辨識的外觀特徵。" +
            "如果原圖同時呈現同一款商品的多種顏色或款式排列展示（例如一整排不同顏色的商品），" +
            "最多只保留其中2個具代表性的顏色或款式，不需要全部保留。" +
            "如果這張圖片本身是行銷banner（帶有大量疊加的標題文字、標語、勾選圖示、按鈕、價格標籤等），" +
            "把這些疊加在圖片上的文字和圖層全部移除，只保留商品本體，" +
            "重新生成一個乾淨自然的情境背景，讓整張圖看起來像一張正常拍攝的商品照片。" +
            "檢查商品在原圖中的擺放姿態是否符合這類商品正常展示/使用時的樣子，" +
            "如果看起來不自然（懸空、無支撐、角度違反物理），依商品類型調整成合理的擺放方式" +
            "——例如衣物用衣架吊掛或平整攤開展示、鞋子立於地面或斜放展示、包袋立放或側放、" +
            "小型電子用品放在桌面或使用情境中、麥克風、遙控器、吹風機等手持小物整支側面平放貼合桌面，" +
            "或立於底座/支架上，不要用單一小面積斜靠撐住整支的不穩定姿勢，" +
            "珠寶飾品用展示台或平整擺放，" +
            "其他沒列出來的品類也依常理判斷合適的擺法——並加上柔和寫實的陰影讓畫面看起來穩固自然。" +
            "但如果原圖本身是拆解圖／材質分層展示圖這種刻意呈現結構的手法，" +
            "維持原本的分解排列方式，不用強制擺放。" +
            "這張圖片裡只能有商品本身，不可以加入任何真人模特兒、人物、手部或身體部位。" +
            "如果原圖裡有人穿著或拿著這個商品，把人物移除，" +
            "同時判斷這個商品本身能不能在沒有人撐著的狀況下維持原本的形狀站立或攤平" +
            "——如果不能（例如斗篷、雨衣、連帽外套、長版罩衫這類需要身形撐開才有形狀的衣物），" +
            "改成用透明人形模特兒架、木製衣架吊掛展示，或是平整攤開放在平面上展示，" +
            "不要讓商品維持人穿著時的立體形狀卻沒有任何支撐物懸空在半空中，" +
            "並合理重建商品原本被人物遮住的背景。" +
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

    fun getImageSelectProvider(context: Context): ImageSelectProvider {
        val name = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_IMAGE_SELECT_PROVIDER, ImageSelectProvider.GEMINI.name)
        return try {
            ImageSelectProvider.valueOf(name ?: ImageSelectProvider.GEMINI.name)
        } catch (e: Exception) {
            ImageSelectProvider.GEMINI
        }
    }

    fun setImageSelectProvider(context: Context, provider: ImageSelectProvider) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_IMAGE_SELECT_PROVIDER, provider.name)
            .apply()
    }
}
