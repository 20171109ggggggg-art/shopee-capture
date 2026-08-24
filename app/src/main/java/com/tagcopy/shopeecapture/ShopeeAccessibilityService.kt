package com.tagcopy.shopeecapture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
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
            val alreadyProcessedCount = cards.count { cardKey(it) in processedKeys }
            if (alreadyProcessedCount > 0) {
                appendDebugLog("  → 畫面上偵測到 ${cards.size} 張卡片，其中 $alreadyProcessedCount 張已處理過（用標題比對），直接跳過選取，不會重複點進去")
            }
            val nextCard = cards.firstOrNull { cardKey(it) !in processedKeys }

            if (nextCard == null) {
                emptyScrollAttempts++
                appendDebugLog("  → 找不到新商品卡片（畫面上共 ${cards.size} 張，全部已處理過），準備往下滑動（第 $emptyScrollAttempts/${maxEmptyScrollAttempts} 次嘗試），目前套件名稱：${getCurrentPackageName() ?: "讀不到"}")
                if (emptyScrollAttempts > maxEmptyScrollAttempts) {
                    appendDebugLog("  → 已達最大滑動嘗試次數，判定沒有更多商品，結束前記錄目前畫面內容供除錯")
                    dumpClickableNodesToLog(root)
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
    /** 分潤率讀取時實際比對到的原始文字片段，供除錯用（診斷讀錯數字時比對來源）。*/
    private var lastCommissionSourceText: String? = null

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

        var productName = findLikelyProductNameText(detailRoot)
        if (productName == null) {
            // 找不到標題時先不急著判定失敗：waitForDetailPageLoaded 只確認「分享按鈕」這類底部固定
            // 按鈕出現，代表頁面骨架已經載入，但標題、價格等內容可能還在非同步渲染中，早一步讀取
            // 剛好只抓到查看更多／聊聊／收藏／立即推廣這幾個固定不變的按鈕。稍等一下重新抓取最新畫面
            // （不能沿用舊的 detailRoot，要重新拿 rootInActiveWindow 才能看到剛渲染完成的內容）再試一次。
            delay(1600)
            val freshRoot = rootInActiveWindow
            if (freshRoot != null) {
                productName = findLikelyProductNameText(freshRoot)
                if (productName != null) {
                    appendDebugLog("  → 商品名稱第一次讀不到，等待 0.8 秒後重新讀取成功：$productName")
                }
            }
        }

        // 早期判斷：這個商品名稱如果先前任何一次執行就擷取過，直接跳過，省下後面截圖、讀剪貼簿的時間。
        // 但跳過前先停留 3-4 秒再返回，避免進入商品頁不到 1 秒就跳出，看起來太不像真人操作。
        if (isProductNameAlreadyCaptured(productName)) {
            appendDebugLog("商品：$productName | 結果=跳過（重複商品，先前已擷取過）")
            onEvent(AutoCaptureEvent.Log("○ 已擷取過此商品，略過：$productName"))
            delay(Random.nextLong(6000, 8001))
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
                delay(4000)
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
        // 這裡先用截圖版本墊底，等分享面板打開後如果能用「下載鈕」抓到乾淨原圖，會直接覆蓋掉這批
        var galleryImages = captureGalleryImages(detailRoot)
        appendDebugLog("商品：${productName ?: "未知"} | 已擷取商品圖片（截圖版本）${galleryImages.size} 張")

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
        // 分享面板裡的輪播縮圖下載鈕能拿到完全乾淨的原圖（無浮水印、無介面元素），
        // 畫質也比截圖再裁切好，優先用這個；抓不到（例如沒有讀取相簿權限、或面板結構跟預期不同）就沿用上面截圖版本
        if (sheetRoot != null) {
            val downloaded = try {
                captureGalleryImagesViaDownload(sheetRoot)
            } catch (e: Exception) {
                appendDebugLog("  → 下載式圖片擷取發生例外，改用截圖版本：${e.message}")
                null
            }
            if (!downloaded.isNullOrEmpty()) {
                appendDebugLog("商品：${productName ?: "未知"} | 改用分享面板下載鈕擷取到原圖 ${downloaded.size} 張（取代截圖版本）")
                galleryImages = downloaded
            }
        }
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
        var copyInfoRoot = rootInActiveWindow
        var copyInfoNode = copyInfoRoot?.let { findNodeByTexts(it, matchRules.copyInfoButtonTexts) }
        if (copyInfoNode == null) {
            // 找不到時先不急著判定失敗：剛才讀取連結時可能觸發了剪貼簿焦點橋接 Activity，
            // 畫面焦點短暫切換過去又切回來，這裡的 rootInActiveWindow 有時會抓到過渡狀態
            // （半空的畫面），稍等一下重新抓一次再找。
            delay(800)
            copyInfoRoot = rootInActiveWindow
            copyInfoNode = copyInfoRoot?.let { findNodeByTexts(it, matchRules.copyInfoButtonTexts) }
        }
        if (copyInfoNode != null) {
            copyInfoNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            delay(600) // 給點擊一點反應時間，避免立刻讀到「複製連結」殘留的舊值
            caption = readClipboardWithRetry()
            if (!caption.isNullOrBlank() && caption == link) {
                // 讀到的內容跟連結一模一樣：代表剪貼簿根本還沒被「複製資訊」寫入新內容，是殘留的舊值，不是真正的文案。
                appendDebugLog("  → 複製資訊：讀到的內容跟連結完全相同，判定為剪貼簿還沒更新的舊值，重試一次")
                copyInfoNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                delay(1200)
                val retryCaption = readClipboardWithRetry()
                caption = if (!retryCaption.isNullOrBlank() && retryCaption != link) retryCaption else null
            }
            appendDebugLog("  → 複製資訊：${if (caption.isNullOrBlank()) "讀不到有效文案內容（可能跟連結重複或剪貼簿讀取失敗）" else "成功，長度 ${caption!!.length} 字"}")
        } else {
            appendDebugLog("商品：${productName ?: "未知"} | 找不到「複製資訊」按鈕，文案留空")
            appendDebugLog("  → 候選字串：${matchRules.copyInfoButtonTexts}")
            copyInfoRoot?.let { dumpClickableNodesToLog(it) }
        }

        val bitmap = if (galleryImages.isEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            withTimeoutOrNull(4000) { captureScreenshotSuspend() }
        } else null

        // 關閉分享面板並回到搜尋結果列表。
        // 使用者多數情況下實測確認：這個分享面板跟商品詳情頁是合併成同一層的，只要按「一次」
        // 返回鍵就會直接回到搜尋結果頁。但這次 log 證實：遇到內容特別複雜的商品頁（例如圖片輪播
        // 多達 28 張），單次返回鍵有時「按了但沒真正離開」，畫面會卡在原地的分享面板，導致後續
        // 一直判斷不到新商品卡片、誤以為列表已經到底而提前結束整個流程。這裡加上返回後驗證：
        // 如果返回後畫面上還看得到分享面板的標題文字，代表根本沒離開，額外再按最多 2 次返回鍵，
        // 每次都重新確認，直到真正離開或已經試過上限次數。
        performBack()
        delay(randomDelay(config))
        appendDebugLog("  → 返回後目前畫面套件名稱：${getCurrentPackageName() ?: "讀不到"}")

        var backRetryCount = 0
        while (backRetryCount < 2) {
            val stillOnSheet = rootInActiveWindow?.let { r ->
                matchRules.shareSheetTitleTexts.any { t -> r.findAccessibilityNodeInfosByText(t).isNotEmpty() }
            } ?: false
            if (!stillOnSheet) break
            backRetryCount++
            appendDebugLog("  → ⚠ 返回後偵測到分享面板文字仍在畫面上，判定單次返回鍵沒有真正離開，額外補按一次返回鍵（第 $backRetryCount 次）")
            performBack()
            delay(randomDelay(config))
        }
        if (backRetryCount > 0) {
            appendDebugLog("  → 補按返回鍵後目前畫面套件名稱：${getCurrentPackageName() ?: "讀不到"}")
        }

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
        val allTexts = mutableListOf<String>()
        collectTextNodes(root, allTexts, maxDepth = 20)

        // 關鍵修正：商品詳情頁往下捲動會有「逛逛賣場其他好物」區塊，裡面是同賣場其他商品的
        // 推薦卡片，每張卡片也各自標了「分潤 X%」——這是「別的商品」的分潤率，不是目前這個
        // 商品的。實測發現這些卡片的分潤率文字剛好是單一完整節點（同時含「分潤」＋數字＋%），
        // 比本商品自己的徽章（常被拆成「分潤加碼」跟「10.5%」兩個獨立節點）更早被逐一比對
        // 命中，導致誤抓到「其他好物」裡別的商品分潤率（例如商品實際 10.5%，誤讀成其他好物
        // 卡片的 0.5%）。修法：只用「逛逛賣場其他好物」這個區塊標記之前的文字做指標解析，
        // 標記之後的內容（其他商品的資訊）一律不採用，避免任何欄位被跨商品污染。
        val relatedSectionIndex = allTexts.indexOfFirst { it.contains("逛逛賣場其他好物") }
        val texts = if (relatedSectionIndex >= 0) allTexts.subList(0, relatedSectionIndex) else allTexts

        // 分潤率：中文「分潤」或英文「Comm Rate」「COMMSXTRA」等變體
        val commissionRegex = Regex("(?:分潤|Comm\\s*Rate|COMMS?\\s*XTRA)[^\\d%]*([\\d]+\\.?[\\d]*)\\s*%", RegexOption.IGNORE_CASE)
        // 裸百分比數字（例如單一節點只有「10.5%」，前後不含任何文字）：
        // 實測發現商品自己的分潤率徽章在畫面上常常是「分潤加碼」跟「10.5%」兩個獨立節點，
        // 用關鍵字比對法（上面那個 regex）永遠配對不到「分潤加碼」節點本身（缺數字），
        // 只能在合併文字時才勉強配對到，但合併文字時反而容易誤抓到「最高分潤率」（賣場整體）
        // 或「逛逛賣場其他好物」（別的商品）這些格式相似但意義不同的分潤率文字。
        // 真正可靠的判斷依據是「位置」：商品自己的分潤率數字節點，緊接在價格節點之後幾個節點內出現。
        val barePercentRegex = Regex("^([\\d]+\\.?[\\d]*)\\s*%$")

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
        lastCommissionSourceText = null

        // 主要判斷：找到價格的位置，往後幾個節點內找「裸百分比」文字，那才是商品自己的分潤率。
        // 注意：貨幣符號（$）跟數字（304.00）常常是兩個分開的節點，不會同時出現在同一段文字裡，
        // 所以錨點改成「單獨一個貨幣符號」的節點（不要求同節點內要有數字），比對更寬鬆、更準確。
        val currencySymbols = setOf("$", "₱", "฿", "₫")
        var priceAnchorIndex = texts.indexOfFirst { it.trim() in currencySymbols }
        if (priceAnchorIndex < 0) {
            // 找不到單獨貨幣符號節點，退而求其次找同節點內含貨幣符號＋數字的（例如「$304.00」黏在一起的情況）
            priceAnchorIndex = texts.indexOfFirst { !isCouponBannerText(it) && priceRegex.containsMatchIn(it) }
        }
        if (priceAnchorIndex >= 0) {
            for (i in (priceAnchorIndex + 1)..(priceAnchorIndex + 6).coerceAtMost(texts.size - 1)) {
                val candidate = texts[i].trim()
                if (isCouponBannerText(candidate)) continue
                barePercentRegex.find(candidate)?.let {
                    commission = it.groupValues[1].toDoubleOrNull()
                    lastCommissionSourceText = "[價格鄰近比對] 價格錨點「${texts[priceAnchorIndex]}」之後第 ${i - priceAnchorIndex} 個節點：「$candidate」"
                }
                if (commission != null) break
            }
        }
        if (commission == null) {
            // 位置比對沒找到時也要留下診斷線索：記錄價格錨點位置附近實際看到的節點內容，
            // 方便下次還讀不到分潤率時，直接從 log 比對是不是節點順序跟預期不同。
            val debugWindow = if (priceAnchorIndex >= 0) {
                texts.subList(priceAnchorIndex, (priceAnchorIndex + 7).coerceAtMost(texts.size)).joinToString(" | ")
            } else {
                "找不到價格錨點（無單獨貨幣符號節點，也無含貨幣符號+數字的節點）"
            }
            lastCommissionSourceText = "[位置比對找不到，價格錨點附近節點內容] $debugWindow"
        }

        for (text in texts) {
            if (isCouponBannerText(text)) continue // 優惠券橫幅文字（例如「低消 $49」）不是商品資訊，整段跳過避免誤判
            if (commission == null && !text.contains("最高分潤率")) {
                // 「最高分潤率」是賣場整體徽章，不是這個商品自己的分潤率，明確排除避免誤判
                commissionRegex.find(text)?.let {
                    commission = it.groupValues[1].toDoubleOrNull()
                    lastCommissionSourceText = text
                }
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
            // 分潤率的合併比對額外排除含「最高分潤率」的節點，避免抓到賣場整體徽章而非商品本身
            val combinedForCommission = texts.filterNot { isCouponBannerText(it) || it.contains("最高分潤率") }.joinToString(" ")
            if (commission == null) {
                commissionRegex.find(combinedForCommission)?.let {
                    commission = it.groupValues[1].toDoubleOrNull()
                    // 合併文字比對時只記錄比對到的片段本身（前後各留一點上下文），
                    // 不記錄整段 combined（太長，洗版）。
                    val matchStart = it.range.first
                    val contextStart = (matchStart - 15).coerceAtLeast(0)
                    val contextEnd = (it.range.last + 5).coerceAtMost(combinedForCommission.length - 1)
                    lastCommissionSourceText = "[合併文字比對，已排除最高分潤率] …${combinedForCommission.substring(contextStart, contextEnd + 1)}…"
                }
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
        // 分潤率是誤判紀錄最多的欄位，不管有沒有讀到都記下診斷來源：
        // 讀到值時記錄比對到的原始文字，下次誤判可以對照找出哪裡抓錯；
        // 讀不到值時記錄附近節點內容，才能知道是錨點沒找到還是範圍內真的沒有百分比文字。
        if (lastCommissionSourceText != null) {
            appendDebugLog("  → 分潤率讀取來源文字：「${lastCommissionSourceText}」")
        }
    }

    /**
     * 找不到分享按鈕時，把畫面上「所有可點擊元件」的文字／描述／resource-id 都記錄下來，
     * 這樣不用再靠猜測，直接從 log 裡看蝦皮這個按鈕實際叫什麼名字，之後就能把正確字串加進比對規則。
     */
    private fun dumpClickableNodesToLog(root: AccessibilityNodeInfo) {
        // 原本只收集 isClickable=true 的節點，但實測發現許多按鈕的可點擊範圍其實是在父節點，
        // 子節點本身（顯示文字的那一層）isClickable 是 false，導致條件太嚴格、常常抓到 0 個元件、
        // 診斷起不了作用。改成列出「所有有文字或描述」的節點，不再限制 isClickable，
        // 並額外標註 clickable 狀態方便判斷實際可點的是哪一層。
        val lines = mutableListOf<String>()
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 25 || lines.size > 60) return
            val text = node.text?.toString()?.trim()
            val desc = node.contentDescription?.toString()?.trim()
            val id = node.viewIdResourceName
            if (!text.isNullOrEmpty() || !desc.isNullOrEmpty()) {
                lines.add("text=${text ?: "-"} | desc=${desc ?: "-"} | id=${id ?: "-"} | clickable=${node.isClickable}")
            }
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }
        walk(root, 0)
        appendDebugLog("  → 畫面上元件清單（前 ${lines.size} 個，含不可點擊節點）：")
        if (lines.isEmpty()) {
            appendDebugLog("     （完全沒有找到任何有文字/描述的節點，畫面可能還沒載入完成或讀取範圍有問題）")
        }
        lines.forEach { appendDebugLog("     $it") }
    }

    /**
     * 【診斷用，一次性】列出分享面板內所有節點的 class、bounds、clickable、text/desc、resource-id，
     * 用來確認使用者手動測試發現的「輪播縮圖下載鈕」在無障礙服務眼中長什麼樣子
     * （class name／content-description／resource-id／座標範圍），才能準確地找到並自動點擊它。
     * 不限制要有文字/描述才列出（既有的 dumpClickableNodesToLog 會漏掉純圖示按鈕），
     * 且额外印出 bounds 方便比對畫面上的實際位置。
     */
    private fun dumpShareSheetFullTreeToLog(root: AccessibilityNodeInfo) {
        val lines = mutableListOf<String>()
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 25 || lines.size > 150) return
            val text = node.text?.toString()?.trim()
            val desc = node.contentDescription?.toString()?.trim()
            val id = node.viewIdResourceName
            val cls = node.className?.toString()
            val b = Rect()
            node.getBoundsInScreen(b)
            lines.add("depth=$depth class=${cls ?: "-"} text=${text ?: "-"} desc=${desc ?: "-"} id=${id ?: "-"} clickable=${node.isClickable} bounds=$b")
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }
        walk(root, 0)
        appendDebugLog("  → 【診斷】分享面板完整節點樹（前 ${lines.size} 個）：")
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
            delay(400)
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

    /**
     * 依目前畫面的套件名稱判斷地區，寫進meta.json的region欄位。
     * 蝦皮菲律賓套件名稱慣例格式是 com.shopee.ph（跟台灣 com.shopee.tw 對應），
     * 目前App實際只操作過台灣蝦皮，這裡先做好判斷邏輯備用，
     * 判斷不出來就預設TW，跟Python端make_video.py的load_region()預設值保持一致。
     */
    private fun currentRegionFromPackage(): String {
        val pkg = getCurrentPackageName() ?: return "TW"
        return if (pkg.contains(".ph", ignoreCase = true)) "PH" else "TW"
    }

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
        if (query.isNullOrBlank()) {
            appendDebugLog("  → 自動恢復：沒有記錄到搜尋關鍵字，無法恢復")
            return false
        }

        val root = rootInActiveWindow
        if (root == null) {
            appendDebugLog("  → 自動恢復：讀不到目前畫面")
            return false
        }
        val searchBox = findSearchBoxNode(root)
        if (searchBox == null) {
            appendDebugLog("  → 自動恢復：目前畫面找不到搜尋框（可編輯欄位），可能不在蝦皮首頁或列表相關畫面")
            return false
        }

        val bundle = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, query)
        }
        searchBox.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
        delay(800)

        // 送出搜尋：優先用編輯器的「搜尋」動作（Android 11+），找不到就退而求其次點擊搜尋框本身
        val submitted = searchBox.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        appendDebugLog("  → 自動恢復：已填入關鍵字「$query」並送出搜尋（${if (submitted) "用編輯器搜尋動作" else "改點擊搜尋框"}），等待結果載入")
        if (!submitted) {
            searchBox.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        delay(3000)

        val afterRoot = rootInActiveWindow
        if (afterRoot == null) {
            appendDebugLog("  → 自動恢復：送出搜尋後讀不到畫面")
            return false
        }
        val cardCount = findProductCards(afterRoot).size
        appendDebugLog("  → 自動恢復：搜尋後畫面偵測到 $cardCount 張商品卡片")
        return cardCount > 0
    }

    /**
     * 在螢幕上「指定座標」直接點一下（方法2備援）：用於節點樹裡完全找不到可點擊元件的情況
     * （例如撰寫內文畫面那三個開關，蝦皮這幾顆是自訂繪製元件，無障礙樹裡沒有暴露對應節點，
     * 見開發時的dump診斷紀錄）。x/y吃「screenWidthRatio」「screenHeightRatio」這種佔螢幕
     * 寬高的比例（0~1），不是寫死的像素值，不同解析度的手機上跑起來座標會自動換算，
     * 比直接寫死pixel數字更耐用。
     */
    private fun tapAtScreenRatio(xRatio: Float, yRatio: Float) {
        val metrics = resources.displayMetrics
        val x = metrics.widthPixels * xRatio
        val y = metrics.heightPixels * yRatio
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        dispatchGesture(gesture, null, null)
    }

    /**
     * 在「指定節點的Y座標」、「螢幕寬度的某個比例」處點一下——給撰寫內文畫面那三個開關用：
     * Y座標從對應文字標籤節點的bounds算出來（每次執行都動態抓，不會跑掉），
     * X座標用螢幕寬度比例（開關固定在畫面右側同一個相對位置，比例在不同解析度手機上比較準）。
     */
    /**
     * 在「指定節點的Y座標」、「螢幕右側固定dp留白距離」處點一下——給撰寫內文畫面那三個開關用。
     * 改用dp（跟解析度無關的邏輯像素）而不是「螢幕寬度佔比」：Android原生排版本來就是用
     * 固定dp留白對齊邊界，不是用百分比，用dp換算在不同手機（不同螢幕密度/解析度）上
     * 才會準確對齊到同一個相對位置，不用每台手機重新校正。
     * marginDp的值是從這次實測校正抓出來的估計值，如果之後在別的裝置上測試偏了，
     * 只要調整這一個數字就好，其他邏輯不用動。
     * 點擊持續時間從80ms拉長到150ms：太短的觸控可能被系統判定成無效點擊，
     * 有機率造成同樣的座標點擊卻沒生效（懷疑是這次三顆只中一顆的原因之一）。
     */
    /**
     * 在「指定節點的Y座標」、「螢幕寬度的實測比例」處點一下——給撰寫內文畫面的開關用。
     * xRatio=0.921是用App內建的「校正」工具實測量出來的精確值（不是估算），
     * 比先前用dp留白理論推算的0.905更準確——目前只有這一台裝置的實測資料，
     * 如果之後在別的裝置上跑偏了，同樣用「校正」工具重新量一次，改這個數字就好。
     */
    private fun tapToggleNearLabel(labelNode: AccessibilityNodeInfo, xRatio: Float = 0.921f) {
        val bounds = Rect().also { labelNode.getBoundsInScreen(it) }
        val metrics = resources.displayMetrics
        val x = metrics.widthPixels * xRatio
        val y = bounds.centerY().toFloat()
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 150))
            .build()
        dispatchGesture(gesture, null, null)
    }

    private fun performBack() {
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    private fun performScrollDown() {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        // 滑動距離：原本 75%→30%（滑動45%）會漏抓中間商品，改成 78%→48%（滑動30%）又造成
        // 重疊過多、4張卡片有一半是滑動前就出現過的舊卡片，浪費時間跳過。
        // 實測後調整為 80%→40%（滑動40%），在「不漏抓」與「不過度重疊」之間取折衷。
        val path = Path().apply {
            moveTo(width / 2f, height * 0.80f)
            lineTo(width / 2f, height * 0.40f)
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
            .addStroke(GestureDescription.StrokeDescription(path, 0, 500))
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

    /**
     * 收斂輪播裁切範圍，排除兩塊不屬於商品圖片本身的區域：
     * 1. 頂部系統狀態列（時間／電量／WiFi等，由系統繪製，不在 App 畫面節點內，
     *    用系統資源高度直接扣除，不受個別手機狀態列高度不同影響）
     * 2. 底部「N 規格」縮圖列（找到該文字節點，裁切到其上緣之前，把整排規格縮圖排除在外）
     * 找不到「規格」節點（例如商品沒有多規格選項）時就不裁切底部，維持原本範圍。
     */
    private fun refineCarouselBounds(root: AccessibilityNodeInfo, raw: Rect): Rect {
        val statusBarHeight = run {
            val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
            if (resId > 0) resources.getDimensionPixelSize(resId) else 0
        }
        val top = raw.top.coerceAtLeast(statusBarHeight)

        var specTop: Int? = null
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || specTop != null || depth > 20) return
            val t = node.text?.toString()?.trim()
            if (t != null && Regex("^\\d+\\s*規格$").matches(t)) {
                val b = Rect()
                node.getBoundsInScreen(b)
                if (b.top in raw.top..raw.bottom) specTop = b.top
                return
            }
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }
        walk(root, 0)

        val bottom = specTop?.takeIf { it > top }?.coerceAtMost(raw.bottom) ?: raw.bottom
        if (bottom <= top) return raw // 保險：算出異常範圍就退回原本的，避免產生高度為0或負值的裁切框
        return Rect(raw.left, top, raw.right, bottom)
    }

    /**
     * 在輪播範圍內找出疊加在圖片上方的控制項（返回／分享箭頭、「X/N」頁碼文字），
     * 事後在截圖上塗黑遮蓋，避免這些介面元件被拍進商品圖片。
     * 這些控制項是疊加繪製在圖片同一層，無法靠單純裁切排除，只能用遮蓋處理。
     * 判斷依據：(a) 符合「數字/數字」格式的頁碼文字節點；(b) 小尺寸、可點擊、
     * 位置貼近輪播範圍頂部（上緣18%範圍內）且偏靠左或右角落的節點（典型返回/分享箭頭大小與位置）。
     */
    private fun findOverlayControlBounds(root: AccessibilityNodeInfo, carouselBounds: Rect): List<Rect> {
        val results = mutableListOf<Rect>()
        val topBandBottom = carouselBounds.top + (carouselBounds.height() * 0.18f).toInt()
        val leftBandRight = carouselBounds.left + (carouselBounds.width() * 0.25f).toInt()
        val rightBandLeft = carouselBounds.right - (carouselBounds.width() * 0.25f).toInt()
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 20) return
            val b = Rect()
            node.getBoundsInScreen(b)
            if (Rect.intersects(b, carouselBounds)) {
                val t = node.text?.toString()?.trim()
                val isPageIndicator = t != null && Regex("^\\d+\\s*/\\s*\\d+$").matches(t)
                val isSmallCornerButton = node.isClickable &&
                    b.width() in 40..220 && b.height() in 40..220 &&
                    b.top <= topBandBottom &&
                    (b.left <= leftBandRight || b.right >= rightBandLeft)
                if (isPageIndicator || isSmallCornerButton) results.add(Rect(b))
            }
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }
        walk(root, 0)
        return results
    }

    /**
     * 幫遮蓋色塊取樣周圍背景顏色，取代單純塗黑，讓色塊融入商品照片背景，不會顯得突兀。
     * 取樣方向優先選「往圖片中心方向」的那一側（例如色塊在左邊角落，就往右側取樣），
     * 因為商品照片背景通常延伸到中心附近，比色塊外側（貼近圖片邊緣）更可能還是純背景色，
     * 不會不小心取樣到商品本體的顏色。取不到樣本（極端情況）才退回白色。
     */
    private fun sampleFillColor(bmp: Bitmap, rect: Rect): Int {
        val margin = 6
        val sampleSize = 14
        data class Sample(val r: Long, val g: Long, val b: Long, val count: Long)
        val samples = mutableListOf<Sample>()
        fun sampleRegion(x0: Int, y0: Int, x1: Int, y1: Int) {
            var rSum = 0L; var gSum = 0L; var bSum = 0L; var count = 0L
            for (y in y0 until y1) {
                for (x in x0 until x1) {
                    if (x in 0 until bmp.width && y in 0 until bmp.height) {
                        val c = bmp.getPixel(x, y)
                        rSum += Color.red(c); gSum += Color.green(c); bSum += Color.blue(c)
                        count++
                    }
                }
            }
            if (count > 0) samples.add(Sample(rSum, gSum, bSum, count))
        }
        val cx = bmp.width / 2
        val cy = bmp.height / 2
        if (rect.centerX() < cx) {
            sampleRegion(rect.right + margin, rect.top, rect.right + margin + sampleSize, rect.bottom)
        } else {
            sampleRegion(rect.left - margin - sampleSize, rect.top, rect.left - margin, rect.bottom)
        }
        if (rect.centerY() < cy) {
            sampleRegion(rect.left, rect.bottom + margin, rect.right, rect.bottom + margin + sampleSize)
        } else {
            sampleRegion(rect.left, rect.top - margin - sampleSize, rect.right, rect.top - margin)
        }
        if (samples.isEmpty()) return Color.WHITE
        var rSum = 0L; var gSum = 0L; var bSum = 0L; var total = 0L
        for (s in samples) { rSum += s.r; gSum += s.g; bSum += s.b; total += s.count }
        if (total == 0L) return Color.WHITE
        return Color.rgb((rSum / total).toInt(), (gSum / total).toInt(), (bSum / total).toInt())
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
            delay(240)
        }
    }

    // ===== 分享面板「輪播縮圖下載鈕」擷取原圖（取代截圖＋裁切＋遮蓋） =====
    // 使用者手動測試發現：分享面板的圖片輪播縮圖上有下載鈕，點了會把完全乾淨（無浮水印、無介面元素）
    // 的原始商品圖存進手機相簿，畫質也比截圖再裁切好。這裡把這個操作自動化。
    // 時間參數依需求「間隔再加50%」，括號內標註原始基準值。
    private val DOWNLOAD_AFTER_CLICK_INITIAL_DELAY_MS = 2700L // 原900ms，再放慢100%
    private val DOWNLOAD_POLL_INTERVAL_MS = 900L // 原300ms，再放慢100%
    private val DOWNLOAD_POLL_TIMEOUT_MS = 6000L // 原3000ms，再放慢100%
    private val DOWNLOAD_BETWEEN_IMAGES_DELAY_MS = 1500L // 原500ms，再放慢100%
    private val DOWNLOAD_SCROLL_SETTLE_DELAY_MS = 1200L // 原400ms，再放慢100%

    /** 檢查有沒有讀取相簿的權限（Android 13+ 用 READ_MEDIA_IMAGES，以下用 READ_EXTERNAL_STORAGE）。 */
    private fun hasMediaReadPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 33) {
            android.Manifest.permission.READ_MEDIA_IMAGES
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    /** 讀分享面板裡「N/M」頁碼文字裡的總張數 M，讀不到就回傳 null（代表面板結構跟預期不同，不走這條路）。 */
    private fun findShareSheetTotalCount(root: AccessibilityNodeInfo): Int? {
        var result: Int? = null
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || result != null || depth > 20) return
            val t = node.text?.toString()?.trim()
            if (t != null) {
                val m = Regex("^(\\d+)\\s*/\\s*(\\d+)$").find(t)
                if (m != null) {
                    result = m.groupValues[2].toIntOrNull()
                    return
                }
            }
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }
        walk(root, 0)
        return result
    }

    /** 找出對應第 index 張圖片的容器節點：靠「index/total」頁碼文字節點的父層來定位（同一容器底下的兄弟關係）。
     * 重要：呼叫時 root 參數務必傳「分享面板專屬的縮圖列 scrollView」，不能傳整個畫面 root——
     * 背景商品詳情頁自己也有「X/N」頁碼文字，範圍沒限定住會抓錯到背景頁去（見 findShareSheetScrollView 的說明）。
     */
    private fun findShareSheetItemContainer(root: AccessibilityNodeInfo, index: Int, total: Int): AccessibilityNodeInfo? {
        var result: AccessibilityNodeInfo? = null
        // 這裡改用跟 findShareSheetTotalCount 一致的寬鬆正規表示式比對「index/total」頁碼文字，
        // 不再用完全相等（t == "$index/$total"）比對。根因：蝦皮App畫面若把頁碼格式從「1/15」
        // 改成「1 / 15」（斜線前後多了空格），完全相等比對會永遠比對失敗，但總張數判斷那邊本來就
        // 用寬鬆正規表示式抓，才會出現「總張數偵測成功、但每一張都找不到容器」這種矛盾現象。
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || result != null || depth > 20) return
            val t = node.text?.toString()?.trim()
            if (t != null) {
                val m = Regex("^(\\d+)\\s*/\\s*(\\d+)$").find(t)
                if (m != null &&
                    m.groupValues[1].toIntOrNull() == index &&
                    m.groupValues[2].toIntOrNull() == total
                ) {
                    result = node.parent
                    return
                }
            }
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }
        walk(root, 0)
        return result
    }

    /** 在圖片容器內找下載鈕：先找到下載圖示節點，再往上找第一個可點擊的父層（圖示本身通常不可點）。 */
    private fun findDownloadButtonInContainer(container: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var iconNode: AccessibilityNodeInfo? = null
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || iconNode != null || depth > 10) return
            if (node.viewIdResourceName?.endsWith("AN_ShareDrawer_DownloadPng_Img_1") == true) {
                iconNode = node
                return
            }
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }
        walk(container, 0)
        var p = iconNode ?: return null
        var depth = 0
        while (depth < 6) {
            if (p.isClickable) return p
            p = p.parent ?: return null
            depth++
        }
        return null
    }

    /** 找出輪播的 HorizontalScrollView 容器，供橫向滑動使用。
     * 關鍵：畫面上背景的商品詳情頁自己也有一個 HorizontalScrollView（含自己的輪播圖片與「X/N」頁碼），
     * 分享面板打開後背景頁面只是被蓋住、節點還在無障礙樹裡，兩者結構高度相似、頁碼還可能剛好數字一樣，
     * 光用「是不是 HorizontalScrollView」去找一定會抓錯（實測就是抓到背景頁那個）。
     * 這裡改成「這個 HorizontalScrollView 底下必須真的含有分享面板專屬的 AN_ShareDrawer_ 開頭 id」才算數，
     * 確保抓到的是分享面板的縮圖列，不是背景商品頁的輪播。
     */
    private fun findShareSheetScrollView(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        fun containsShareDrawerId(node: AccessibilityNodeInfo?, depth: Int): Boolean {
            if (node == null || depth > 6) return false
            val id = node.viewIdResourceName
            if (id != null && (id.endsWith("AN_ShareDrawer_ImageUrl_Img") || id.endsWith("AN_ShareDrawer_DownloadPng_Img_1"))) {
                return true
            }
            for (i in 0 until node.childCount) {
                if (containsShareDrawerId(node.getChild(i), depth + 1)) return true
            }
            return false
        }
        var result: AccessibilityNodeInfo? = null
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || result != null || depth > 20) return
            if (node.className == "android.widget.HorizontalScrollView" && containsShareDrawerId(node, 0)) {
                result = node
                return
            }
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }
        walk(root, 0)
        return result
    }

    /** 在指定範圍內由右往左滑一下，捲動輪播到下一批項目。 */
    private fun swipeHorizontalLeft(bounds: Rect) {
        val y = bounds.centerY().toFloat()
        val startX = bounds.right - bounds.width() * 0.1f
        val endX = bounds.left + bounds.width() * 0.1f
        val path = Path().apply { moveTo(startX, y); lineTo(endX, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 400))
            .build()
        dispatchGesture(gesture, null, null)
    }

    /**
     * 【階段2用】代表一個「已生成影片、還沒上架」的候選商品，掃描結果的資料結構。
     * narrationText可能是空字串（例如舊資料還沒補這個欄位、或AI/規則模板都抽取不到內容），
     * 呼叫端（撰寫內文步驟）要自己決定空字串時要不要退回用別的文字來源。
     */
    data class UploadCandidate(
        val folder: File,
        val promoLink: String,
        val narrationText: String,
        val videoFile: File,
        val productName: String,
        val price: Double
    )

    /**
     * 【階段2用】掃描 CaptionQueue 底下所有商品資料夾，找出符合上架條件的候選清單：
     * meta.json 的 videoGeneratedAt 有值（代表影片已生成）且 shopeePosted 還是 false（代表還沒上架）。
     * 這個函式不會動到任何檔案（純讀取），可以放心重複呼叫。
     *
     * 實作細節：org.json.JSONObject 對於值是 JSONObject.NULL 的欄位，optString() 讀出來
     * 會拿到字串"null"（不是空字串、也不是丟例外），這是org.json函式庫的已知怪異行為，
     * 這裡用 .takeIf { it.isNotBlank() && it != "null" } 統一過濾掉，避免誤判成「有值」。
     */
    private fun scanUploadCandidates(): List<UploadCandidate> {
        val baseDir = File(
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
            "CaptionQueue"
        )
        val candidates = mutableListOf<UploadCandidate>()
        val dirs = baseDir.listFiles()?.filter { it.isDirectory } ?: run {
            appendDebugLog("  → 掃描候選商品：找不到 CaptionQueue 資料夾（${baseDir.absolutePath}）")
            return candidates
        }
        appendDebugLog("  → 掃描候選商品：CaptionQueue 底下共 ${dirs.size} 個商品資料夾")

        for (dir in dirs) {
            val metaFile = File(dir, "meta.json")
            if (!metaFile.isFile) {
                appendDebugLog("  → 掃描候選商品：${dir.name} 沒有 meta.json，跳過")
                continue
            }
            try {
                val json = org.json.JSONObject(metaFile.readText())

                val videoGeneratedAt = json.optString("videoGeneratedAt", "")
                    .takeIf { it.isNotBlank() && it != "null" }
                if (videoGeneratedAt == null) {
                    appendDebugLog("  → 掃描候選商品：${dir.name} 影片還沒生成（videoGeneratedAt為空），跳過")
                    continue
                }

                val shopeePosted = json.optBoolean("shopeePosted", false)
                if (shopeePosted) {
                    appendDebugLog("  → 掃描候選商品：${dir.name} 已經上架過了（shopeePosted=true），跳過")
                    continue
                }

                val promoLink = json.optString("promoLink", "")
                    .takeIf { it.isNotBlank() && it != "null" }
                if (promoLink == null) {
                    appendDebugLog("  → 掃描候選商品：${dir.name} 沒有商品連結，跳過")
                    continue
                }

                val videoFile = File(dir, "output.mp4")
                if (!videoFile.isFile) {
                    appendDebugLog("  → 掃描候選商品：${dir.name} 標記已生成影片但實際找不到output.mp4，跳過")
                    continue
                }

                val narrationText = json.optString("narrationText", "")
                    .takeIf { it.isNotBlank() && it != "null" } ?: ""
                val productName = json.optString("productName", "")
                    .takeIf { it.isNotBlank() && it != "null" } ?: ""
                val price = json.optDouble("price", 0.0)

                appendDebugLog("  → 掃描候選商品：${dir.name} 符合條件，加入候選清單（文案長度=${narrationText.length}字）")
                candidates.add(UploadCandidate(dir, promoLink, narrationText, videoFile, productName, price))
            } catch (e: Exception) {
                appendDebugLog("  → 掃描候選商品：讀取 ${dir.name}/meta.json 失敗，跳過（${e.javaClass.simpleName}：${e.message}）")
            }
        }
        appendDebugLog("  → 掃描候選商品：掃描完成，共 ${candidates.size} 筆符合條件的候選商品")
        return candidates
    }

    // ===================== 階段2第3塊：上架自動化本體 =====================

    private var uploadJob: Job? = null

    fun isUploadAutomationRunning(): Boolean = uploadJob?.isActive == true

    /**
     * 啟動上架自動化。呼叫時機的前提：蝦皮App目前畫面必須已經在「分潤按讚好物」清單頁
     * （這段導航——開啟蝦皮App→切到帳戶分頁→點分潤按讚好物——目前還沒自動化，
     * 因為還沒有這幾個畫面的節點dump資料，先由使用者手動導航過去，之後有需要可以再補）。
     * maxCount：本次最多處理幾筆候選商品（獨立於蝦皮平台本身「每日50支」的上限，
     * 這裡只是呼叫端自己設定的批次大小，實際能不能發成功還是要看當下蝦皮還有沒有額度）。
     */
    fun startUploadAutomation(maxCount: Int, onEvent: (UploadEvent) -> Unit) {
        if (isUploadAutomationRunning()) return
        uploadJob = serviceScope.launch {
            try {
                uploadAutomationLoop(maxCount, onEvent)
            } catch (e: kotlinx.coroutines.CancellationException) {
                onEvent(UploadEvent.Log("已停止上架自動化"))
            } catch (e: Exception) {
                onEvent(UploadEvent.Log("發生未預期錯誤：${e.javaClass.simpleName} ${e.message}"))
            }
        }
    }

    fun stopUploadAutomation() {
        uploadJob?.cancel()
        uploadJob = null
    }

    private suspend fun uploadAutomationLoop(maxCount: Int, onEvent: (UploadEvent) -> Unit) {
        appendDebugLog("===== 開始上架自動化，本次上限 $maxCount 支 =====")
        onEvent(UploadEvent.Log("開始上架自動化，本次上限 $maxCount 支"))

        val candidates = scanUploadCandidates()
        if (candidates.isEmpty()) {
            appendDebugLog("  → 上架自動化：沒有找到任何候選商品，結束")
            onEvent(UploadEvent.Finished(0, 0, UploadFinishReason.NO_CANDIDATES))
            return
        }
        appendDebugLog("  → 上架自動化：共 ${candidates.size} 筆候選，本次最多處理 $maxCount 支")

        var successCount = 0
        var failCount = 0
        var reason = UploadFinishReason.ALL_DONE

        for ((index, candidate) in candidates.withIndex()) {
            if (successCount >= maxCount) {
                reason = UploadFinishReason.MAX_COUNT_REACHED
                break
            }
            onEvent(UploadEvent.Progress(index + 1, candidates.size))
            appendDebugLog("  → [${index + 1}/${candidates.size}] 開始處理：${candidate.folder.name}")
            onEvent(UploadEvent.Log("處理中：${candidate.folder.name}"))

            val ok = try {
                processOneUploadCandidate(candidate)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                appendDebugLog("  → [${candidate.folder.name}] 發生例外：${e.javaClass.simpleName} ${e.message}")
                false
            }

            if (ok) {
                successCount++
                markShopeePosted(candidate.folder)
                appendDebugLog("  → [${candidate.folder.name}] 上架成功，已標記 shopeePosted=true")
                onEvent(UploadEvent.Log("✓ 上架成功：${candidate.folder.name}"))
            } else {
                failCount++
                appendDebugLog("  → [${candidate.folder.name}] 上架失敗，停止本次批次（避免對同樣的錯誤/每日上限持續重試）")
                onEvent(UploadEvent.Log("✗ 上架失敗：${candidate.folder.name}，停止本次批次"))
                reason = UploadFinishReason.STOPPED_ON_FAILURE
                break
            }

            if (successCount < maxCount) {
                delay(Random.nextLong(9000, 15000))
            }
        }

        appendDebugLog("===== 上架自動化結束：成功 $successCount／失敗 $failCount，原因=$reason =====")
        onEvent(UploadEvent.Finished(successCount, failCount, reason))
    }

    /**
     * 處理一筆候選商品的完整流程：匯入連結→勾選→分享→選短影音→媒體庫選片→撰寫內文→發佈。
     * 任何一步逾時或失敗就回傳false並記錄詳細原因，不拋例外（呼叫端已包try/catch保護整批）。
     */
    private suspend fun processOneUploadCandidate(candidate: UploadCandidate): Boolean {
        // 0. 確認目前在「分潤按讚好物」清單畫面
        // 注意：不能只比對「分潤按讚好物」這幾個字，因為蝦皮首頁本身也有一個同名的功能入口
        // （點進去才是清單頁），單純比對文字會把首頁誤判成已經在清單畫面。改用actionbar標題
        // 節點比對，清單頁標題格式固定是「分潤按讚好物(數字)」帶括號，首頁選單項目不會是這個格式。
        var root = rootInActiveWindow ?: run {
            appendDebugLog("  → 讀不到目前畫面"); return false
        }
        val titleNode = findNodeByIdSuffix(root, "labelActionBarTitle")
        val titleText = titleNode?.text?.toString() ?: findTextContaining(root, "分潤按讚好物(")
        if (titleText == null || !titleText.contains("分潤按讚好物(")) {
            appendDebugLog("  → 目前畫面不是「分潤按讚好物」清單（畫面標題=${titleText ?: "讀不到"}），請先手動導航到這個畫面再啟動")
            return false
        }

        // 1. 點「+」→「從匯入連結新增」
        val addIconNode = findFirstNodeById(root, "AN_SellerInvitedOfferPage_AddIcon_Img")
        if (addIconNode == null || !clickNodeBestEffort(addIconNode)) {
            appendDebugLog("  → 找不到或點擊「+」按鈕失敗"); return false
        }
        if (!waitForAnyText(listOf("從匯入連結新增"), 3000)) {
            appendDebugLog("  → 等不到「從匯入連結新增」選單"); return false
        }
        delay(1200)
        val importMenuNode = findNodeByTexts(rootInActiveWindow ?: return false, listOf("從匯入連結新增"))
        if (importMenuNode == null || !clickNodeBestEffort(importMenuNode)) {
            appendDebugLog("  → 點擊「從匯入連結新增」失敗"); return false
        }

        // 2. 貼上商品連結
        if (!waitForAnyText(listOf("商品連結"), 3000)) {
            appendDebugLog("  → 等不到「商品連結」輸入畫面"); return false
        }
        delay(1200)
        root = rootInActiveWindow ?: return false
        val linkInput = findSearchBoxNode(root)
        if (linkInput == null) {
            appendDebugLog("  → 找不到商品連結輸入框"); return false
        }
        val linkBundle = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, candidate.promoLink)
        }
        linkInput.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, linkBundle)
        delay(1200)
        // 填完文字後鍵盤還開著、輸入框還focus著，「新增至按讚好物」按鈕不會被觸發／可能被鍵盤蓋住，
        // 要主動清除焦點+收起鍵盤，模擬使用者「點輸入框外面一下」的動作，畫面才會切到確認狀態。
        linkInput.performAction(AccessibilityNodeInfo.ACTION_CLEAR_FOCUS)
        delay(450)
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
        imm?.hideSoftInputFromWindow(null, 0)
        delay(1050)
        // 保險再點一次畫面上方的「商品連結」標籤文字，確保焦點確實離開輸入框
        root = rootInActiveWindow ?: return false
        findNodeByTexts(root, listOf("商品連結"))?.let { clickNodeBestEffort(it) }
        delay(1050)

        // 3. 點「新增至按讚好物」
        root = rootInActiveWindow ?: return false
        val addToListButton = findNodeByTexts(root, listOf("新增至按讚好物"))
        if (addToListButton == null || !clickNodeBestEffort(addToListButton)) {
            appendDebugLog("  → 找不到或點擊「新增至按讚好物」失敗"); return false
        }
        delay(3750)

        // 4. 回到清單，勾選第一筆（排序：最新，剛新增的理論上會排在最上面）
        // 「新增至按讚好物」是打後端API，清單重新排序可能有延遲，等待時間要留寬一點，
        // 不然抓到的可能還是舊排序、選到別的商品——這是之前實測發現商品對不上的根因，
        // 所以特別拉長這裡的等待，並把選到的商品文字記進log，方便之後直接從log驗證選對了沒，
        // 不用等整個流程跑完才能靠肉眼確認。
        if (!waitForAnyText(listOf("分潤按讚好物"), 4000)) {
            appendDebugLog("  → 新增連結後等不到回到清單畫面"); return false
        }
        delay(5250)
        root = rootInActiveWindow ?: return false
        val firstCheckbox = findFirstNodeById(root, "AN_Checkbox_CheckedIconUnCheckIcon_Img")
        if (firstCheckbox == null) {
            appendDebugLog("  → 找不到清單第一筆的勾選框"); return false
        }
        // 記錄這一列的文字內容（商品名稱等），讓debug log能驗證選到的是不是剛匯入的那筆
        run {
            var rowNode: AccessibilityNodeInfo? = firstCheckbox
            var d = 0
            while (rowNode?.parent != null && d < 5) { rowNode = rowNode.parent; d++ }
            val rowTexts = mutableListOf<String>()
            rowNode?.let { collectTextNodes(it, rowTexts, maxDepth = 8, maxNodes = 15) }
            appendDebugLog("  → 清單第一筆內容（用來核對是否為剛匯入的商品，本次匯入連結=${candidate.promoLink}）：${rowTexts.joinToString(" | ")}")
        }
        if (!clickNodeBestEffort(firstCheckbox)) {
            appendDebugLog("  → 點擊清單第一筆的勾選框失敗"); return false
        }
        delay(1800)

        // 5. 點「分享」
        root = rootInActiveWindow ?: return false
        val shareTextNode = root.findAccessibilityNodeInfosByText("分享").firstOrNull { it.text?.toString() == "分享" }
        val shareButton = shareTextNode?.let { node ->
            if (node.isClickable) node else {
                var p = node.parent; var d = 0; var c: AccessibilityNodeInfo? = null
                while (p != null && d < 6) { if (p.isClickable) { c = p; break }; p = p.parent; d++ }
                c
            }
        }
        if (shareButton == null || !clickNodeBestEffort(shareButton)) {
            appendDebugLog("  → 找不到或點擊「分享」按鈕失敗"); return false
        }

        // 6. 等分享面板出現「蝦皮短影音」選項（等不到最常見原因：已達每日上架上限或商品已分享過）
        if (!waitForAnyText(listOf("蝦皮短影音"), 4000)) {
            appendDebugLog("  → 分享面板沒有出現「蝦皮短影音」選項，可能已達每日上架上限或其他限制")
            return false
        }
        root = rootInActiveWindow ?: return false
        val shortVideoOption = findNodeByTexts(root, listOf("蝦皮短影音"))
        if (shortVideoOption == null || !clickNodeBestEffort(shortVideoOption)) {
            appendDebugLog("  → 點擊「蝦皮短影音」失敗"); return false
        }

        // 7. 趁畫面切換的空檔，把影片登記進媒體庫，確保等一下的選片畫面找得到
        delay(3000)
        registerVideoInMediaStore(candidate.videoFile)
        delay(1500)

        // 8. 點「媒體庫」
        root = rootInActiveWindow ?: run { appendDebugLog("  → 等不到短影音錄影頁"); return false }
        val galleryEntrance = findNodeByIdSuffix(root, "ll_gallery_entrance") ?: findNodeByTexts(root, listOf("媒體庫"))
        if (galleryEntrance == null || !clickNodeBestEffort(galleryEntrance)) {
            appendDebugLog("  → 找不到或點擊「媒體庫」入口失敗"); return false
        }

        // 9. 等媒體庫畫面出現，切到「短影音」分頁，選第一個（剛登記進媒體庫的最新影片會排最前面）
        if (!waitForAnyText(listOf("相片集"), 4000)) {
            appendDebugLog("  → 等不到媒體庫選片畫面"); return false
        }
        delay(1500)
        root = rootInActiveWindow ?: return false
        val videoTab = root.findAccessibilityNodeInfosByText("短影音").firstOrNull { it.isClickable }
        if (videoTab != null) {
            clickNodeBestEffort(videoTab)
            delay(1800)
        }
        root = rootInActiveWindow ?: return false
        val firstGalleryItem = findNodeByIdSuffix(root, "ll_check")
        if (firstGalleryItem == null || !clickNodeBestEffort(firstGalleryItem)) {
            appendDebugLog("  → 找不到或點擊媒體庫第一個項目失敗"); return false
        }
        delay(1500)

        // 10. 點「下一步」
        root = rootInActiveWindow ?: return false
        // 「下一步」按鈕的id會隨選取狀態改變：還沒選任何項目時是tv_pick_next，
        // 選了項目之後畫面版型會變（按鈕移到上方、顯示已選數量），id變成tv_pick_top_next。
        // 這裡已經選好1個項目了，正常應該找tv_pick_top_next，但兩個都試一次比較保險。
        val nextButton = findNodeByIdSuffix(root, "tv_pick_top_next") ?: findNodeByIdSuffix(root, "tv_pick_next")
        if (nextButton == null || !clickNodeBestEffort(nextButton)) {
            appendDebugLog("  → 找不到或點擊「下一步」失敗"); return false
        }

        // 10.5 選片後蝦皮會先進到一個「影片編輯預覽」畫面（剪輯／文字／貼紙／配音／音效），
        // 這個畫面有自己獨立的「下一步」按鈕，要點過這關才會進到撰寫內文畫面——
        // 之前漏掉這一關，才會一直卡在「等不到撰寫內文畫面」。
        if (waitForAnyText(listOf("剪輯", "配音", "音效"), 5000)) {
            delay(2250)
            root = rootInActiveWindow ?: return false
            val editorNextButton = findNodeByTexts(root, listOf("下一步"))
            if (editorNextButton != null) {
                clickNodeBestEffort(editorNextButton)
                appendDebugLog("  → 影片編輯預覽畫面：已點擊下一步")
                delay(1500)
            } else {
                appendDebugLog("  → 影片編輯預覽畫面：找不到「下一步」按鈕，嘗試繼續往下走")
            }
        } else {
            appendDebugLog("  → 沒看到影片編輯預覽畫面，可能版面不同或已跳過，嘗試繼續往下走")
        }

        // 11. 等「撰寫內文」畫面，填入文案
        if (!waitForAnyText(listOf("撰寫內文", "為您的短影音撰寫內文"), 5000)) {
            appendDebugLog("  → 等不到「撰寫內文」畫面"); return false
        }
        delay(1700)
        root = rootInActiveWindow ?: return false
        val captionInput = findNodeByIdSuffix(root, "et_caption")
        if (captionInput == null) {
            appendDebugLog("  → 找不到文案輸入框"); return false
        }
        val shortVideoCaption = buildShortVideoCaption(candidate)
        appendDebugLog("  → 撰寫內文：套用黃金3秒/痛點/導購格式文案（長度=${shortVideoCaption.length}字）")
        run {
            val captionBundle = android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, shortVideoCaption)
            }
            captionInput.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, captionBundle)
            delay(1700)
            captionInput.performAction(AccessibilityNodeInfo.ACTION_CLEAR_FOCUS)
            val imm2 = getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            imm2?.hideSoftInputFromWindow(null, 0)
            delay(1190)
            // 填完文案後，畫面有時會卡在「文字選取模式」（螢幕上出現綠色選取控點、背景變暗），
            // 這是ACTION_CLEAR_FOCUS在部分機型上被系統誤判成長按選取文字，沒有真的跳出來，
            // 導致後面點開關／發佈其實都點在這層看不見的選取狀態上、完全沒反應。
            // 比照之前處理「貼連結」畫面的做法，額外點一下畫面上安全的文字區塊，確保真的跳出選取模式。
            rootInActiveWindow?.let { r ->
                val safeAnchor = findNodeByTexts(r, listOf("新增商品"))
                if (safeAnchor != null) {
                    clickNodeBestEffort(safeAnchor)
                    appendDebugLog("  → 已點擊「新增商品」標題，確保跳出文字選取模式")
                } else {
                    // 找不到就退回對螢幕上方偏空白處點一下（大約在商品卡上方的空白區域）
                    tapAtScreenRatio(0.5f, 0.62f)
                    appendDebugLog("  → 找不到安全錨點文字，改用座標點擊跳出文字選取模式")
                }
            }
            delay(1190)
        }

        // 12. 確認商品卡是否自動帶入（只記錄不當失敗條件，避免因為判斷誤差擋住整個流程）
        root = rootInActiveWindow ?: return false
        val productCardPresent = findNodeByIdSuffix(root, "rl_product_item") != null
        appendDebugLog("  → 撰寫內文畫面：商品卡${if (productCardPresent) "已自動帶入" else "沒看到（請留意，可能要手動補加）"}")

        // 13. 調整三個開關：關閉「允許他人合拍」「允許他人拼接」、開啟「AI生成影片標記」。
        // 實測確認方法1（對文字標籤節點下ACTION_CLICK）完全無效——這幾個開關是蝦皮自訂繪製元件，
        // 無障礙樹裡真的沒有暴露對應的可點擊節點。改用方法2：對開關實際所在的螢幕座標直接tap。
        // Y座標從文字標籤節點的bounds動態算（不會跑掉），X座標用「螢幕寬度的比例」
        // （固定在畫面右側同一個相對位置，比例在不同解析度手機上比寫死像素準）。
        root = rootInActiveWindow ?: return false
        findNodeByIdSuffix(root, "tv_allow_duet")?.let {
            tapToggleNearLabel(it)
            appendDebugLog("  → 已點擊「允許他人合拍」開關（座標點擊法）")
        }
        delay(1190)
        root = rootInActiveWindow ?: return false
        findNodeByIdSuffix(root, "tv_allow_stitch")?.let {
            tapToggleNearLabel(it)
            appendDebugLog("  → 已點擊「允許他人拼接」開關（座標點擊法）")
        }
        delay(1190)
        root = rootInActiveWindow ?: return false
        findNodeByIdSuffix(root, "tv_ai_generated_title")?.let { titleNode ->
            // 實測校正發現：這顆開關的垂直位置不是對齊標題那一行，而是對齊「標題+底下
            // 說明文字」整塊區域的中點（說明文字很長，開關視覺上對齊在偏中間、偏下的位置）。
            // 用標題node跟說明node合併起來的bounds算中點，比只用標題一行準確很多。
            val descNode = findNodeByIdSuffix(root, "tv_ai_generated_desc")
            if (descNode != null) {
                val titleBounds = Rect().also { titleNode.getBoundsInScreen(it) }
                val descBounds = Rect().also { descNode.getBoundsInScreen(it) }
                val combinedTop = titleBounds.top
                val combinedBottom = descBounds.bottom
                val metrics = resources.displayMetrics
                val x = metrics.widthPixels * 0.921f
                val y = ((combinedTop + combinedBottom) / 2).toFloat()
                val path = Path().apply { moveTo(x, y) }
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, 150))
                    .build()
                dispatchGesture(gesture, null, null)
            } else {
                tapToggleNearLabel(titleNode)
            }
            appendDebugLog("  → 已點擊「AI生成影片標記」開關（座標點擊法，標題+說明文字合併中點）")
        }
        delay(1700)

        // 14. 點「發佈」
        root = rootInActiveWindow ?: return false
        val postButton = findNodeByIdSuffix(root, "btn_post")
        if (postButton == null || !clickNodeBestEffort(postButton)) {
            appendDebugLog("  → 找不到或點擊「發佈」失敗"); return false
        }

        // 15. 判定成功的依據：按下發佈後，畫面上不再有文案輸入框（代表已經離開撰寫內文畫面）
        delay(5100)
        val stillOnCaptionScreen = rootInActiveWindow?.let { findNodeByIdSuffix(it, "et_caption") } != null
        if (stillOnCaptionScreen) {
            appendDebugLog("  → 按下發佈後仍停在撰寫內文畫面，判定失敗（可能跳出錯誤提示或達到每日上限）")
            return false
        }

        return true
    }

    /**
     * 在整個節點樹裡找「文字包含指定子字串」的第一個節點文字內容（部分比對，
     * 不是完全相等）。用在需要比對「文字開頭固定、後面帶動態數字」的情況，
     * 例如清單畫面標題「分潤按讚好物(1000)」這種帶括號數字的格式。
     */
    private fun findTextContaining(root: AccessibilityNodeInfo, substring: String): String? {
        var found: String? = null
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || found != null || depth > 30) return
            val text = node.text?.toString()
            if (text != null && text.contains(substring)) {
                found = text
                return
            }
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }
        walk(root, 0)
        return found
    }

    /**
     * 依照「黃金前3秒抓眼球→中間一句痛點共鳴」公式，組出蝦皮短影音「撰寫內文」要填的文案，
     * 控制在150字元上限內（畫面實測有這個限制）。不放價格跟連結——商品卡本身已經帶價格，
     * 連結蝦皮平台也會自動附上，文字裡重複寫反而占用字數。結尾直接接5個#標籤
     * （蝦皮這幾顆開關旁的「# 標籤」是獨立輸入按鈕，目前還沒有那個畫面的節點資訊，
     * 先用「內文文字裡直接打#標籤」這個大部分短影音平台都通用的做法達到同樣效果）。
     * narrationText是空字串時退回用商品名稱組最基本的一句，避免內文整個空白。
     */
    private fun buildShortVideoCaption(candidate: UploadCandidate): String {
        val maxLength = 150
        val sentences = candidate.narrationText
            .split("。")
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val hook = sentences.firstOrNull()
            ?: candidate.productName.ifBlank { "這個好物" }
        val middle = sentences.getOrNull(1)

        val tags = buildHashtags(candidate)
        val tagsLine = tags.joinToString(" ") { "#$it" }

        // 先組「鉤子＋標籤」這個一定要保留的核心部分，字數還有剩才加中間痛點句
        val core = "$hook！\n$tagsLine"
        val withMiddle = if (!middle.isNullOrBlank()) "$hook！$middle。\n$tagsLine" else core

        val result = if (withMiddle.length <= maxLength) withMiddle else core
        return if (result.length <= maxLength) result else result.take(maxLength)
    }

    /**
     * 生成5個標籤：優先取商品名稱裡看起來像品牌／品項的片段（用空白/常見分隔符切開，
     * 取前面幾段有意義的中文詞），不夠5個的話用固定的蝦皮分潤短影音常用標籤補滿。
     */
    private fun buildHashtags(candidate: UploadCandidate): List<String> {
        val fromName = candidate.productName
            .split(" ", "　", "-", "/")
            .map { it.trim() }
            .filter { it.length in 2..8 }
            .take(2)

        val genericPool = listOf("蝦皮好物", "分潤推薦", "開箱推薦", "生活好物", "必買推薦", "好物分享")
        val tags = mutableListOf<String>()
        tags.addAll(fromName)
        for (tag in genericPool) {
            if (tags.size >= 5) break
            if (!tags.contains(tag)) tags.add(tag)
        }
        return tags.take(5)
    }

    /**
     * 依 viewIdResourceName「結尾」比對找節點——蝦皮不同畫面套件名稱前綴不一樣
     * （例如 com.shopee.tw:id/xxx 跟 com.shopee.tw.dfpluginshopee16:id/xxx），
     * 只比對冒號後面那段id本身。回傳畫面上第一個符合的節點（依樹狀結構的文件順序）。
     */
    private fun findNodeByIdSuffix(root: AccessibilityNodeInfo, idSuffix: String): AccessibilityNodeInfo? {
        var found: AccessibilityNodeInfo? = null
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || found != null || depth > 30) return
            val id = node.viewIdResourceName
            if (id != null && id.substringAfter(":id/", id) == idSuffix) {
                found = node
                return
            }
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }
        walk(root, 0)
        return found
    }

    /**
     * 依完整 viewIdResourceName 找第一個符合的節點（用於蝦皮自訂的語意id，例如AN_開頭這種
     * 沒有套件名稱前綴的id）。回傳畫面上第一個符合的節點，依文件順序——
     * 例如清單裡每一列商品都有一個同id的勾選框，用這個函式會拿到「最上面那一列」的。
     */
    private fun findFirstNodeById(root: AccessibilityNodeInfo, id: String): AccessibilityNodeInfo? {
        var found: AccessibilityNodeInfo? = null
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || found != null || depth > 30) return
            if (node.viewIdResourceName == id) { found = node; return }
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }
        walk(root, 0)
        return found
    }

    /**
     * 點擊節點：不管 isClickable 回報是不是true都直接嘗試ACTION_CLICK
     * （Compose UI常見isClickable回報不準的狀況，findNodeByTexts也是同樣的處理方式），
     * 失敗的話再往上找最多6層可點擊的祖先節點重試一次。
     */
    private fun clickNodeBestEffort(node: AccessibilityNodeInfo): Boolean {
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        var parent = node.parent
        var depth = 0
        while (parent != null && depth < 6) {
            if (parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            parent = parent.parent
            depth++
        }
        return false
    }

    /**
     * 標記某個商品資料夾已經上架成功：寫入 shopeePosted=true 跟時間戳記，
     * 讓下次 scanUploadCandidates() 掃描時自動跳過這筆，避免重複上架同一支影片。
     */
    private fun markShopeePosted(folder: File) {
        try {
            val metaFile = File(folder, "meta.json")
            if (!metaFile.isFile) return
            val json = org.json.JSONObject(metaFile.readText())
            json.put("shopeePosted", true)
            json.put("shopeePostedAt", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date()))
            metaFile.writeText(json.toString(2))
        } catch (e: Exception) {
            appendDebugLog("  → 標記shopeePosted失敗（${folder.name}）：${e.javaClass.simpleName} ${e.message}")
        }
    }

    /**
     * 【階段2測試用】暫時借用「偵測」按鈕觸發：只處理1筆候選商品，方便小範圍實測整套流程。
     * 確認流程穩定、開關調整也驗證有效後，之後可以在MainActivity.kt加專屬的「開始上架」按鈕
     * 跟數量設定，取代這個暫時的測試掛鉤，跟其他test開頭的函式一起移除。
     */
    /**
     * 【座標校正用】把使用者在校正模式下點擊的螢幕座標記進debug log，
     * 供FloatingButtonService的校正覆蓋層呼叫。用來精準測量像撰寫內文畫面那三個開關
     * 這種無障礙節點樹完全抓不到的自訂元件，實際落在螢幕上的哪個位置。
     */
    fun logCalibrationTap(x: Float, y: Float) {
        val metrics = resources.displayMetrics
        val xRatio = x / metrics.widthPixels
        val yRatio = y / metrics.heightPixels
        appendDebugLog("  → 【座標校正】點擊位置 X=%.1f Y=%.1f（螢幕解析度 %dx%d，比例 X=%.4f Y=%.4f）".format(
            x, y, metrics.widthPixels, metrics.heightPixels, xRatio, yRatio
        ))
    }

    fun testUploadAutomation() {
        if (isUploadAutomationRunning()) {
            appendDebugLog("  → 【上架自動化測試】已經在執行中，本次觸發略過")
            return
        }
        startUploadAutomation(1) { event ->
            when (event) {
                is UploadEvent.Log -> appendDebugLog("  → 【上架自動化測試】${event.message}")
                is UploadEvent.Progress -> appendDebugLog("  → 【上架自動化測試】進度 ${event.current}/${event.total}")
                is UploadEvent.Finished -> appendDebugLog(
                    "  → 【上架自動化測試】結束：成功${event.successCount}／失敗${event.failCount}，原因=${event.reason}"
                )
            }
        }
    }

    /**
     * 【階段2開發用診斷工具】暫時借用「偵測」按鈕觸發：把當下畫面完整的無障礙節點樹
     * dump成一份文字檔，存到 Download/NodeTreeDump/dump_<時間戳記>.txt，用來確認
     * 「分潤按讚好物清單→匯入連結→勾選→分享→短影音錄製→撰寫內文」這幾個畫面的
     * 節點id/文字/可點擊層級，寫第3塊自動化邏輯前先靠這個看清楚，不要用猜的
     * （教訓見fix44~50）。每次呼叫只dump「當下這一個畫面」，所以要在流程走到
     * 每一個關鍵畫面時各按一次「偵測」分別記錄。
     * 這是暫時開發工具，等第3塊流程寫完可以跟FloatingButtonService.kt裡的呼叫一起移除。
     */
    fun dumpCurrentNodeTree() {
        val root = rootInActiveWindow
        if (root == null) {
            appendDebugLog("  → 【節點樹診斷】rootInActiveWindow 是 null，抓不到目前畫面")
            return
        }
        val sb = StringBuilder()
        val packageName = root.packageName?.toString() ?: "(未知)"
        sb.appendLine("===== 節點樹 dump：${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())} =====")
        sb.appendLine("目前畫面套件名稱：$packageName")
        sb.appendLine()
        var nodeCount = 0
        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            nodeCount++
            if (nodeCount > 3000) return // 保險上限，避免極端情況dump過大
            val indent = "  ".repeat(depth)
            val id = node.viewIdResourceName ?: ""
            val text = node.text?.toString()?.replace("\n", "\\n") ?: ""
            val desc = node.contentDescription?.toString()?.replace("\n", "\\n") ?: ""
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            val flags = buildString {
                if (node.isClickable) append("[可點擊]")
                if (node.isCheckable) append("[可勾選:${node.isChecked}]")
                if (node.isEditable) append("[可輸入]")
                if (!node.isVisibleToUser) append("[不可見]")
            }
            sb.appendLine("$indent${node.className}${if (id.isNotBlank()) " id=$id" else ""}${if (text.isNotBlank()) " text=\"$text\"" else ""}${if (desc.isNotBlank()) " desc=\"$desc\"" else ""} $flags bounds=$bounds")
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { walk(it, depth + 1) }
            }
        }
        walk(root, 0)
        sb.appendLine()
        sb.appendLine("===== 共 $nodeCount 個節點 =====")

        try {
            val dumpDir = File(
                android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                "NodeTreeDump"
            )
            if (!dumpDir.exists()) dumpDir.mkdirs()
            val fileName = "dump_${SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())}.txt"
            val dumpFile = File(dumpDir, fileName)
            dumpFile.writeText(sb.toString())
            appendDebugLog("  → 【節點樹診斷】已存檔：NodeTreeDump/$fileName（畫面=$packageName，共 $nodeCount 個節點）")
        } catch (e: Exception) {
            appendDebugLog("  → 【節點樹診斷】存檔失敗：${e.javaClass.simpleName}：${e.message}")
        }
    }

    /**
     * 【階段2測試用】暫時借用「偵測」按鈕觸發這個測試：掃描候選商品清單，
     * 把找到的每一筆（資料夾名稱/連結/文案長度）印進debug log，方便確認掃描邏輯抓得對不對。
     * 跟testMediaStoreRegistration()一樣，等階段2整個上架流程做完、這個函式跟
     * FloatingButtonService.kt裡暫時加的呼叫都可以一起移除，不是正式功能的一部分。
     */
    fun testScanUploadCandidates() {
        val candidates = scanUploadCandidates()
        appendDebugLog("  → 【候選商品掃描測試】共找到 ${candidates.size} 筆待上架商品")
        candidates.forEach { c ->
            appendDebugLog("     - ${c.folder.name}｜連結=${c.promoLink}｜文案長度=${c.narrationText.length}字")
        }
    }

    /**
     * 【階段2測試用】暫時借用「偵測」按鈕觸發這個測試：
     * 找CaptionQueue底下最新一個已經有output.mp4的商品資料夾，測試registerVideoInMediaStore()
     * 能不能成功把它登記進媒體庫，結果寫進debug log。等階段2整個上架流程做完、
     * 這個函式跟FloatingButtonService.kt裡暫時加的呼叫都可以一起移除，不是正式功能的一部分。
     */
    fun testMediaStoreRegistration() {
        serviceScope.launch {
            val baseDir = File(
                android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                "CaptionQueue"
            )
            val testVideo = baseDir.listFiles()
                ?.filter { it.isDirectory }
                ?.mapNotNull { dir -> File(dir, "output.mp4").takeIf { it.isFile } }
                ?.maxByOrNull { it.lastModified() }
            if (testVideo == null) {
                appendDebugLog("  → 【媒體庫測試】找不到任何 output.mp4 可供測試（CaptionQueue底下沒有已生成的影片）")
                return@launch
            }
            appendDebugLog("  → 【媒體庫測試】開始測試：${testVideo.absolutePath}")
            registerVideoInMediaStore(testVideo)
        }
    }

    /**
     * 【階段2用】把已經寫入公開儲存空間的影片檔案「登記」進系統媒體庫(MediaStore)。
     * 背景：擷取器寫檔案是直接用File API寫進Downloads資料夾，不是透過MediaStore.insert()，
     * 這種寫法系統相簿/媒體庫選片器（包括蝦皮App的「媒體庫」選片畫面）預設不會自動看到這個檔案
     * ——Android的媒體庫索引是靠MediaStore資料庫，不是單純掃資料夾內容。
     * 用MediaScannerConnection主動觸發掃描這一個檔案，掃完系統才會把它加進MediaStore資料庫，
     * 之後蝦皮的媒體庫選片畫面才找得到這支影片。
     * 回傳掃描完成後系統給的content Uri（掃描失敗或逾時回傳null，不影響其他流程繼續執行）。
     */
    /**
     * 把影片登記進媒體庫，並確保它在蝦皮「短影音」選片畫面（照最新排序）真的排在最前面。
     * 關鍵：如果MediaStore裡已經有這個檔案的紀錄（例如之前擷取/測試時就登記過），
     * 單純呼叫MediaScannerConnection.scanFile()只會確認/回傳既有的Uri，
     * 不會更新DATE_ADDED這個排序用的時間欄位——這是候選商品的影片明明剛登記過，
     * 選片畫面卻選到別支（時間更新的其他候選）影片的根本原因。
     * 修法：先查有沒有既有紀錄，有的話直接把DATE_ADDED／DATE_MODIFIED強制更新成現在；
     * 沒有的話才走原本MediaScannerConnection首次掃描的路徑（首次掃描本來就會是最新時間）。
     */
    private suspend fun registerVideoInMediaStore(videoFile: File): Uri? {
        if (!videoFile.exists()) {
            appendDebugLog("  → 影片登記進媒體庫失敗：檔案不存在（${videoFile.absolutePath}）")
            return null
        }
        try { videoFile.setLastModified(System.currentTimeMillis()) } catch (_: Exception) { /* 部分機型/儲存權限下可能不允許，忽略即可 */ }

        val nowSeconds = System.currentTimeMillis() / 1000
        try {
            val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(MediaStore.Video.Media._ID)
            val selection = "${MediaStore.Video.Media.DATA} = ?"
            val selectionArgs = arrayOf(videoFile.absolutePath)
            val existingUri: Uri? = contentResolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID))
                    ContentUris.withAppendedId(collection, id)
                } else null
            }
            if (existingUri != null) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DATE_ADDED, nowSeconds)
                    put(MediaStore.Video.Media.DATE_MODIFIED, nowSeconds)
                }
                val updated = contentResolver.update(existingUri, values, null, null)
                appendDebugLog("  → 影片已存在媒體庫，強制更新時間戳記讓它排到最新（更新筆數=$updated）：${videoFile.name}")
                return existingUri
            }
        } catch (e: Exception) {
            appendDebugLog("  → 查詢/更新媒體庫既有紀錄時發生例外：${e.javaClass.simpleName} ${e.message}")
        }

        val result = withTimeoutOrNull(8000) {
            suspendCancellableCoroutine<Uri?> { continuation ->
                try {
                    MediaScannerConnection.scanFile(
                        applicationContext,
                        arrayOf(videoFile.absolutePath),
                        arrayOf("video/mp4")
                    ) { _, uri ->
                        if (continuation.isActive) continuation.resume(uri)
                    }
                } catch (e: Exception) {
                    appendDebugLog("  → 影片登記進媒體庫時發生例外：${e.javaClass.simpleName} ${e.message}")
                    if (continuation.isActive) continuation.resume(null)
                }
            }
        }
        if (result == null) {
            appendDebugLog("  → 影片登記進媒體庫：逾時或失敗（${videoFile.name}）")
        } else {
            appendDebugLog("  → 影片登記進媒體庫成功：${videoFile.name} -> $result")
        }
        return result
    }

    /** 查詢相簿裡「指定時間之後」新增的最新一張圖片，回傳它的 content Uri（讀不到就回傳 null）。 */
    private fun queryLatestImageUriAfter(afterTimeMs: Long): Uri? {
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED)
        val selection = "${MediaStore.Images.Media.DATE_ADDED} >= ?"
        // MediaStore 的 DATE_ADDED 是「秒」為單位，click 時間是毫秒，這裡要換算
        val selectionArgs = arrayOf((afterTimeMs / 1000).toString())
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        return try {
            contentResolver.query(collection, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                    ContentUris.withAppendedId(collection, id)
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 等待相簿出現指定時間之後新增的圖片，最多等 DOWNLOAD_POLL_TIMEOUT_MS，逾時回傳 null。 */
    private suspend fun waitForNewImageUri(afterTimeMs: Long): Uri? {
        val start = System.currentTimeMillis()
        delay(DOWNLOAD_AFTER_CLICK_INITIAL_DELAY_MS)
        while (System.currentTimeMillis() - start < DOWNLOAD_POLL_TIMEOUT_MS) {
            val uri = queryLatestImageUriAfter(afterTimeMs)
            if (uri != null) return uri
            delay(DOWNLOAD_POLL_INTERVAL_MS)
        }
        return null
    }

    private fun loadBitmapFromUri(uri: Uri): Bitmap? = try {
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
    } catch (e: Exception) {
        null
    }

    /**
     * 透過分享面板的輪播縮圖下載鈕，逐張擷取乾淨原圖（取代截圖＋裁切＋遮蓋）。
     * 沒有讀取相簿權限、或面板結構跟預期不同（找不到「N/M」頁碼、找不到下載鈕）就回傳 null，
     * 由呼叫端自動退回原本的截圖版本，不影響既有流程穩定性。
     */
    private suspend fun captureGalleryImagesViaDownload(sheetRoot: AccessibilityNodeInfo): List<Bitmap>? {
        if (!hasMediaReadPermission()) {
            appendDebugLog("  → 下載式圖片擷取：沒有讀取相簿權限，跳過（改用截圖版本）")
            return null
        }
        // 關鍵修正：total 有兩種用途，之前混用同一個變數導致 bug——
        // (1) 跟畫面上「X/N」頁碼文字比對時，N 必須是「真實總張數」（例如商品實際有25張，
        //     畫面就會顯示 1/25、2/25...25/25），這個數字絕對不能被砍掉，砍了就永遠比對不上真實畫面文字；
        // (2) 「實際要下載幾張」這件事才需要上限（避免商品圖片太多跑太久），這個才是可以砍到15的地方。
        // 先前把這兩件事混在同一個變數裡（total = 真實總數.coerceAtMost(15)），
        // 導致比對分母被錯砍成15，但畫面文字分母永遠是真實總數（例如25），兩者對不上、每張都找不到容器。
        val rawTotal = findShareSheetTotalCount(sheetRoot)
        if (rawTotal == null || rawTotal <= 0) {
            appendDebugLog("  → 下載式圖片擷取：找不到分享面板的「N/M」頁碼，跳過（改用截圖版本）")
            return null
        }
        val downloadCount = rawTotal.coerceAtMost(10)
        appendDebugLog("  → 下載式圖片擷取：偵測到共 $rawTotal 張（實際下載前 $downloadCount 張），開始逐張點下載鈕")
        val screenWidth = resources.displayMetrics.widthPixels
        val images = mutableListOf<Bitmap>()
        // 每次都重新抓「當下畫面」再重找 scrollView，避免沿用舊 root/舊節點引用（可能已經過期）。
        // 關鍵修正：容器搜尋範圍務必限定在分享面板專屬的 scrollView 底下，不能搜整個畫面 root——
        // 背景商品詳情頁自己也有輪播跟「X/N」頁碼，範圍沒限定住會抓錯到背景頁那組完全不相干的節點。
        fun currentScrollView(): AccessibilityNodeInfo? =
            findShareSheetScrollView(rootInActiveWindow ?: sheetRoot)
        fun currentItemContainer(idx: Int): AccessibilityNodeInfo? =
            currentScrollView()?.let { findShareSheetItemContainer(it, idx, rawTotal) }

        for (index in 1..downloadCount) {
            var container = currentItemContainer(index)
            var scrollAttempts = 0
            // 抓到容器後，不代表整個容器已經完全滑進畫面可視範圍——之前誤抓到背景頁全螢幕節點時，
            // 這個檢查形同虛設（背景頁節點永遠「完全可見」），範圍修正後這裡才會真的發揮作用。
            fun isFullyVisible(node: AccessibilityNodeInfo): Boolean {
                val b = Rect()
                node.getBoundsInScreen(b)
                return b.left >= 0 && b.right <= screenWidth
            }
            while ((container == null || !isFullyVisible(container)) && scrollAttempts < downloadCount + 3) {
                val sv = currentScrollView() ?: break
                val svBounds = Rect()
                sv.getBoundsInScreen(svBounds)
                swipeHorizontalLeft(svBounds)
                delay(DOWNLOAD_SCROLL_SETTLE_DELAY_MS)
                container = currentItemContainer(index)
                scrollAttempts++
                // 已經滑到不能再滑（畫面沒有再變化的跡象時，容器仍抓得到但沒完全可見也只能將就用），
                // 這裡沒有偵測「滑到底」的機制，用總嘗試次數上限（total+3）避免無限迴圈卡住。
            }
            if (container == null) {
                appendDebugLog("  → 下載式圖片擷取：第 $index 張滑動多次仍找不到容器，放棄這張（已嘗試滑動 $scrollAttempts 次）")
                // 【診斷用】萬一頁碼正規表示式修正後這裡還會發生，把分享面板scrollView底下的
                // 文字節點都印出來，直接看實際頁碼文字長什麼樣子，不用再靠螢幕截圖來回確認
                val sv = currentScrollView()
                if (sv != null) {
                    val lines = mutableListOf<String>()
                    fun walk(node: AccessibilityNodeInfo?, depth: Int) {
                        if (node == null || depth > 10 || lines.size > 30) return
                        val text = node.text?.toString()?.trim()
                        if (!text.isNullOrEmpty()) {
                            lines.add("depth=$depth text=\"$text\" id=${node.viewIdResourceName ?: "-"}")
                        }
                        for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
                    }
                    walk(sv, 0)
                    appendDebugLog("     【診斷】分享面板scrollView底下的文字節點（${lines.size} 個）：")
                    lines.forEach { appendDebugLog("        $it") }
                }
                continue
            }
            val downloadBtn = run {
                // 找到容器後，下載鈕圖示可能還在非同步渲染中，立刻找常常撲空，
                // 改成重試幾次、每次都重新抓最新容器再找，給渲染多一點時間。
                // 注意：這裡不去動外層的 container，避免同一個 closure 裡「讀取又重新賦值」
                // 外層變數導致編譯器 smart-cast 失敗。
                var btn: AccessibilityNodeInfo? = findDownloadButtonInContainer(container)
                var retry = 0
                while (btn == null && retry < 3) {
                    delay(DOWNLOAD_SCROLL_SETTLE_DELAY_MS)
                    val freshContainer = currentItemContainer(index)
                    if (freshContainer != null) {
                        btn = findDownloadButtonInContainer(freshContainer)
                    }
                    retry++
                }
                btn
            }
            if (downloadBtn == null) {
                // 【診斷用】保留診斷輸出，萬一範圍修正後還有其他邊界情況，還能繼續看到實際節點內容
                val cb = Rect()
                container.getBoundsInScreen(cb)
                appendDebugLog("  → 下載式圖片擷取：第 $index 張重試 3 次仍找不到下載鈕節點，放棄這張（已嘗試滑動 $scrollAttempts 次，容器 bounds=$cb，螢幕寬=$screenWidth）")
                val lines = mutableListOf<String>()
                fun walk(node: AccessibilityNodeInfo?, depth: Int) {
                    if (node == null || depth > 8 || lines.size > 40) return
                    val text = node.text?.toString()?.trim()
                    val desc = node.contentDescription?.toString()?.trim()
                    val id = node.viewIdResourceName
                    val cls = node.className?.toString()
                    val b = Rect()
                    node.getBoundsInScreen(b)
                    lines.add("depth=$depth class=${cls ?: "-"} text=${text ?: "-"} desc=${desc ?: "-"} id=${id ?: "-"} clickable=${node.isClickable} bounds=$b")
                    for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
                }
                walk(container, 0)
                appendDebugLog("     【診斷】第 $index 張容器內部節點樹（${lines.size} 個）：")
                lines.forEach { appendDebugLog("        $it") }
                continue
            }
            val clickTime = System.currentTimeMillis()
            downloadBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            val uri = waitForNewImageUri(clickTime)
            if (uri == null) {
                appendDebugLog("  → 下載式圖片擷取：第 $index 張點擊後逾時沒偵測到相簿新圖，放棄這張")
                delay(DOWNLOAD_BETWEEN_IMAGES_DELAY_MS)
                continue
            }
            val bmp = loadBitmapFromUri(uri)
            if (bmp != null) {
                images.add(bmp)
            } else {
                appendDebugLog("  → 下載式圖片擷取：第 $index 張讀取相簿檔案失敗，放棄這張")
            }
            delay(DOWNLOAD_BETWEEN_IMAGES_DELAY_MS)
        }
        appendDebugLog("  → 下載式圖片擷取完成：成功 ${images.size}/$downloadCount 張")
        return images
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
        val rawBounds = findImageCarouselBounds(root)
        if (rawBounds == null) {
            appendDebugLog("  → 圖片輪播擷取：找不到輪播範圍（沒偵測到「X/N」頁碼），跳過")
            return emptyList()
        }
        // 排除頂部狀態列與底部「N 規格」縮圖列，避免這兩塊區域被拍進商品圖片
        val bounds = refineCarouselBounds(root, rawBounds)
        // 截圖前先隱藏懸浮視窗（擷取／自動／偵測按鈕），避免這幾顆按鈕被一起拍進商品圖片裡。
        // 用 try-finally 確保萬一擷取過程中發生例外，懸浮視窗還是一定會被恢復，
        // 不會卡在隱藏狀態讓使用者以為 App 當掉了。
        FloatingButtonService.hideForScreenshot()
        delay(400) // 給畫面一點時間重繪，確保懸浮視窗真的消失了才開始截圖，避免第一張還是抓到隱藏前的殘影
        try {
            val total = readCarouselTotal(root).coerceIn(1, 10) // 上限 10 張，跟 make_video.py 的 MAX_IMAGES_IN_VIDEO 一致，多抓的用不到
            appendDebugLog("  → 圖片輪播擷取：偵測到範圍 $bounds，共 $total 張，開始逐張截圖")
            val images = mutableListOf<Bitmap>()
            var timeoutCount = 0
            val overallDeadline = System.currentTimeMillis() + 40000 // 整體時間上限，改成內部自己控管，逾時就跳出迴圈回傳「已經抓到的部分」，不會像外層 withTimeoutOrNull 那樣把整批結果都作廢（滑動速度放慢一倍後，上限同步拉長，避免20張圖片還沒抓完就被打斷）

            for (index in 1..total) {
                if (System.currentTimeMillis() > overallDeadline) {
                    appendDebugLog("  → 圖片輪播擷取：已達整體時間上限，提前結束（已成功 ${images.size} 張，剩餘 ${total - index + 1} 張放棄）")
                    break
                }
                if (index > 1) {
                    swipeCarouselNext(bounds)
                    waitForCarouselIndex(index, 2400)
                    delay(800) // 滑動動畫緩衝（使用者反映抓取速度太快，整體放慢一倍）
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
                    if (width > 0 && height > 0) {
                        val bmp = Bitmap.createBitmap(full, left, top, width, height)
                        // 返回/分享箭頭與頁碼是疊加在圖片同一層的控制項，裁切排除不了，改用塗黑遮蓋
                        // 每滑一張畫面就換了，不能沿用函式開頭那個舊 root，這裡重新抓當下畫面再找位置
                        val liveRoot = rootInActiveWindow ?: root
                        val overlays = findOverlayControlBounds(liveRoot, bounds)
                        if (overlays.isEmpty()) {
                            bmp
                        } else {
                            val mutable = bmp.copy(Bitmap.Config.ARGB_8888, true)
                            val canvas = Canvas(mutable)
                            val paint = Paint()
                            for (ob in overlays) {
                                val rLeft = (ob.left - left).coerceIn(0, mutable.width)
                                val rTop = (ob.top - top).coerceIn(0, mutable.height)
                                val rRight = (ob.right - left).coerceIn(0, mutable.width)
                                val rBottom = (ob.bottom - top).coerceIn(0, mutable.height)
                                if (rRight > rLeft && rBottom > rTop) {
                                    val rect = Rect(rLeft, rTop, rRight, rBottom)
                                    paint.color = sampleFillColor(mutable, rect)
                                    canvas.drawRect(rLeft.toFloat(), rTop.toFloat(), rRight.toFloat(), rBottom.toFloat(), paint)
                                }
                            }
                            mutable
                        }
                    } else full
                } catch (e: Exception) {
                    full
                }
                images.add(cropped)
            }
            appendDebugLog("  → 圖片輪播擷取完成：成功 ${images.size}/$total 張（逾時或失敗 $timeoutCount 張）")
            return images
        } finally {
            FloatingButtonService.restoreAfterScreenshot()
        }
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
                delay(500) // 分享按鈕出現後，分潤率／價格等文字通常緊接著渲染完成，多留一點緩衝
                return rootInActiveWindow
            }
            delay(300)
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
            delay(300)
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
        // 只用商品標題文字當識別碼，不再混入螢幕座標。
        // 之前用「標題+座標」的組合，滑動後同一張卡片座標會變，導致被誤判成新卡片、重複點進去，
        // 靠後面的防重複機制攔下來雖然沒有真的重複存檔，但還是多浪費一次點擊詳情頁的時間。
        val texts = mutableListOf<String>()
        collectTextNodes(node, texts, maxDepth = 6)
        val title = texts.firstOrNull { it.length in 4..80 }
        if (!title.isNullOrBlank()) return title
        // 標題抓不到的極少數情況，退回用座標當備援識別碼，避免不同商品因為都是空字串而互相誤判成重複
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return "(無標題)|$bounds"
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

    /**
     * 找不到可點擊祖先層時的容錯：直接對文字節點本身嘗試點擊。
     * Compose UI 元件常見的狀況是 isClickable=false，但實際上仍能回應 ACTION_CLICK，
     * isClickable 只是輔助標記，不是能否點擊的絕對依據。
     */
    private fun findNodeByTexts(root: AccessibilityNodeInfo, texts: List<String>): AccessibilityNodeInfo? {
        var fallbackNode: AccessibilityNodeInfo? = null
        for (text in texts) {
            val matches = root.findAccessibilityNodeInfosByText(text)
            for (node in matches) {
                if (node.isClickable) return node
                // 有些按鈕的可點擊區域在父節點。原本只往上找 4 層，實測發現部分按鈕
                // （例如「複製資訊」）的可點擊容器比「複製連結」深，4 層不夠會漏找，
                // 這裡放寬到 10 層。
                var parent = node.parent
                var depth = 0
                while (parent != null && depth < 10) {
                    if (parent.isClickable) return parent
                    parent = parent.parent
                    depth++
                }
                // 全部祖先都不是 isClickable=true：記下這個節點本身當備援，
                // 如果最後真的找不到任何可點擊層，就直接試著點擊文字節點本身。
                if (fallbackNode == null) fallbackNode = node
            }
        }
        return fallbackNode
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
        // 搜尋深度從 12 提高到 20：診斷 log 證實部分商品畫面的標題節點層數比較深（超過 12 層），
        // extractProductMetrics 用 maxDepth=20 能穩定讀到同一頁的價格，代表 20 層對這個 App 的畫面結構是夠的，
        // 這裡跟著提高到一致的深度，避免標題因為搜尋深度不夠而完全掃不到。
        collectTextNodes(root, candidates, maxDepth = 20, maxNodes = 60)
        // 取長度落在合理商品標題範圍（8~90 字）且非按鈕文字的第一筆，
        // 排除優惠券橫幅相關文字（例如「提供優惠券給您的粉絲/關注者」），避免誤判成商品名稱，
        // 也排除純數字／價格格式的文字（例如「1,680.00」），這類文字不可能是真正的商品標題。
        // 長度上限從 60 放寬到 90：實測發現部分商品標題（含品牌名、規格描述）超過 60 字。
        val result = candidates.firstOrNull {
            it.length in 8..90 && !isCouponBannerText(it) && !isPureNumberOrPriceText(it)
        }
        if (result == null) {
            // 找不到時記錄候選清單前 15 筆，方便下次排查是「標題太長/太短被排除」還是「根本沒掃到標題」
            appendDebugLog("  → ⚠ 商品名稱找不到，候選文字清單（前 ${candidates.size.coerceAtMost(15)} 筆）：${candidates.take(15)}")
        }
        return result
    }

    /** 判斷文字是不是「純數字」「價格格式」或「價格範圍格式」（例如 1,680.00、399、$399、$169.00-$399.00），這種不可能是商品標題。 */
    private fun isPureNumberOrPriceText(text: String): Boolean {
        val stripped = text.trim().removePrefix("$").removePrefix("₱").removePrefix("฿").removePrefix("₫")
        if (Regex("^[\\d,]+(\\.\\d+)?$").matches(stripped)) return true
        // 部分商品類別（例如跑步機、多規格商品）價格顯示成範圍「$169.00-$399.00」，
        // 整段文字剛好落在標題長度範圍內，之前的純數字判斷沒涵蓋帶減號的範圍格式，導致誤判成商品名稱。
        if (Regex("^[\$₱฿₫]?[\\d,]+(\\.\\d+)?\\s*-\\s*[\$₱฿₫]?[\\d,]+(\\.\\d+)?$").matches(text.trim())) return true
        return false
    }

    /** 商品詳情頁常見的「優惠券／折扣橫幅」文字，不是商品資訊，比對商品名稱或價格時要排除掉。 */
    private fun isCouponBannerText(text: String): Boolean {
        val keywords = listOf("提供優惠券", "低消", "社群媒體", "推廣限定", "條款與規範", "有效期限")
        return keywords.any { text.contains(it) }
    }

    private fun collectTextNodes(node: AccessibilityNodeInfo?, out: MutableList<String>, maxDepth: Int, depth: Int = 0, maxNodes: Int = 30) {
        if (node == null || depth > maxDepth || out.size > maxNodes) return
        val text = node.text?.toString()?.trim()
        if (!text.isNullOrEmpty()) out.add(text)
        for (i in 0 until node.childCount) {
            collectTextNodes(node.getChild(i), out, maxDepth, depth + 1, maxNodes)
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
    private suspend fun readClipboardWithRetry(timeoutMs: Long = 3000): String? {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            val text = readClipboard()
            if (!text.isNullOrBlank()) return text
            delay(300)
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
            while (System.currentTimeMillis() - start < 1950 && !ClipboardBridgeActivity.resultReady) {
                delay(100)
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
            // 地區判斷：目前只支援台灣蝦皮(com.shopee.tw)，菲律賓套件名稱慣例格式為 com.shopee.ph，
            // 這裡先做好判斷邏輯，之後真的要支援菲律賓時不用回頭改資料結構。
            // 判斷不出來（例如套件名稱讀不到）就預設TW，跟Python端make_video.py的load_region()預設值一致。
            put("region", currentRegionFromPackage())
            // 這兩個狀態欄位由擷取器初始化成「尚未完成」，實際完成時間由後續流程回寫：
            // videoGeneratedAt 由 make_video.py 生成影片成功後回寫時間戳記；
            // shopeePosted 由未來的上架自動化流程在成功上架後改成 true。
            // 上架自動化之後可以直接掃這個資料夾底下所有meta.json，找
            // videoGeneratedAt有值但shopeePosted還是false的，就是待上架清單，
            // 不用另外維護一份清單、不用複製移動影片檔案。
            put("videoGeneratedAt", org.json.JSONObject.NULL)
            put("shopeePosted", false)
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
