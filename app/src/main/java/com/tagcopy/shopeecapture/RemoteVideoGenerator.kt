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

    private var currentVideoLogFileName: String? = null

    /**
     * 【2026-09-04新增】比照ShopeeAccessibilityService.kt的appendDebugLog()同一套持久化
     * log機制，供之後如果再發生「App回報生成成功、但手機本地找不到output.mp4」這類異常
     * 時，能有實際證據可查、不用再靠猜的排查。放在同一個資料夾（Download/
     * ShopeeCaptureDebugLog/）但檔名前綴改成video_gen_log_（原本擷取階段的是
     * debug_log_），方便辨識、不會互相覆蓋覆寫。同一批次（一次runBatch()）共用同一份
     * 檔案，每次呼叫runBatch()開頭都會重置成新檔名（見runBatch()裡的reset）。
     * 寫檔失敗（例如存取權限被系統收回）只忽略，不能讓記錄本身變成新的失敗點。
     */
    private fun appendVideoLog(line: String) {
        try {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "ShopeeCaptureDebugLog"
            )
            if (!dir.exists()) dir.mkdirs()
            val fileName = currentVideoLogFileName
                ?: "video_gen_log_${java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())}.txt".also {
                    currentVideoLogFileName = it
                }
            val file = File(dir, fileName)
            val timestamp = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
            file.appendText("[$timestamp] $line\n")
        } catch (e: Exception) {
            // 寫檔失敗不影響主流程，忽略即可
        }
    }

    /**
     * 【2026-09-04新增】把筆電端process_folder()生成成功後回寫的videoGeneratedAt/
     * narrationText/hashtags這幾個欄位，合併寫回手機本地的meta.json。
     * 只更新這幾個固定欄位，不是整份覆蓋——account/promoLink/shopeePosted/fbPosted/
     * region這些欄位是手機本地才有正確狀態的，筆電端那份meta.json（server端只是暫時
     * 解壓縮出來跑生成用的副本）不會有這些欄位的正確值，覆蓋掉會出問題。
     * 背景：這幾個欄位（尤其videoGeneratedAt）是蝦皮上架自動化掃描候選商品的必要
     * 條件（見ShopeeAccessibilityService.kt的scanUploadCandidates()），v1.026改成
     * 筆電生成架構後，這個回寫從來沒有真的傳回手機，導致上架自動化永遠掃不到
     * 任何候選商品，這裡修正。
     * 合併失敗只記log，不拋例外——這支影片本身已經確認生成/寫入成功，不該因為
     * 這個次要欄位合併失敗就讓整支影片被判定失敗（比較嚴重的後果只是上架自動化
     * 暫時掃不到這支候選，之後重新生成一次就會補上，是可回復的）。
     */
    private fun mergeGeneratedMetaFields(folder: File, remoteMetaJsonText: String) {
        try {
            val remote = JSONObject(remoteMetaJsonText)
            val localMetaFile = File(folder, "meta.json")
            val local = if (localMetaFile.isFile) {
                try {
                    JSONObject(localMetaFile.readText())
                } catch (e: Exception) {
                    JSONObject()
                }
            } else {
                JSONObject()
            }
            for (key in listOf("videoGeneratedAt", "narrationText", "hashtags")) {
                if (remote.has(key)) {
                    local.put(key, remote.get(key))
                }
            }
            localMetaFile.writeText(local.toString())
            appendVideoLog("[${folder.name}] 已合併videoGeneratedAt等欄位回本地meta.json")
        } catch (e: Exception) {
            appendVideoLog("[${folder.name}] 合併meta.json欄位失敗（不影響影片本身，但上架自動化可能還是掃不到這支）：${e.javaClass.simpleName} ${e.message}")
        }
    }



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

        currentVideoLogFileName = null
        appendVideoLog("===== 開始生成批次，共 ${folders.size} 支，force=$force =====")

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
                outcome = generateOne(context, folder, serverUrl, force) { step ->
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

        appendVideoLog("===== 批次結束：成功${okNames.size}／跳過${skippedNames.size}／失敗${errorItems.size}／停止=$stopped／筆電斷線=$laptopUnreachable =====")

        if (serverUrl.isNotBlank()) {
            backupDedupHistory(serverUrl)
        }
    }

    private fun generateOne(
        context: Context,
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
            appendVideoLog("[${folder.name}] output.mp4已存在且驗證通過，跳過")
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
        appendVideoLog("[${folder.name}] 送出generate-video請求，zip大小=${zipBytes.size} bytes")
        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            appendVideoLog("[${folder.name}] 連線筆電失敗：${e.javaClass.simpleName} ${e.message}")
            throw ServerUnreachableException("連線筆電服務失敗（$serverUrl）：${e.javaClass.simpleName} ${e.message}")
        }

        response.use { resp ->
            appendVideoLog("[${folder.name}] 收到回應，HTTP ${resp.code}，isSuccessful=${resp.isSuccessful}")
            if (!resp.isSuccessful) {
                val detail = try {
                    val text = resp.body?.string()
                    if (text.isNullOrBlank()) "HTTP ${resp.code}"
                    else JSONObject(text).optString("message", text.take(200))
                } catch (e: Exception) {
                    "HTTP ${resp.code}"
                }
                appendVideoLog("[${folder.name}] 筆電回報失敗：$detail")
                return GenOutcome.Error("筆電服務回報生成失敗：$detail")
            }
            // 【2026-09-04】原本resp.body?.bytes()這行沒有自己的try/catch，只有下面
            // outputFile.writeBytes()那行有包——如果讀取回應內容本身就失敗（例如傳輸
            // 中斷、逾時），例外會直接從這裡往外丟，不會被下面那個try/catch接住。
            // 外層runBatch()的呼叫端有一層Exception兜底會記錄成失敗，理論上不會被
            // 完全吃掉，但這裡明確補上自己的try/catch，讓失敗原因（讀取回應失敗
            // vs 寫檔失敗）在log裡分得更清楚，之後真的再發生能一眼看出是哪一段。
            val bytes = try {
                resp.body?.bytes()
            } catch (e: Exception) {
                appendVideoLog("[${folder.name}] 讀取筆電回應內容失敗：${e.javaClass.simpleName} ${e.message}")
                return GenOutcome.Error("讀取筆電回應內容失敗：${e.javaClass.simpleName} ${e.message}")
            }
            if (bytes == null) {
                appendVideoLog("[${folder.name}] 筆電回應內容是空的（body為null）")
                return GenOutcome.Error("筆電服務回應內容是空的")
            }
            appendVideoLog("[${folder.name}] 讀到回應內容 ${bytes.size} bytes，解析zip內容")
            // 【2026-09-04新增】筆電端/generate-video現在回傳的是zip（裡面裝output.mp4＋
            // 更新後的meta.json），不是單純的mp4本身——筆電端process_folder()生成成功後
            // 會把videoGeneratedAt/narrationText/hashtags這幾個欄位回寫進meta.json，原本
            // 手機端完全沒收到這份更新，導致本地meta.json的videoGeneratedAt永遠是空的，
            // 蝦皮上架自動化掃描候選商品時（要求videoGeneratedAt有值）完全找不到候選，
            // 這裡解析zip，把mp4內容跟meta.json內容分開處理。
            var mp4Bytes: ByteArray? = null
            var remoteMetaJsonText: String? = null
            try {
                java.util.zip.ZipInputStream(bytes.inputStream()).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        when (entry.name) {
                            "output.mp4" -> mp4Bytes = zis.readBytes()
                            "meta.json" -> remoteMetaJsonText = zis.readBytes().toString(Charsets.UTF_8)
                        }
                        entry = zis.nextEntry
                    }
                }
            } catch (e: Exception) {
                appendVideoLog("[${folder.name}] 解析zip回應失敗：${e.javaClass.simpleName} ${e.message}")
                return GenOutcome.Error("解析筆電回應的zip失敗：${e.javaClass.simpleName} ${e.message}")
            }
            val videoBytes = mp4Bytes
            if (videoBytes == null) {
                appendVideoLog("[${folder.name}] zip裡沒有找到output.mp4")
                return GenOutcome.Error("筆電回應的zip裡沒有output.mp4")
            }
            appendVideoLog("[${folder.name}] zip解析完成，mp4=${videoBytes.size} bytes，meta.json=${if (remoteMetaJsonText != null) "有" else "無"}，準備寫入 ${outputFile.path}")
            // 【2026-09-04再修正】比fsync+延遲複查更徹底的做法：改成atomic write模式——
            // 先寫進output.mp4.part這個暫存檔名，驗證完整無誤後才rename成正式的
            // output.mp4。rename在同一個磁碟區上是瞬間完成、不會有中間狀態的操作，
            // 所以output.mp4這個檔名要嘛完全不存在，要嘛就是完整寫好的檔案，不會被誰
            // （包括這支App自己下次判斷是否需要重新生成的邏輯）誤判成「看得到但其實
            // 是殘缺檔案」。加上失敗自動重試最多3次（bytes已經在記憶體裡，不用重新
            // 連筆電），比單純多等一下再檢查更有機會真的解決暫時性的系統卡頓問題。
            val tempFile = File(folder, "output.mp4.part")
            var writeOk = false
            var lastWriteError = ""
            for (attempt in 1..3) {
                try {
                    if (tempFile.exists()) tempFile.delete()
                    java.io.FileOutputStream(tempFile).use { fos ->
                        fos.write(videoBytes)
                        fos.fd.sync()
                    }
                    val tempSize = if (tempFile.isFile) tempFile.length() else -1L
                    if (tempSize != videoBytes.size.toLong()) {
                        lastWriteError = "暫存檔大小不符（預期${videoBytes.size}，實際$tempSize）"
                        appendVideoLog("[${folder.name}] 第${attempt}次寫入暫存檔驗證失敗：$lastWriteError")
                        Thread.sleep(500)
                        continue
                    }
                    if (outputFile.exists()) outputFile.delete()
                    if (!tempFile.renameTo(outputFile)) {
                        lastWriteError = "暫存檔改名為output.mp4失敗"
                        appendVideoLog("[${folder.name}] 第${attempt}次$lastWriteError")
                        Thread.sleep(500)
                        continue
                    }
                    val finalSize = if (outputFile.isFile) outputFile.length() else -1L
                    if (finalSize == videoBytes.size.toLong()) {
                        appendVideoLog("[${folder.name}] 第${attempt}次寫入成功並改名完成，最終檔案大小=$finalSize bytes")
                        writeOk = true
                        break
                    } else {
                        lastWriteError = "改名後最終大小不符（預期${videoBytes.size}，實際$finalSize）"
                        appendVideoLog("[${folder.name}] 第${attempt}次$lastWriteError")
                    }
                } catch (e: Exception) {
                    lastWriteError = "${e.javaClass.simpleName} ${e.message}"
                    appendVideoLog("[${folder.name}] 第${attempt}次寫入發生例外：$lastWriteError")
                }
                Thread.sleep(500)
            }
            if (!writeOk) {
                return GenOutcome.Error("寫入影片檔案失敗（重試3次後仍失敗）：$lastWriteError")
            }

            // 【2026-09-04新增】把筆電端process_folder()回寫的videoGeneratedAt/narrationText/
            // hashtags這幾個欄位合併回本地meta.json——只更新這幾個欄位，account/promoLink/
            // shopeePosted/fbPosted/region這些手機本地才有正確狀態的欄位不會被蓋掉。
            // 合併失敗只記log，不影響這支影片本身算不算生成成功（比較嚴重的後果是
            // 上架自動化掃不到這支候選，但那是可以之後補救的，不該讓整個生成流程失敗）。
            if (remoteMetaJsonText != null) {
                mergeGeneratedMetaFields(folder, remoteMetaJsonText!!)
            }

            // 【2026-09-04新增】比照ShopeeAccessibilityService.kt裡registerVideoInMediaStore()
            // 註解說明的同一個背景：這裡是直接用File API寫檔案，不是透過MediaStore.insert()，
            // 系統的媒體索引不會自動知道多了這個檔案，部分檔案總管/相簿App在索引更新前可能
            // 看不到它（但檔案本身確實已經完整寫入，不是遺失）。這裡只是單純觸發一次掃描
            // 讓索引盡快更新，不等待掃描結果、不影響回傳速度；掃描失敗也只記log，不當成
            // 這支影片生成失敗（檔案本身已經確認寫入成功，索引與否是次要的顯示問題）。
            try {
                android.media.MediaScannerConnection.scanFile(
                    context,
                    arrayOf(outputFile.absolutePath),
                    arrayOf("video/mp4"),
                    null
                )
            } catch (e: Exception) {
                appendVideoLog("[${folder.name}] 觸發媒體庫掃描時發生例外（不影響生成結果）：${e.javaClass.simpleName} ${e.message}")
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
     * 見ShopeeAccessibilityService.getCaptureHistoryFile()）依每筆紀錄自己的account
     * 欄位分組，同一帳號的紀錄各自組成一份內容分開呼叫/backup-dedup備份——這樣筆電
     * 端才能依帳號分開存放，不會混成一份誰也分不清楚內容的檔案。缺account欄位的
     * 舊資料歸到AccountPrefs.DEFAULT_ACCOUNT。備份失敗只記錄警告，不影響本次批次
     * 已經完成的結果。 */
    private fun backupDedupHistory(serverUrl: String) {
        val historyFile = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "CaptureHistory/captured_history.jsonl"
        )
        if (!historyFile.isFile) return

        val byAccount = linkedMapOf<String, StringBuilder>()
        try {
            historyFile.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                val account = try {
                    JSONObject(line).optString("account", "").ifBlank { AccountPrefs.DEFAULT_ACCOUNT }
                } catch (e: Exception) {
                    AccountPrefs.DEFAULT_ACCOUNT
                }
                byAccount.getOrPut(account) { StringBuilder() }.append(line).append("\n")
            }
        } catch (e: Exception) {
            Log.w("RemoteVideoGenerator", "讀取防重複資料庫失敗：${e.javaClass.simpleName} ${e.message}")
            return
        }

        for ((account, content) in byAccount) {
            try {
                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("account", account)
                    .addFormDataPart(
                        "history_file", "captured_history.jsonl",
                        content.toString().toRequestBody("application/octet-stream".toMediaType())
                    )
                    .build()
                val request = Request.Builder()
                    .url("$serverUrl/backup-dedup")
                    .post(requestBody)
                    .build()
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.w("RemoteVideoGenerator", "防重複資料庫備份失敗（帳號=$account）：HTTP ${resp.code}")
                    }
                }
            } catch (e: Exception) {
                Log.w("RemoteVideoGenerator", "防重複資料庫備份失敗（帳號=$account）：${e.javaClass.simpleName} ${e.message}")
            }
        }
    }

    /**
     * 【2026-09-02新增】把指定帳號在筆電上備份過的防重複資料庫抓回來，合併寫回手機
     * 本地檔案。用「合併」而不是直接覆蓋，是因為手機本地檔案可能還留著其他帳號的
     * 紀錄，整份覆蓋會把那些紀錄弄丟；合併時用每行文字本身去重，避免同一批紀錄
     * 重複寫入。回傳實際還原了幾筆新紀錄，供UI顯示結果。找不到該帳號的備份、或
     * 連線失敗都會丟例外，由呼叫端（UI層）自行捕捉並顯示訊息。
     */
    suspend fun restoreDedupHistory(context: Context, account: String): Int = withContext(Dispatchers.IO) {
        val serverUrl = ServerPrefs.getServerUrl(context)
        if (serverUrl.isBlank()) throw IllegalStateException("尚未設定筆電生成伺服器網址")

        val request = Request.Builder()
            .url("$serverUrl/restore-dedup?account=${java.net.URLEncoder.encode(account, "UTF-8")}")
            .get()
            .build()

        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw ServerUnreachableException("連線筆電服務失敗（$serverUrl）：${e.javaClass.simpleName} ${e.message}")
        }

        val remoteText = response.use { resp ->
            if (!resp.isSuccessful) {
                val detail = try {
                    val text = resp.body?.string()
                    if (text.isNullOrBlank()) "HTTP ${resp.code}"
                    else JSONObject(text).optString("message", text.take(200))
                } catch (e: Exception) {
                    "HTTP ${resp.code}"
                }
                throw IOException(detail)
            }
            resp.body?.string() ?: ""
        }

        val historyFile = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "CaptureHistory/captured_history.jsonl"
        )
        historyFile.parentFile?.mkdirs()

        val existingLines = if (historyFile.isFile) historyFile.readLines().toMutableSet() else mutableSetOf()
        var addedCount = 0
        val toAppend = StringBuilder()
        remoteText.lineSequence().forEach { line ->
            if (line.isBlank()) return@forEach
            if (line !in existingLines) {
                toAppend.append(line).append("\n")
                existingLines.add(line)
                addedCount++
            }
        }
        if (toAppend.isNotEmpty()) {
            historyFile.appendText(toAppend.toString())
        }
        addedCount
    }

    /**
     * 【2026-09-02新增】把App設定（帳號/地區/伺服器網址/Gemini Key等，由呼叫端
     * 組好JSON字串傳進來）上傳到筆電依帳號存放，取代舊版剪貼簿匯出方式——不用
     * 兩支手機同時在手邊複製貼上，新手機只要能連到這個筆電服務就能直接下載回去。
     */
    suspend fun uploadSettings(context: Context, account: String, settingsJson: String): Unit =
        withContext(Dispatchers.IO) {
            val serverUrl = ServerPrefs.getServerUrl(context)
            if (serverUrl.isBlank()) throw IllegalStateException("尚未設定筆電生成伺服器網址")

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("account", account)
                .addFormDataPart(
                    "settings_file", "settings.json",
                    settingsJson.toRequestBody("application/json".toMediaType())
                )
                .build()
            val request = Request.Builder()
                .url("$serverUrl/backup-settings")
                .post(requestBody)
                .build()

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
                    throw IOException(detail)
                }
            }
        }

    /**
     * 【2026-09-02新增】把指定帳號在筆電上備份過的設定抓回來，回傳原始JSON字串，
     * 由呼叫端（UI層）自行解析並套用到各個Prefs。找不到備份、連線失敗都會丟例外，
     * 由呼叫端捕捉並顯示訊息。
     */
    suspend fun downloadSettings(context: Context, account: String): String = withContext(Dispatchers.IO) {
        val serverUrl = ServerPrefs.getServerUrl(context)
        if (serverUrl.isBlank()) throw IllegalStateException("尚未設定筆電生成伺服器網址")

        val request = Request.Builder()
            .url("$serverUrl/restore-settings?account=${java.net.URLEncoder.encode(account, "UTF-8")}")
            .get()
            .build()

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
                throw IOException(detail)
            }
            resp.body?.string() ?: throw IOException("筆電服務回應內容是空的")
        }
    }

    /**
     * 【2026-09-03新增，同日補上依地區分開；2026-09-04新增productLink】把AI選圖+
     * 改圖完成的圖片（最多3張）連同商品名稱／連結同步到筆電的共用資料夾，讓之後
     * 其他帳號擷取到同一個商品時可以重複利用這幾張圖，不用整套辨識/改圖流程重跑
     * 一次（省時間也省Gemini API費用）。
     *
     * 依地區（TW/PH）分開存放——台灣跟菲律賓賣的是完全不同的商品目錄，共用資料夾
     * 不該把兩邊混在一起比對。
     *
     * 【2026-09-04】productLink有值時，筆電端會優先解析出「店鋪ID_商品ID」當分類鍵
     * （已驗證這組數字跨帳號分享同一商品時一致，比商品名稱精準）；解析失敗才退回
     * 用商品名稱分類，這裡不用先判斷連結格式，交給筆電端統一處理。
     *
     * 同步失敗會丟例外，由呼叫端決定要不要提示使用者；不影響本次選圖/改圖/生成流程
     * 本身是否成功。
     */
    suspend fun uploadSharedProductImages(
        context: Context,
        account: String,
        region: String,
        productName: String,
        productLink: String = "",
        images: List<File>
    ): Unit = withContext(Dispatchers.IO) {
        if (images.isEmpty()) return@withContext
        val serverUrl = ServerPrefs.getServerUrl(context)
        if (serverUrl.isBlank()) throw IllegalStateException("尚未設定筆電生成伺服器網址")

        val bodyBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("account", account)
            .addFormDataPart("region", region)
            .addFormDataPart("product_name", productName)
            .addFormDataPart("product_link", productLink)

        images.forEachIndexed { index, file ->
            bodyBuilder.addFormDataPart(
                "image_${index + 1}", file.name,
                file.asRequestBody("image/jpeg".toMediaType())
            )
        }

        val request = Request.Builder()
            .url("$serverUrl/backup-shared-images")
            .post(bodyBuilder.build())
            .build()

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
                throw IOException(detail)
            }
        }
    }

    /**
     * 【2026-09-04新增】共用資料夾第二階段：查詢並套用其他帳號已經處理過的同一個
     * 商品圖片。用meta.json裡的promoLink向筆電查詢，筆電會解析出「店鋪ID_商品ID」
     * 精準比對（不用商品名稱模糊比對）。
     *
     * 找到就清掉folder裡舊的候選圖片（image_1/2/3等），把下載回來的共用圖片解壓
     * 進去、重新編號成image_1/2/3，回傳true；呼叫端據此判斷可以直接跳過AI選圖/
     * 改圖兩步驟。
     *
     * 沒找到（伺服器回404，代表連結解析不出商品編號、或沒有其他帳號處理過這個
     * 商品）回傳false，不當例外——這是預期內的正常情況，呼叫端應該退回原本的
     * AI選圖/改圖流程，不是錯誤。連線失敗等其他問題也回傳false（不中斷整個
     * 商品的處理，跟uploadSharedProductImages()「同步失敗不影響本次生成」是
     * 不同的錯誤處理哲學——這裡屬於「錦上添花」的加速功能，找不到/連不上都
     * 應該靜靜退回正常流程，不該讓呼叫端還要額外處理例外）。
     */
    suspend fun applySharedProductImages(
        context: Context,
        region: String,
        productLink: String,
        folder: File
    ): Boolean = withContext(Dispatchers.IO) {
        if (productLink.isBlank()) return@withContext false
        val serverUrl = ServerPrefs.getServerUrl(context)
        if (serverUrl.isBlank()) return@withContext false

        val url = "$serverUrl/check-shared-product" +
            "?region=${java.net.URLEncoder.encode(region, "UTF-8")}" +
            "&product_link=${java.net.URLEncoder.encode(productLink, "UTF-8")}"
        val request = Request.Builder().url(url).get().build()

        val zipBytes = try {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext false
                resp.body?.bytes()
            }
        } catch (e: Exception) {
            Log.w("RemoteVideoGenerator", "查詢共用商品圖片失敗：${e.javaClass.simpleName} ${e.message}")
            return@withContext false
        } ?: return@withContext false

        try {
            // 清掉folder裡舊的候選圖片，避免共用圖片跟原本擷取到的候選圖混在一起，
            // 讓make_video.py的find_images()誤判張數或抓到不該用的舊檔案。
            folder.listFiles { f -> f.isFile && f.nameWithoutExtension.startsWith("image_") }
                ?.forEach { it.delete() }

            java.util.zip.ZipInputStream(zipBytes.inputStream()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val outFile = File(folder, entry.name)
                    outFile.outputStream().use { out -> zis.copyTo(out) }
                    entry = zis.nextEntry
                }
            }
            true
        } catch (e: Exception) {
            Log.w("RemoteVideoGenerator", "套用共用商品圖片失敗：${e.javaClass.simpleName} ${e.message}")
            false
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
