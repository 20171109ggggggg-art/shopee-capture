package com.tagcopy.shopeecapture

import android.content.Context
import org.json.JSONArray

/**
 * 蝦皮帳號設定。
 *
 * 一支手機可能會切換使用多個蝦皮帳號擷取商品，帳號因此不能是手機層級的固定值，
 * 而是「目前使用中」的帳號名稱——使用者在設定畫面手動輸入/切換，之後每次擷取
 * 商品寫入 meta.json 時，把當下這個值一併寫進去，讓後續影片生成／電腦端備份
 * 可以依照每個商品資料夾自己的account欄位分類，不是依手機分類。
 * 支援中文帳號名稱。設計比照 RegionPrefs.kt 同樣的 SharedPreferences 寫法。
 *
 * 【2026-09-02新增】額外記錄「曾經用過的帳號」歷史清單（最近使用排最前面），
 * 讓設定畫面能把這些帳號顯示成可點選的按鈕，切換帳號不用每次重新手打名稱。
 */
object AccountPrefs {
    private const val PREFS_NAME = "account_prefs"
    private const val KEY_ACCOUNT = "account"
    private const val KEY_HISTORY = "account_history"
    private const val MAX_HISTORY = 10
    const val DEFAULT_ACCOUNT = "未分類帳號"

    fun getAccount(context: Context): String {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val value = sp.getString(KEY_ACCOUNT, DEFAULT_ACCOUNT) ?: DEFAULT_ACCOUNT
        return value.ifBlank { DEFAULT_ACCOUNT }
    }

    /** 【2026-09-03新增】使用者是不是「真的手動存過帳號」，跟getAccount()不一樣——
     * getAccount()一律回傳一個可用的值（沒設定過就回傳DEFAULT_ACCOUNT，供擷取/備份
     * 等功能性用途使用，不能是空字串）。這個函式純粹給UI判斷「輸入框該不該顯示
     * 預設文字」用：全新安裝、從來沒按過「儲存帳號」時，輸入框應該顯示空白，
     * 讓使用者能直接輸入，不用先手動刪掉「未分類帳號」這幾個字。 */
    fun isSet(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).contains(KEY_ACCOUNT)
    }

    /** 設定目前使用帳號，同時把這個名稱加進歷史清單最前面（如果已經在清單裡就
     * 先移除舊位置再插到最前面，避免同一個帳號在清單裡重複出現）。歷史清單只留
     * 最近MAX_HISTORY個，超過的自動從尾端捨棄。 */
    fun setAccount(context: Context, account: String) {
        val trimmed = account.trim().ifBlank { DEFAULT_ACCOUNT }
        val history = getAccountHistory(context).toMutableList()
        history.remove(trimmed)
        history.add(0, trimmed)
        while (history.size > MAX_HISTORY) history.removeAt(history.lastIndex)

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_ACCOUNT, trimmed)
            .putString(KEY_HISTORY, JSONArray(history).toString())
            .apply()
    }

    /** 依最近使用順序（最近在前）回傳曾經用過的帳號名稱清單，供設定畫面顯示成
     * 可點選的歷史帳號按鈕。用JSON字串存（不是SharedPreferences的StringSet），
     * 因為StringSet不保證順序，這裡需要保留「最近用過」的排序資訊。 */
    fun getAccountHistory(context: Context): List<String> {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = sp.getString(KEY_HISTORY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
