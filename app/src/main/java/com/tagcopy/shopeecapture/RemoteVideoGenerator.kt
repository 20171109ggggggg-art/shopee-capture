package com.tagcopy.shopeecapture

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 【2026-09-02新增】App直接呼叫筆電端FastAPI服務（main.py）生成影片，取代舊版透過
 * Termux執行batch_generate.py的做法——手機不再需要安裝Termux，只裝這個App就能完成
 * 從擷取到生成影片的全部流程（上架/FB上架本來就已經是App自己在做，跟Termux無關）。
 *
 * 進度回報方式刻意沿用舊版同一套機制：把進度寫進<CaptionQueue根目錄>/.progress.json，
 * 欄位格式跟舊版batch_generate.py的write_progress()完全一致，讓GenerateVideoScreen
 * 既有的TermuxRunner.readBatchProgress()輪詢邏輯不用改一行就能繼續運作，只是這次
 * 寫入這個檔案的變成App自己（在RemoteVideoGenService背景執行），不是Termux裡的Python。
 */
object RemoteVideoGenerator {

    class ServerUnreachableException(message: String) : IOException(message)

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .callTimeout(320, TimeUnit.SECONDS)
        .build()

    private const val UNCLASSIFIED_ACCOUNT = "未分類帳號"

    private sealed class GenOutcome {
        data class Ok(val outputPath: String) : GenOutcome()
        data class Skipped(val message: String) : GenOutcome()
        data class Error(val message: String) : GenOutcome()
    }

    /**
     * 處理整批選定的商品資料夾。行為刻意跟舊版run_batch()對齊：
     * - 連不上筆電（逾時/連不上/DNS解析失敗等網路層級問題）視為整批中止，
     *   status寫"error_laptop_unreachable"，不會一支一支各自失敗浪費時間跑完全部
     * - 筆電有回應、但單一商品處理過程本身失敗（伺服器回應非200），視為一般單支
     *   失敗，繼續處理下一支
     * - 每支處理完都檢查<root>/.stop_signal，看到就完成目前這支後結束整批
     * - 批次結束（不管成功/停止/斷線中止）都會嘗試備份防重複資料庫，失敗只印警告
     *   不影響本次批次已經完成的結果
     */
    suspend fun runBatch(
        context: Context,
        captionQueueDir: File,
        selectedFolderNames: Set<String>,
        force: Boolean
    ): Unit = withContext(Dispatchers.IO) {
        val serverUrl = ServerPrefs.getServerUrl(context)
        val folders = captionQueueDir.listFiles { f -> f.isDirectory && f.name in selectedFolderNames }
            ?.sortedBy { it.name } ?: emptyList()

        val okNames = mutableListOf<String>()
        val skippedNames = mutableListOf<String>()
        val errorItems = mutableListOf<Pair<String, String>>()
        val total = folders.size
        val startTime = System.currentTimeMillis()
        var stopped = false
        var laptopUnreachable = false

        // 開始新一批之前，先清掉可能殘留的舊訊號/進度檔案，避免App第一次輪詢就讀到
        // 上一批留下的舊狀態、誤判目前這批的進度（跟舊版邏輯一致）。
        try {
            File(captionQueueDir, ".stop_signal").delete()
            File(captionQueueDir, ".progress.json").delete()
        } catch (e: Exception) {
            // 檔案本來就不存在時刪除會失敗，忽略即可
        }

        writeProgress(captionQueueDir, total, 0, "", "running", 0, 0, 0)

        for ((idx, folder) in folders.withIndex()) {
            val name = folder.name
            writeProgress(captionQueueDir, total, idx, name, "running", okNames.size, skippedNames.size, errorItems.size)

            var outcome: GenOutcome? = null
            try {
                outcome = generateOne(folder, serverUrl, force) { step ->
                    writeProgress(
                        captionQueueDir, total, idx, name, "running",
                        okNames.size, skippedNames.size, errorItems.size, step = step
                    )
                }
            } catch (e: ServerUnreachableException) {
                laptopUnreachable = true
                writeProgress(
                    captionQueueDir, total, idx, name, "error_laptop_unreachable",
                    okNames.size, skippedNames.size, errorItems.size,
                    okNames = okNames, skippedNames = skippedNames, errorItems = errorItems,
                    elapsedSeconds = (System.currentTimeMillis() - startTime) / 1000.0
                )
                break
            } catch (e: Exception) {
                errorItems.add(name to "${e.javaClass.simpleName} ${e.message}")
            }

            when (outcome) {
                is GenOutcome.Ok -> okNames.add(name)
                is GenOutcome.Skipped -> skippedNames.add(name)
                is GenOutcome.Error -> errorItems.add(name to outcome.message)
                null -> { /* 上面的catch區塊已經處理過 */ }
            }

            if (File(captionQueueDir, ".stop_signal").exists()) {
                try {
                    File(captionQueueDir, ".stop_signal").delete()
                } catch (e: Exception) { /* 忽略 */ }
                stopped = true
                writeProgress(
                    captionQueueDir, total, idx + 1, "", "stopped",
                    okNames.size, skippedNames.size, errorItems.size,
                    okNames = okNames, skippedNames = skippedNames, errorItems = errorItems,
                    elapsedSeconds = (System.currentTimeMillis() - startTime) / 1000.0
                )
                break
            }
        }

        if (!stopped && !laptopUnreachable) {
            writeProgress(
                captionQueueDir, total, total, "", "done",
                okNames.size, skippedNames.size, errorItems.size,
                okNames = okNames, skippedNames = skippedNames, errorItems = errorItems,
                elapsedSeconds = (System.currentTimeMillis() - startTime) / 1000.0
            )
        }

        if (serverUrl.isNotBlank()) {
            backupDedupHistory(serverUrl)
        }
    }

