package com.tagcopy.shopeecapture

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 遠端設定檔網址：放在你自己的 GitHub repo 裡的一個 JSON 檔。
 * 蝦皮改版、按鈕文字或元件 ID 變動時，只要更新這個檔案內容並 push，
 * App 下次啟動會自動抓到新規則，不需要重新打包 apk。
 *
 * 範例 JSON 內容：
 * {
 *   "copyLinkButtonTexts": ["複製連結", "Copy link"],
 *   "productImageResourceIdKeywords": ["image", "img", "photo"],
 *   "shareSheetTitleTexts": ["分享以獲得分潤金"]
 * }
 */
object RemoteConfigLoader {

    private const val CONFIG_URL =
        "https://raw.githubusercontent.com/YOUR_GITHUB_USERNAME/shopee-capture-config/main/match_rules.json"

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    fun load(): MatchRules {
        return try {
            val request = Request.Builder().url(CONFIG_URL).build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return MatchRules.DEFAULT
                val body = resp.body?.string() ?: return MatchRules.DEFAULT
                parse(body)
            }
        } catch (e: Exception) {
            MatchRules.DEFAULT
        }
    }

    private fun parse(json: String): MatchRules {
        val obj = JSONObject(json)
        fun readList(key: String, fallback: List<String>): List<String> {
            if (!obj.has(key)) return fallback
            val arr: JSONArray = obj.getJSONArray(key)
            return (0 until arr.length()).map { arr.getString(it) }
        }
        return MatchRules(
            copyLinkButtonTexts = readList("copyLinkButtonTexts", MatchRules.DEFAULT.copyLinkButtonTexts),
            productImageResourceIdKeywords = readList("productImageResourceIdKeywords", MatchRules.DEFAULT.productImageResourceIdKeywords),
            shareSheetTitleTexts = readList("shareSheetTitleTexts", MatchRules.DEFAULT.shareSheetTitleTexts),
            shareButtonDescriptors = readList("shareButtonDescriptors", MatchRules.DEFAULT.shareButtonDescriptors),
            priceIndicatorPrefixes = readList("priceIndicatorPrefixes", MatchRules.DEFAULT.priceIndicatorPrefixes)
        )
    }
}
