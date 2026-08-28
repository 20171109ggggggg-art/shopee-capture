package com.tagcopy.shopeecapture

import android.content.Context

/**
 * 「匯入舊短影音商品名稱」功能的批次大小設定。觸發改成浮球按鈕之後，浮球上沒有輸入框可以
 * 現場輸入數字，所以這個數字要事先在App主畫面設定好，浮球按下去時直接讀取這裡存的值。
 */
object VideoImportPrefs {
    private const val PREFS_NAME = "video_import_prefs"
    private const val KEY_BATCH_SIZE = "batch_size"
    const val DEFAULT_BATCH_SIZE = 200

    fun loadBatchSize(context: Context): Int {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return sp.getInt(KEY_BATCH_SIZE, DEFAULT_BATCH_SIZE)
    }

    fun saveBatchSize(context: Context, batchSize: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putInt(KEY_BATCH_SIZE, batchSize)
            .apply()
    }
}
