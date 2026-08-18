package com.tagcopy.shopeecapture

import android.content.Context

object RegionPrefs {
    private const val PREFS_NAME = "region_prefs"
    private const val KEY_REGION = "region"

    fun getRegion(context: Context): ShopeeRegion {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val label = sp.getString(KEY_REGION, ShopeeRegion.TAIWAN.label) ?: ShopeeRegion.TAIWAN.label
        return ShopeeRegion.fromLabel(label)
    }

    fun setRegion(context: Context, region: ShopeeRegion) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_REGION, region.label)
            .apply()
    }
}
