package com.tagcopy.shopeecapture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
 * 呼叫Gemini API做「保留商品本體、只換背景」的AI改圖（Nano Banana 2 Lite，
 * 模型代號 gemini-3.1-flash-lite-image）。這是【測試功能】，透過App主畫面的開關
 * 決定要不要啟用，預設關閉——不影響原本擷取流程的穩定性，API Key沒填或呼叫失敗
 * 都會直接沿用原圖，不會讓整個擷取失敗。
 *
 * 申請API Key的方式：瀏覽器打開 https://aistudio.google.com/apikey ，用Google帳號登入後
 * 點「Create API key」就會產生一組金鑰字串，複製貼到App主畫面的設定欄位即可。
 * 有免費額度可以先試用，額度用完或想要更高流量才需要綁信用卡升級付費方案。
 */
object GeminiImageEditor {

    private const val MODEL = "gemini-3.1-flash-lite-image"
    private const val ENDPOINT_TEMPLATE =
        "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=%s"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * 換背景結果。success=true時editedBitmap一定有值；失敗時errorMessage說明原因，
     * 呼叫端應該保留原圖繼續走原本流程，不要因為這裡失敗就中斷整個擷取。
     */
    data class Result(val success: Boolean, val editedBitmap: Bitmap?, val errorMessage: String?)

    suspend fun editBackground(bitmap: Bitmap, apiKey: String, prompt: String): Result =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) {
                return@withContext Result(false, null, "尚未設定API Key")
            }
            try {
                val inputBase64 = bitmapToBase64(bitmap)

                val requestJson = JSONObject().apply {
                    put("contents", JSONArray().put(
                        JSONObject().apply {
                            put("parts", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("inline_data", JSONObject().apply {
                                        put("mime_type", "image/jpeg")
                                        put("data", inputBase64)
                                    })
                                })
                                put(JSONObject().apply { put("text", prompt) })
                            })
                        }
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

                    val outputBase64 = extractFirstImageBase64(responseBody)
                        ?: return@withContext Result(false, null, "回應裡沒有找到圖片資料：${responseBody.take(300)}")

                    val editedBytes = Base64.decode(outputBase64, Base64.DEFAULT)
                    val editedBitmap = BitmapFactory.decodeByteArray(editedBytes, 0, editedBytes.size)
                        ?: return@withContext Result(false, null, "回傳的圖片資料無法解碼")

                    Result(true, editedBitmap, null)
                }
            } catch (e: Exception) {
                Result(false, null, "${e.javaClass.simpleName}：${e.message}")
            }
        }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }

    /** 從Gemini API的JSON回應裡，找出第一個inline_data圖片資料（base64字串）。 */
    private fun extractFirstImageBase64(responseJson: String): String? {
        val root = JSONObject(responseJson)
        val candidates = root.optJSONArray("candidates") ?: return null
        for (i in 0 until candidates.length()) {
            val content = candidates.optJSONObject(i)?.optJSONObject("content") ?: continue
            val parts = content.optJSONArray("parts") ?: continue
            for (j in 0 until parts.length()) {
                val inlineData = parts.optJSONObject(j)?.optJSONObject("inline_data")
                    ?: parts.optJSONObject(j)?.optJSONObject("inlineData")
                val data = inlineData?.optString("data")
                if (!data.isNullOrBlank()) return data
            }
        }
        return null
    }
}
