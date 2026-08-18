package com.tagcopy.shopeecapture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipboardManager
import android.content.Context
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
            delay(randomDelay(config))
        }

        if (successCount >= config.targetCount) {
            reason = FinishReason.TARGET_REACHED
        } else if (totalAttempts >= maxAttempts && reason == FinishReason.TARGET_REACHED) {
            reason = FinishReason.MAX_ATTEMPTS_REACHED
        }

        when (reason) {
            FinishReason.TIME_LIMIT_REACHED ->
                onEvent(AutoCaptureEvent.Log("已達篩選時間上限，僅擷取到 $successCount／${config.targetCount} 件符合條件的商品，已停止"))
            FinishReason.MAX_ATTEMPTS_REACHED ->
                onEvent(AutoCaptureEvent.Log("已達最大嘗試次數（多數商品不符篩選條件），提前結束"))
            else -> {}
        }

        onEvent(AutoCaptureEvent.Finished(successCount, failCount, filteredCount, reason))
        autoJob = null
    }

    private enum class ProcessResult { SUCCESS, FILTERED, FAILED }

    private suspend fun processOneProduct(
        card: AccessibilityNodeInfo,
        config: AutoCaptureConfig,
        onEvent: (AutoCaptureEvent) -> Unit
    ): ProcessResult {
        if (!card.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            onEvent(AutoCaptureEvent.Log("點擊商品卡片失敗，跳過"))
            return ProcessResult.FAILED
        }

        // 等待商品詳情頁真正載入完成（而不是固定延遲後就讀取），避免抓到還沒渲染完的殘缺畫面
        val detailRoot = waitForDetailPageLoaded(3500)
        if (detailRoot == null) {
            performBack()
            return ProcessResult.FAILED
        }

        val productName = findLikelyProductNameText(detailRoot)

        // 篩選檢查：讀取分潤率／價格／已售出／已推廣者，不符合就直接返回上一層跳過
        if (!config.filter.isEmpty()) {
            val metrics = extractProductMetrics(detailRoot)
            if (!config.filter.matches(metrics)) {
                onEvent(AutoCaptureEvent.Log("○ 篩選未通過，略過：${productName ?: "未知商品"}"))
                performBack()
                delay(randomDelay(config))
                return ProcessResult.FILTERED
            }
        }

        val shareNode = findNodeByDescriptors(detailRoot, matchRules.shareButtonDescriptors)
        if (shareNode == null) {
            onEvent(AutoCaptureEvent.Log("找不到分享按鈕，跳過此商品"))
            performBack()
            delay(randomDelay(config))
            return ProcessResult.FAILED
        }

        shareNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        delay(randomDelay(config))

        val sheetAppeared = waitForAnyText(matchRules.shareSheetTitleTexts, 2500)
        if (!sheetAppeared) {
            onEvent(AutoCaptureEvent.Log("分享面板未出現，跳過此商品"))
            performBack()
            delay(randomDelay(config))
            return ProcessResult.FAILED
        }

        val sheetRoot = rootInActiveWindow
        val copyLinkNode = sheetRoot?.let { findNodeByTexts(it, matchRules.copyLinkButtonTexts) }
        if (copyLinkNode == null) {
            onEvent(AutoCaptureEvent.Log("找不到「複製連結」按鈕，跳過此商品"))
            performBack()
            delay(randomDelay(config))
            performBack()
            delay(randomDelay(config))
            return ProcessResult.FAILED
        }

        copyLinkNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        delay(600)
        val link = readClipboard()
        val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) captureScreenshotSuspend() else null

        // 關閉分享面板，回到商品頁，再回到列表
        performBack()
        delay(randomDelay(config))
        performBack()
        delay(randomDelay(config))

        return when (val result = saveResult(productName, link, bitmap)) {
            is CaptureResult.Success -> {
                onEvent(AutoCaptureEvent.Log("✓ 已擷取：${productName ?: "未知商品"}"))
                ProcessResult.SUCCESS
            }
            is CaptureResult.Failure -> {
                onEvent(AutoCaptureEvent.Log("存檔失敗：${result.reason}"))
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

        return ProductMetrics(commission, price, sold, promoter)
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

    private fun performBack() {
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    private fun performScrollDown() {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val path = Path().apply {
            moveTo(width / 2f, height * 0.75f)
            lineTo(width / 2f, height * 0.3f)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
            .build()
        dispatchGesture(gesture, null, null)
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

    private fun findLikelyProductNameText(root: AccessibilityNodeInfo): String? {
        val candidates = mutableListOf<String>()
        collectTextNodes(root, candidates, maxDepth = 12)
        // 取長度落在合理商品標題範圍（8~60 字）且非按鈕文字的第一筆
        return candidates.firstOrNull { it.length in 8..60 }
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
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).text?.toString()
    }

    private fun takeScreenshotAndSave(
        productName: String?,
        link: String?,
        onResult: (CaptureResult) -> Unit
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            // API 30 以下沒有 takeScreenshot()，直接存純文字資料，不含圖片
            val saved = saveResult(productName, link, bitmap = null)
            onResult(saved)
            return
        }
        takeScreenshotCompat { bitmap ->
            val saved = saveResult(productName, link, bitmap)
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

    private fun saveResult(productName: String?, link: String?, bitmap: Bitmap?): CaptureResult {
        val id = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val baseDir = File(
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
            "CaptionQueue/$id"
        )
        if (!baseDir.exists()) baseDir.mkdirs()

        if (bitmap != null) {
            try {
                val imgFile = File(baseDir, "image.jpg")
                FileOutputStream(imgFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                }
            } catch (e: Exception) {
                // 圖片存檔失敗仍繼續保留文字資料
            }
        }

        val metaFile = File(baseDir, "meta.json")
        val metaJson = org.json.JSONObject().apply {
            put("id", id)
            put("productName", productName ?: org.json.JSONObject.NULL)
            put("promoLink", link ?: org.json.JSONObject.NULL)
            put("capturedAt", System.currentTimeMillis())
        }
        metaFile.writeText(metaJson.toString())

        val product = CapturedProduct(id, productName, link, System.currentTimeMillis())
        return CaptureResult.Success(product, baseDir.absolutePath)
    }
}
