package com.tagcopy.shopeecapture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.random.Random

/**
 * 核心無障礙服務。
 *
 * 設計為「半自動」：不會自己在蝦皮 App 裡到處亂逛或亂點，
 * 只有使用者主動呼叫 [captureCurrentScreen] 時才會動作 —— 對應到懸浮按鈕被按下的那一刻。
 * 動作範圍限定在「當下畫面看到的分享面板」，比對按鈕文字找「複製連結」並點擊、
 * 讀取商品名稱節點、截圖商品圖片區域。
 */
class ShopeeAccessibilityService : AccessibilityService() {

    companion object {
        var instance: ShopeeAccessibilityService? = null
            private set
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var baseMatchRules: MatchRules = MatchRules.DEFAULT
    private val matchRules: MatchRules
        get() = baseMatchRules.mergeWithRegion(RegionPrefs.getRegion(this))
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var autoJob: Job? = null
    private var currentDebugLogFileName: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        CoroutineScope(Dispatchers.IO).launch {
            baseMatchRules = RemoteConfigLoader.load()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        serviceScope.cancel()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 目前不需要即時監聽事件做任何動作；擷取動作由使用者主動觸發（見 captureCurrentScreen）。
    }

    override fun onInterrupt() {}

    // ===================== 全自動擷取 =====================

    fun isAutoCaptureRunning(): Boolean = autoJob?.isActive == true

    fun startAutoCapture(config: AutoCaptureConfig, onEvent: (AutoCaptureEvent) -> Unit) {
        if (isAutoCaptureRunning()) return
        autoJob = serviceScope.launch {
            try {
                autoCaptureLoop(config, onEvent)
            } catch (e: kotlinx.coroutines.CancellationException) {
                onEvent(AutoCaptureEvent.Log("已停止自動擷取"))
            } catch (e: Exception) {
                onEvent(AutoCaptureEvent.Log("發生錯誤：${e.message}"))
            }
        }
    }

    fun stopAutoCapture() {
        autoJob?.cancel()
        autoJob = null
    }

    private suspend fun autoCaptureLoop(config: AutoCaptureConfig, onEvent: (AutoCaptureEvent) -> Unit) {
        val processedKeys = mutableSetOf<String>()
        var successCount = 0
        var failCount = 0
        var filteredCount = 0
        var emptyScrollAttempts = 0
        val maxEmptyScrollAttempts = 3
        var totalAttempts = 0
        val maxAttempts = if (config.maxAttemptsLimitEnabled) {
            (config.targetCount * 6).coerceAtLeast(30)
        } else {
            Int.MAX_VALUE
        }
        val startTime = System.currentTimeMillis()

        navigationLostFlag = false
        lastKnownSearchQuery = null
        currentDebugLogFileName = "debug_log_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.txt"
        appendDebugLog("===== 開始自動擷取，目標 ${config.targetCount} 件，篩選條件：${if (config.filter.isEmpty()) "無" else "有"} =====")
        run {
            val dedupPrefs = getDedupPrefs()
            val nameCount = (dedupPrefs.getStringSet("captured_names", emptySet()) ?: emptySet()).size
            val linkCount = (dedupPrefs.getStringSet("captured_links", emptySet()) ?: emptySet()).size
            appendDebugLog("  → 目前防重複記錄庫累積：商品名稱 $nameCount 筆、連結 $linkCount 筆")
        }
        onEvent(AutoCaptureEvent.Log("開始自動擷取，目標 ${config.targetCount} 件商品"))
        if (!config.filter.isEmpty()) {
            onEvent(AutoCaptureEvent.Log("已套用篩選條件，不符合的商品會自動跳過"))
        }
        if (config.timeLimitEnabled && config.timeLimitMs != null) {
            onEvent(AutoCaptureEvent.Log("篩選時間上限：${config.timeLimitMs / 60000} 分鐘"))
        }
        if (!config.maxAttemptsLimitEnabled) {
            onEvent(AutoCaptureEvent.Log("已關閉最大嘗試次數限制"))
        }

        var reason = FinishReason.TARGET_REACHED

        while (successCount < config.targetCount && totalAttempts < maxAttempts) {
            if (config.timeLimitEnabled && config.timeLimitMs != null &&
                System.currentTimeMillis() - startTime >= config.timeLimitMs
            ) {
                reason = FinishReason.TIME_LIMIT_REACHED
                break
            }

            val root = rootInActiveWindow
            if (root == null) {
                onEvent(AutoCaptureEvent.Log("讀不到目前畫面，停止"))
                reason = FinishReason.ERROR
                break
            }

            // 記錄目前的搜尋關鍵字，萬一之後返回鍵跳過頭跑到首頁，可以用同樣關鍵字重新搜尋復原
            findSearchBoxText(root)?.let { if (it.isNotBlank()) lastKnownSearchQuery = it }

            val cards = findProductCards(root)
            val nextCard = cards.firstOrNull { cardKey(it) !in processedKeys }

            if (nextCard == null) {
                emptyScrollAttempts++
                if (emptyScrollAttempts > maxEmptyScrollAttempts) {
                    onEvent(AutoCaptureEvent.Log("已無更多商品可擷取，結束"))
                    reason = FinishReason.NO_MORE_PRODUCTS
                    break
                }
                onEvent(AutoCaptureEvent.Log("往下滑動尋找更多商品…"))
                performScrollDown()
                delay(randomDelay(config))
                continue
            }
            emptyScrollAttempts = 0
            processedKeys.add(cardKey(nextCard))
            totalAttempts++

            when (processOneProduct(nextCard, config, onEvent)) {
                ProcessResult.SUCCESS -> successCount++
                ProcessResult.FILTERED -> filteredCount++
                ProcessResult.FAILED -> failCount++
            }
            onEvent(AutoCaptureEvent.Progress(successCount, config.targetCount))
            if (navigationLostFlag) {
                onEvent(AutoCaptureEvent.Log("因返回鍵導航異常，自動擷取安全停止，請手動回到商品列表後再重新啟動"))
                appendDebugLog("===== 因返回鍵導航異常（跑到蝦皮首頁）安全停止 =====")
                reason = FinishReason.ERROR
                break
            }
            delay(randomDelay(config))
        }

        if (successCount >= config.targetCount) {
            reason = FinishReason.TARGET_REACHED
        } else if (totalAttempts >= maxAttempts && reason == FinishReason.TARGET_REACHED) {
            reason = FinishReason.MAX_ATTEMPTS_REACHED
        }

        // 整個流程結束前最後確認一次：畫面應該停在搜尋結果列表，而不是卡在商品詳情頁／分享面板。
        // 如果不是列表畫面，用剛才記下的搜尋關鍵字自動搜一次，確保結束時畫面是乾淨的列表狀態。
        val finalRoot = rootInActiveWindow
        if (finalRoot != null && findProductCards(finalRoot).isEmpty() && !lastKnownSearchQuery.isNullOrBlank()) {
            appendDebugLog("  → 結束前檢查：目前畫面看不到商品列表，嘗試自動搜尋回到列表")
            val recovered = tryRecoverToSearchResults()
            appendDebugLog("  → 結束前恢復結果：${if (recovered) "成功回到列表" else "失敗，維持原畫面"}")
        }

        when (reason) {
            FinishReason.TIME_LIMIT_REACHED ->
                onEvent(AutoCaptureEvent.Log("已達篩選時間上限，僅擷取到 $successCount／${config.targetCount} 件符合條件的商品，已停止"))
            FinishReason.MAX_ATTEMPTS_REACHED ->
                onEvent(AutoCaptureEvent.Log("已達最大嘗試次數（多數商品不符篩選條件），提前結束"))
            else -> {}
        }

        appendDebugLog("===== 結束：成功 $successCount／篩選跳過 $filteredCount／失敗 $failCount，原因=$reason =====")
        onEvent(AutoCaptureEvent.Finished(successCount, failCount, filteredCount, reason))
        autoJob = null
    }

    private enum class ProcessResult { SUCCESS, FILTERED, FAILED }
    private var navigationLostFlag = false
    private var lastKnownSearchQuery: String? = null

    private suspend fun processOneProduct(
        card: AccessibilityNodeInfo,
        config: AutoCaptureConfig,
        onEvent: (AutoCaptureEvent) -> Unit
    ): ProcessResult {
        if (!card.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            onEvent(AutoCaptureEvent.Log("點擊商品卡片失敗，跳過"))
            appendDebugLog("結果=失敗（點擊商品卡片失敗）")
            return ProcessResult.FAILED
        }

        // 等待商品詳情頁真正載入完成（而不是固定延遲後就讀取），避免抓到還沒渲染完的殘缺畫面
        val detailRoot = waitForDetailPageLoaded(3500)
        if (detailRoot == null) {
            appendDebugLog("結果=失敗（商品詳情頁載入逾時）")
            performBack()
            return ProcessResult.FAILED
        }

        val productName = findLikelyProductNameText(detailRoot)

        // 早期判斷：這個商品名稱如果先前任何一次執行就擷取過，直接跳過，省下後面截圖、讀剪貼簿的時間
        if (isProductNameAlreadyCaptured(productName)) {
            appendDebugLog("商品：$productName | 結果=跳過（重複商品，先前已擷取過）")
            onEvent(AutoCaptureEvent.Log("○ 已擷取過此商品，略過：$productName"))
            performBack()
            delay(randomDelay(config))
            return ProcessResult.FILTERED
        }

        // 無論有沒有設篩選條件，都讀取一次四個參數的狀態並寫進除錯 log，方便排查「明明符合卻沒被擷取」這類問題
        var metrics = extractProductMetrics(detailRoot)
        if (!config.filter.isEmpty() && !hasRequiredFields(metrics, config.filter)) {
            // 分享按鈕出現不代表所有數字都渲染完成了（例如「已推廣者數量」有時會晚一點才出來），
            // 這裡針對「你有設限制、但目前讀不到」的欄位再多等一下，避免因為讀取太早而誤判成不符合。
            metrics = waitForRequiredMetrics(config.filter, 2000) ?: metrics
        }
        // 有些商品的數字（尤其分潤率）會先顯示一個過渡值、稍後才變成正確值。
        // 這裡再等一下重讀一次做確認，如果兩次讀到的不一樣，代表數字還在變動，採用比較晚讀到的那次。
        // 寧可慢一點也要正確，所以等待時間拉長到 2 秒。
        run {
            val currentRoot = rootInActiveWindow
            if (currentRoot != null) {
                delay(2000)
                val recheck = extractProductMetrics(currentRoot)
                if (recheck != metrics) {
                    appendDebugLog("  → 數值確認：分潤率/價格/已售出/已推廣者第一次讀到的跟 2 秒後不一致，改用較晚讀到的結果（原=$metrics，改=$recheck）")
                    metrics = recheck
                }
            }
        }
        logMetricsDebug(productName, metrics)

        // 篩選檢查：不符合就直接返回上一層跳過
        if (!config.filter.isEmpty()) {
            if (!config.filter.matches(metrics)) {
                val reason = config.filter.describeMismatch(metrics) ?: "未知原因"
                onEvent(AutoCaptureEvent.Log("○ 篩選未通過（$reason），略過：${productName ?: "未知商品"}"))
                appendDebugLog("商品：${productName ?: "未知"} | 結果=篩選跳過（$reason）")
                performBack()
                delay(randomDelay(config))
                return ProcessResult.FILTERED
            }
        }

        // 篩選通過後，趁還在商品詳情頁時先把圖片輪播全部滑過一輪存下來（給後續生成影片用）
        val galleryImages = captureGalleryImages(detailRoot)
        appendDebugLog("商品：${productName ?: "未知"} | 已擷取商品圖片 ${galleryImages.size} 張")

        val shareNode = findNodeByDescriptors(detailRoot, matchRules.shareButtonDescriptors)
        if (shareNode == null) {
            onEvent(AutoCaptureEvent.Log("找不到分享按鈕，跳過此商品"))
            appendDebugLog("商品：${productName ?: "未知"} | 結果=失敗（找不到分享按鈕）")
            appendDebugLog("  → 目前規則比對的候選字串：${matchRules.shareButtonDescriptors}")
            dumpClickableNodesToLog(detailRoot)
            performBack()
            delay(randomDelay(config))
            return ProcessResult.FAILED
        }

        shareNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        delay(randomDelay(config))

        val sheetAppeared = waitForAnyText(matchRules.shareSheetTitleTexts, 2500)
        if (!sheetAppeared) {
            onEvent(AutoCaptureEvent.Log("分享面板未出現，跳過此商品"))
            appendDebugLog("商品：${productName ?: "未知"} | 結果=失敗（分享面板未出現）")
            performBack()
            delay(randomDelay(config))
            return ProcessResult.FAILED
        }

        val sheetRoot = rootInActiveWindow
        val copyLinkNode = sheetRoot?.let { findNodeByTexts(it, matchRules.copyLinkButtonTexts) }
        if (copyLinkNode == null) {
            onEvent(AutoCaptureEvent.Log("找不到「複製連結」按鈕，跳過此商品"))
            appendDebugLog("商品：${productName ?: "未知"} | 結果=失敗（找不到複製連結按鈕）")
            performBack()
            delay(randomDelay(config))
            performBack()
            delay(randomDelay(config))
            return ProcessResult.FAILED
        }

        copyLinkNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val link = readClipboardWithRetry()
        appendDebugLog("  → 複製連結：${if (link.isNullOrBlank()) "點擊成功但剪貼簿讀不到內容" else "成功，長度 ${link.length} 字"}")

        // 「複製連結」跟「複製資訊」都是寫進同一個剪貼簿，要分開點、分開讀，
        // 不然後點的會把先點的內容蓋掉。這裡先讀完連結，再點複製資訊、讀取文案。
        var caption: String? = null
        val copyInfoNode = rootInActiveWindow?.let { findNodeByTexts(it, matchRules.copyInfoButtonTexts) }
        if (copyInfoNode != null) {
            copyInfoNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            delay(300) // 給點擊一點反應時間，避免立刻讀到「複製連結」殘留的舊值
            caption = readClipboardWithRetry()
            if (!caption.isNullOrBlank() && caption == link) {
                // 讀到的內容跟連結一模一樣：代表剪貼簿根本還沒被「複製資訊」寫入新內容，是殘留的舊值，不是真正的文案。
                appendDebugLog("  → 複製資訊：讀到的內容跟連結完全相同，判定為剪貼簿還沒更新的舊值，重試一次")
                copyInfoNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                delay(600)
                val retryCaption = readClipboardWithRetry()
                caption = if (!retryCaption.isNullOrBlank() && retryCaption != link) retryCaption else null
            }
            appendDebugLog("  → 複製資訊：${if (caption.isNullOrBlank()) "讀不到有效文案內容（可能跟連結重複或剪貼簿讀取失敗）" else "成功，長度 ${caption!!.length} 字"}")
        } else {
            appendDebugLog("商品：${productName ?: "未知"} | 找不到「複製資訊」按鈕，文案留空")
            appendDebugLog("  → 候選字串：${matchRules.copyInfoButtonTexts}")
            rootInActiveWindow?.let { dumpClickableNodesToLog(it) }
        }

        val bitmap = if (galleryImages.isEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            withTimeoutOrNull(4000) { captureScreenshotSuspend() }
        } else null

        // 關閉分享面板並回到搜尋結果列表。
        // 使用者實測確認：這個分享面板跟商品詳情頁是合併成同一層的，不管面板還在不在畫面上，
        // 只要按「一次」返回鍵就會直接回到搜尋結果頁，不需要也不能按兩次（按兩次會多跳一層）。
        performBack()
        delay(randomDelay(config))
        appendDebugLog("  → 返回後目前畫面套件名稱：${getCurrentPackageName() ?: "讀不到"}")

        // 有些情況下（疑似 App 內部混合式頁面架構）一次「返回」會跳過不只一層，
        // 導致跳出商品列表、跑到蝦皮首頁。這裡偵測到跑錯畫面時，先嘗試用剛才記下的搜尋關鍵字
        // 自動重新搜尋、恢復到商品列表繼續跑；真的恢復不了才安全停止（該存的這次擷取還是照存）。
        val landedRoot = rootInActiveWindow
        if (landedRoot != null && looksLikeShopeeHomeScreen(landedRoot)) {
            appendDebugLog("  → ⚠ 偵測到目前畫面疑似跑到蝦皮首頁而非商品列表，關鍵字=${lastKnownSearchQuery ?: "無記錄"}，嘗試自動恢復")
            onEvent(AutoCaptureEvent.Log("⚠ 返回後畫面異常（疑似跳到首頁），嘗試自動恢復搜尋…"))
            val recovered = tryRecoverToSearchResults()
            appendDebugLog("  → 自動恢復結果：${if (recovered) "成功，恢復到商品列表繼續" else "失敗，將安全停止"}")
            if (recovered) {
                onEvent(AutoCaptureEvent.Log("✓ 已自動恢復到商品列表，繼續擷取"))
            } else {
                onEvent(AutoCaptureEvent.Log("自動恢復失敗，這次擷取仍會保留，但流程即將停止"))
                navigationLostFlag = true
            }
        }

        // 最終確認：用連結比對（比商品名稱準確），避免早期名稱判斷漏掉的重複商品被存下來
        if (isLinkAlreadyCaptured(link)) {
            appendDebugLog("商品：${productName ?: "未知"} | 結果=跳過（連結重複，先前已擷取過：$link）")
            onEvent(AutoCaptureEvent.Log("○ 連結重複，先前已擷取過，不重複存檔：${productName ?: "未知商品"}"))
            return ProcessResult.FILTERED
        }

        return when (val result = saveResult(productName, link, caption, galleryImages.ifEmpty { listOfNotNull(bitmap) }, metrics)) {
            is CaptureResult.Success -> {
                markAsCaptured(productName, link)
                onEvent(AutoCaptureEvent.Log("✓ 已擷取：${productName ?: "未知商品"}"))
                appendDebugLog("商品：${productName ?: "未知"} | 結果=成功 | 連結=${link ?: "null（沒讀到）"} | 文案=${if (caption.isNullOrBlank()) "null（沒讀到）" else "已讀到"}")
                ProcessResult.SUCCESS
            }
            is CaptureResult.Failure -> {
                onEvent(AutoCaptureEvent.Log("存檔失敗：${result.reason}"))
                appendDebugLog("商品：${productName ?: "未知"} | 結果=失敗（存檔失敗：${result.reason}）")
                ProcessResult.FAILED
            }
        }
    }

    /**
     * 從商品頁面文字中解析分潤率、價格、已售出、已推廣者數值。
     * 讀不到的欄位回傳 null（篩選時會視為不通過，而不是放行）。
     *
     * 支援兩種數字格式：
     * - 完整數字：「已售出 1,234」
     * - K/M 縮寫：「10K+ sold」「11.5K+ Affiliates Promoted」（菲律賓版常見格式）
     */
    private fun extractProductMetrics(root: AccessibilityNodeInfo): ProductMetrics {
        val texts = mutableListOf<String>()
        collectTextNodes(root, texts, maxDepth = 20)

        // 分潤率：中文「分潤」或英文「Comm Rate」「COMMSXTRA」等變體
        val commissionRegex = Regex("(?:分潤|Comm\\s*Rate|COMMS?\\s*XTRA)[^\\d%]*([\\d]+\\.?[\\d]*)\\s*%", RegexOption.IGNORE_CASE)

        // 已售出：中文「已售出 1,234」或英文「10K+ sold」「10K+ Sold」
        val soldRegex = Regex("已售出\\s*([\\d,]+)\\+?")
        val soldAbbrevRegex = Regex("([\\d.]+)\\s*([KM])?\\+?\\s*[Ss]old")

        // 已推廣者：中文「64 位推廣者」或英文「11.5K+ Affiliates Promoted」
        val promoterRegex = Regex("([\\d,]+)\\s*位推廣者")
        val promoterAbbrevRegex = Regex("([\\d.]+)\\s*([KM])?\\+?\\s*Affiliates?\\s*Promoted", RegexOption.IGNORE_CASE)

        // 價格：$ ₱ ฿ ₫ 等貨幣符號開頭的數字（範圍價格如 ₱17.00-₱66.00 只取第一個數字當代表值）
        val priceRegex = Regex("[\$₱฿₫]\\s*([\\d,]+\\.?[\\d]*)")

        var commission: Double? = null
        var sold: Int? = null
        var promoter: Int? = null
        var price: Double? = null

        for (text in texts) {
            if (isCouponBannerText(text)) continue // 優惠券橫幅文字（例如「低消 $49」）不是商品資訊，整段跳過避免誤判
            if (commission == null) {
                commissionRegex.find(text)?.let { commission = it.groupValues[1].toDoubleOrNull() }
            }
            if (sold == null) {
                soldRegex.find(text)?.let { sold = it.groupValues[1].replace(",", "").toIntOrNull() }
                if (sold == null) {
                    soldAbbrevRegex.find(text)?.let { sold = parseAbbreviatedNumber(it.groupValues[1], it.groupValues[2]) }
                }
            }
            if (promoter == null) {
                promoterRegex.find(text)?.let { promoter = it.groupValues[1].replace(",", "").toIntOrNull() }
                if (promoter == null) {
                    promoterAbbrevRegex.find(text)?.let { promoter = parseAbbreviatedNumber(it.groupValues[1], it.groupValues[2]) }
                }
            }
            if (price == null) {
                priceRegex.find(text)?.let { price = it.groupValues[1].replace(",", "").toDoubleOrNull() }
            }
        }

        // 有些畫面把符號跟數字拆成不同文字元件（例如「$」跟「362.00」是兩個獨立節點、
        // 「56」跟「位推廣者已推廣」也是兩個獨立節點），逐一比對單一節點時兩邊各自都比對不到。
        // 這裡針對「還是讀不到」的欄位，把整頁文字接在一起再比對一次做為補救，
        // 已經讀到的欄位不受影響，避免因為合併文字而誤配對到別的商品區塊。
        if (commission == null || sold == null || promoter == null || price == null) {
            val combined = texts.filterNot { isCouponBannerText(it) }.joinToString(" ")
            if (commission == null) {
                commissionRegex.find(combined)?.let { commission = it.groupValues[1].toDoubleOrNull() }
            }
            if (sold == null) {
                soldRegex.find(combined)?.let { sold = it.groupValues[1].replace(",", "").toIntOrNull() }
                if (sold == null) {
                    soldAbbrevRegex.find(combined)?.let { sold = parseAbbreviatedNumber(it.groupValues[1], it.groupValues[2]) }
                }
            }
            if (promoter == null) {
                promoterRegex.find(combined)?.let { promoter = it.groupValues[1].replace(",", "").toIntOrNull() }
                if (promoter == null) {
                    promoterAbbrevRegex.find(combined)?.let { promoter = parseAbbreviatedNumber(it.groupValues[1], it.groupValues[2]) }
                }
            }
            if (price == null) {
                priceRegex.find(combined)?.let { price = it.groupValues[1].replace(",", "").toDoubleOrNull() }
            }
        }

        return ProductMetrics(commission, price, sold, promoter)
    }

    /** 檢查「有設篩選條件的欄位」是否都已經讀到值（null 代表那個欄位還沒渲染出來，不是真的沒有）。 */
    /**
     * 把「這個商品讀到的四個參數」寫進除錯 log 檔案（Download/ShopeeCaptureDebugLog/debug_log.txt），
     * 不管有沒有設篩選條件都會記錄，這樣事後可以直接把整個檔案傳出來看每個商品當時讀到什麼、
     * 哪個欄位是 null（代表沒讀到），排查「明明符合卻沒被擷取」比對截圖準確很多。
     */
    private fun logMetricsDebug(productName: String?, metrics: ProductMetrics) {
        val line = "商品：${productName ?: "未知"} | 分潤率=${metrics.commissionPercent?.toString() ?: "null（沒讀到）"} | " +
            "價格=${metrics.price?.toString() ?: "null（沒讀到）"} | " +
            "已售出=${metrics.soldCount?.toString() ?: "null（沒讀到）"} | " +
            "已推廣者=${metrics.promoterCount?.toString() ?: "null（沒讀到）"}"
        appendDebugLog(line)
    }

    /**
     * 找不到分享按鈕時，把畫面上「所有可點擊元件」的文字／描述／resource-id 都記錄下來，
     * 這樣不用再靠猜測，直接從 log 裡看蝦皮這個按鈕實際叫什麼名字，之後就能把正確字串加進比對規則。
     */
    private fun dumpClickableNodesToLog(root: AccessibilityNodeInfo) {
        val lines = mutableListOf<String>()
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 25 || lines.size > 40) return
            if (node.isClickable) {
                val text = node.text?.toString()?.trim()
                val desc = node.contentDescription?.toString()?.trim()
                val id = node.viewIdResourceName
                if (!text.isNullOrEmpty() || !desc.isNullOrEmpty()) {
                    lines.add("text=${text ?: "-"} | desc=${desc ?: "-"} | id=${id ?: "-"}")
                }
            }
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }
        walk(root, 0)
        appendDebugLog("  → 畫面上可點擊元件清單（前 ${lines.size} 個）：")
        lines.forEach { appendDebugLog("     $it") }
    }

    private fun appendDebugLog(line: String) {
        try {
            val dir = File(
                android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                "ShopeeCaptureDebugLog"
            )
            if (!dir.exists()) dir.mkdirs()
            val fileName = currentDebugLogFileName ?: "debug_log_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.txt".also {
                currentDebugLogFileName = it
            }
            val file = File(dir, fileName)
            val timestamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())
            file.appendText("[$timestamp] $line\n")
        } catch (e: Exception) {
            // 寫檔失敗不影響主流程，忽略即可
        }
    }

    private fun hasRequiredFields(metrics: ProductMetrics, filter: ProductFilterConfig): Boolean {
        if ((filter.minCommissionPercent != null || filter.maxCommissionPercent != null) && metrics.commissionPercent == null) return false
        if ((filter.minPrice != null || filter.maxPrice != null) && metrics.price == null) return false
        if ((filter.minSoldCount != null || filter.maxSoldCount != null) && metrics.soldCount == null) return false
        if ((filter.minPromoterCount != null || filter.maxPromoterCount != null) && metrics.promoterCount == null) return false
        return true
    }

    /** 針對「有設限制但還沒讀到值」的欄位輪詢重讀，直到齊全或逾時，逾時就回傳當下讀到的結果（可能仍有欄位是 null）。 */
    private suspend fun waitForRequiredMetrics(filter: ProductFilterConfig, timeoutMs: Long): ProductMetrics? {
        val start = System.currentTimeMillis()
        var last: ProductMetrics? = null
        while (System.currentTimeMillis() - start < timeoutMs) {
            val root = rootInActiveWindow ?: break
            val metrics = extractProductMetrics(root)
            last = metrics
            if (hasRequiredFields(metrics, filter)) return metrics
            delay(200)
        }
        return last
    }

    /** 把「10」「K」這種拆開的數字＋單位轉成整數，例如 (10, "K") -> 10000，(11.5, "M") -> 11500000。 */
    private fun parseAbbreviatedNumber(numberPart: String, suffix: String?): Int? {
        val base = numberPart.toDoubleOrNull() ?: return null
        val multiplier = when (suffix?.uppercase()) {
            "K" -> 1_000.0
            "M" -> 1_000_000.0
            else -> 1.0
        }
        return (base * multiplier).toInt()
    }

    /** 讀取目前畫面所屬 App 的套件名稱，用來確認無障礙服務有沒有正確監控到目標 App。 */
    fun getCurrentPackageName(): String? = rootInActiveWindow?.packageName?.toString()

    /** 判斷目前畫面是不是蝦皮聯盟合作 App 的首頁（成效表現／熱門賣場等），用來偵測返回鍵是否跳過頭。 */
    private fun looksLikeShopeeHomeScreen(root: AccessibilityNodeInfo): Boolean {
        val texts = mutableListOf<String>()
        collectTextNodes(root, texts, maxDepth = 15)
        val homeMarkers = listOf("成效表現", "熱門賣場", "熱門商品", "專屬推薦", "推廣計畫")
        return homeMarkers.count { marker -> texts.any { it.contains(marker) } } >= 2
    }

    /** 從目前畫面找搜尋框（可編輯的文字欄位）節點。找不到就回傳 null。 */
    private fun findSearchBoxNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var found: AccessibilityNodeInfo? = null
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || found != null || depth > 15) return
            if (node.isEditable) {
                found = node
                return
            }
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }
        walk(root, 0)
        return found
    }

    /** 讀出目前畫面搜尋框裡的關鍵字文字。找不到搜尋框就回傳 null。 */
    private fun findSearchBoxText(root: AccessibilityNodeInfo): String? {
        return findSearchBoxNode(root)?.text?.toString()
    }

    /**
     * 跑到蝦皮首頁時的自動恢復：從首頁找搜尋框，填回剛才記下的關鍵字並送出搜尋，
     * 等結果頁出現商品卡片就算成功。找不到搜尋框、沒有關鍵字記錄、或送出後仍看不到商品卡片，都算失敗。
     */
    private suspend fun tryRecoverToSearchResults(): Boolean {
        val query = lastKnownSearchQuery
        if (query.isNullOrBlank()) return false

        val root = rootInActiveWindow ?: return false
        val searchBox = findSearchBoxNode(root) ?: return false

        val bundle = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, query)
        }
        searchBox.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
        delay(400)

        // 送出搜尋：優先用編輯器的「搜尋」動作（Android 11+），找不到就退而求其次點擊搜尋框本身
        val submitted = searchBox.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        if (!submitted) {
            searchBox.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        delay(1500)

        val afterRoot = rootInActiveWindow ?: return false
        return findProductCards(afterRoot).isNotEmpty()
    }

    private fun performBack() {
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    private fun performScrollDown() {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        // 滑動距離縮短（原本 75%→30%，改成 78%→48%），讓前後兩次畫面有更多重疊，
        // 避免商品格子之間有落差時，中間那排商品完全沒出現過就被跳過。
        val path = Path().apply {
            moveTo(width / 2f, height * 0.78f)
            lineTo(width / 2f, height * 0.48f)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
            .build()
        dispatchGesture(gesture, null, null)
    }

    /** 在圖片輪播範圍內，由右往左滑一下，切到下一張圖。 */
    private fun swipeCarouselNext(bounds: Rect) {
        val y = bounds.centerY().toFloat()
        val startX = bounds.right - bounds.width() * 0.1f
        val endX = bounds.left + bounds.width() * 0.1f
        val path = Path().apply {
            moveTo(startX, y)
            lineTo(endX, y)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 250))
            .build()
        dispatchGesture(gesture, null, null)
    }

    /**
     * 找出商品圖片輪播的範圍。做法：先找到「1/8」這種頁碼指示文字的節點，
     * 再往上找父節點，找到「寬度幾乎等於整個螢幕寬度」的那一層，判定是輪播容器本身。
     */
    private fun findImageCarouselBounds(root: AccessibilityNodeInfo): Rect? {
        val screenWidth = resources.displayMetrics.widthPixels
        var indicator: AccessibilityNodeInfo? = null
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || indicator != null || depth > 20) return
            val t = node.text?.toString()?.trim()
            if (t != null && Regex("^\\d+\\s*/\\s*\\d+$").matches(t)) {
                indicator = node
                return
            }
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }
        walk(root, 0)
        val start = indicator ?: return null
        var p: AccessibilityNodeInfo? = start.parent
        var depth = 0
        while (p != null && depth < 10) {
            val b = Rect()
            p.getBoundsInScreen(b)
            if (b.width() >= screenWidth - 20 && b.height() > 100) return b
            p = p.parent
            depth++
        }
        return null
    }

    /** 讀取目前畫面上「X/N」頁碼指示文字裡的總張數 N，讀不到就回傳 1（當作只有一張圖）。 */
    private fun readCarouselTotal(root: AccessibilityNodeInfo): Int {
        val texts = mutableListOf<String>()
        collectTextNodes(root, texts, maxDepth = 20)
        for (t in texts) {
            val m = Regex("^(\\d+)\\s*/\\s*(\\d+)$").find(t.trim())
            if (m != null) return m.groupValues[2].toIntOrNull() ?: 1
        }
        return 1
    }

    /** 等待頁碼指示文字變成「index/total」，最多等 timeoutMs，逾時就直接放棄等待（沿用目前畫面）。 */
    private suspend fun waitForCarouselIndex(index: Int, timeoutMs: Long) {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            val root = rootInActiveWindow
            if (root != null && root.findAccessibilityNodeInfosByText("$index/").isNotEmpty()) return
            delay(120)
        }
    }

    /**
     * 依序滑過商品圖片輪播，把每一張都截圖存下來（用於後續生成影片／文案素材）。
     * 只在 Android 11（API 30）以上支援截圖；抓不到輪播範圍或截圖失敗時，該張直接跳過不中斷整體流程。
     */
    private suspend fun captureGalleryImages(root: AccessibilityNodeInfo): List<Bitmap> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            appendDebugLog("  → 圖片輪播擷取：系統版本低於 Android 11，不支援截圖，跳過")
            return emptyList()
        }
        val bounds = findImageCarouselBounds(root)
        if (bounds == null) {
            appendDebugLog("  → 圖片輪播擷取：找不到輪播範圍（沒偵測到「X/N」頁碼），跳過")
            return emptyList()
        }
        val total = readCarouselTotal(root).coerceIn(1, 20) // 上限 20 張，避免極端情況跑太久
        appendDebugLog("  → 圖片輪播擷取：偵測到範圍 $bounds，共 $total 張，開始逐張截圖")
        val images = mutableListOf<Bitmap>()
        var timeoutCount = 0
        val overallDeadline = System.currentTimeMillis() + 20000 // 整體時間上限，改成內部自己控管，逾時就跳出迴圈回傳「已經抓到的部分」，不會像外層 withTimeoutOrNull 那樣把整批結果都作廢

        for (index in 1..total) {
            if (System.currentTimeMillis() > overallDeadline) {
                appendDebugLog("  → 圖片輪播擷取：已達整體時間上限，提前結束（已成功 ${images.size} 張，剩餘 ${total - index + 1} 張放棄）")
                break
            }
            if (index > 1) {
                swipeCarouselNext(bounds)
                waitForCarouselIndex(index, 1200)
                delay(200) // 滑動動畫緩衝
            }
            // 加上逾時保護：截圖請求萬一卡住（例如系統沒回應），最多等 4 秒就放棄這張，
            // 不會讓整個自動擷取流程被卡死，之前就是因為沒有這層保護才整個凍結。
            val full = withTimeoutOrNull(4000) { captureScreenshotSuspend() }
            if (full == null) {
                timeoutCount++
                appendDebugLog("  → 第 $index 張截圖逾時或失敗，跳過")
                continue
            }
            val cropped = try {
                val left = bounds.left.coerceIn(0, full.width)
                val top = bounds.top.coerceIn(0, full.height)
                val width = bounds.width().coerceAtMost(full.width - left)
                val height = bounds.height().coerceAtMost(full.height - top)
                if (width > 0 && height > 0) Bitmap.createBitmap(full, left, top, width, height) else full
            } catch (e: Exception) {
                full
            }
            images.add(cropped)
        }
        appendDebugLog("  → 圖片輪播擷取完成：成功 ${images.size}/$total 張（逾時或失敗 $timeoutCount 張）")
        return images
    }

    /**
     * 點進商品詳情頁後，畫面不一定馬上完整渲染（圖片、分潤率、已售出等資訊可能還在載入）。
     * 這裡改成「輪詢等待分享按鈕出現」再多留一點緩衝時間，取代原本寫死的固定延遲，
     * 避免網路較慢時抓到還沒載完的殘缺畫面。逾時仍會回傳當下畫面（不是 null），
     * 讓後續流程照舊判斷「找不到分享按鈕」並跳過，而不是直接當成整體失敗。
     */
    private suspend fun waitForDetailPageLoaded(timeoutMs: Long): AccessibilityNodeInfo? {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            val root = rootInActiveWindow
            if (root != null && findNodeByDescriptors(root, matchRules.shareButtonDescriptors) != null) {
                delay(250) // 分享按鈕出現後，分潤率／價格等文字通常緊接著渲染完成，多留一點緩衝
                return rootInActiveWindow
            }
            delay(150)
        }
        return rootInActiveWindow
    }

    private suspend fun waitForAnyText(texts: List<String>, timeoutMs: Long): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            val root = rootInActiveWindow
            if (root != null) {
                for (t in texts) {
                    if (root.findAccessibilityNodeInfosByText(t).isNotEmpty()) return true
                }
            }
            delay(150)
        }
        return false
    }

    private fun randomDelay(config: AutoCaptureConfig): Long =
        Random.nextLong(config.minDelayMs, config.maxDelayMs + 1)

    /**
     * 找出目前畫面上「看起來像商品卡片」的可點擊節點：
     * 大小落在合理卡片範圍內，且底下有價格符號文字。
     * 這是近似猜測邏輯，蝦皮改版可能需要調整（見 RemoteConfigLoader 的比對規則）。
     */
    private fun findProductCards(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 20 || result.size > 50) return
            if (node.isClickable) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                if (bounds.height() in 120..900 && bounds.width() in 120..1200) {
                    val texts = mutableListOf<String>()
                    collectTextNodes(node, texts, maxDepth = 6)
                    val hasPrice = texts.any { txt -> matchRules.priceIndicatorPrefixes.any { txt.contains(it) } }
                    if (hasPrice) {
                        result.add(node)
                        return
                    }
                }
            }
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }
        walk(root, 0)
        return result
    }

    private fun cardKey(node: AccessibilityNodeInfo): String {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val texts = mutableListOf<String>()
        collectTextNodes(node, texts, maxDepth = 6)
        val title = texts.firstOrNull { it.length in 4..80 } ?: ""
        return "$title|$bounds"
    }

    private fun findNodeByDescriptors(root: AccessibilityNodeInfo, texts: List<String>): AccessibilityNodeInfo? {
        findNodeByTexts(root, texts)?.let { return it }
        var found: AccessibilityNodeInfo? = null
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || found != null || depth > 25) return
            val cd = node.contentDescription?.toString()
            if (cd != null && texts.any { cd.contains(it, ignoreCase = true) }) {
                found = if (node.isClickable) node else {
                    var p = node.parent
                    var d = 0
                    var candidate: AccessibilityNodeInfo? = null
                    while (p != null && d < 4) {
                        if (p.isClickable) { candidate = p; break }
                        p = p.parent
                        d++
                    }
                    candidate ?: node
                }
                return
            }
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }
        walk(root, 0)
        return found
    }

    private suspend fun captureScreenshotSuspend(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return suspendCancellableCoroutine { cont ->
            takeScreenshotCompat { bitmap ->
                if (cont.isActive) cont.resume(bitmap)
            }
        }
    }

    // ===================== 半自動（單次）擷取 =====================

    /**
     * 使用者按下懸浮按鈕時呼叫。回傳結果透過 callback 通知（在主執行緒）。
     */
    fun captureCurrentScreen(onResult: (CaptureResult) -> Unit) {
        val root = rootInActiveWindow
        if (root == null) {
            onResult(CaptureResult.Failure("無法讀取目前畫面，請確認蝦皮 App 在最上層"))
            return
        }

        // 1. 找「複製連結」按鈕節點
        val copyLinkNode = findNodeByTexts(root, matchRules.copyLinkButtonTexts)
        if (copyLinkNode == null) {
            onResult(CaptureResult.Failure("找不到「複製連結」按鈕，請確認目前是在分潤分享面板"))
            return
        }

        // 2. 找商品名稱節點（分享面板上方通常有商品標題文字，取畫面中較長的文字節點當作商品名候選）
        val productName = findLikelyProductNameText(root)

        // 3. 點擊複製連結
        val clicked = copyLinkNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (!clicked) {
            onResult(CaptureResult.Failure("點擊「複製連結」失敗，請手動點擊一次再重試"))
            return
        }

        // 4. 延遲讀取剪貼簿（給系統時間把連結寫入剪貼簿）並截圖
        mainHandler.postDelayed({
            val link = readClipboard()
            takeScreenshotAndSave(productName, link, onResult)
        }, 600)
    }

    private fun findNodeByTexts(root: AccessibilityNodeInfo, texts: List<String>): AccessibilityNodeInfo? {
        for (text in texts) {
            val matches = root.findAccessibilityNodeInfosByText(text)
            for (node in matches) {
                if (node.isClickable) return node
                // 有些按鈕的可點擊區域在父節點
                var parent = node.parent
                var depth = 0
                while (parent != null && depth < 4) {
                    if (parent.isClickable) return parent
                    parent = parent.parent
                    depth++
                }
            }
        }
        return null
    }

    /**
     * 記錄「哪些商品已經擷取過」的持久化清單，跨越不同次「自動」執行、甚至跨越 App 重啟都會保留，
     * 避免今天跑過的商品明天重新搜尋又被抓一次、產生重複資料夾。
     * 用商品名稱做「早期快速判斷」（省下後面截圖、讀剪貼簿的時間），用連結做最終確認（比較準確）。
     */
    private fun getDedupPrefs() = getSharedPreferences("capture_dedup_prefs", Context.MODE_PRIVATE)

    private fun isProductNameAlreadyCaptured(name: String?): Boolean {
        if (name.isNullOrBlank() || name == "未知") return false
        val set = getDedupPrefs().getStringSet("captured_names", emptySet()) ?: emptySet()
        return set.contains(name)
    }

    private fun isLinkAlreadyCaptured(link: String?): Boolean {
        if (link.isNullOrBlank()) return false
        val set = getDedupPrefs().getStringSet("captured_links", emptySet()) ?: emptySet()
        return set.contains(link)
    }

    private fun markAsCaptured(name: String?, link: String?) {
        val prefs = getDedupPrefs()
        val editor = prefs.edit()
        if (!name.isNullOrBlank() && name != "未知") {
            val names = (prefs.getStringSet("captured_names", emptySet()) ?: emptySet()).toMutableSet()
            names.add(name)
            editor.putStringSet("captured_names", names)
        }
        if (!link.isNullOrBlank()) {
            val links = (prefs.getStringSet("captured_links", emptySet()) ?: emptySet()).toMutableSet()
            links.add(link)
            editor.putStringSet("captured_links", links)
        }
        editor.apply()
    }

    private fun findLikelyProductNameText(root: AccessibilityNodeInfo): String? {
        val candidates = mutableListOf<String>()
        collectTextNodes(root, candidates, maxDepth = 12)
        // 取長度落在合理商品標題範圍（8~60 字）且非按鈕文字的第一筆，
        // 排除優惠券橫幅相關文字（例如「提供優惠券給您的粉絲/關注者」），避免誤判成商品名稱，
        // 也排除純數字／價格格式的文字（例如「1,680.00」），這類文字不可能是真正的商品標題。
        return candidates.firstOrNull {
            it.length in 8..60 && !isCouponBannerText(it) && !isPureNumberOrPriceText(it)
        }
    }

    /** 判斷文字是不是「純數字」或「價格格式」（例如 1,680.00、399、$399），這種不可能是商品標題。 */
    private fun isPureNumberOrPriceText(text: String): Boolean {
        val stripped = text.trim().removePrefix("$").removePrefix("₱").removePrefix("฿").removePrefix("₫")
        return Regex("^[\\d,]+(\\.\\d+)?$").matches(stripped)
    }

    /** 商品詳情頁常見的「優惠券／折扣橫幅」文字，不是商品資訊，比對商品名稱或價格時要排除掉。 */
    private fun isCouponBannerText(text: String): Boolean {
        val keywords = listOf("提供優惠券", "低消", "社群媒體", "推廣限定", "條款與規範", "有效期限")
        return keywords.any { text.contains(it) }
    }

    private fun collectTextNodes(node: AccessibilityNodeInfo?, out: MutableList<String>, maxDepth: Int, depth: Int = 0) {
        if (node == null || depth > maxDepth || out.size > 30) return
        val text = node.text?.toString()?.trim()
        if (!text.isNullOrEmpty()) out.add(text)
        for (i in 0 until node.childCount) {
            collectTextNodes(node.getChild(i), out, maxDepth, depth + 1)
        }
    }

    private fun readClipboard(): String? {
        return try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip ?: return null
            if (clip.itemCount == 0) return null
            clip.getItemAt(0).text?.toString()
        } catch (e: SecurityException) {
            lastClipboardError = "讀取被系統拒絕（SecurityException：${e.message}），疑似手機廠牌的背景剪貼簿權限限制"
            null
        } catch (e: Exception) {
            lastClipboardError = "讀取剪貼簿發生例外：${e.javaClass.simpleName} ${e.message}"
            null
        }
    }

    private var lastClipboardError: String? = null

    /** 剪貼簿有時候不是點擊當下就馬上寫入完成，這裡輪詢重試最多 1.5 秒，取代單次固定延遲後讀取。 */
    private suspend fun readClipboardWithRetry(timeoutMs: Long = 1500): String? {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            val text = readClipboard()
            if (!text.isNullOrBlank()) return text
            delay(150)
        }
        var finalResult = readClipboard()
        if (finalResult.isNullOrBlank()) {
            // 重試逾時後仍讀不到，把詳細診斷資訊記一次（不是每次輪詢都記，避免洗版）
            val detail = lastClipboardError ?: run {
                try {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = cm.primaryClip
                    when {
                        clip == null -> "primaryClip 是 null（系統回報完全沒有剪貼簿內容）"
                        clip.itemCount == 0 -> "primaryClip 存在但 itemCount=0"
                        else -> {
                            val label = clip.description?.label
                            val mime = if ((clip.description?.mimeTypeCount ?: 0) > 0) clip.description?.getMimeType(0) else "無"
                            "有 clip 但文字是空的，label=$label mimeType=$mime"
                        }
                    }
                } catch (e: Exception) {
                    "診斷讀取本身也失敗：${e.javaClass.simpleName}"
                }
            }
            appendDebugLog("     [剪貼簿診斷] 重試 ${timeoutMs}ms 後仍讀不到：$detail")
            lastClipboardError = null

            // 一般讀取失敗，很可能是 Android 10+ 的「非焦點 App 不能讀剪貼簿」限制。
            // 改用透明橋接 Activity 短暫搶焦點再讀一次。
            appendDebugLog("     [剪貼簿診斷] 改用焦點橋接 Activity 重試一次")
            finalResult = readClipboardViaFocusBridge()
            appendDebugLog("     [剪貼簿診斷] 焦點橋接結果：${if (finalResult.isNullOrBlank()) "仍讀不到" else "成功，長度 ${finalResult.length} 字"}")
        }
        return finalResult
    }

    /**
     * 用透明橋接 Activity 短暫搶下畫面焦點來讀取剪貼簿，繞過 Android 10+「非焦點 App 不能讀剪貼簿」的限制。
     * 啟動後輪詢等待橋接 Activity 完成讀取並自動關閉（最多等 1.5 秒），逾時就放棄回傳 null。
     */
    private suspend fun readClipboardViaFocusBridge(): String? {
        return try {
            ClipboardBridgeActivity.pendingClipboardResult = null
            ClipboardBridgeActivity.resultReady = false
            val intent = Intent(this, ClipboardBridgeActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            }
            startActivity(intent)
            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < 1500 && !ClipboardBridgeActivity.resultReady) {
                delay(50)
            }
            ClipboardBridgeActivity.pendingClipboardResult
        } catch (e: Exception) {
            appendDebugLog("     [剪貼簿診斷] 焦點橋接啟動失敗：${e.javaClass.simpleName} ${e.message}")
            null
        }
    }

    private fun takeScreenshotAndSave(
        productName: String?,
        link: String?,
        onResult: (CaptureResult) -> Unit
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            // API 30 以下沒有 takeScreenshot()，直接存純文字資料，不含圖片
            val saved = saveResult(productName, link, caption = null, bitmaps = emptyList())
            onResult(saved)
            return
        }
        takeScreenshotCompat { bitmap ->
            val saved = saveResult(productName, link, caption = null, bitmaps = listOfNotNull(bitmap))
            onResult(saved)
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun takeScreenshotCompat(onBitmap: (Bitmap?) -> Unit) {
        takeScreenshot(
            android.view.Display.DEFAULT_DISPLAY,
            executor,
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val bitmap = try {
                        val hw: HardwareBuffer = result.hardwareBuffer
                        val bmp = Bitmap.wrapHardwareBuffer(hw, result.colorSpace)
                        hw.close()
                        bmp?.copy(Bitmap.Config.ARGB_8888, false)
                    } catch (e: Exception) {
                        null
                    }
                    mainHandler.post { onBitmap(bitmap) }
                }

                override fun onFailure(errorCode: Int) {
                    mainHandler.post { onBitmap(null) }
                }
            }
        )
    }

    private fun saveResult(
        productName: String?,
        link: String?,
        caption: String?,
        bitmaps: List<Bitmap>,
        metrics: ProductMetrics? = null
    ): CaptureResult {
        val id = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val baseDir = File(
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
            "CaptionQueue/$id"
        )
        if (!baseDir.exists()) baseDir.mkdirs()

        // 多張圖片依序存成 image_1.jpg、image_2.jpg...；只有一張的話同時存一份 image.jpg 保留舊格式相容
        bitmaps.forEachIndexed { index, bitmap ->
            try {
                val imgFile = File(baseDir, "image_${index + 1}.jpg")
                FileOutputStream(imgFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                }
            } catch (e: Exception) {
                // 單張圖片存檔失敗不影響其他張與文字資料
            }
        }
        if (bitmaps.size == 1) {
            try {
                val imgFile = File(baseDir, "image.jpg")
                FileOutputStream(imgFile).use { out ->
                    bitmaps[0].compress(Bitmap.CompressFormat.JPEG, 90, out)
                }
            } catch (e: Exception) {
                // 忽略
            }
        }

        // 連結跟文案文字都各自另存一個純文字檔（跟 meta.json 內容重複，但方便之後的文案／影片生成
        // 工具直接讀取單一檔案，不用每次都解析 JSON）。
        if (!link.isNullOrBlank()) {
            try {
                File(baseDir, "link.txt").writeText(link)
            } catch (e: Exception) {
                // 忽略
            }
        }
        if (!caption.isNullOrBlank()) {
            try {
                File(baseDir, "caption.txt").writeText(caption)
            } catch (e: Exception) {
                // 忽略
            }
        }

        val metaFile = File(baseDir, "meta.json")
        val metaJson = org.json.JSONObject().apply {
            put("id", id)
            put("productName", productName ?: org.json.JSONObject.NULL)
            put("promoLink", link ?: org.json.JSONObject.NULL)
            put("caption", caption ?: org.json.JSONObject.NULL)
            put("commissionPercent", metrics?.commissionPercent ?: org.json.JSONObject.NULL)
            put("price", metrics?.price ?: org.json.JSONObject.NULL)
            put("soldCount", metrics?.soldCount ?: org.json.JSONObject.NULL)
            put("promoterCount", metrics?.promoterCount ?: org.json.JSONObject.NULL)
            put("imageCount", bitmaps.size)
            put("capturedAt", System.currentTimeMillis())
        }
        metaFile.writeText(metaJson.toString())

        val product = CapturedProduct(
            id, productName, link, caption,
            metrics?.commissionPercent, metrics?.price, metrics?.soldCount, metrics?.promoterCount,
            System.currentTimeMillis()
        )
        return CaptureResult.Success(product, baseDir.absolutePath)
    }
}
