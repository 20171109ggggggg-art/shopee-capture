package com.tagcopy.shopeecapture

import android.graphics.Bitmap
import android.util.Base64
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

/**
 * 【2026-09-07新增】呼叫OpenAI的Chat Completions API（gpt-5-nano，支援視覺輸入的
 * 輕量／低成本模型）做「AI辨識選圖」，作為GeminiImageSelector的替代供應商——原本這一步
 * 寫死只能用Gemini（OpenAI沒有對應的圖片評分/選圖專用API，所以用一般的視覺對話模型
 * 自己組一個功能等效的版本）。使用者在設定畫面「AI辨識選圖服務」手動切換，方便實際測試
 * Gemini/ChatGPT兩邊在「挑主圖」這件事上的判斷差異，跟AI改圖是各自獨立的供應商設定。
 *
 * 直接沿用GeminiImageSelector.MultiResult當回傳型別，呼叫端不用為了多一個供應商
 * 另外寫一套處理邏輯。
 */
object OpenAiImageSelector {

    private const val MODEL = "gpt-5-nano"
    private const val ENDPOINT = "https://api.openai.com/v1/chat/completions"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private const val MULTI_PROMPT_TEMPLATE =
        "以下是同一個商品拍到的多張候選圖片，依序編號從0開始。請選出最多%d張「最完整、" +
            "清楚拍出商品本體」的圖片——排除以下這幾種情況：整張是行銷banner（大量疊加文字/標語/" +
            "按鈕、商品本體很小或看不清楚）、商品被裁切不完整、規格表或參數截圖、純文字說明圖、" +
            "模糊失焦、光線太暗看不清楚。如果符合條件的圖片不到%d張，有幾張算幾張，不要硬湊。" +
            "只回傳用逗號分隔的數字清單（例如：2,0,5），不要有任何其他文字、不要加句號或說明。"

    suspend fun selectBestImages(
        bitmaps: List<Bitmap>,
        apiKey: String,
        maxCount: Int = 3
    ): GeminiImageSelector.MultiResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext GeminiImageSelector.MultiResult(false, emptyList(), "尚未設定OpenAI API Key")
        }
        if (bitmaps.isEmpty()) {
            return@withContext GeminiImageSelector.MultiResult(false, emptyList(), "沒有候選圖片")
        }
        if (bitmaps.size == 1) {
            // 只有一張圖不用問AI，直接選它，省一次API呼叫。
            return@withContext GeminiImageSelector.MultiResult(true, listOf(0), null)
        }
        try {
            val content = JSONArray()
            content.put(JSONObject().apply {
                put("type", "text")
                put("text", String.format(MULTI_PROMPT_TEMPLATE, maxCount, maxCount))
            })
            bitmaps.forEachIndexed { index, bitmap ->
                content.put(JSONObject().apply {
                    put("type", "text")
                    put("text", "圖片編號 $index：")
                })
                content.put(JSONObject().apply {
                    put("type", "image_url")
                    put("image_url", JSONObject().apply {
                        put("url", "data:image/jpeg;base64,${bitmapToBase64(bitmap)}")
                    })
                })
            }

            val requestJson = JSONObject().apply {
                put("model", MODEL)
                put(
                    "messages",
                    JSONArray().put(
                        JSONObject().apply {
                            put("role", "user")
                            put("content", content)
                        }
                    )
                )
            }

            val body = requestJson.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(ENDPOINT)
                .addHeader("Authorization", "Bearer $apiKey")
                .post(body)
                .build()

            client.newCall(request).execute().use { resp ->
                val responseBody = resp.body?.string()
                if (!resp.isSuccessful || responseBody.isNullOrBlank()) {
                    return@withContext GeminiImageSelector.MultiResult(
                        false, emptyList(),
                        "HTTP ${resp.code}：${responseBody?.take(300) ?: "無回應內容"}"
                    )
                }

                val text = extractResponseText(responseBody)
                    ?: return@withContext GeminiImageSelector.MultiResult(
                        false, emptyList(), "回應裡沒有找到文字內容：${responseBody.take(300)}"
                    )

                val indexes = parseIndexList(text, bitmaps.size, maxCount)
                if (indexes.isEmpty()) {
                    return@withContext GeminiImageSelector.MultiResult(
                        false, emptyList(), "無法從AI回應解析出有效的圖片編號：「$text」"
                    )
                }

                GeminiImageSelector.MultiResult(true, indexes, null)
            }
        } catch (e: Exception) {
            GeminiImageSelector.MultiResult(false, emptyList(), "${e.javaClass.simpleName}：${e.message}")
        }
    }

    /** 從AI回應文字裡抓出逗號分隔的數字清單，過濾掉超出範圍或重複的編號，最多取maxCount個。 */
    private fun parseIndexList(text: String, count: Int, maxCount: Int): List<Int> {
        val seen = linkedSetOf<Int>()
        Regex("\\d+").findAll(text.trim()).forEach { m ->
            val index = m.value.toIntOrNull()
            if (index != null && index in 0 until count) {
                seen.add(index)
            }
        }
        return seen.take(maxCount)
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }

    /** 從OpenAI Chat Completions API的JSON回應裡，找出第一段文字回應。 */
    private fun extractResponseText(responseJson: String): String? {
        val root = JSONObject(responseJson)
        val choices = root.optJSONArray("choices") ?: return null
        for (i in 0 until choices.length()) {
            val message = choices.optJSONObject(i)?.optJSONObject("message") ?: continue
            val text = message.optString("content")
            if (text.isNotBlank()) return text
        }
        return null
    }
}
