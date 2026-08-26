package com.tagcopy.shopeecapture

import android.content.Context

/**
 * 上架自動化的設定儲存：目前只有一個「本次最多處理支數」，跟AutoCapturePrefs（擷取設定）
 * 分開獨立存放，避免混在一起、也方便之後各自擴充。
 */
object UploadAutomationPrefs {
    private const val PREFS_NAME = "upload_automation_prefs"
    private const val KEY_TARGET_COUNT = "upload_target_count"

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
}
