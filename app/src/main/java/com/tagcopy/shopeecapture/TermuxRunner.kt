package com.tagcopy.shopeecapture

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import org.json.JSONObject
import java.io.File

/**
 * 透過Termux的RUN_COMMAND介面在背景觸發指令，使用者完全不用打開Termux App。
 *
 * 前提（一次性設定，需在使用者手機上手動做一次）：
 * 1. 已裝Termux App
 * 2. ~/.termux/termux.properties 裡要有一行 allow-external-apps=true
 *    （沒這行的話Termux會直接拒絕外部App送來的指令，這是Termux的安全機制）
 * 3. 本App要有 com.termux.permission.RUN_COMMAND 權限（已在AndroidManifest.xml宣告，
 *    但這是「一般權限」不是危險權限，安裝時系統會自動授予，不用像無障礙服務那樣手動開）
 *
 * 所有Intent action/extra字串都是直接對照Termux官方原始碼
 * （termux-shared的TermuxConstants.java）確認過的正確值，不是憑印象猜的。
 */
object TermuxRunner {
    private const val TERMUX_PACKAGE = "com.termux"
    private const val RUN_COMMAND_SERVICE_CLASS = "com.termux.app.RunCommandService"
    private const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
    private const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
    private const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
    private const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
    private const val EXTRA_RUNNER = "com.termux.RUN_COMMAND_RUNNER"
    private const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"
    private const val RUNNER_APP_SHELL = "app-shell" // 背景執行、不開Termux介面

    private const val BASH_PATH = "/data/data/com.termux/files/usr/bin/bash"

    private const val PREFS_NAME = "termux_runner_prefs"
    private const val KEY_LAST_RESULT = "last_result_json"

    data class TermuxResult(
        val exitCode: Int?,
        val stdout: String,
        val stderr: String,
        val internalError: String?
    ) {
        val succeeded: Boolean get() = internalError == null && exitCode == 0
    }

    fun isTermuxInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 觸發Termux在背景執行一段bash指令（不會跳出Termux介面）。
     * command：完整的bash指令字串，例如：
     *   "cd ~/shopee-capture && python batch_generate.py ~/storage/downloads/CaptionQueue"
     * 執行結果會透過TermuxResultReceiver廣播接收後寫進SharedPreferences，
     * 呼叫端不用自己管理BroadcastReceiver生命週期（背景執行可能跑很久，Activity/Service
     * 有可能中途被系統回收，用靜態註冊的BroadcastReceiver+SharedPreferences比較穩）。
     * 用getLastResult()輪詢或在下次進App時讀取結果。
     *
     * 回傳true代表Intent已成功送出給Termux（不代表指令執行成功，那要看之後的結果）；
     * 回傳false代表送出當下就失敗了（例如Termux沒安裝、或RUN_COMMAND權限被拒絕），
     * 這種情況呼叫端要立刻用其他方式告知使用者，不會有非同步結果進來。
     */
    fun runCommand(context: Context, command: String): Boolean {
        if (!isTermuxInstalled(context)) return false

        val resultIntent = Intent(context, TermuxResultReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            System.currentTimeMillis().toInt(),
            resultIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_MUTABLE else 0)
        )

        val runIntent = Intent(ACTION_RUN_COMMAND).apply {
            setClassName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE_CLASS)
            putExtra(EXTRA_COMMAND_PATH, BASH_PATH)
            putExtra(EXTRA_ARGUMENTS, arrayOf("-c", command))
            putExtra(EXTRA_WORKDIR, "/data/data/com.termux/files/home")
            putExtra(EXTRA_RUNNER, RUNNER_APP_SHELL)
            putExtra(EXTRA_PENDING_INTENT, pendingIntent)
        }

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(runIntent)
            } else {
                context.startService(runIntent)
            }
            markRunning(context)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun markRunning(context: Context) {
        prefs(context).edit().remove(KEY_LAST_RESULT).apply()
    }

    /** TermuxResultReceiver收到結果時呼叫這個，把結果存起來給App畫面讀取 */
    internal fun saveResult(context: Context, result: TermuxResult) {
        val json = JSONObject().apply {
            put("exitCode", result.exitCode ?: JSONObject.NULL)
            put("stdout", result.stdout)
            put("stderr", result.stderr)
            put("internalError", result.internalError ?: JSONObject.NULL)
        }
        prefs(context).edit().putString(KEY_LAST_RESULT, json.toString()).apply()
    }

    /**
     * 讀取最近一次Termux指令的執行結果。還在跑（沒收到結果廣播）時回傳null，
     * 呼叫端應改讀進度檔案（見readBatchProgress）顯示即時進度，而不是死等這個。
     */
    fun getLastResult(context: Context): TermuxResult? {
        val raw = prefs(context).getString(KEY_LAST_RESULT, null) ?: return null
        return try {
            val json = JSONObject(raw)
            TermuxResult(
                exitCode = if (json.isNull("exitCode")) null else json.getInt("exitCode"),
                stdout = json.optString("stdout", ""),
                stderr = json.optString("stderr", ""),
                internalError = if (json.isNull("internalError")) null else json.getString("internalError")
            )
        } catch (e: Exception) {
            null
        }
    }

    data class BatchProgress(
        val total: Int,
        val completed: Int,
        val current: String,
        val status: String, // "running" 或 "done"
        val okCount: Int,
        val skippedCount: Int,
        val errorCount: Int
    )

    /**
     * 讀取batch_generate.py即時寫入的進度檔案（<CaptionQueue根目錄>/.progress.json）。
     * 用來在App畫面顯示「第幾支/共幾支」，不用等整批跑完、也不用解析Termux回傳的完整stdout。
     * 檔案不存在或格式錯誤時回傳null（例如還沒開始跑，或跑的是舊版沒有寫進度檔案的腳本）。
     */
    fun readBatchProgress(captionQueueDir: File): BatchProgress? {
        val progressFile = File(captionQueueDir, ".progress.json")
        if (!progressFile.exists()) return null
        return try {
            val json = JSONObject(progressFile.readText())
            BatchProgress(
                total = json.optInt("total", 0),
                completed = json.optInt("completed", 0),
                current = json.optString("current", ""),
                status = json.optString("status", "running"),
                okCount = json.optInt("okCount", 0),
                skippedCount = json.optInt("skippedCount", 0),
                errorCount = json.optInt("errorCount", 0)
            )
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * 靜態註冊的BroadcastReceiver，接收Termux執行完指令後送回的結果。
 * 用靜態註冊（在AndroidManifest.xml宣告）而不是在Activity裡動態註冊，
 * 是因為batch_generate.py可能跑好幾分鐘，App畫面有可能被系統回收，
 * 動態註冊的receiver會跟著消失、接不到結果；靜態註冊不受這個影響。
 */
class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val resultBundle = intent.getBundleExtra("result")
        val result = if (resultBundle != null) {
            TermuxRunner.TermuxResult(
                exitCode = if (resultBundle.containsKey("exitCode")) resultBundle.getInt("exitCode") else null,
                stdout = resultBundle.getString("stdout") ?: "",
                stderr = resultBundle.getString("stderr") ?: "",
                internalError = resultBundle.getString("errmsg")
            )
        } else {
            TermuxRunner.TermuxResult(
                exitCode = null,
                stdout = "",
                stderr = "",
                internalError = "收不到Termux回傳的結果（result bundle是null），請確認Termux版本夠新、且termux.properties有設定allow-external-apps=true"
            )
        }
        TermuxRunner.saveResult(context, result)
    }
}
