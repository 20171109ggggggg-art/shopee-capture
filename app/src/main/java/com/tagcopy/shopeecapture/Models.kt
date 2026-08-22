package com.tagcopy.shopeecapture

/**
 * 從蝦皮分享面板擷取到的一筆商品資料。
 */
data class CapturedProduct(
    val id: String,
    val productName: String?,
    val promoLink: String?,
    val caption: String? = null,
    val commissionPercent: Double? = null,
    val price: Double? = null,
    val soldCount: Int? = null,
    val promoterCount: Int? = null,
    val capturedAt: Long
)

/**
 * 蝦皮所在地區。不同地區 App 顯示語言不同，
 * 按鈕文字（複製連結、分享等）跟貨幣符號也不一樣，
 * 選了地區後，這些在地文字會自動加進比對規則裡（用「額外候選」的方式疊加，
 * 不會取代原本的規則，同時支援多語言介面切換的裝置）。
 */
enum class ShopeeRegion(
    val label: String,
    val currencySymbol: String,
    val extraCopyLinkTexts: List<String>,
    val extraShareDescriptors: List<String>,
    val extraShareSheetTexts: List<String>
) {
    TAIWAN("台灣", "$", emptyList(), emptyList(), emptyList()),
    PHILIPPINES(
        "菲律賓 Philippines",
        "₱",
        extraCopyLinkTexts = listOf("Kopyahin ang link", "Copy Link", "I-copy ang link"),
        extraShareDescriptors = listOf("Ibahagi", "Share", "Magbahagi"),
        extraShareSheetTexts = listOf(
            "Magbahagi para kumita ng komisyon",
            "Share to earn commission"
        )
    );

    companion object {
        fun fromLabel(label: String): ShopeeRegion = entries.firstOrNull { it.label == label } ?: TAIWAN
    }
}

/**
 * 用來比對蝦皮畫面元件的規則，從遠端 JSON 載入，
 * 這樣蝦皮改版時只要更新設定檔，不用重新編譯整個 App。
 */
data class MatchRules(
    val copyLinkButtonTexts: List<String> = listOf("複製連結", "Copy link", "Copy Link"),
    val copyInfoButtonTexts: List<String> = listOf("複製資訊", "Copy Info", "Copy info"),
    val productImageResourceIdKeywords: List<String> = listOf("image", "img", "photo", "cover"),
    val shareSheetTitleTexts: List<String> = listOf("分享以獲得分潤金", "Share to earn commission"),
    val shareButtonDescriptors: List<String> = listOf("立即推廣", "分享", "Share", "分享至", "Share to Earn"),
    val priceIndicatorPrefixes: List<String> = listOf("$", "₱", "RM", "Rp", "₫", "฿")
) {
    /** 把選定地區的在地語言候選字串疊加進來，不影響原本的規則。 */
    fun mergeWithRegion(region: ShopeeRegion): MatchRules = copy(
        copyLinkButtonTexts = (copyLinkButtonTexts + region.extraCopyLinkTexts).distinct(),
        shareButtonDescriptors = (shareButtonDescriptors + region.extraShareDescriptors).distinct(),
        shareSheetTitleTexts = (shareSheetTitleTexts + region.extraShareSheetTexts).distinct()
    )

    companion object {
        val DEFAULT = MatchRules()
    }
}

/**
 * 商品篩選條件。任一欄位留 null 代表不限制該項。
 * 篩選發生在「點開商品、還沒觸發分享」的階段，讀取畫面上的
 * 分潤率／價格／已售出／已推廣者文字比對，不符合就跳過、換下一個。
 */
