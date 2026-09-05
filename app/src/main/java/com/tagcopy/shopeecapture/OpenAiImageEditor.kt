package com.tagcopy.shopeecapture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 【2026-09-05新增】呼叫OpenAI圖片編輯API（/v1/images/edits，gpt-image-1模型）做
 * 「保留商品本體、只換背景」的AI改圖，作為GeminiImageEditor的替代供應商——使用者在
 * App設定畫面的「AI改圖服務」選單手動切換要用Gemini還是ChatGPT，不做自動判斷/自動
 * 備援切換（品質好壞很難用程式準確判斷，切錯了反而更難排查）。
 *
 * 跟GeminiImageEditor共用同一份換背景提示詞（GeminiApiPrefs.getPrompt()），先不分裂
 * 成兩份維護；之後如果發現ChatGPT對這份提示詞有系統性理解落差，再考慮另外調整。
 *
 * 申請API Key：瀏覽器打開 https://platform.openai.com/api-keys，登入後點
 * 「Create new secret key」，複製貼到App設定畫面的欄位即可。OpenAI沒有Gemini那種
 * 免費額度，第一次使用需要先在帳戶加入付款方式（用量不大的話單次改圖成本很低）。
 *
 * API回應格式：gpt-image-1一律回傳base64編碼圖片（沒有Gemini那種url選項可切換），
 * 欄位在JSON的data陣列裡，key是b64_json。
 */
object OpenAiImageEditor {

    private const val MODEL = "gpt-image-1"
    private const val ENDPOINT = "https://api.openai.com/v1/images/edits"

    // 【2026-09-05】品質選medium：查過實際市場行情，medium畫質的gpt-image-1單價比
    // 目前用的Nano Banana 2 Lite還便宜，不是「換品質好的就要多花錢」的取捨，純粹是
    // 開發工作量問題才沒有一開始就換供應商。
    private const val QUALITY = "medium"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .build()

    /**
     * 換背景結果。success=true時editedBitmap一定有值；失敗時errorMessage說明原因，
     * 呼叫端應該保留原圖繼續走原本流程，不要因為這裡失敗就中斷整個擷取。
     * 跟GeminiImageEditor.Result是各自獨立的data class，呼叫端依使用者選擇的供應商
     * 各自呼叫對應的object，不需要共用同一個型別。
     */
    data class Result(val success: Boolean, val editedBitmap: Bitmap?, val errorMessage: String?)

    suspend fun editBackground(bitmap: Bitmap, apiKey: String, prompt: String): Result =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) {
                return@withContext Result(false, null, "尚未設定OpenAI API Key")
            }
            var tempFile: File? = null
            try {
                val imageBytes = bitmapToPngBytes(bitmap)
                tempFile = File.createTempFile("openai_edit_", ".png").apply {
                    writeBytes(imageBytes)
                    deleteOnExit()
                }

                val bodyBuilder = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("model", MODEL)
                    .addFormDataPart("prompt", prompt)
                    .addFormDataPart("size", "auto")
                    .addFormDataPart("quality", QUALITY)
                    .addFormDataPart("n", "1")
                    .addFormDataPart(
                        "image", tempFile.name,
                        tempFile.asRequestBody("image/png".toMediaType())
                    )

                val request = Request.Builder()
                    .url(ENDPOINT)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .post(bodyBuilder.build())
                    .build()

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
            } finally {
                tempFile?.delete()
            }
        }

    private fun bitmapToPngBytes(bitmap: Bitmap): ByteArray {
        // gpt-image-1接受png/webp/jpg，這裡固定用PNG（不失真），避免JPEG壓縮痕跡
        // 疊加在已經壓縮過一次的商品圖上，兩次壓縮容易讓細節更模糊。
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        return stream.toByteArray()
    }

    /** 從OpenAI API的JSON回應裡，找出第一張圖片的base64資料（欄位名是b64_json）。 */
    private fun extractFirstImageBase64(responseJson: String): String? {
        val root = JSONObject(responseJson)
        val data = root.optJSONArray("data") ?: return null
        for (i in 0 until data.length()) {
            val b64 = data.optJSONObject(i)?.optString("b64_json")
            if (!b64.isNullOrBlank()) return b64
        }
        return null
    }
}
