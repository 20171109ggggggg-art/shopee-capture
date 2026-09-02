package com.tagcopy.shopeecapture

import android.content.Context

/**
 * 蝦皮帳號設定。
 *
 * 一支手機可能會切換使用多個蝦皮帳號擷取商品，帳號因此不能是手機層級的固定值，
 * 而是「目前使用中」的帳號名稱——使用者在設定畫面手動輸入/切換，之後每次擷取
 * 商品寫入 meta.json 時，把當下這個值一併寫進去，讓後續影片生成／電腦端備份
 * 可以依照每個商品資料夾自己的account欄位分類，不是依手機分類。
 * 支援中文帳號名稱。設計比照 RegionPrefs.kt 同樣的 SharedPreferences 寫法。
 */
object AccountPrefs {
    private const val PREFS_NAME = "account_prefs"
    private const val KEY_ACCOUNT = "account"
    const val DEFAULT_ACCOUNT = "未分類帳號"

    fun getAccount(context: Context): String {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val value = sp.getString(KEY_ACCOUNT, DEFAULT_ACCOUNT) ?: DEFAULT_ACCOUNT
        return value.ifBlank { DEFAULT_ACCOUNT }
    }

    fun setAccount(context: Context, account: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_ACCOUNT, account.trim().ifBlank { DEFAULT_ACCOUNT })
            .apply()
    }
}
