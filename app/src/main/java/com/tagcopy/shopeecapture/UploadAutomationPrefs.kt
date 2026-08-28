package com.tagcopy.shopeecapture

import android.content.Context

/**
 * 上架自動化的設定儲存：本次最多處理支數、FB上架功能總開關。
 * 跟AutoCapturePrefs（擷取設定）分開獨立存放，避免混在一起、也方便之後各自擴充。
 */
object UploadAutomationPrefs {
    private const val PREFS_NAME = "upload_automation_prefs"
    private const val KEY_TARGET_COUNT = "upload_target_count"
    // 【2026-08-28新增】FB上架功能總開關：關閉時，蝦皮批次跑完不會自動啟動FB App接續上架，
    // 懸浮視窗的「FB上架」按鈕按下去也會直接提示已關閉、不執行任何動作。
    // 預設true（開啟），因為這是使用者已經在用的功能，關掉是例外狀況才需要做的選擇。
    private const val KEY_FB_UPLOAD_ENABLED = "fb_upload_enabled"

    fun getTargetCount(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_TARGET_COUNT, 50)
    }

    fun setTargetCount(context: Context, count: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_TARGET_COUNT, count)
            .apply()
    }

    fun isFbUploadEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_FB_UPLOAD_ENABLED, true)
    }

    fun setFbUploadEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_FB_UPLOAD_ENABLED, enabled)
            .apply()
    }
}
