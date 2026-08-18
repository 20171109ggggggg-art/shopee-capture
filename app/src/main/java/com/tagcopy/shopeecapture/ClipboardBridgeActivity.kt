package com.tagcopy.shopeecapture

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/**
 * Android 10 以上，只有「目前擁有畫面焦點」的 App 才能讀取系統剪貼簿；
 * 我們的無障礙服務在背景運作，沒有自己的畫面在最上層，所以系統會直接擋下讀取請求。
 *
 * 這個 Activity 完全透明、不會顯示任何內容，唯一目的是短暫搶下畫面焦點，
 * 讓 App 有機會讀到剪貼簿內容，讀完立刻自動關閉——整個過程使用者不會看到任何畫面跳動。
 */
class ClipboardBridgeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 延遲一小段時間，確保系統真的把焦點切給了這個 Activity 再讀取
        Handler(Looper.getMainLooper()).postDelayed({
            pendingClipboardResult = try {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = cm.primaryClip
                if (clip != null && clip.itemCount > 0) clip.getItemAt(0).text?.toString() else null
            } catch (e: Exception) {
                null
            }
            resultReady = true
            finish()
        }, 150)
    }

    override fun onPause() {
        super.onPause()
        overridePendingTransition(0, 0) // 不要有轉場動畫，避免使用者看到畫面閃一下
    }

    companion object {
        @Volatile
        var pendingClipboardResult: String? = null

        @Volatile
        var resultReady: Boolean = false
    }
}
