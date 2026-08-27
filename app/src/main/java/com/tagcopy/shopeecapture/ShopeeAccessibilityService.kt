package com.tagcopy.shopeecapture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipboardManager
import android.content.ContentUris
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
                onEvent(AutoCaptureEvent.Log(getString(R.string.auto_capture_stopped)))
            } catch (e: Exception) {
                onEvent(AutoCaptureEvent.Log(getString(R.string.auto_capture_error, e.message)))
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
        // 每次開始自動擷取前，先從CaptionQueue磁碟上實際存在的資料夾「自癒」防重複資料庫。
        // 根因：防重複比對用的captured_names/captured_links是存在App自己的SharedPreferences，
        // 跟磁碟上實際已擷取的資料夾完全脫鉤——只要App被重新安裝過一次（例如部署新版APK），
        // SharedPreferences就會被系統清空歸零，即使CaptionQueue裡明明還躺著幾十支已擷取商品的
        // 完整紀錄，也會被當成「全新環境」，導致同一批商品被重複擷取、白白浪費後續生成影片
        // 的大量時間。這裡改成每次開始前重新掃一次磁碟，把meta.json裡的商品名稱/連結補回
        // SharedPreferences，不管SharedPreferences有沒有被重置，都能從磁碟資料自動校正回來。
        syncDedupPrefsFromDisk()
        run {
            val dedupPrefs = getDedupPrefs()
            val nameCount = (dedupPrefs.getStringSet("captured_names", emptySet()) ?: emptySet()).size
            val linkCount = (dedupPrefs.getStringSet("captured_links", emptySet()) ?: emptySet()).size
            appendDebugLog("  → 目前防重複記錄庫累積：商品名稱 $nameCount 筆、連結 $linkCount 筆")
        }
        onEvent(AutoCaptureEvent.Log(getString(R.string.auto_capture_started, config.targetCount)))
        if (!config.filter.isEmpty()) {
            onEvent(AutoCaptureEvent.Log(getString(R.string.auto_capture_filter_applied)))
        }
        if (config.timeLimitEnabled && config.timeLimitMs != null) {
            onEvent(AutoCaptureEvent.Log(getString(R.string.auto_capture_time_limit, config.timeLimitMs / 60000)))
        }
        if (!config.maxAttemptsLimitEnabled) {
            onEvent(AutoCaptureEvent.Log(getString(R.string.auto_capture_no_attempt_limit)))
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
                onEvent(AutoCaptureEvent.Log(getString(R.string.auto_capture_cannot_read_screen)))
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
                    onEvent(AutoCaptureEvent.Log(getString(R.string.auto_capture_no_more_products)))
                    reason = FinishReason.NO_MORE_PRODUCTS
                    break
                }
                onEvent(AutoCaptureEvent.Log(getString(R.string.auto_capture_scrolling)))
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
                onEvent(AutoCaptureEvent.Log(getString(R.string.auto_capture_back_nav_error)))
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
                onEvent(AutoCaptureEvent.Log(getString(R.string.auto_capture_time_limit_reached, successCount, config.targetCount)))
            FinishReason.MAX_ATTEMPTS_REACHED ->
                onEvent(AutoCaptureEvent.Log(getString(R.string.auto_capture_max_attempts_reached)))
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
            onEvent(AutoCaptureEvent.Log(getString(R.string.auto_capture_click_card_failed)))
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
            onEvent(AutoCaptureEvent.Log(getString(R.string.auto_capture_already_captured, productName)))
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
                onEvent(AutoCaptureEvent.Log(getString(R.string.auto_capture_filter_failed, reason, productName ?: getString(R.string.auto_capture_unknown_product))))
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
            onEvent(AutoCaptureEvent.Log(getString(R.string.auto_capture_no_share_button)))
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
            onEvent(AutoCaptureEvent.Log(getString(R.string.auto_capture_no_share_panel)))
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
            onEvent(AutoCaptureEvent.Log(getString(R.string.auto_capture_no_copy_link_button)))
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
            onEvent(AutoCaptureEvent.Log(getString(R.string.auto_capture_screen_abnormal_recovering)))
            val recovered = tryRecoverToSearchResults()
            appendDebugLog("  → 自動恢復結果：${if (recovered) "成功，恢復到商品列表繼續" else "失敗，將安全停止"}")
            if (recovered) {
                onEvent(AutoCaptureEvent.Log(getString(R.string.auto_capture_recovered)))
            } else {
                onEvent(AutoCaptureEvent.Log(getString(R.string.auto_capture_recovery_failed)))
                navigationLostFlag = true
            }
        }

        // 最終確認：用連結比對（比商品名稱準確），避免早期名稱判斷漏掉的重複商品被存下來
        if (isLinkAlreadyCaptured(link)) {
            appendDebugLog("商品：${productName ?: "未知"} | 結果=跳過（連結重複，先前已擷取過：$link）")
            onEvent(AutoCaptureEvent.Log(getString(R.string.auto_capture_duplicate_link, productName ?: getString(R.string.auto_capture_unknown_product))))
            return ProcessResult.FILTERED
        }

        return when (val result = saveResult(productName, link, caption, galleryImages.ifEmpty { listOfNotNull(bitmap) }, metrics)) {
            is CaptureResult.Success -> {
                markAsCaptured(productName, link)
                onEvent(AutoCaptureEvent.Log(getString(R.string.auto_capture_captured, productName ?: getString(R.string.auto_capture_unknown_product))))
                appendDebugLog("商品：${productName ?: "未知"} | 結果=成功 | 連結=${link ?: "null（沒讀到）"} | 文案=${if (caption.isNullOrBlank()) "null（沒讀到）" else "已讀到"}")
                ProcessResult.SUCCESS
            }
            is CaptureResult.Failure -> {
                onEvent(AutoCaptureEvent.Log(getString(R.string.auto_capture_save_failed, result.reason)))
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
            // 【2026-08-28修正】原本depth>15就放棄，蝦皮的畫面結構夠淺所以一直沒事，
            // 但FB「聯盟合作→商品」搜尋框實測藏在第20層（node樹dump證實），15層會找不到、
            // 導致FB上架流程一開始就誤判「找不到搜尋框」而直接失敗。放寬到40層，
            // 蝦皮這邊搜尋順序完全不變（一樣是先找到的isEditable節點優先），只是多給更深的畫面空間。
            if (node == null || found != null || depth > 40) return
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
     * 【2026-08-28新增】對節點所在的螢幕座標中心點送出一個真實手勢點擊，取代
     * AccessibilityNodeInfo.performAction(ACTION_CLICK)。實測發現FB搜尋結果商品卡
     * 這類節點，ACTION_CLICK呼叫本身回傳true（accessibility系統認為指令有送達），
     * 但App實際完全沒反應、畫面沒有跳轉——這是自繪UI元件（例如Meta常用的Litho）
     * 常見狀況：畫面上看得到、accessibility樹也讀得到節點屬性，但實際互動綁定的是
     * 真實觸控事件而非accessibility action，所以改用dispatchGesture在節點中心點
     * 送出模擬手指點擊，比較貼近真人操作、對這類UI更可靠。
     */
    private fun tapNodeCenter(node: AccessibilityNodeInfo): Boolean {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.width() <= 0 || bounds.height() <= 0) return false
        val x = bounds.centerX().toFloat()
        val y = bounds.centerY().toFloat()
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 120))
            .build()
        return dispatchGesture(gesture, null, null)
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
    private fun tapToggleNearLabel(labelNode: AccessibilityNodeInfo, xRatio: Float = 0.9298f): Pair<Float, Float> {
        val bounds = Rect().also { labelNode.getBoundsInScreen(it) }
        val metrics = resources.displayMetrics
        val x = metrics.widthPixels * xRatio
        val y = bounds.centerY().toFloat()
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 150))
            .build()
        dispatchGesture(gesture, null, null)
        return Pair(x, y)
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
        val price: Double,
        val hashtags: List<String>
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
                val hashtagsArray = json.optJSONArray("hashtags")
                val hashtags = if (hashtagsArray != null) {
                    (0 until hashtagsArray.length()).mapNotNull { i ->
                        hashtagsArray.optString(i, "").takeIf { it.isNotBlank() && it != "null" }
                    }
                } else {
                    emptyList()
                }

                appendDebugLog("  → 掃描候選商品：${dir.name} 符合條件，加入候選清單（文案長度=${narrationText.length}字，hashtags=${hashtags.size}個）")
                candidates.add(UploadCandidate(dir, promoLink, narrationText, videoFile, productName, price, hashtags))
            } catch (e: Exception) {
                appendDebugLog("  → 掃描候選商品：讀取 ${dir.name}/meta.json 失敗，跳過（${e.javaClass.simpleName}：${e.message}）")
            }
        }
        appendDebugLog("  → 掃描候選商品：掃描完成，共 ${candidates.size} 筆符合條件的候選商品")
        return candidates
    }

    /**
     * 【階段3用，開發中】掃描 CaptionQueue 底下所有商品資料夾，找出符合FB上架條件的候選清單：
     * shopeePosted=true（已經蝦皮上架過，代表影片可以重複利用）且 fbPosted 還是 false（FB還沒上架）。
     * 跟scanUploadCandidates()共用同一個UploadCandidate資料結構、同一套解析邏輯，
     * 差別只在篩選條件相反（這裡要求shopeePosted一定要true，而不是false）。
     * 這個函式目前只用來確認候選清單抓得對不對，實際的FB上架流程本體還沒寫
     * （導航到商品詳情頁「建立貼文」按鈕的節點結構還沒確認，見dumpCurrentNodeTree()診斷）。
     */
    private fun scanFbUploadCandidates(): List<UploadCandidate> {
        val baseDir = File(
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
            "CaptionQueue"
        )
        val candidates = mutableListOf<UploadCandidate>()
        val dirs = baseDir.listFiles()?.filter { it.isDirectory } ?: run {
            appendDebugLog("  → 【FB候選掃描】找不到 CaptionQueue 資料夾（${baseDir.absolutePath}）")
            return candidates
        }

        for (dir in dirs) {
            val metaFile = File(dir, "meta.json")
            if (!metaFile.isFile) continue
            try {
                val json = org.json.JSONObject(metaFile.readText())

                val shopeePosted = json.optBoolean("shopeePosted", false)
                if (!shopeePosted) {
                    // 還沒蝦皮上架過的不算FB候選（FB階段的前提是影片已經在蝦皮用過一次）
                    continue
                }
                val fbPosted = json.optBoolean("fbPosted", false)
                if (fbPosted) {
                    appendDebugLog("  → 【FB候選掃描】${dir.name} 已經FB上架過了（fbPosted=true），跳過")
                    continue
                }

                val promoLink = json.optString("promoLink", "")
                    .takeIf { it.isNotBlank() && it != "null" }
                if (promoLink == null) {
                    appendDebugLog("  → 【FB候選掃描】${dir.name} 沒有商品連結，跳過")
                    continue
                }

                val videoFile = File(dir, "output.mp4")
                if (!videoFile.isFile) {
                    appendDebugLog("  → 【FB候選掃描】${dir.name} 找不到output.mp4（可能蝦皮上架後資料夾被舊版邏輯刪除過），跳過")
                    continue
                }

                val narrationText = json.optString("narrationText", "")
                    .takeIf { it.isNotBlank() && it != "null" } ?: ""
                val productName = json.optString("productName", "")
                    .takeIf { it.isNotBlank() && it != "null" } ?: ""
                val price = json.optDouble("price", 0.0)
                val hashtagsArray = json.optJSONArray("hashtags")
                val hashtags = if (hashtagsArray != null) {
                    (0 until hashtagsArray.length()).mapNotNull { i ->
                        hashtagsArray.optString(i, "").takeIf { it.isNotBlank() && it != "null" }
                    }
                } else {
                    emptyList()
                }

                appendDebugLog("  → 【FB候選掃描】${dir.name} 符合條件，加入FB候選清單")
                candidates.add(UploadCandidate(dir, promoLink, narrationText, videoFile, productName, price, hashtags))
            } catch (e: Exception) {
                appendDebugLog("  → 【FB候選掃描】讀取 ${dir.name}/meta.json 失敗，跳過（${e.javaClass.simpleName}：${e.message}）")
            }
        }
        appendDebugLog("  → 【FB候選掃描】掃描完成，共 ${candidates.size} 筆符合條件的FB候選商品")
        return candidates
    }

    /**
     * 【階段3開發用，暫時工具】確認FB候選商品掃得對不對，結果看debug log。
     * 之後正式串上FB上架流程本體時可以拿掉，或整合進正式的上架函式裡。
     */
    fun testScanFbUploadCandidates() {
        scanFbUploadCandidates()
    }

    /**
     * 【階段3測試用，暫時加的】只處理1筆FB候選商品，方便小範圍實測整套流程。
     * 呼叫前提跟startFbUploadAutomation()一樣：畫面要先在FB「聯盟合作→商品」畫面。
     * 第一次測試務必全程盯著畫面，任何一步失敗都會停下來、詳細原因看debug log。
     */
    fun testFbUploadAutomation() {
        if (isFbUploadAutomationRunning()) {
            appendDebugLog("  → 【FB上架自動化測試】已經在執行中，本次觸發略過")
            return
        }
        startFbUploadAutomation(1) { event ->
            when (event) {
                is UploadEvent.Log -> appendDebugLog("  → 【FB上架自動化測試】${event.message}")
                is UploadEvent.Progress -> appendDebugLog("  → 【FB上架自動化測試】進度 ${event.current}/${event.total}")
                is UploadEvent.Finished -> appendDebugLog(
                    "  → 【FB上架自動化測試】結束：成功${event.successCount}／失敗${event.failCount}，原因=${event.reason}"
                )
            }
        }
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
            } finally {
                cleanupTempUploadCopy()
            }

            if (ok) {
                successCount++
                markShopeePosted(candidate.folder)
                appendDebugLog("  → [${candidate.folder.name}] 上架成功，已標記 shopeePosted=true")
                onEvent(UploadEvent.Log("✓ 上架成功：${candidate.folder.name}"))
                // 【2026-08-27改版】不再上架蝦皮完就立刻刪除整個資料夾——階段3(FB上架)要
                // 重複利用這支影片，所以改成「兩邊都上架完才真的刪除」，見deleteFolderIfFullyPosted()。
                // 目前FB階段還沒做完，這裡呼叫的當下實際上不會真的刪除，只是先把判斷邏輯接上，
                // 之後FB流程做完、markFbPosted()寫入後，下次任何一邊呼叫這個函式就會自動清掉。
                deleteFolderIfFullyPosted(candidate.folder)
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
        val titleText = titleNode?.text?.toString()
            ?: findTextContaining(root, "分潤按讚好物(")
            ?: findTextContaining(root, "My Likes(")
        if (titleText == null || !(titleText.contains("分潤按讚好物(") || titleText.contains("My Likes("))) {
            appendDebugLog("  → 目前畫面不是「分潤按讚好物」清單（畫面標題=${titleText ?: "讀不到"}），請先手動導航到這個畫面再啟動")
            return false
        }

        // 1~3. 貼上連結→按「新增至按讚好物」這整套動作當一個單位，做兩次當保險
        // （不是像前一版只在同一個對話框裡重貼文字兩次，而是連「點+重新叫出對話框」
        // 都一起重做——使用者實測反映前一版少了「按Add to My Likes」這個動作，
        // 等於只在原本那個視窗裡動作了兩次，沒有真正重跑一整輪）。
        // 第一次任何一步失敗就直接回報失敗中止；第二次只是保險加強，即使某個節點
        // 一時找不到（例如清單還沒完全回來），也不讓它擋掉整體流程——反正已經成功送出
        // 過一次，第二次找不到通常代表已經在回清單的路上，不算真正的錯誤。
        suspend fun pasteAndSubmitOnce(attemptLabel: String): Boolean {
            val addIconNode = findFirstNodeById(rootInActiveWindow ?: return false, "AN_SellerInvitedOfferPage_AddIcon_Img")
            if (addIconNode == null || !clickNodeBestEffort(addIconNode)) {
                appendDebugLog("  → [$attemptLabel] 找不到或點擊「+」按鈕失敗"); return false
            }
            if (!waitForAnyText(listOf("從匯入連結新增", "Add by Import Link"), 3000)) {
                appendDebugLog("  → [$attemptLabel] 等不到「從匯入連結新增」選單"); return false
            }
            delay(1200)
            val importMenuNode = findNodeByTexts(rootInActiveWindow ?: return false, listOf("從匯入連結新增", "Add by Import Link"))
            if (importMenuNode == null || !clickNodeBestEffort(importMenuNode)) {
                appendDebugLog("  → [$attemptLabel] 點擊「從匯入連結新增」失敗"); return false
            }

            if (!waitForAnyText(listOf("商品連結", "Products URL"), 3000)) {
                appendDebugLog("  → [$attemptLabel] 等不到「商品連結」輸入畫面"); return false
            }
            delay(1200)
            var root2 = rootInActiveWindow ?: return false
            val linkInput = findSearchBoxNode(root2)
            if (linkInput == null) {
                appendDebugLog("  → [$attemptLabel] 找不到商品連結輸入框"); return false
            }
            val bundle = android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, candidate.promoLink)
            }
            linkInput.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
            appendDebugLog("  → [$attemptLabel] 貼上商品連結")
            delay(1500)
            // 填完文字後鍵盤還開著、輸入框還focus著，「新增至按讚好物」按鈕不會被觸發／可能被鍵盤蓋住，
            // 要主動清除焦點+收起鍵盤，模擬使用者「點輸入框外面一下」的動作，畫面才會切到確認狀態。
            root2 = rootInActiveWindow ?: return false
            (findSearchBoxNode(root2) ?: linkInput).performAction(AccessibilityNodeInfo.ACTION_CLEAR_FOCUS)
            delay(450)
            val imm2 = getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            imm2?.hideSoftInputFromWindow(null, 0)
            delay(1050)
            root2 = rootInActiveWindow ?: return false
            findNodeByTexts(root2, listOf("商品連結", "Products URL"))?.let { clickNodeBestEffort(it) }
            delay(1050)

            root2 = rootInActiveWindow ?: return false
            val addToListButton = findNodeByTexts(root2, listOf("新增至按讚好物", "Add to My Likes"))
            if (addToListButton == null || !clickNodeBestEffort(addToListButton)) {
                appendDebugLog("  → [$attemptLabel] 找不到或點擊「新增至按讚好物」失敗"); return false
            }
            appendDebugLog("  → [$attemptLabel] 已按下「新增至按讚好物」")
            delay(3750)
            return true
        }

        if (!pasteAndSubmitOnce("第1次")) {
            appendDebugLog("  → 第1次貼上連結失敗，中止本次上架")
            return false
        }
        // 第2次是保險加強，失敗也不中止整體流程（已經成功送出過一次）。
        pasteAndSubmitOnce("第2次（保險）")

        // 4. 回到清單，勾選第一筆（排序：最新，剛新增的理論上會排在最上面）
        // 「新增至按讚好物」是打後端API，清單重新排序可能有延遲，等待時間要留寬一點，
        // 不然抓到的可能還是舊排序、選到別的商品——這是之前實測發現商品對不上的根因，
        // 所以特別拉長這裡的等待，並把選到的商品文字記進log，方便之後直接從log驗證選對了沒，
        // 不用等整個流程跑完才能靠肉眼確認。
        if (!waitForAnyText(listOf("分潤按讚好物", "My Likes"), 4000)) {
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
        val shareTextNode = (root.findAccessibilityNodeInfosByText("分享") + root.findAccessibilityNodeInfosByText("Share"))
            .firstOrNull { it.text?.toString() == "分享" || it.text?.toString() == "Share" }
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
        if (!waitForAnyText(listOf("蝦皮短影音", "Shopee Video"), 4000)) {
            appendDebugLog("  → 分享面板沒有出現「蝦皮短影音」選項，可能已達每日上架上限或其他限制")
            return false
        }
        root = rootInActiveWindow ?: return false
        val shortVideoOption = findNodeByTexts(root, listOf("蝦皮短影音", "Shopee Video"))
        if (shortVideoOption == null || !clickNodeBestEffort(shortVideoOption)) {
            appendDebugLog("  → 點擊「蝦皮短影音」失敗"); return false
        }

        // 7. 趁畫面切換的空檔，把影片登記進媒體庫，確保等一下的選片畫面找得到
        delay(3000)
        registerVideoInMediaStore(candidate.videoFile)
        delay(1500)

        // 8. 點「媒體庫」
        root = rootInActiveWindow ?: run { appendDebugLog("  → 等不到短影音錄影頁"); return false }
        val galleryEntrance = findNodeByIdSuffix(root, "ll_gallery_entrance") ?: findNodeByTexts(root, listOf("媒體庫", "Library"))
        if (galleryEntrance == null || !clickNodeBestEffort(galleryEntrance)) {
            appendDebugLog("  → 找不到或點擊「媒體庫」入口失敗"); return false
        }

        // 9. 等媒體庫畫面出現，切到「短影音」分頁，選第一個（剛登記進媒體庫的最新影片會排最前面）
        if (!waitForAnyText(listOf("相片集", "Gallery"), 4000)) {
            appendDebugLog("  → 等不到媒體庫選片畫面"); return false
        }
        delay(1500)
        root = rootInActiveWindow ?: return false
        val videoTab = (root.findAccessibilityNodeInfosByText("短影音") + root.findAccessibilityNodeInfosByText("Video"))
            .firstOrNull { it.isClickable }
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
        if (waitForAnyText(listOf("剪輯", "配音", "音效", "Trimmer", "Voiceover", "Stickers"), 5000)) {
            delay(2250)
            root = rootInActiveWindow ?: return false
            val editorNextButton = findNodeByTexts(root, listOf("下一步", "Next"))
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

        // 11. 等「撰寫內文」畫面
        if (!waitForAnyText(listOf("撰寫內文", "為您的短影音撰寫內文", "Add Caption", "Add caption to your videos"), 5000)) {
            appendDebugLog("  → 等不到「撰寫內文」畫面"); return false
        }
        delay(1700)

        // 12. 確認商品卡是否自動帶入（只記錄不當失敗條件，避免因為判斷誤差擋住整個流程）
        root = rootInActiveWindow ?: return false
        val productCardPresent = findNodeByIdSuffix(root, "rl_product_item") != null
        appendDebugLog("  → 撰寫內文畫面：商品卡${if (productCardPresent) "已自動帶入" else "沒看到（請留意，可能要手動補加）"}")

        // 13. 調整三個開關：關閉「允許他人合拍」「允許他人拼接」、開啟「AI生成影片標記」。
        // 【順序調整】改到填文案之前執行，避免文字輸入框的焦點/選取狀態干擾開關點擊。
        // 實測確認方法1（對文字標籤節點下ACTION_CLICK）完全無效——這幾個開關是蝦皮自訂繪製元件，
        // 無障礙樹裡真的沒有暴露對應的可點擊節點。改用方法2：對開關實際所在的螢幕座標直接tap。
        // Y座標從文字標籤節點的bounds動態算（不會跑掉），X座標用「螢幕寬度的比例」
        // （固定在畫面右側同一個相對位置，比例在不同解析度手機上比寫死像素準）。
        // 【診斷用，暫時對調順序】懷疑蝦皮可能有「合拍／拼接至少要保留一個開啟」的隱藏驗證規則，
        // 先關閉的那個會被系統改回開啟——這次先點拼接、再點合拍，用來驗證這個猜測，
        // 如果猜測成立，這次應該會變成「拼接」被改回開啟、「合拍」關閉成功（順序互換）。
        delay(2500)
        root = rootInActiveWindow ?: return false
        findNodeByIdSuffix(root, "tv_allow_stitch")?.let {
            val (tapX, tapY) = tapToggleNearLabel(it)
            appendDebugLog("  → 已點擊「允許他人拼接」開關（座標點擊法，實際點擊位置 X=%.1f Y=%.1f）".format(tapX, tapY))
        }
        delay(1190)
        root = rootInActiveWindow ?: return false
        findNodeByIdSuffix(root, "tv_allow_duet")?.let {
            val (tapX, tapY) = tapToggleNearLabel(it)
            appendDebugLog("  → 已點擊「允許他人合拍」開關（座標點擊法，實際點擊位置 X=%.1f Y=%.1f）".format(tapX, tapY))
        }
        delay(1190)
        root = rootInActiveWindow ?: return false
        findNodeByIdSuffix(root, "tv_ai_generated_title")?.let { titleNode ->
            // 實測校正發現：這顆開關的垂直位置不是對齊標題那一行，而是對齊「標題+底下
            // 說明文字」整塊區域的中點（說明文字很長，開關視覺上對齊在偏中間、偏下的位置）。
            // 用標題node跟說明node合併起來的bounds算中點，比只用標題一行準確很多。
            val descNode = findNodeByIdSuffix(root, "tv_ai_generated_desc")
            val (aiTapX, aiTapY) = if (descNode != null) {
                val titleBounds = Rect().also { titleNode.getBoundsInScreen(it) }
                val descBounds = Rect().also { descNode.getBoundsInScreen(it) }
                val combinedTop = titleBounds.top
                val combinedBottom = descBounds.bottom
                val metrics = resources.displayMetrics
                val x = metrics.widthPixels * 0.9298f
                val y = ((combinedTop + combinedBottom) / 2).toFloat()
                val path = Path().apply { moveTo(x, y) }
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, 150))
                    .build()
                dispatchGesture(gesture, null, null)
                Pair(x, y)
            } else {
                tapToggleNearLabel(titleNode)
            }
            appendDebugLog("  → 已點擊「AI生成影片標記」開關（座標點擊法，標題+說明文字合併中點，實際點擊位置 X=%.1f Y=%.1f）".format(aiTapX, aiTapY))
        }
        delay(1700)

        // 14. 填入文案（【順序調整】改到最後，緊接著點「發佈」之前）
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
            // 導致後面點發佈其實點在這層看不見的選取狀態上、完全沒反應。
            // 比照之前處理「貼連結」畫面的做法，額外點一下畫面上安全的文字區塊，確保真的跳出選取模式。
            rootInActiveWindow?.let { r ->
                val safeAnchor = findNodeByTexts(r, listOf("新增商品", "Add product"))
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

        // 15. 點「發佈」
        root = rootInActiveWindow ?: return false
        val postButton = findNodeByIdSuffix(root, "btn_post")
        if (postButton == null || !clickNodeBestEffort(postButton)) {
            appendDebugLog("  → 找不到或點擊「發佈」失敗"); return false
        }

        // 16. 判定成功的依據：按下發佈後，畫面上不再有文案輸入框（代表已經離開撰寫內文畫面）
        delay(5100)
        val stillOnCaptionScreen = rootInActiveWindow?.let { findNodeByIdSuffix(it, "et_caption") } != null
        if (stillOnCaptionScreen) {
            appendDebugLog("  → 按下發佈後仍停在撰寫內文畫面，判定失敗（可能跳出錯誤提示或達到每日上限）")
            return false
        }

        // 17. 發佈成功後導航回「分潤按讚好物」清單畫面，讓下一筆候選商品能接續處理
        // （目前的路徑是PH實測確認：可能會先跳出「Share to Whatsapp」分享詢問彈窗（PH特有，
        // 點Cancel跳過）→ 點底部導覽列「我／Me」→ 點「Affiliate Program」卡片進分潤首頁
        // →點「My Likes／分潤按讚好物」圖示。TW是否走同一條路徑尚未實測確認，這裡中英文都先列，
        // 之後TW／PH都要驗證這段。任何一步失敗都不當作整體上架失敗（商品本身已經發佈成功），
        // 只記錄log，讓使用者知道需要手動導航。
        navigateBackToLikesListAfterPost()

        return true
    }

    /**
     * 發佈成功後導航回「分潤按讚好物」清單畫面。任何一步失敗都只記錄log、不影響
     * processOneUploadCandidate()的回傳結果（該筆商品已經算發佈成功），
     * 讓下一輪迴圈的起始畫面檢查（見processOneUploadCandidate開頭）決定要不要繼續。
     */
    private suspend fun navigateBackToLikesListAfterPost() {
        // 17a-0. 等畫面穩定下來——使用者實測發現，發佈成功後跳轉到「Live & Video」動態牆
        // 的時間不固定（有時很快、有時明顯較慢），原本寫死delay(1500)不夠、常常太早去找
        // 底部導覽列時畫面還沒渲染完成。原本改成等「Home」/「首頁」文字出現，但實測發現
        // TW的首頁分頁其實叫「蝦拼」（蝦皮暱稱，不是「首頁」），害這一步在TW每次都空等滿10秒
        // 才繼續。改成直接等「我的」分頁本身出現（含跨地區共用、不受語言影響的
        // contentDescription「tab_bar_button_me」），這樣不用去猜各地區「首頁」分頁叫什麼，
        // 而且等到的東西就是接下來步驟17b真正要點的目標，一次到位。
        waitForAnyText(listOf("我的", "我", "Me", "tab_bar_button_me"), 10000)
        delay(800)

        // 17a. 若跳出「Share to Whatsapp」分享詢問彈窗，點「Cancel」跳過（PH特有，目前未見TW版本）
        var root = rootInActiveWindow
        if (root != null && root.findAccessibilityNodeInfosByText("Share to Whatsapp").isNotEmpty()) {
            val cancelBtn = findNodeByTexts(root, listOf("Cancel", "取消"))
            if (cancelBtn != null && clickNodeBestEffort(cancelBtn)) {
                appendDebugLog("  → [返回清單] 偵測到「Share to Whatsapp」彈窗，已點擊Cancel跳過")
            } else {
                appendDebugLog("  → [返回清單] 偵測到「Share to Whatsapp」彈窗，但點擊Cancel失敗")
            }
            delay(1200)
        }

        // 17b. 點底部導覽列「我／Me」
        // 注意：畫面上可能不只一個地方文字/描述含「Me」（例如某些頁面右上角個人頭像icon
        // 也可能標記含Me的描述），用一般的findNodeByTexts可能誤點到不是底部導覽列的那個。
        // 底部導覽列固定在螢幕最下方，改用「畫面上所有符合的候選節點裡，取bounds最靠近
        // 螢幕底部（top座標最大）的那個」來鎖定，比純文字比對可靠。
        // 加上重試（最多3次、每次間隔1秒）：畫面偶爾在這個當下還沒完全穩定，第一次找不到
        // 不代表真的沒有，稍等一下再試一次成功率高很多。
        var meTab: AccessibilityNodeInfo? = null
        for (attempt in 1..3) {
            root = rootInActiveWindow
            meTab = root?.let { findBottommostNodeByTexts(it, listOf("我的", "我", "Me", "tab_bar_button_me")) }
            if (meTab != null) break
            appendDebugLog("  → [返回清單] 第${attempt}次找不到底部導覽列「我／Me」，1秒後重試")
            delay(1000)
        }
        if (meTab == null || !clickNodeBestEffort(meTab)) {
            appendDebugLog("  → [返回清單] 找不到或點擊底部導覽列「我／Me」失敗，請手動導航回清單畫面")
            rootInActiveWindow?.let { dumpClickableNodesToLog(it) }
            return
        }
        delay(1500)

        // 17c. 等「Affiliate Program／蝦皮分潤計畫」卡片出現並點擊
        // （TW實測確認正確文字是「蝦皮分潤計畫」，之前猜的「聯盟計畫」「聯盟合作」都是錯的）
        // 使用者實測發現：「我的」頁面剛進入時這張卡片常常不在畫面可視範圍內（該頁面底下
        // 卡片區塊疑似用RecyclerView，畫面外的內容不會出現在無障礙節點樹裡，單純等待
        // 是永遠等不到的），必須先下拉一次才找得到；手動下拉過一次之後，蝦皮那個分頁會
        // 記住捲動位置，同一個App session內之後都維持在下拉後的位置，所以才會有「手動拉一次、
        // 後面就一路順」的現象。這裡改成：短暫等待→找不到就自動下拉→再等待，最多重試3次，
        // 不用每次都仰賴使用者手動介入。
        var affiliateProgramCard: AccessibilityNodeInfo? = null
        for (attempt in 1..3) {
            if (waitForAnyText(listOf("Affiliate Program", "蝦皮分潤計畫"), 1500)) {
                root = rootInActiveWindow
                affiliateProgramCard = root?.let { findNodeByTexts(it, listOf("Affiliate Program", "蝦皮分潤計畫")) }
                if (affiliateProgramCard != null) break
            }
            appendDebugLog("  → [返回清單] 第${attempt}次找不到「Affiliate Program」卡片，往下滑動後重試")
            performScrollDown()
            delay(600)
        }
        if (affiliateProgramCard == null) {
            appendDebugLog("  → [返回清單] 滑動3次後仍找不到「Affiliate Program」卡片，請手動導航回清單畫面")
            return
        }
        if (!clickNodeBestEffort(affiliateProgramCard)) {
            appendDebugLog("  → [返回清單] 點擊「Affiliate Program」卡片失敗，請手動導航回清單畫面")
            return
        }
        delay(1800)

        // 17d. 等分潤首頁出現，點「My Likes／分潤按讚好物」圖示進清單畫面
        if (!waitForAnyText(listOf("My Likes", "分潤按讚好物"), 3000)) {
            appendDebugLog("  → [返回清單] 等不到分潤首頁的「My Likes」入口，請手動導航回清單畫面")
            return
        }
        root = rootInActiveWindow
        val myLikesEntry = root?.let { findNodeByTexts(it, listOf("My Likes", "分潤按讚好物")) }
        if (myLikesEntry == null || !clickNodeBestEffort(myLikesEntry)) {
            appendDebugLog("  → [返回清單] 點擊「My Likes」入口失敗，請手動導航回清單畫面")
            return
        }
        delay(1500)

        // 17e. 確認真的回到清單畫面（標題含括號數字）
        val backOk = waitForAnyText(listOf("My Likes(", "分潤按讚好物("), 3000)
        if (backOk) {
            appendDebugLog("  → [返回清單] 已成功導航回「My Likes」清單畫面，可接續處理下一筆")
        } else {
            appendDebugLog("  → [返回清單] 導航完成但畫面標題不符預期，請確認目前畫面")
        }
    }

    // ===================== 階段3：FB上架自動化本體 =====================
    // 根據使用者2026-08-27手動測試10份節點樹dump重建的實際流程：
    // FB App「聯盟合作」分頁→「商品」子分頁（有「搜尋商品、品牌或連結」搜尋框）
    // →貼上商品名稱→點搜尋結果商品卡→商品詳情頁→「建立貼文」→「加到新Reel」
    // →FB Reel錄影介面→左下角「圖庫」→選相簿影片（用MediaStore時間戳記讓目標影片
    // 排在最前面「項目1」，跟階段2選片邏輯同一招）→影片編輯預覽畫面「下一步」
    // →「Reel設定」畫面（商品連結/商品卡已自動帶入，不用手動點「新增商品」）→填文案
    // →捲動找「立即分享」→點擊發佈。
    // 【2026-08-27修正】原本猜貼「商品連結」去搜尋，使用者實測發現貼連結、貼蝦皮
    // 「複製資訊」文案都搜不到，只有貼「商品名稱本身」搜得到（已改用candidate.productName）。
    // 【尚未實測確認的部分，第一次跑務必先maxCount=1小心觀察】：貼上商品名稱後搜尋結果
    // 卡片的精確點擊目標——目前用「同時符合『蝦皮購物』desc與價格/佣金文字」的卡片
    // 外層可點擊節點，是根據使用者提供的瀏覽畫面（未實際搜尋、純瀏覽情境）節點結構推測，
    // 精準搜尋後版面可能不同，第一次執行務必全程盯著畫面，log裡任何一步找不到
    // 節點都會停下來、不會亂點。

    private var fbUploadJob: Job? = null

    fun isFbUploadAutomationRunning(): Boolean = fbUploadJob?.isActive == true

    /**
     * 啟動FB上架自動化。呼叫時機的前提：FB App目前畫面必須已經在「聯盟合作」分頁的
     * 「商品」子分頁（畫面上看得到「搜尋商品、品牌或連結」搜尋框），這段導航
     * （開啟FB App→我的帳號→營利→聯盟合作→商品）目前還沒自動化，先手動切過去。
     */
    fun startFbUploadAutomation(maxCount: Int, onEvent: (UploadEvent) -> Unit) {
        if (isFbUploadAutomationRunning()) return
        fbUploadJob = serviceScope.launch {
            try {
                fbUploadAutomationLoop(maxCount, onEvent)
            } catch (e: kotlinx.coroutines.CancellationException) {
                onEvent(UploadEvent.Log("已停止FB上架自動化"))
            } catch (e: Exception) {
                onEvent(UploadEvent.Log("發生未預期錯誤：${e.javaClass.simpleName} ${e.message}"))
            }
        }
    }

    fun stopFbUploadAutomation() {
        fbUploadJob?.cancel()
        fbUploadJob = null
    }

    private suspend fun fbUploadAutomationLoop(maxCount: Int, onEvent: (UploadEvent) -> Unit) {
        appendDebugLog("===== 開始FB上架自動化，本次上限 $maxCount 支 =====")
        onEvent(UploadEvent.Log("開始FB上架自動化，本次上限 $maxCount 支"))

        val candidates = scanFbUploadCandidates()
        if (candidates.isEmpty()) {
            appendDebugLog("  → FB上架自動化：沒有找到任何候選商品，結束")
            onEvent(UploadEvent.Finished(0, 0, UploadFinishReason.NO_CANDIDATES))
            return
        }
        appendDebugLog("  → FB上架自動化：共 ${candidates.size} 筆候選，本次最多處理 $maxCount 支")

        var successCount = 0
        var failCount = 0
        var reason = UploadFinishReason.ALL_DONE

        for ((index, candidate) in candidates.withIndex()) {
            if (successCount >= maxCount) {
                reason = UploadFinishReason.MAX_COUNT_REACHED
                break
            }
            onEvent(UploadEvent.Progress(index + 1, candidates.size))
            appendDebugLog("  → [FB ${index + 1}/${candidates.size}] 開始處理：${candidate.folder.name}")
            onEvent(UploadEvent.Log("FB處理中：${candidate.folder.name}"))

            // 選片前先把目標影片登記進媒體庫（複製暫時副本，時間戳記最新），
            // 這樣稍後在FB相簿選片畫面它才會排在最前面「項目1」。跟階段2選片邏輯同一招。
            registerVideoInMediaStore(candidate.videoFile)

            val ok = try {
                processOneFbUploadCandidate(candidate)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                appendDebugLog("  → [FB ${candidate.folder.name}] 發生例外：${e.javaClass.simpleName} ${e.message}")
                false
            } finally {
                cleanupTempUploadCopy()
            }

            if (ok) {
                successCount++
                markFbPosted(candidate.folder)
                appendDebugLog("  → [FB ${candidate.folder.name}] FB上架成功，已標記 fbPosted=true")
                onEvent(UploadEvent.Log("✓ FB上架成功：${candidate.folder.name}"))
                deleteFolderIfFullyPosted(candidate.folder)
            } else {
                failCount++
                appendDebugLog("  → [FB ${candidate.folder.name}] FB上架失敗，停止本次批次")
                onEvent(UploadEvent.Log("✗ FB上架失敗：${candidate.folder.name}，停止本次批次"))
                reason = UploadFinishReason.STOPPED_ON_FAILURE
                break
            }

            if (successCount < maxCount) {
                delay(Random.nextLong(9000, 15000))
            }
        }

        appendDebugLog("===== FB上架自動化結束：成功 $successCount／失敗 $failCount，原因=$reason =====")
        onEvent(UploadEvent.Finished(successCount, failCount, reason))
    }

    /**
     * 處理一筆候選商品的完整FB上架流程。任何一步逾時或失敗就回傳false並記錄詳細原因，
     * 不拋例外（呼叫端已包try/catch保護整批）。
     */
    private suspend fun processOneFbUploadCandidate(candidate: UploadCandidate): Boolean {
        // 0. 確認目前在FB「聯盟合作／商品」畫面（找得到搜尋框，且畫面上有「聯盟合作」字樣）
        var root = rootInActiveWindow ?: run {
            appendDebugLog("  → [FB] 讀不到目前畫面"); return false
        }
        if (findTextContaining(root, "聯盟合作") == null) {
            appendDebugLog("  → [FB] 目前畫面找不到「聯盟合作」字樣，請先手動導航到FB「聯盟合作→商品」畫面再啟動")
            return false
        }
        val searchBox = findSearchBoxNode(root)
        if (searchBox == null) {
            appendDebugLog("  → [FB] 找不到搜尋框（搜尋商品、品牌或連結），請確認目前在「商品」子分頁")
            return false
        }

        // 1. 貼上商品名稱到搜尋框並送出搜尋
        // 【2026-08-27修正】原本貼商品連結(promoLink)去搜尋，使用者實測發現FB的
        // 「依商品連結搜尋」貼連結搜不到、貼蝦皮「複製資訊」文案內容也搜不到，
        // 唯一搜得到的是「商品名稱本身」（螢幕截圖圈起來測試確認）。改用candidate.productName。
        // 【2026-08-28修正】原本只有ACTION_SET_TEXT把文字貼進框裡，使用者實測發現文字
        // 有貼上但畫面沒有真的執行搜尋（還停在瀏覽/分類畫面），因為ACTION_SET_TEXT只是
        // 改變文字內容，不會觸發App的搜尋送出邏輯（跟手動打字最後按下Enter/搜尋鍵是兩回事）。
        // 改成：先點擊搜尋框聚焦（讓輸入框進入編輯狀態）→貼字→用ACTION_IME_ENTER模擬
        // 按下鍵盤的搜尋/Enter鍵送出（minSdk=30，這個action從API 30才有，符合需求）。
        if (candidate.productName.isBlank()) {
            appendDebugLog("  → [FB] 這筆候選商品沒有商品名稱(productName為空)，無法用名稱搜尋，跳過")
            return false
        }
        clickNodeBestEffort(searchBox)
        delay(600)
        root = rootInActiveWindow ?: return false
        val focusedSearchBox = findSearchBoxNode(root) ?: searchBox
        val searchBundle = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, candidate.productName)
        }
        focusedSearchBox.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, searchBundle)
        appendDebugLog("  → [FB] 已貼上商品名稱到搜尋框：${candidate.productName}")
        delay(1000)
        root = rootInActiveWindow ?: return false
        val searchBoxBeforeSubmit = findSearchBoxNode(root) ?: focusedSearchBox
        val imeEnterOk = searchBoxBeforeSubmit.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        appendDebugLog("  → [FB] 送出搜尋（ACTION_IME_ENTER）：${if (imeEnterOk) "成功" else "失敗，畫面可能仍停在貼字狀態未執行搜尋"}")
        delay(2200)

        // 2. 點搜尋結果的商品卡（【未完全確認】找同時符合「有可點擊」+「子節點desc含蝦皮購物」
        // 的最外層卡片節點；貼連結後理論上應該只會出現這一件商品的結果）
        root = rootInActiveWindow ?: run {
            appendDebugLog("  → [FB] 貼上連結後讀不到畫面"); return false
        }
        val resultCard = findClickableAncestorContainingDesc(root, "蝦皮購物")
        if (resultCard == null) {
            appendDebugLog("  → [FB] 貼上連結後找不到搜尋結果商品卡，請確認畫面實際狀態（可能連結格式不符或還在載入）")
            return false
        }
        // 【2026-08-28修正】原本用clickNodeBestEffort（靠ACTION_CLICK），實測發現FB
        // 這類自繪商品卡ACTION_CLICK回報成功但畫面完全沒反應，改用真實座標手勢點擊。
        val cardBounds = Rect()
        resultCard.getBoundsInScreen(cardBounds)
        appendDebugLog("  → [FB] 準備點擊搜尋結果商品卡，座標中心點=(${cardBounds.centerX()}, ${cardBounds.centerY()})，卡片範圍=$cardBounds")
        if (!tapNodeCenter(resultCard)) {
            appendDebugLog("  → [FB] 點擊搜尋結果商品卡失敗（手勢送出失敗）"); return false
        }
        delay(1800)

        // 【2026-08-28新增診斷】點擊後兩種方式（ACTION_CLICK、座標手勢）都實測失敗過，
        // 先不急著猜第三種，直接截圖記錄點擊後當下畫面實況，比繼續猜更快找到真正原因。
        run {
            val bitmap = withTimeoutOrNull(4000) { captureScreenshotSuspend() }
            if (bitmap != null) {
                try {
                    val screenshotDir = File(
                        android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                        "FbUploadDebugScreenshots"
                    )
                    if (!screenshotDir.exists()) screenshotDir.mkdirs()
                    val fileName = "after_tap_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
                    val imgFile = File(screenshotDir, fileName)
                    FileOutputStream(imgFile).use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out) }
                    appendDebugLog("  → [FB] 點擊後截圖已存檔：${imgFile.absolutePath}")
                } catch (e: Exception) {
                    appendDebugLog("  → [FB] 點擊後截圖存檔失敗：${e.javaClass.simpleName} ${e.message}")
                }
            } else {
                appendDebugLog("  → [FB] 點擊後截圖失敗或逾時")
            }
        }

        // 3. 等商品詳情頁出現（找「建立貼文」按鈕）
        // 【2026-08-28調整】原本4秒常常等不到，實測發現商品詳情頁（含佣金/賣家資料）
        // 載入比預期慢，拉長到10秒給網路請求足夠時間。
        if (!waitForAnyText(listOf("建立貼文"), 10000)) {
            appendDebugLog("  → [FB] 等不到商品詳情頁「建立貼文」按鈕")
            // 逾時失敗時也順手dump一次節點樹，跟截圖互相對照，兩份證據一起看更準
            dumpCurrentNodeTree()
            return false
        }
        delay(600)
        root = rootInActiveWindow ?: return false
        val createPostButton = findNodeByTexts(root, listOf("建立貼文"))
        if (createPostButton == null || !clickNodeBestEffort(createPostButton)) {
            appendDebugLog("  → [FB] 找不到或點擊「建立貼文」失敗"); return false
        }
        delay(1200)

        // 4. 等「加到新Reel／加到新貼文」選單，點「加到新Reel」
        if (!waitForAnyText(listOf("加到新 Reel", "加到新Reel"), 3000)) {
            appendDebugLog("  → [FB] 等不到「加到新Reel」選單"); return false
        }
        root = rootInActiveWindow ?: return false
        val addToReelButton = findNodeByTexts(root, listOf("加到新 Reel", "加到新Reel"))
        if (addToReelButton == null || !clickNodeBestEffort(addToReelButton)) {
            appendDebugLog("  → [FB] 找不到或點擊「加到新Reel」失敗"); return false
        }
        delay(2500)

        // 5. 等Reel錄影介面，點左下角「圖庫」
        if (!waitForAnyText(listOf("圖庫"), 5000)) {
            appendDebugLog("  → [FB] 等不到Reel錄影介面「圖庫」按鈕"); return false
        }
        delay(500)
        root = rootInActiveWindow ?: return false
        val galleryButton = findNodeByTexts(root, listOf("圖庫"))
        if (galleryButton == null || !clickNodeBestEffort(galleryButton)) {
            appendDebugLog("  → [FB] 找不到或點擊「圖庫」失敗"); return false
        }
        delay(1500)

        // 6. 等相簿選片畫面，點「項目1」（最近登記進媒體庫、時間戳記最新的就是目標影片）
        if (!waitForAnyText(listOf("建立 Reel", "項目1"), 4000)) {
            appendDebugLog("  → [FB] 等不到相簿選片畫面"); return false
        }
        delay(500)
        root = rootInActiveWindow ?: return false
        val firstVideoItem = findNodeByDescContaining(root, "項目1，拍攝於")
        if (firstVideoItem == null || !clickNodeBestEffort(firstVideoItem)) {
            appendDebugLog("  → [FB] 找不到或點擊相簿第一個影片項目失敗"); return false
        }
        delay(1800)

        // 7. 等影片編輯預覽畫面，點「下一步」
        if (!waitForAnyText(listOf("下一步"), 5000)) {
            appendDebugLog("  → [FB] 等不到影片編輯預覽畫面「下一步」"); return false
        }
        delay(600)
        root = rootInActiveWindow ?: return false
        val editorNextButton = findNodeByTexts(root, listOf("下一步"))
        if (editorNextButton == null || !clickNodeBestEffort(editorNextButton)) {
            appendDebugLog("  → [FB] 找不到或點擊影片編輯預覽「下一步」失敗"); return false
        }
        delay(2200)

        // 8. 等「Reel設定」畫面
        if (!waitForAnyText(listOf("Reel 設定", "Reel設定"), 4000)) {
            appendDebugLog("  → [FB] 等不到「Reel設定」畫面"); return false
        }
        delay(1200)

        // 8b. 若跳出「已新增商品連結」提示橫幅，點「關閉」讓畫面乾淨（不影響商品已自動帶入的結果）
        root = rootInActiveWindow ?: return false
        findTextContaining(root, "已新增商品連結")?.let {
            val closeBtn = findNodeByTexts(root, listOf("關閉"))
            if (closeBtn != null) {
                clickNodeBestEffort(closeBtn)
                appendDebugLog("  → [FB] 已關閉「已新增商品連結」提示橫幅")
                delay(600)
            }
        }

        // 9. 填文案（沿用跟蝦皮短影音同一套「黃金3秒/痛點/導購」文案組裝邏輯）
        root = rootInActiveWindow ?: return false
        val captionInput = findSearchBoxNode(root)
        if (captionInput == null) {
            appendDebugLog("  → [FB] 找不到文案輸入框"); return false
        }
        val fbCaption = buildShortVideoCaption(candidate)
        val captionBundle = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, fbCaption)
        }
        captionInput.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, captionBundle)
        appendDebugLog("  → [FB] 已填入文案（長度=${fbCaption.length}字）")
        delay(1500)
        captionInput.performAction(AccessibilityNodeInfo.ACTION_CLEAR_FOCUS)
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
        imm?.hideSoftInputFromWindow(null, 0)
        delay(1000)

        // 10. 往下捲動找「新增 AI 標籤」開關並開啟（跟蝦皮流程的「AI生成影片標記」對應，
        // 使用者要求跟蝦皮一樣開啟。這顆開關跟蝦皮那幾顆自訂繪製的開關不同，節點樹裡有
        // 正常的[可勾選]屬性、本身就是可點擊節點，不用像蝦皮那樣用座標點擊法繞過）。
        var aiTagToggle: AccessibilityNodeInfo? = null
        for (attempt in 1..4) {
            root = rootInActiveWindow ?: return false
            aiTagToggle = findNodeByDescContaining(root, "新增 AI 標籤")
            if (aiTagToggle != null) break
            appendDebugLog("  → [FB] 第${attempt}次找不到「新增AI標籤」開關，往下滑動後重試")
            performScrollDown()
            delay(700)
        }
        if (aiTagToggle == null) {
            appendDebugLog("  → [FB] 捲動4次後仍找不到「新增AI標籤」開關，跳過不開啟（不當作整體失敗）")
        } else {
            val alreadyChecked = aiTagToggle.isChecked
            if (alreadyChecked) {
                appendDebugLog("  → [FB] 「新增AI標籤」開關本來就是開啟狀態，不用再點")
            } else if (clickNodeBestEffort(aiTagToggle)) {
                appendDebugLog("  → [FB] 已點擊開啟「新增AI標籤」開關")
            } else {
                appendDebugLog("  → [FB] 找到「新增AI標籤」開關但點擊失敗，跳過不開啟（不當作整體失敗）")
            }
            delay(900)
        }

        // 11. 往下捲動，找「立即分享」按鈕（畫面較長，不一定在可視範圍內）
        var shareButton: AccessibilityNodeInfo? = null
        for (attempt in 1..4) {
            root = rootInActiveWindow ?: return false
            shareButton = findNodeByTexts(root, listOf("立即分享"))
            if (shareButton != null) break
            appendDebugLog("  → [FB] 第${attempt}次找不到「立即分享」，往下滑動後重試")
            performScrollDown()
            delay(700)
        }
        if (shareButton == null) {
            appendDebugLog("  → [FB] 捲動4次後仍找不到「立即分享」按鈕"); return false
        }
        if (!clickNodeBestEffort(shareButton)) {
            appendDebugLog("  → [FB] 點擊「立即分享」失敗"); return false
        }

        // 12. 判定成功：按下分享後，畫面上不再有「Reel設定」標題
        delay(4500)
        val stillOnSettingsScreen = rootInActiveWindow?.let { findTextContaining(it, "Reel 設定") != null || findTextContaining(it, "Reel設定") != null } == true
        if (stillOnSettingsScreen) {
            appendDebugLog("  → [FB] 按下分享後仍停在「Reel設定」畫面，判定失敗")
            return false
        }

        appendDebugLog("  → [FB] 發佈完成，尚未自動導航回「聯盟合作」搜尋畫面，下一筆需要手動導航回去（待之後補上自動導航）")
        return true
    }

    /**
     * 在節點樹裡找「可點擊、且自己或子孫節點的contentDescription包含指定子字串」的
     * 最外層（最先符合條件、深度最淺）節點。用來定位FB搜尋結果商品卡這種「整張卡片
     * 都可點擊、但真正帶關鍵字的desc在裡面某個子節點」的結構。
     */
    /**
     * 【2026-08-28重寫】原本邏輯是「由上往下找，第一個同時符合可點擊+子孫節點藏著這個desc」，
     * 結果實測發現FB搜尋結果清單最外層的可捲動容器本身也可點擊、也符合「子孫裡有這個desc」，
     * 所以每次都被最外層攔截，點下去等於點在整個清單的空白處，完全沒反應（截圖證實：點擊後
     * 畫面停在原本的搜尋結果列表，沒有任何變化）。改成反過來：先精準找到desc完全等於這個
     * 字串的節點本身（不看可不可點擊），再從它開始往上找最近一層可點擊的祖先節點，
     * 這樣才會準確落在真正的商品卡片上，而不是外層容器。
     */
    private fun findClickableAncestorContainingDesc(root: AccessibilityNodeInfo, exactDesc: String): AccessibilityNodeInfo? {
        var targetNode: AccessibilityNodeInfo? = null
        fun findExact(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || targetNode != null || depth > 30) return
            if (node.contentDescription?.toString() == exactDesc) {
                targetNode = node
                return
            }
            for (i in 0 until node.childCount) findExact(node.getChild(i), depth + 1)
        }
        findExact(root, 0)
        val start = targetNode ?: return null

        var ancestor = start.parent
        var depth = 0
        while (ancestor != null && depth < 8) {
            if (ancestor.isClickable) return ancestor
            ancestor = ancestor.parent
            depth++
        }
        return null
    }

    /**
     * 在節點樹裡找「contentDescription包含指定子字串」的第一個可點擊節點。
     * 用來定位FB相簿選片畫面裡desc格式「影片，項目1，拍攝於...」這種節點。
     */
    private fun findNodeByDescContaining(root: AccessibilityNodeInfo, substring: String): AccessibilityNodeInfo? {
        var found: AccessibilityNodeInfo? = null
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || found != null || depth > 25) return
            val desc = node.contentDescription?.toString()
            if (node.isClickable && desc != null && desc.contains(substring)) {
                found = node
                return
            }
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }
        walk(root, 0)
        return found
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
        // narrationText現在用換行符號分隔句子（Python端make_video.py已改成"\n".join()，
        // 不再用中文句號「。」——PH版是英文/Taglish句子，中文句號分隔會混進不必要的中文標點）
        val sentences = candidate.narrationText
            .split("\n")
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val hook = sentences.firstOrNull() ?: candidate.productName
        val middle = sentences.getOrNull(1)

        val tags = buildHashtags(candidate)
        val tagsLine = tags.joinToString(" ") { "#$it" }

        // AI／規則模板產生的句子本身已經帶有完整標點（問號、句號等），不再額外附加中文標點，
        // 先組「鉤子＋標籤」這個一定要保留的核心部分，字數還有剩才加中間句
        val core = "$hook\n$tagsLine"
        val withMiddle = if (!middle.isNullOrBlank()) "$hook $middle\n$tagsLine" else core

        val result = if (withMiddle.length <= maxLength) withMiddle else core
        return if (result.length <= maxLength) result else result.take(maxLength)
    }

    /**
     * 產生5個標籤：優先使用meta.json裡AI（或規則模板）已經生成好的hashtags欄位
     * （Python端make_video.py已經依地區產生對應語言的標籤，不再需要這裡另外拼湊）。
     * candidate.hashtags為空時（例如舊資料、meta.json缺欄位）才退回：從商品名稱抽取候選詞
     * 補上固定的中文標籤池（僅適用TW舊資料的相容性備援，PH資料應該都會有hashtags欄位）。
     */
    private fun buildHashtags(candidate: UploadCandidate): List<String> {
        if (candidate.hashtags.isNotEmpty()) return candidate.hashtags.take(5)

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
     * 【階段3新增】標記某個商品資料夾已經上架到FB成功：寫入 fbPosted=true 跟時間戳記，
     * 跟markShopeePosted()是同一套機制，各自獨立標記兩個平台各自的上架狀態。
     */
    private fun markFbPosted(folder: File) {
        try {
            val metaFile = File(folder, "meta.json")
            if (!metaFile.isFile) return
            val json = org.json.JSONObject(metaFile.readText())
            json.put("fbPosted", true)
            json.put("fbPostedAt", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date()))
            metaFile.writeText(json.toString(2))
        } catch (e: Exception) {
            appendDebugLog("  → 標記fbPosted失敗（${folder.name}）：${e.javaClass.simpleName} ${e.message}")
        }
    }

    /**
     * 【2026-08-27改版，取代原本的deleteFolderAfterPosted()】
     * 原本蝦皮上架成功後會立刻整個刪除資料夾（含影片）騰空間，但階段3（FB上架）需要
     * 重複利用同一支影片，蝦皮上架完就把影片刪了的話FB階段就沒有影片可以用了。
     * 改成：兩個平台都上架完（shopeePosted=true 且 fbPosted=true）才真的刪除資料夾；
     * 只完成其中一邊的話，先保留整個資料夾（含影片），等另一邊也完成再刪。
     * 目前FB階段還沒開發，所以現況等於「蝦皮上架完先保留，之後接上FB流程才會真的清掉」，
     * 磁碟空間吃緊的問題會回來，之後FB流程做完、跑順了要留意觀察空間狀況。
     */
    private fun deleteFolderIfFullyPosted(folder: File) {
        try {
            val metaFile = File(folder, "meta.json")
            if (!metaFile.isFile) {
                appendDebugLog("  → [${folder.name}] 找不到meta.json，無法判斷是否兩邊都上架完，暫不刪除")
                return
            }
            val json = org.json.JSONObject(metaFile.readText())
            val shopeePosted = json.optBoolean("shopeePosted", false)
            val fbPosted = json.optBoolean("fbPosted", false)
            if (!shopeePosted || !fbPosted) {
                appendDebugLog("  → [${folder.name}] 尚未兩邊都上架完成（shopeePosted=$shopeePosted, fbPosted=$fbPosted），保留資料夾與影片")
                return
            }
            val deleted = folder.deleteRecursively()
            if (deleted) {
                appendDebugLog("  → [${folder.name}] 蝦皮與FB都已上架完成，已刪除整個資料夾，騰出磁碟空間")
            } else {
                appendDebugLog("  → [${folder.name}] 兩邊都上架完成但刪除資料夾失敗（部分檔案可能刪除不完全），可能需要手動清理")
            }
        } catch (e: Exception) {
            appendDebugLog("  → [${folder.name}] 判斷/刪除資料夾發生例外：${e.javaClass.simpleName} ${e.message}")
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

    /**
     * 【開發除錯用，獨立測試】不用跑完整套上架流程，假設呼叫當下畫面已經在
     * 「撰寫內文」畫面（使用者自己手動導航過去），只單獨測試「允許他人合拍」開關的
     * 點擊手法，省去每次都要重跑前面14步的時間。連續嘗試3種手法，中間都有間隔：
     * 手法A：純單點（原本的做法，維持150ms停留，當作對照組）
     * 手法B：模擬真人手指按下後有輕微位移（DOWN在某點，MOVE到附近8px，再UP，停留400ms），
     *        懷疑蝦皮的偵測邏輯可能是「完美靜止的單點=判定為程式模擬」，真人手指
     *        按下去幾乎不可能完全不動，這個手法試著讓觸控軌跡更像真人
     * 手法C：連續點擊2次，中間間隔約1秒——如果單點其實有效但這個開關的render/rebind
     *        有延遲、第一次點擊被「畫面還沒完全準備好接受輸入」吃掉，點第二次可能就會生效；
     *        風險：如果兩次都生效，狀態會被切回原本開啟，這是刻意接受的實驗性風險，
     *        重點是觀察「點兩次前後」狀態到底有沒有變化，藉此判斷是不是這個原因
     * 每次點擊後都記錄座標到debug log，實際成功與否要使用者自己截圖確認畫面。
     * 【2026-08-24追加】網路查到兩個關鍵線索：(1) 有開發者反應dispatchGesture連續呼叫時，
     * 系統的手勢處理狀態有時沒有正確清空，需要一個「真實觸控事件」介入才會恢復正常，
     * 手動滑一下螢幕後同樣的程式碼就能生效；(2) 我們之前呼叫dispatchGesture時
     * callback都傳null，從來不知道每次手勢系統到底是「真的執行了」還是「直接取消」，
     * 這次全部加上GestureResultCallback記錄onCompleted/onCancelled，才能真正對症下藥。
     */
    fun testDuetToggleGestures() {
        serviceScope.launch {
            val root = rootInActiveWindow
            if (root == null) {
                appendDebugLog("  → 【合拍測試】讀不到目前畫面")
                return@launch
            }
            val duetNode = findNodeByIdSuffix(root, "tv_allow_duet")
            if (duetNode == null) {
                appendDebugLog("  → 【合拍測試】目前畫面找不到「允許他人合拍」節點，請確認已經在撰寫內文畫面")
                return@launch
            }

            // 這個開關的實際on/off狀態完全沒有暴露在無障礙節點樹裡，log沒辦法記錄狀態變化，
            // 只能用截圖留存證據。這裡自動截圖存檔，不用再靠使用者眼睛盯著螢幕即時抓時機。
            val sessionId = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val screenshotDir = File(
                android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                "DuetTestScreenshots/$sessionId"
            )
            if (!screenshotDir.exists()) screenshotDir.mkdirs()

            suspend fun captureAndSave(label: String) {
                val bitmap = withTimeoutOrNull(4000) { captureScreenshotSuspend() }
                if (bitmap == null) {
                    appendDebugLog("  → 【合拍測試】$label 後截圖失敗或逾時")
                    return
                }
                try {
                    val safeLabel = label.replace(Regex("[^A-Za-z0-9\\u4e00-\\u9fa5]"), "_")
                    val imgFile = File(screenshotDir, "${safeLabel}.jpg")
                    FileOutputStream(imgFile).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                    }
                    appendDebugLog("  → 【合拍測試】$label 後截圖已存檔：${imgFile.absolutePath}")
                } catch (e: Exception) {
                    appendDebugLog("  → 【合拍測試】$label 後截圖存檔失敗：${e.javaClass.simpleName} ${e.message}")
                }
            }

            appendDebugLog("  → 【合拍測試】開始，共測試5種手法，每個手法點擊後都自動截圖存到 Download/DuetTestScreenshots/$sessionId/")
            captureAndSave("00_測試開始前")

            // 手勢執行結果callback：記錄系統到底是「真的完成」還是「直接取消」這個手勢，
            // 這是之前完全沒有的資訊——過去callback都傳null，等於盲測。
            suspend fun tapDuetOnceWithResult(
                durationMs: Long,
                offsetX: Float,
                offsetY: Float,
                startTime: Long,
                label: String
            ) {
                val rootNow = rootInActiveWindow
                if (rootNow == null) {
                    appendDebugLog("  → 【合拍測試】$label 前讀不到畫面，跳過")
                    return
                }
                val duetNodeNow = findNodeByIdSuffix(rootNow, "tv_allow_duet")
                if (duetNodeNow == null) {
                    appendDebugLog("  → 【合拍測試】$label 前找不到節點，跳過")
                    return
                }
                val bounds = Rect()
                duetNodeNow.getBoundsInScreen(bounds)
                val metrics = resources.displayMetrics
                val x = metrics.widthPixels * 0.9298f
                val y = bounds.centerY().toFloat()
                val path = Path().apply {
                    moveTo(x, y)
                    if (offsetX != 0f || offsetY != 0f) lineTo(x + offsetX, y + offsetY)
                }
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, startTime, durationMs))
                    .build()
                val result = withTimeoutOrNull(3000) {
                    suspendCancellableCoroutine<String> { continuation ->
                        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
                            override fun onCompleted(gestureDescription: GestureDescription?) {
                                if (continuation.isActive) continuation.resume("onCompleted（系統回報手勢有真的執行完成）")
                            }
                            override fun onCancelled(gestureDescription: GestureDescription?) {
                                if (continuation.isActive) continuation.resume("onCancelled（系統回報手勢被取消，沒有真的執行）")
                            }
                        }, null)
                        if (!dispatched && continuation.isActive) {
                            continuation.resume("dispatchGesture()呼叫本身就回傳false（連送出都失敗）")
                        }
                    }
                } ?: "等待callback逾時（3秒內沒收到onCompleted/onCancelled）"
                appendDebugLog("  → 【合拍測試】$label 已送出，X=%.1f Y=%.1f 停留=${durationMs}ms startTime=${startTime}ms → 結果：$result".format(x, y))
                delay(300) // 讓畫面有時間反映點擊後的視覺變化，再截圖
                captureAndSave(label)
            }

            // 手法A：純單點（原本的做法，當對照組）
            tapDuetOnceWithResult(durationMs = 150, offsetX = 0f, offsetY = 0f, startTime = 0, label = "手法A（純單點）")
            delay(2000)

            // 手法B：模擬真人手指輕微位移（DOWN→小幅度MOVE→UP），停留拉長到400ms
            tapDuetOnceWithResult(durationMs = 400, offsetX = 8f, offsetY = 4f, startTime = 0, label = "手法B（帶輕微位移+400ms停留）")
            delay(2000)

            // 手法C：連續點擊2次，中間間隔約1秒
            appendDebugLog("  → 【合拍測試】手法C開始：連續點擊2次，中間間隔1秒")
            tapDuetOnceWithResult(durationMs = 150, offsetX = 0f, offsetY = 0f, startTime = 0, label = "手法C第1次點擊")
            delay(1000)
            tapDuetOnceWithResult(durationMs = 150, offsetX = 0f, offsetY = 0f, startTime = 0, label = "手法C第2次點擊")
            delay(2000)

            // 手法D：網路查到的線索——先送一個「無害的」手勢到畫面空白處，
            // 懷疑這能讓系統的手勢處理管線恢復正常狀態，再點合拍可能就會生效
            appendDebugLog("  → 【合拍測試】手法D開始：先點空白處喚醒手勢管線，再點合拍")
            run {
                val metrics = resources.displayMetrics
                val wakeX = metrics.widthPixels * 0.85f
                val wakeY = metrics.heightPixels * 0.09f // 畫面右上角字數統計附近，非互動區域，比較安全
                val wakePath = Path().apply { moveTo(wakeX, wakeY) }
                val wakeGesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(wakePath, 0, 100))
                    .build()
                dispatchGesture(wakeGesture, null, null)
                appendDebugLog("  → 【合拍測試】手法D：已送出喚醒手勢（空白處點擊）X=%.1f Y=%.1f".format(wakeX, wakeY))
            }
            delay(500)
            tapDuetOnceWithResult(durationMs = 150, offsetX = 0f, offsetY = 0f, startTime = 0, label = "手法D（喚醒後點合拍）")
            delay(2000)

            // 手法E：調整StrokeDescription的startTime參數（從0改成10ms），
            // 網路上找到的範例程式碼都是用startTime=10而不是0，可能有微妙差異
            tapDuetOnceWithResult(durationMs = 150, offsetX = 0f, offsetY = 0f, startTime = 10, label = "手法E（startTime=10ms）")

            appendDebugLog("  → 【合拍測試】5種手法全部結束，麻煩截圖給我看每個手法測試後的開關實際狀態")
        }
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
            cleanupTempUploadCopy()
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
     * 原本的修法是「查有沒有既有紀錄，用UPDATE強制把DATE_ADDED改成現在」，
     * 但實測發現這個UPDATE在這個Android版本上完全無效（回報更新筆數永遠是0，
     * 疑似系統對DATE_ADDED這種欄位的UPDATE做了額外限制，不會報錯但也不會真的生效）。
     * 改成更直接的做法：既有紀錄直接刪除，再重新登記一次——全新登記（不管是首次
     * 掃描還是重新掃描）DATE_ADDED一定會確實是「現在」，不會被系統靜默擋掉。
     */
    /**
     * 【安全性重大修正】上一版做法是「查到既有紀錄就contentResolver.delete()刪掉再重新登記」，
     * 原本以為delete()只會清掉MediaStore的索引紀錄，但實測發現：在有完整儲存權限
     * （MANAGE_EXTERNAL_STORAGE）的情況下，delete()會把「實體檔案」一併刪除，
     * 不只是索引！這個bug已經造成使用者的候選影片檔案被意外刪除，必須立刻修正。
     *
     * 現在改用完全不會動到原始檔案的做法：複製一份暫時的影片副本（檔名帶時間戳記，
     * 不會跟任何既有檔案衝突），登記這份副本進媒體庫（全新檔案，時間戳記保證是最新，
     * 不需要delete或update任何既有紀錄）。副本用完後（呼叫端在選片流程結束、
     * 不管成功失敗）記得呼叫 cleanupTempUploadCopy() 清掉，不會留下垃圾檔案，
     * 但整個過程完全不會刪除或修改原始 output.mp4。
     */
    private var lastTempUploadCopy: File? = null

    private suspend fun registerVideoInMediaStore(videoFile: File): Uri? {
        if (!videoFile.exists()) {
            appendDebugLog("  → 影片登記進媒體庫失敗：檔案不存在（${videoFile.absolutePath}）")
            return null
        }

        val tempCopy = try {
            val tempDir = File(
                android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                "ShopeeUploadTemp"
            )
            if (!tempDir.exists()) tempDir.mkdirs()
            val tempFile = File(tempDir, "upload_${System.currentTimeMillis()}.mp4")
            videoFile.copyTo(tempFile, overwrite = true)
            tempFile
        } catch (e: Exception) {
            appendDebugLog("  → 複製暫時上架副本失敗，改直接用原始檔案登記（風險：時間戳記可能不是最新）：${e.javaClass.simpleName} ${e.message}")
            null
        }

        val fileToRegister = tempCopy ?: videoFile
        lastTempUploadCopy = tempCopy

        val result = withTimeoutOrNull(8000) {
            suspendCancellableCoroutine<Uri?> { continuation ->
                try {
                    MediaScannerConnection.scanFile(
                        applicationContext,
                        arrayOf(fileToRegister.absolutePath),
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
            appendDebugLog("  → 影片登記進媒體庫：逾時或失敗（${fileToRegister.name}）")
        } else {
            appendDebugLog("  → 影片登記進媒體庫成功（暫時副本）：${fileToRegister.name} -> $result")
        }
        return result
    }

    /**
     * 清掉registerVideoInMediaStore()複製的暫時上架副本（只刪副本，不動原始output.mp4）。
     * 呼叫端在每次處理完一筆候選商品（不管成功失敗）都要呼叫這個，避免暫時副本累積佔空間。
     */
    private fun cleanupTempUploadCopy() {
        lastTempUploadCopy?.let { temp ->
            try {
                if (temp.exists()) {
                    val deleted = temp.delete()
                    appendDebugLog("  → 清除暫時上架副本：${temp.name}（成功=$deleted）")
                }
            } catch (e: Exception) {
                appendDebugLog("  → 清除暫時上架副本失敗（不影響原始檔案）：${e.javaClass.simpleName} ${e.message}")
            }
        }
        lastTempUploadCopy = null
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

    /**
     * 找分享／立即推廣按鈕。之前實測發現bug：商品詳情頁中段常有「Learn From Creator」
     * 相關影片推薦區塊，這個區塊部分元件的文字/描述也可能被寬鬆的關鍵字（如單獨的「Share」
     * 「分享」）誤配對到，導致點進別人的短影音貼文而不是本商品自己的分享按鈕。
     * 真正的分享按鈕（「立即推廣」／「Share to Earn」）固定在畫面最下方的操作列，
     * 改成收集所有符合文字/描述的候選節點，取螢幕bounds最靠近底部（top座標最大）的那個，
     * 用位置而不是「第一個比對到的」來鎖定目標，比純文字比對可靠。
     */
    private fun findNodeByDescriptors(root: AccessibilityNodeInfo, texts: List<String>): AccessibilityNodeInfo? {
        val candidates = mutableListOf<AccessibilityNodeInfo>()

        fun resolveClickable(node: AccessibilityNodeInfo, maxDepth: Int): AccessibilityNodeInfo {
            if (node.isClickable) return node
            var parent = node.parent
            var depth = 0
            while (parent != null && depth < maxDepth) {
                if (parent.isClickable) return parent
                parent = parent.parent
                depth++
            }
            return node
        }

        // 文字比對候選
        for (text in texts) {
            for (node in root.findAccessibilityNodeInfosByText(text)) {
                candidates.add(resolveClickable(node, 10))
            }
        }

        // 內容描述（contentDescription）比對候選
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 25) return
            val cd = node.contentDescription?.toString()
            if (cd != null && texts.any { cd.contains(it, ignoreCase = true) }) {
                candidates.add(resolveClickable(node, 4))
            }
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }
        walk(root, 0)

        if (candidates.isEmpty()) return null
        return candidates.maxByOrNull { node ->
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            bounds.top
        }
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
     * 專用於「底部導覽列」這類畫面上可能有多個同文字節點的情境（例如某頁面上方也有
     * 含「Me」文字/描述的圖示，跟底下導覽列的「Me」分頁搞混）。在所有符合文字的候選節點
     * （含往上找可點擊祖先，邏輯同findNodeByTexts）裡，取螢幕bounds的top座標最大（也就是
     * 最靠近畫面底部）的那一個——底部導覽列固定貼在螢幕最下方，這個位置特徵比純文字比對可靠。
     * 找不到任何候選時回傳null。
     */
    private fun findBottommostNodeByTexts(root: AccessibilityNodeInfo, texts: List<String>): AccessibilityNodeInfo? {
        // 原本用 findAccessibilityNodeInfosByText 做子字串比對，抓「Me」這種短字串時
        // 會誤配對到「Home」（Home 這個字本身就包含「me」子字串！），導致找到/點到錯的分頁。
        // 改成手動walk整棵樹，只挑「節點自己的文字或描述，去除頭尾空白後跟目標字串完全相等」
        // 的節點（不分大小寫），比對更嚴謹，不會被字串包含關係誤觸。
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        val targets = texts.map { it.trim().lowercase() }
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 30) return
            val text = node.text?.toString()?.trim()?.lowercase()
            val desc = node.contentDescription?.toString()?.trim()?.lowercase()
            if ((text != null && text in targets) || (desc != null && desc in targets)) {
                if (node.isClickable) {
                    candidates.add(node)
                } else {
                    var parent = node.parent
                    var d = 0
                    var found: AccessibilityNodeInfo? = null
                    while (parent != null && d < 10) {
                        if (parent.isClickable) { found = parent; break }
                        parent = parent.parent
                        d++
                    }
                    candidates.add(found ?: node)
                }
            }
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }
        walk(root, 0)
        if (candidates.isEmpty()) return null
        return candidates.maxByOrNull { node ->
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            bounds.top
        }
    }

    /**
     * 記錄「哪些商品已經擷取過」的持久化清單，跨越不同次「自動」執行、甚至跨越 App 重啟都會保留，
     * 避免今天跑過的商品明天重新搜尋又被抓一次、產生重複資料夾。
     * 用商品名稱做「早期快速判斷」（省下後面截圖、讀剪貼簿的時間），用連結做最終確認（比較準確）。
     */
    private fun getDedupPrefs() = getSharedPreferences("capture_dedup_prefs", Context.MODE_PRIVATE)

    /**
     * 永久擷取歷史記錄檔案的路徑。這個檔案獨立存在Downloads底下，跟CaptionQueue商品資料夾
     * 完全分開——就算使用者事後把某支商品的資料夾（含影片）整個刪掉，這裡的紀錄依然保留，
     * 之後也不會被誤判成「沒擷取過」而重新擷取一次。格式是每行一筆JSON物件（JSON Lines），
     * append-only，只會增加不會覆寫，寫入失敗不影響擷取主流程。
     */
    private fun getCaptureHistoryFile(): File {
        val dir = File(
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
            "CaptureHistory"
        )
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "captured_history.jsonl")
    }

    /**
     * 掃過CaptionQueue底下所有資料夾的meta.json，加上永久擷取歷史記錄檔案，把商品名稱與連結
     * 補進防重複SharedPreferences——這是讓防重複資料庫在App被重裝、SharedPreferences被清空後
     * 也能「自癒」回正確狀態的關鍵函式。只掃CaptionQueue的話，使用者事後刪除影片資料夾＋
     * App剛好又被重裝這兩件事同時發生時，該商品的擷取記憶會徹底消失、被誤判成新商品重新
     * 擷取一次；額外合併永久歷史記錄檔案（不隨資料夾刪除而消失）就能避免這個邊界情況。
     * 只會新增不會覆蓋，掃描失敗的單一資料夾/單一行略過不影響其他資料，整個函式失敗也不
     * 影響擷取主流程繼續進行。
     */
    private fun syncDedupPrefsFromDisk() {
        try {
            val existingNames = (getDedupPrefs().getStringSet("captured_names", emptySet()) ?: emptySet()).toMutableSet()
            val existingLinks = (getDedupPrefs().getStringSet("captured_links", emptySet()) ?: emptySet()).toMutableSet()
            val beforeNameCount = existingNames.size
            val beforeLinkCount = existingLinks.size

            // 來源2先讀（永久歷史記錄檔案），記下目前永久記錄裡已經有哪些，等一下掃磁碟時
            // 才知道哪些是永久記錄裡「還沒有」的、需要回填進去，不會每次都整批重複寫入。
            val historyFile = getCaptureHistoryFile()
            val namesInHistory = mutableSetOf<String>()
            val linksInHistory = mutableSetOf<String>()
            if (historyFile.isFile) {
                historyFile.forEachLine { line ->
                    if (line.isBlank()) return@forEachLine
                    try {
                        val json = org.json.JSONObject(line)
                        json.optString("name", "").takeIf { it.isNotBlank() }?.let {
                            namesInHistory.add(it); existingNames.add(it)
                        }
                        json.optString("link", "").takeIf { it.isNotBlank() }?.let {
                            linksInHistory.add(it); existingLinks.add(it)
                        }
                    } catch (e: Exception) {
                        // 單行格式異常略過，不影響其他行
                    }
                }
            }

            // 來源1：CaptionQueue底下目前實際還存在的商品資料夾。
            // 這批資料如果永久歷史記錄裡還沒有，代表是這個修正上線「之前」就已經擷取好的
            // 舊商品（當時的markAsCaptured()還不會寫進永久記錄），這裡順便回填進永久記錄，
            // 這樣即使之後被刪除，記憶依然保得住，不用等使用者手動處理舊資料。
            val root = File(
                android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                "CaptionQueue"
            )
            val dirs = if (root.isDirectory) root.listFiles { f -> f.isDirectory } ?: emptyArray() else emptyArray()
            val backfillLines = StringBuilder()
            var backfillCount = 0
            for (dir in dirs) {
                val metaFile = File(dir, "meta.json")
                if (!metaFile.isFile) continue
                try {
                    val json = org.json.JSONObject(metaFile.readText())
                    val name = json.optString("productName", "").takeIf { it.isNotBlank() && it != "未知" }
                    val link = json.optString("promoLink", "").takeIf { it.isNotBlank() }
                    name?.let { existingNames.add(it) }
                    link?.let { existingLinks.add(it) }

                    val nameNeedsBackfill = name != null && name !in namesInHistory
                    val linkNeedsBackfill = link != null && link !in linksInHistory
                    if (nameNeedsBackfill || linkNeedsBackfill) {
                        val record = org.json.JSONObject().apply {
                            put("name", name ?: "")
                            put("link", link ?: "")
                            put("capturedAt", System.currentTimeMillis())
                            put("backfilled", true)
                        }
                        backfillLines.append(record.toString()).append("\n")
                        name?.let { namesInHistory.add(it) }
                        link?.let { linksInHistory.add(it) }
                        backfillCount++
                    }
                } catch (e: Exception) {
                    // 單一資料夾的meta.json讀取/解析失敗不影響其他資料夾繼續掃描
                }
            }
            if (backfillCount > 0) {
                try {
                    historyFile.appendText(backfillLines.toString())
                    appendDebugLog("  → 已將 $backfillCount 筆磁碟上既有商品回填進永久歷史記錄")
                } catch (e: Exception) {
                    appendDebugLog("  → ⚠ 回填永久歷史記錄失敗（不影響本次擷取）：${e.javaClass.simpleName} ${e.message}")
                }
            }

            val editor = getDedupPrefs().edit()
            editor.putStringSet("captured_names", existingNames)
            editor.putStringSet("captured_links", existingLinks)
            editor.apply()

            val addedNames = existingNames.size - beforeNameCount
            val addedLinks = existingLinks.size - beforeLinkCount
            if (addedNames > 0 || addedLinks > 0) {
                appendDebugLog("  → 從磁碟資料＋永久歷史記錄校正防重複資料庫：新補入商品名稱 $addedNames 筆、連結 $addedLinks 筆")
            }
        } catch (e: Exception) {
            appendDebugLog("  → ⚠ 校正防重複資料庫時發生例外（不影響本次擷取繼續進行）：${e.javaClass.simpleName} ${e.message}")
        }
    }

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

        // 同步寫進永久歷史記錄檔案，就算之後這支商品的資料夾被刪掉，這筆記憶依然保留，
        // 不會因為刪除影片＋App重裝這兩件事剛好同時發生，就被誤判成沒擷取過而重新擷取。
        try {
            val record = org.json.JSONObject().apply {
                put("name", name ?: "")
                put("link", link ?: "")
                put("capturedAt", System.currentTimeMillis())
            }
            getCaptureHistoryFile().appendText(record.toString() + "\n")
        } catch (e: Exception) {
            // 寫入永久歷史記錄失敗不影響本次擷取結果，SharedPreferences那份記錄依然有效
        }
    }

    private fun findLikelyProductNameText(root: AccessibilityNodeInfo): String? {
        val candidates = mutableListOf<String>()
        // 搜尋深度從 12 提高到 20：診斷 log 證實部分商品畫面的標題節點層數比較深（超過 12 層），
        // extractProductMetrics 用 maxDepth=20 能穩定讀到同一頁的價格，代表 20 層對這個 App 的畫面結構是夠的，
        // 這裡跟著提高到一致的深度，避免標題因為搜尋深度不夠而完全掃不到。
        collectTextNodes(root, candidates, maxDepth = 20, maxNodes = 60)

        // 過濾掉已知不可能是標題的文字類型（優惠券橫幅、純數字／價格格式、變體數量標籤、
        // 統計徽章標籤），長度落在合理範圍（8~200字）。
        val validCandidates = candidates.filter {
            it.length in 8..200 && !isCouponBannerText(it) && !isPureNumberOrPriceText(it) &&
                !isVariationCountText(it) && !isMetricBadgeText(it)
        }

        // 關鍵修正：改用「挑最長的候選文字」而不是「挑第一個符合的候選文字」。
        // 之前用「第一個符合」的邏輯，每次遇到新的短徽章文字（變體數量、推廣者數、已售出、
        // COMMSXTRA分潤標籤...）只要沒被黑名單擋到，就會搶在真正標題之前被誤判成商品名稱——
        // 這種黑名單式排除法治標不治本，每冒出一種新的UI文字組合就要再中一次獎才補得到規則。
        // 商品標題天生是全畫面裡最長的一段自然語言文字（完整品名+規格描述），一定比任何徽章／
        // 標籤文字長出一截，改成「挑最長」從根本上堵住整類「短文字搶先誤判」的問題，
        // 不用再窮舉每一種可能出現的徽章文字。
        val result = validCandidates.maxByOrNull { it.length }

        if (result == null) {
            // 找不到時記錄候選清單前 15 筆，方便下次排查是「標題太長/太短被排除」還是「根本沒掃到標題」
            appendDebugLog("  → ⚠ 商品名稱找不到，候選文字清單（前 ${candidates.size.coerceAtMost(15)} 筆）：${candidates.take(15)}")
        } else if (result.length < 25 && validCandidates.size > 1) {
            // 防禦性記錄：如果選中的名稱意外偏短（<25字）、但候選清單裡還有其他文字，
            // 記下完整候選清單方便日後排查是不是又出現了新的徽章文字變種，不用等使用者
            // 回報「名稱看起來怪怪的」才後知後覺去查。真正的商品標題幾乎不會短於25字。
            appendDebugLog("  → ⚠ 商品名稱意外偏短（「$result」，${result.length}字），候選清單：${validCandidates.take(10)}")
        }
        return result
    }

    /**
     * 判斷文字是不是「N variations」這種變體數量標籤（例如「8 variations」），不是商品標題。
     * 這個文字長度剛好落在商品標題合理範圍內，又不是純數字也不是優惠券文字，
     * 之前沒有專門排除，導致商品詳情頁裡排在標題「之前」出現的這行變體數量文字，
     * 每次都被誤判搶先當成商品名稱——不只名稱顯示不正確，更嚴重的是防重複比對機制
     * 是拿商品名稱去比對「有沒有擷取過」，只要任何一支商品的變體數量剛好相同（例如都是8個
     * 變體），就會被誤判成「同一支商品」而被錯誤跳過，即使其實是完全不同、從沒擷取過的商品。
     */
    private fun isVariationCountText(text: String): Boolean {
        return Regex("^\\d+\\s*variations?$", RegexOption.IGNORE_CASE).matches(text.trim())
    }

    /**
     * 判斷文字是不是「已推廣者」「已售出」這類統計徽章的純文字標籤半段（例如「Affiliates
     * Promoted」「Sold」），不含數字、不是商品標題。
     * 這類徽章在畫面上常態顯示成「844 Affiliates Promoted」「10K+ Sold」，但accessibility節點
     * 樹裡數字跟文字標籤常被拆成兩個獨立節點：數字那半段會被isPureNumberOrPriceText擋掉，
     * 但純文字標籤那半段完全不含數字，長度又落在標題合理範圍內，如果標題節點本身因為太長
     * 被長度上限排除掉，比對就會往下滑到這種文字，誤判成商品名稱。中文版「位推廣者」「已售出」
     * 通常整段（含數字）就是同一個節點，一併放進來涵蓋以防萬一。
     */
    private fun isMetricBadgeText(text: String): Boolean {
        val t = text.trim()
        val patterns = listOf(
            Regex("^Affiliates?\\s*Promoted$", RegexOption.IGNORE_CASE),
            Regex("^Sold$", RegexOption.IGNORE_CASE),
            Regex("^[\\d,]+\\s*位推廣者$"),
            Regex("^已售出\\s*[\\d,]+\\+?$")
        )
        return patterns.any { it.matches(t) }
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
            // 【階段3新增】跟shopeePosted成對，代表這支影片是否已經FB上架過；
            // 新商品從一開始就寫false，舊資料靠scanFbUploadCandidates()的optBoolean預設值(false)相容。
            put("fbPosted", false)
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
