package com.tagcopy.shopeecapture

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import android.util.Base64

/**
 * 呼叫Gemini API（純文字+視覺判斷模型，不是圖片生成模型）從擷取到的多張候選圖片裡，
 * 自動挑出「最完整清楚拍出商品本體」的一張，取代原本人工選圖的九宮格畫面。
 *
 * 跟GeminiImageEditor共用同一組API Key（GeminiApiPrefs），但用不同模型——這裡只需要
 * 語意判斷、回傳一個數字，不需要圖片生成能力，用gemini-3.1-flash-lite這種輕量模型
 * 成本低很多、速度也快很多。
 *
 * 2026-08-30新增：整合進「生成影片」流程，取代原本手動選圖的第一步。辨識失敗
 * （沒網路、API出錯、回應解析不到有效數字）時該商品直接跳過，由呼叫端引導使用者
 * 改用原本的人工選圖畫面處理，不會用猜的隨便選一張。
 */
object GeminiImageSelector {

    private const val MODEL = "gemini-3.1-flash-lite"
    private const val ENDPOINT_TEMPLATE =
        "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=%s"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * 選圖結果。success=true時selectedIndex是bitmaps清單裡的索引（0-based）；
     * 失敗時errorMessage說明原因，呼叫端應該把該商品標記為「待人工選圖」，
     * 不要用猜的隨便挑一張頂替。
     */
    data class Result(val success: Boolean, val selectedIndex: Int?, val errorMessage: String?)

    private const val PROMPT =
        "以下是同一個商品拍到的多張候選圖片，依序編號從0開始。請選出「最完整、清楚拍出商品本體」" +
            "的那一張——排除以下這幾種情況：整張是行銷banner（大量疊加文字/標語/按鈕、商品本體很小或\n" +
            "看不清楚）、商品被裁切不完整、規格表或參數截圖、純文字說明圖、模糊失焦、光線太暗看不清楚。" +
            "如果好幾張都合格，選構圖最正常、最像一般商品照的那張。" +
            "只回傳一個數字（選中圖片的編號），不要有任何其他文字、不要加句號或說明。"

    suspend fun selectBestImage(bitmaps: List<Bitmap>, apiKey: String): Result =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) {
                return@withContext Result(false, null, "尚未設定API Key")
            }
            if (bitmaps.isEmpty()) {
                return@withContext Result(false, null, "沒有候選圖片")
            }
            if (bitmaps.size == 1) {
                // 只有一張圖不用問AI，直接選它，省一次API呼叫。
                return@withContext Result(true, 0, null)
            }
            try {
                val parts = JSONArray()
                bitmaps.forEachIndexed { index, bitmap ->
                    parts.put(JSONObject().apply { put("text", "圖片編號 $index：") })
                    parts.put(JSONObject().apply {
                        put("inline_data", JSONObject().apply {
                            put("mime_type", "image/jpeg")
                            put("data", bitmapToBase64(bitmap))
                        })
                    })
                }
                parts.put(JSONObject().apply { put("text", PROMPT) })

                val requestJson = JSONObject().apply {
                    put("contents", JSONArray().put(
                        JSONObject().apply { put("parts", parts) }
                    ))
                }

                val url = String.format(ENDPOINT_TEMPLATE, MODEL, apiKey)
                val body = requestJson.toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder().url(url).post(body).build()

                client.newCall(request).execute().use { resp ->
                    val responseBody = resp.body?.string()
                    if (!resp.isSuccessful || responseBody.isNullOrBlank()) {
                        return@withContext Result(
                            false, null,
                            "HTTP ${resp.code}：${responseBody?.take(300) ?: "無回應內容"}"
                        )
                    }

                    val text = extractResponseText(responseBody)
                        ?: return@withContext Result(false, null, "回應裡沒有找到文字內容：${responseBody.take(300)}")

                    val index = parseIndex(text, bitmaps.size)
                        ?: return@withContext Result(false, null, "無法從AI回應解析出有效的圖片編號：「$text」")

                    Result(true, index, null)
                }
            } catch (e: Exception) {
                Result(false, null, "${e.javaClass.simpleName}：${e.message}")
            }
        }

    /** 從AI回應文字裡抓出第一個數字，並確認落在合法範圍內（0 ~ count-1）才採用。 */
    private fun parseIndex(text: String, count: Int): Int? {
        val match = Regex("\\d+").find(text.trim()) ?: return null
        val index = match.value.toIntOrNull() ?: return null
        return if (index in 0 until count) index else null
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }

    /** 從Gemini API的JSON回應裡，找出第一段文字回應。 */
    private fun extractResponseText(responseJson: String): String? {
        val root = JSONObject(responseJson)
        val candidates = root.optJSONArray("candidates") ?: return null
        for (i in 0 until candidates.length()) {
            val content = candidates.optJSONObject(i)?.optJSONObject("content") ?: continue
            val parts = content.optJSONArray("parts") ?: continue
            for (j in 0 until parts.length()) {
                val text = parts.optJSONObject(j)?.optString("text")
                if (!text.isNullOrBlank()) return text
            }
        }
        return null
    }
}