data class ProductFilterConfig(
    val minCommissionPercent: Double? = null,
    val maxCommissionPercent: Double? = null,
    val minPrice: Double? = null,
    val maxPrice: Double? = null,
    val minSoldCount: Int? = null,
    val maxSoldCount: Int? = null,
    val minPromoterCount: Int? = null,
    val maxPromoterCount: Int? = null
) {
    /**
     * 只要有設限制的欄位，metrics 裡對應數值必須存在且落在範圍內才算通過；
     * 讀不到數值的欄位視為不通過（保守做法，避免篩選條件形同虛設）。
     */
    fun matches(metrics: ProductMetrics): Boolean {
        if (minCommissionPercent != null && (metrics.commissionPercent == null || metrics.commissionPercent < minCommissionPercent)) return false
        if (maxCommissionPercent != null && (metrics.commissionPercent == null || metrics.commissionPercent > maxCommissionPercent)) return false
        if (minPrice != null && (metrics.price == null || metrics.price < minPrice)) return false
        if (maxPrice != null && (metrics.price == null || metrics.price > maxPrice)) return false
        if (minSoldCount != null && (metrics.soldCount == null || metrics.soldCount < minSoldCount)) return false
        if (maxSoldCount != null && (metrics.soldCount == null || metrics.soldCount > maxSoldCount)) return false
        if (minPromoterCount != null && (metrics.promoterCount == null || metrics.promoterCount < minPromoterCount)) return false
        if (maxPromoterCount != null && (metrics.promoterCount == null || metrics.promoterCount > maxPromoterCount)) return false
        return true
    }

    fun isEmpty(): Boolean =
        minCommissionPercent == null && maxCommissionPercent == null &&
            minPrice == null && maxPrice == null &&
            minSoldCount == null && maxSoldCount == null &&
            minPromoterCount == null && maxPromoterCount == null

    /**
     * 回傳「哪個欄位、為什麼」沒通過，符合就回傳 null。
     * 用來在跳過商品時顯示具體原因（例如「分潤率讀到 null（讀不到）」或「已售出 8 < 最低 10」），
     * 取代原本只顯示「篩選未通過」看不出實際原因的做法。
     */
    fun describeMismatch(metrics: ProductMetrics): String? {
        if (minCommissionPercent != null && (metrics.commissionPercent == null || metrics.commissionPercent < minCommissionPercent))
            return "分潤率${metrics.commissionPercent?.let { "$it%" } ?: "讀不到"} < 最低 $minCommissionPercent%"
        if (maxCommissionPercent != null && (metrics.commissionPercent == null || metrics.commissionPercent > maxCommissionPercent))
            return "分潤率${metrics.commissionPercent?.let { "$it%" } ?: "讀不到"} > 最高 $maxCommissionPercent%"
        if (minPrice != null && (metrics.price == null || metrics.price < minPrice))
            return "價格${metrics.price ?: "讀不到"} < 最低 $minPrice"
        if (maxPrice != null && (metrics.price == null || metrics.price > maxPrice))
            return "價格${metrics.price ?: "讀不到"} > 最高 $maxPrice"
        if (minSoldCount != null && (metrics.soldCount == null || metrics.soldCount < minSoldCount))
            return "已售出${metrics.soldCount ?: "讀不到"} < 最低 $minSoldCount"
        if (maxSoldCount != null && (metrics.soldCount == null || metrics.soldCount > maxSoldCount))
            return "已售出${metrics.soldCount ?: "讀不到"} > 最高 $maxSoldCount"
        if (minPromoterCount != null && (metrics.promoterCount == null || metrics.promoterCount < minPromoterCount))
            return "已推廣者${metrics.promoterCount ?: "讀不到"} < 最低 $minPromoterCount"
        if (maxPromoterCount != null && (metrics.promoterCount == null || metrics.promoterCount > maxPromoterCount))
            return "已推廣者${metrics.promoterCount ?: "讀不到"} > 最高 $maxPromoterCount"
        return null
    }
}

/** 從商品畫面文字解析出來的數值。任一項讀不到就是 null。 */
data class ProductMetrics(
    val commissionPercent: Double?,
    val price: Double?,
    val soldCount: Int?,
    val promoterCount: Int?
)

/**
 * 全自動擷取的執行參數：擷取目標數量、每步驟之間的隨機延遲區間
 * （延遲隨機化只是讓操作節奏不要規律到一眼就能看出是腳本，
 * 不代表能規避平台的偵測機制，仍有帳號風險）、商品篩選條件，
 * 以及篩選時間上限（timeLimitMs 為 null 代表不限制時間，
 * 只靠次數上限或找不到更多商品時停止）。
 */
data class AutoCaptureConfig(
    val targetCount: Int = 10,
    val minDelayMs: Long = 900,
    val maxDelayMs: Long = 1800,
    val filter: ProductFilterConfig = ProductFilterConfig(),
    val timeLimitMs: Long? = null,
    val maxAttemptsLimitEnabled: Boolean = true,
    val timeLimitEnabled: Boolean = true
)

/** 自動擷取結束的原因，用來決定要不要跳出更明顯的提醒。 */
enum class FinishReason {
    TARGET_REACHED,       // 達到目標數量，正常完成
    NO_MORE_PRODUCTS,     // 列表滑到底，沒有更多商品可判斷
    TIME_LIMIT_REACHED,   // 達到時間上限，但還沒湊到目標數量 —— 需要明顯提醒
    MAX_ATTEMPTS_REACHED, // 達到最大嘗試次數上限，但還沒湊到目標數量 —— 需要明顯提醒
    STOPPED_BY_USER,      // 使用者手動按停止
    ERROR                 // 讀不到畫面等例外狀況
}

sealed class AutoCaptureEvent {
    data class Log(val message: String) : AutoCaptureEvent()
    data class Progress(val current: Int, val total: Int) : AutoCaptureEvent()
    data class Finished(
        val successCount: Int,
        val failCount: Int,
        val filteredCount: Int,
        val reason: FinishReason
    ) : AutoCaptureEvent()
}

sealed class CaptureResult {
    data class Success(val product: CapturedProduct, val savedFolder: String) : CaptureResult()
    data class Failure(val reason: String) : CaptureResult()
}

sealed class ServiceStatus {
    data object NotConnected : ServiceStatus()
    data object Ready : ServiceStatus()
    data class Capturing(val step: String) : ServiceStatus()
}

/**
 * 上架自動化（階段2第3塊）結束的原因。
 * STOPPED_ON_FAILURE：任何一筆處理失敗就整批停止，不繼續嘗試下一筆——
 * 因為失敗最常見的原因是撞到蝦皮每日上架上限，一旦撞到，後面每一筆都會用同樣方式失敗，
 * 繼續重試沒有意義還浪費時間，不如停下來讓使用者檢查狀況。
 */
enum class UploadFinishReason {
    ALL_DONE,              // 候選清單全部處理完（不含被maxCount卡住的情況）
    MAX_COUNT_REACHED,     // 達到本次呼叫設定的上限，正常停止
    NO_CANDIDATES,         // 掃描候選清單時就是空的，一筆都沒處理
    STOPPED_ON_FAILURE     // 某一筆失敗，整批停止
}

sealed class UploadEvent {
    data class Log(val message: String) : UploadEvent()
    data class Progress(val current: Int, val total: Int) : UploadEvent()
    data class Finished(
        val successCount: Int,
        val failCount: Int,
        val reason: UploadFinishReason
    ) : UploadEvent()
}