    private fun generateOne(
        folder: File,
        serverUrl: String,
        force: Boolean,
        onStep: (String) -> Unit
    ): GenOutcome {
        val outputFile = File(folder, "output.mp4")
        // 跳過判斷比照舊版process_folder()邏輯：不是只看output.mp4存不存在，還要驗證
        // 它是不是一支完整可播放的影片，避免上次生成到一半被中斷留下的殘缺檔案被誤判
        // 成「已完成」而永遠不會重新生成。
        if (outputFile.isFile && !force && isValidVideo(outputFile)) {
            return GenOutcome.Skipped("output.mp4 已存在")
        }

        val account = readAccountFromMeta(folder)

        onStep("打包上傳中")
        val zipBytes = try {
            zipFolder(folder)
        } catch (e: Exception) {
            return GenOutcome.Error("打包資料夾失敗：${e.javaClass.simpleName} ${e.message}")
        }

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("account", account)
            .addFormDataPart("folder_name", folder.name)
            .addFormDataPart(
                "product_zip", "product.zip",
                zipBytes.toRequestBody("application/zip".toMediaType())
            )
            .build()

        val request = Request.Builder()
            .url("$serverUrl/generate-video")
            .post(requestBody)
            .build()

        onStep("筆電生成中")
        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw ServerUnreachableException("連線筆電服務失敗（$serverUrl）：${e.javaClass.simpleName} ${e.message}")
        }

        response.use { resp ->
            if (!resp.isSuccessful) {
                val detail = try {
                    val text = resp.body?.string()
                    if (text.isNullOrBlank()) "HTTP ${resp.code}"
                    else JSONObject(text).optString("message", text.take(200))
                } catch (e: Exception) {
                    "HTTP ${resp.code}"
                }
                return GenOutcome.Error("筆電服務回報生成失敗：$detail")
            }
            val bytes = resp.body?.bytes()
                ?: return GenOutcome.Error("筆電服務回應內容是空的")
            try {
                outputFile.writeBytes(bytes)
            } catch (e: Exception) {
                return GenOutcome.Error("寫入影片檔案失敗：${e.javaClass.simpleName} ${e.message}")
            }
        }

        return GenOutcome.Ok(outputFile.path)
    }

    /** 把商品資料夾整包壓成zip（在記憶體中組，不落地暫存檔案）。故意排除output.mp4
     * 本身——會進到這裡代表舊影片不存在或驗證失敗，沒必要把壞掉/不需要的舊檔案也
     * 傳一份耗費手機流量。 */
    private fun zipFolder(folder: File): ByteArray {
        val buffer = ByteArrayOutputStream()
        ZipOutputStream(buffer).use { zos ->
            folder.listFiles()?.forEach { file ->
                if (!file.isFile) return@forEach
                if (file.name == "output.mp4") return@forEach
                zos.putNextEntry(ZipEntry(file.name))
                file.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
        return buffer.toByteArray()
    }

    /** 讀該商品資料夾meta.json裡的account欄位（App v1.023起擷取時會寫入），供傳給
     * 筆電服務做備份分類用。舊資料/讀取失敗都歸類成「未分類帳號」，不會讓生成流程
     * 出錯中斷。 */
    private fun readAccountFromMeta(folder: File): String {
        return try {
            val metaFile = File(folder, "meta.json")
            if (!metaFile.isFile) return UNCLASSIFIED_ACCOUNT
            val json = JSONObject(metaFile.readText())
            val account = json.optString("account", "").trim()
            account.ifBlank { UNCLASSIFIED_ACCOUNT }
        } catch (e: Exception) {
            UNCLASSIFIED_ACCOUNT
        }
    }

    /** 驗證output.mp4是不是一支完整可播放的影片：用系統內建的MediaMetadataRetriever
     * 讀取時長，讀不到／拋例外視為檔案損毀或不完整，不需要額外套件就能達到跟舊版
     * Python那邊is_valid_video()同等的效果。 */
    private fun isValidVideo(file: File): Boolean {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.path)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            duration != null && (duration.toLongOrNull() ?: 0L) > 0L
        } catch (e: Exception) {
            false
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) { /* 忽略 */ }
        }
    }

    /** 把手機的永久擷取歷史記錄檔案（Download/CaptureHistory/captured_history.jsonl，
     * 見ShopeeAccessibilityService.getCaptureHistoryFile()）同步備份一份到筆電。這份
     * 檔案是手機上所有蝦皮帳號共用的單一紀錄，不屬於特定帳號，備份失敗只記錄警告，
     * 不影響本次批次已經完成的結果。 */
    private fun backupDedupHistory(serverUrl: String) {
        val historyFile = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "CaptureHistory/captured_history.jsonl"
        )
        if (!historyFile.isFile) return
        try {
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "history_file", "captured_history.jsonl",
                    historyFile.asRequestBody("application/octet-stream".toMediaType())
                )
                .build()
            val request = Request.Builder()
                .url("$serverUrl/backup-dedup")
                .post(requestBody)
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w("RemoteVideoGenerator", "防重複資料庫備份失敗：HTTP ${resp.code}")
                }
            }
        } catch (e: Exception) {
            Log.w("RemoteVideoGenerator", "防重複資料庫備份失敗：${e.javaClass.simpleName} ${e.message}")
        }
    }

    /** 把進度寫進<CaptionQueue根目錄>/.progress.json，欄位格式跟舊版batch_generate.py
     * 的write_progress()完全一致，供GenerateVideoScreen既有的輪詢邏輯讀取。 */
    private fun writeProgress(
        root: File, total: Int, completed: Int, current: String, status: String,
        okCount: Int, skippedCount: Int, errorCount: Int, step: String = "",
        okNames: List<String>? = null, skippedNames: List<String>? = null,
        errorItems: List<Pair<String, String>>? = null, elapsedSeconds: Double? = null
    ) {
        val progressFile = File(root, ".progress.json")
        try {
            val json = JSONObject().apply {
                put("total", total)
                put("completed", completed)
                put("current", current)
                put("status", status)
                put("okCount", okCount)
                put("skippedCount", skippedCount)
                put("errorCount", errorCount)
                put("step", step)
                put("updatedAt", System.currentTimeMillis() / 1000.0)
                if (okNames != null) put("okNames", JSONArray(okNames))
                if (skippedNames != null) put("skippedNames", JSONArray(skippedNames))
                if (errorItems != null) {
                    put("errorItems", JSONArray(errorItems.map { (n, m) -> JSONArray(listOf(n, m)) }))
                }
                if (elapsedSeconds != null) put("elapsedSeconds", elapsedSeconds)
            }
            progressFile.writeText(json.toString())
        } catch (e: Exception) {
            Log.w("RemoteVideoGenerator", "寫入進度檔案失敗：${e.javaClass.simpleName} ${e.message}")
        }
    }
}
