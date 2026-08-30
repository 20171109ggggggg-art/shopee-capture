package com.tagcopy.shopeecapture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast

class FloatingButtonService : Service() {

    // instance 讓 companion object（見檔案最下方）的靜態方法能找到目前活著的實例，
    // 用來在截圖前後隱藏/恢復懸浮視窗，避免按鈕被拍進商品圖片。
    // Service 生命週期內只會有一個實例，onCreate 設定、onDestroy 清除。
    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private var floatingViewParams: WindowManager.LayoutParams? = null
    private var calibrationOverlayView: View? = null
    private val calibrationTimeoutHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var calibrationTimeoutRunnable: Runnable? = null

    // 【2026-08-28新增】原本7顆按鈕的長條清單跟「自動化期間收合成一顆停止鈕」共用同一組視圖，
    // companion object要能個別控制每顆按鈕的顯示/隱藏，所以把這幾個原本只在showFloatingButton()
    // 裡的區域變數升級成實例欄位。
    private var regularButtons: List<TextView> = emptyList()
    private var stopAutomationFloatingButton: TextView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        // 服務重新啟動時把隱藏計數與自動化模式歸零，避免上次執行中途被強制關閉、狀態卡住，
        // 導致這次浮球一開起來就莫名其妙顯示錯誤的樣子。
        hideRequestCount = 0
        automationModeActive = false
        startForegroundWithNotification()
        showFloatingButton()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
        floatingView?.let { windowManager?.removeView(it) }
        floatingView = null
        calibrationOverlayView?.let { windowManager?.removeView(it) }
        calibrationOverlayView = null
        calibrationTimeoutRunnable?.let { calibrationTimeoutHandler.removeCallbacks(it) }
        calibrationTimeoutRunnable = null
    }

    private fun startForegroundWithNotification() {
        val channelId = "shopee_capture_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, getString(R.string.notif_channel_floating),
                NotificationManager.IMPORTANCE_MIN
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }

        val stopIntent = Intent(this, FloatingButtonService::class.java).apply { action = ACTION_STOP }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // 【2026-08-28新增】擷取／上架自動化跑的期間浮球會被整個隱藏（見startAutoCapture／
        // startUploadAutomation／startFbUploadAutomation），這段期間畫面上完全沒有任何按鈕
        // 可以喊停。原本notification上唯一的「停止」按鈕只會關掉懸浮視窗服務本身（ACTION_STOP→
        // stopSelf()），不會去取消跑在ShopeeAccessibilityService裡的自動化Job——也就是說按了
        // 那顆鈕，背景的擷取/上架流程其實還是會繼續跑，反而更危險。這裡加一顆獨立的
        // 「停止自動化」按鈕，直接呼叫無障礙服務把目前在跑的Job（不管是哪一種）取消掉，
        // 取消後會觸發原本寫好的finally區塊，浮球也會跟著自動恢復顯示。
        val stopAutomationIntent = Intent(this, FloatingButtonService::class.java).apply {
            action = ACTION_STOP_AUTOMATION
        }
        val stopAutomationPending = PendingIntent.getService(
            this, 1, stopAutomationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = Notification.Builder(this, channelId)
            .setContentTitle(getString(R.string.notif_title_running))
            .setContentText(getString(R.string.notif_text_running))
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.btn_stop_automation),
                stopAutomationPending
            )
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.btn_stop), stopPending)
            .build()

        startForeground(1, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            ACTION_STOP_AUTOMATION -> stopAllRunningAutomation()
        }
        return START_STICKY
    }

    /**
     * 「停止自動化」通知按鈕的實際動作：把無障礙服務裡目前在跑的自動化Job統統取消
     * （三個stopXxx()各自都有isXxxRunning()/job為null的保護，不管當下實際在跑哪一種、
     * 或根本沒有在跑，統統呼叫一輪都是安全的no-op）。浮球會因為對應Job的finally區塊
     * 而自動恢復顯示，不用在這裡另外處理。
     */
    private fun stopAllRunningAutomation() {
        val service = ShopeeAccessibilityService.instance
        if (service == null) {
            Toast.makeText(this, getString(R.string.toast_need_accessibility), Toast.LENGTH_SHORT).show()
            return
        }
        var stoppedAny = false
        if (service.isAutoCaptureRunning()) {
            service.stopAutoCapture()
            stoppedAny = true
        }
        if (service.isUploadAutomationRunning()) {
            service.stopUploadAutomation()
            stoppedAny = true
        }
        if (service.isFbUploadAutomationRunning()) {
            service.stopFbUploadAutomation()
            stoppedAny = true
        }
        Toast.makeText(
            this,
            if (stoppedAny) getString(R.string.toast_automation_stopped_from_notification)
            else getString(R.string.toast_no_automation_running),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun showFloatingButton() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }

        val captureButton = TextView(this).apply {
            text = getString(R.string.btn_capture)
            setBackgroundColor(0xFFE8622C.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            setPadding(36, 24, 36, 24)
        }

        val autoButton = TextView(this).apply {
            text = getString(R.string.btn_auto)
            setBackgroundColor(0xFF1C2331.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            setPadding(36, 24, 36, 24)
        }

        val detectButton = TextView(this).apply {
            text = getString(R.string.btn_detect)
            setBackgroundColor(0xFF8A9A87.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            setPadding(36, 24, 36, 24)
        }

        // 【暫時加的座標校正按鈕】用來精準測量撰寫內文畫面那三個開關的實際螢幕座標
        // （無障礙節點樹完全抓不到這幾個自訂元件，只能靠實際點擊測量）。
        // 校正完成、開關點擊邏輯穩定驗證有效之後，這顆按鈕可以移除。
        val calibrateButton = TextView(this).apply {
            text = getString(R.string.btn_calibrate)
            setBackgroundColor(0xFF7A4FBF.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            setPadding(36, 24, 36, 24)
        }

        // 上架自動化正式按鈕：取代原本借用「偵測」測試的testUploadAutomation()（寫死1支）。
        // 使用前提：目前畫面必須已經在蝦皮「分潤按讚好物／My Likes」清單頁
        // （這段導航還沒自動化，需要先手動切過去）。
        val uploadButton = TextView(this).apply {
            text = getString(R.string.btn_upload)
            setBackgroundColor(0xFF2E7D32.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            setPadding(36, 24, 36, 24)
        }

        // FB上架自動化按鈕（階段3，測試階段固定只跑1支）。使用前提：目前畫面必須已經在
        // FB App「聯盟合作→商品」分頁（畫面上看得到「搜尋商品、品牌或連結」搜尋框），
        // 這段導航還沒自動化，需要先手動切過去。用FB品牌藍跟其他按鈕做視覺區分。
        val fbUploadButton = TextView(this).apply {
            text = getString(R.string.btn_fb_upload)
            setBackgroundColor(0xFF1877F2.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            setPadding(36, 24, 36, 24)
        }

        // 【2026-08-29新增】匯入舊短影音商品名稱（一次性功能，見ShopeeAccessibilityService.
        // startVideoImport說明）。使用前提：目前畫面必須已經在蝦皮「我的短影音」的「影片」
        // 分頁（格狀縮圖清單畫面）。批次大小在App主畫面設定（VideoImportPrefs），這裡直接讀取。
        val videoImportButton = TextView(this).apply {
            text = getString(R.string.btn_video_import)
            setBackgroundColor(0xFF6D4C41.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            setPadding(36, 24, 36, 24)
        }

        // 關閉按鈕：讓使用者不用特地下拉通知欄找停止動作，直接在懸浮視窗上就能收起整個服務。
        // 跟通知欄的停止按鈕（ACTION_STOP）共用同一套stopSelf()邏輯。
        val closeButton = TextView(this).apply {
            text = getString(R.string.btn_close)
            setBackgroundColor(0xFF5C5C5C.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            setPadding(36, 24, 36, 24)
        }

        // 【2026-08-28新增】自動化（擷取／上架／FB上架）跑的期間，原本7顆按鈕的長條清單會整組
        // 收合、只留這一顆「停止自動化」按鈕（跟原本使用者熟悉的「長條→自動化開始後只剩一顆
        // 按鈕」操作習慣一致），比起把整個懸浮視窗完全隱藏、只能靠下拉通知欄才能喊停，
        // 這樣不用額外依賴通知權限，畫面上隨時都點得到。平常（沒有自動化在跑）預設是GONE。
        val stopAutomationButton = TextView(this).apply {
            text = getString(R.string.btn_stop_automation)
            setBackgroundColor(0xFFB3261E.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            setPadding(36, 24, 36, 24)
            visibility = View.GONE
        }
        stopAutomationFloatingButton = stopAutomationButton

        container.addView(captureButton)
        container.addView(autoButton)
        container.addView(detectButton)
        container.addView(calibrateButton)
        container.addView(uploadButton)
        container.addView(fbUploadButton)
        container.addView(videoImportButton)
        container.addView(closeButton)
        container.addView(stopAutomationButton)

        regularButtons = listOf(captureButton, autoButton, detectButton, calibrateButton, uploadButton, fbUploadButton, videoImportButton, closeButton)

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = 300

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDrag = false

        val dragListener = View.OnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDrag = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8) isDrag = true
                    params.x = initialX + dx
                    params.y = initialY + dy
                    windowManager?.updateViewLayout(container, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDrag) {
                        when (view) {
                            captureButton -> onCaptureButtonTapped()
                            autoButton -> onAutoButtonTapped(autoButton)
                            detectButton -> onDetectButtonTapped()
                            calibrateButton -> toggleCoordinateCalibrationOverlay()
                            uploadButton -> onUploadButtonTapped(uploadButton)
                            fbUploadButton -> onFbUploadButtonTapped(fbUploadButton)
                            videoImportButton -> onVideoImportButtonTapped()
                            closeButton -> stopSelf()
                            stopAutomationButton -> stopAllRunningAutomation()
                        }
                    }
                    true
                }
                else -> false
            }
        }
        captureButton.setOnTouchListener(dragListener)
        autoButton.setOnTouchListener(dragListener)
        detectButton.setOnTouchListener(dragListener)
        calibrateButton.setOnTouchListener(dragListener)
        uploadButton.setOnTouchListener(dragListener)
        fbUploadButton.setOnTouchListener(dragListener)
        videoImportButton.setOnTouchListener(dragListener)
        closeButton.setOnTouchListener(dragListener)
        stopAutomationButton.setOnTouchListener(dragListener)

        floatingView = container
        floatingViewParams = params
        windowManager?.addView(container, params)

        // 【2026-08-28新增】防呆保險：服務剛啟動時，不管companion object裡的狀態理論上對不對，
        // 都強制依目前狀態重新套用一次顯示邏輯，確保畫面上看到的一定跟實際狀態一致
        // （避免萬一先前有殘留狀態沒清乾淨，導致浮球一開起來就顯示錯誤的樣子）。
        applyVisibilityStateNow()
    }

    /**
     * 【暫時加的座標校正功能】開/關一個全螢幕透明覆蓋層：開啟後，接下來每點一下螢幕
     * （不管點在畫面上什麼東西上面），座標都會記進debug log，不會真的把觸控傳給
     * 底下的蝦皮App（校正模式下點擊到的開關不會真的被切換，純粹只是量測座標）。
     * 再點一次「校正」按鈕就會關閉覆蓋層，恢復正常操作。
     * 校正完成、開關點擊邏輯穩定驗證有效之後，這個功能可以整個移除。
     */
    private fun toggleCoordinateCalibrationOverlay() {
        val existing = calibrationOverlayView
        if (existing != null) {
            disableCalibrationOverlay()
            return
        }

        val overlay = View(this)
        overlay.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                ShopeeAccessibilityService.instance?.logCalibrationTap(event.rawX, event.rawY)
                Toast.makeText(this, "已記錄：X=${event.rawX.toInt()} Y=${event.rawY.toInt()}", Toast.LENGTH_SHORT).show()
            }
            true
        }

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        overlayParams.gravity = Gravity.TOP or Gravity.START

        calibrationOverlayView = overlay
        windowManager?.addView(overlay, overlayParams)

        // 關鍵修正：overlay是後加的視窗，z-order會蓋在浮動按鈕上面，導致想再點「校正」關閉
        // 都會被overlay攔截、完全點不到按鈕（先前實測發生過這個問題，只能重開機解決）。
        // 這裡把浮動按鈕層移除再重新加回去，讓它回到最上層，全程保持可以點得到。
        floatingView?.let { fv ->
            windowManager?.removeView(fv)
            windowManager?.addView(fv, floatingViewParams)
        }

        // 安全機制：就算上面的置頂補救萬一還是失效，30秒後也會自動關閉校正模式，
        // 不會再發生「整個畫面點不到任何東西、只能重開機」的狀況。
        calibrationTimeoutRunnable?.let { calibrationTimeoutHandler.removeCallbacks(it) }
        val timeoutRunnable = Runnable {
            if (calibrationOverlayView != null) {
                Toast.makeText(this, "座標校正模式：逾時自動關閉", Toast.LENGTH_LONG).show()
                disableCalibrationOverlay()
            }
        }
        calibrationTimeoutRunnable = timeoutRunnable
        calibrationTimeoutHandler.postDelayed(timeoutRunnable, 30000)

        Toast.makeText(this, "座標校正模式：已開啟，點畫面任意處會記錄座標（再按一次校正鈕關閉，30秒後也會自動關閉）", Toast.LENGTH_LONG).show()
    }

    private fun disableCalibrationOverlay() {
        calibrationOverlayView?.let { windowManager?.removeView(it) }
        calibrationOverlayView = null
        calibrationTimeoutRunnable?.let { calibrationTimeoutHandler.removeCallbacks(it) }
        calibrationTimeoutRunnable = null
        Toast.makeText(this, "座標校正模式：已關閉", Toast.LENGTH_SHORT).show()
    }

    private fun onDetectButtonTapped() {
        val service = ShopeeAccessibilityService.instance
        if (service == null) {
            Toast.makeText(this, getString(R.string.toast_need_accessibility), Toast.LENGTH_LONG).show()
            return
        }
        // 【階段2測試，暫時加的】借用偵測按鈕順便測試影片登記進媒體庫功能+候選商品掃描+節點樹診斷+上架自動化（1筆）+合拍開關獨立測試，
        // 結果看debug log，不影響下面原本「顯示目前套件名稱」的功能。
        // 這個上架自動化整個做完之後，這幾行連同ShopeeAccessibilityService.kt裡的
        // testMediaStoreRegistration()/testScanUploadCandidates()/dumpCurrentNodeTree()/testUploadAutomation()/testDuetToggleGestures()都可以一起移除。
        service.testMediaStoreRegistration()
        service.testScanUploadCandidates()
        service.testScanFbUploadCandidates()
        service.dumpCurrentNodeTree()
        service.testUploadAutomation()
        service.testDuetToggleGestures()
        val packageName = service.getCurrentPackageName()
        if (packageName == null) {
            Toast.makeText(this, getString(R.string.toast_no_package), Toast.LENGTH_LONG).show()
            return
        }
        showAlertNotification(
            getString(R.string.alert_title_package),
            getString(R.string.alert_msg_package, packageName)
        )
    }

    private fun onAutoButtonTapped(autoButton: TextView) {
        val service = ShopeeAccessibilityService.instance
        if (service == null) {
            Toast.makeText(this, getString(R.string.toast_need_accessibility), Toast.LENGTH_LONG).show()
            return
        }

        if (service.isAutoCaptureRunning()) {
            service.stopAutoCapture()
            autoButton.text = getString(R.string.btn_auto)
            Toast.makeText(this, getString(R.string.toast_auto_stopped), Toast.LENGTH_SHORT).show()
            return
        }

        val config = AutoCapturePrefs.load(this)
        autoButton.text = "0/${config.targetCount}"
        Toast.makeText(this, getString(R.string.toast_auto_started, config.targetCount), Toast.LENGTH_SHORT).show()

        service.startAutoCapture(config) { event ->
            when (event) {
                is AutoCaptureEvent.Log -> {
                    // 簡短提示，避免 Toast 洗版太頻繁只顯示重點
                    if (event.message.startsWith("✓") || event.message.contains("結束") || event.message.contains("錯誤")) {
                        Toast.makeText(this, event.message, Toast.LENGTH_SHORT).show()
                    }
                }
                is AutoCaptureEvent.Progress -> {
                    autoButton.text = "${event.current}/${event.total}"
                    stopAutomationFloatingButton?.text = "${getString(R.string.btn_stop_automation)}\n${event.current}/${event.total}"
                }
                is AutoCaptureEvent.Finished -> {
                    autoButton.text = getString(R.string.btn_auto)
                    stopAutomationFloatingButton?.text = getString(R.string.btn_stop_automation)
                    when (event.reason) {
                        FinishReason.TIME_LIMIT_REACHED -> showAlertNotification(
                            getString(R.string.alert_title_time_limit),
                            getString(R.string.alert_msg_time_limit, event.successCount, event.filteredCount)
                        )
                        FinishReason.MAX_ATTEMPTS_REACHED -> showAlertNotification(
                            getString(R.string.alert_title_max_attempts),
                            getString(R.string.alert_msg_max_attempts, event.successCount)
                        )
                        else -> Toast.makeText(
                            this,
                            getString(
                                R.string.toast_finished_summary,
                                event.successCount, event.filteredCount, event.failCount
                            ),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    /**
     * 正式的「上架」按鈕：取代原本借用「偵測」測試的testUploadAutomation()（寫死只跑1支）。
     * 按下前提：目前畫面必須已經在蝦皮「分潤按讚好物／My Likes」清單頁（這段導航目前
     * 還沒自動化，需要先手動切過去）。支數從UploadAutomationPrefs讀取，可在App主畫面調整。
     */
    private fun onUploadButtonTapped(uploadButton: TextView) {
        val service = ShopeeAccessibilityService.instance
        if (service == null) {
            Toast.makeText(this, getString(R.string.toast_upload_need_accessibility), Toast.LENGTH_LONG).show()
            return
        }

        if (service.isUploadAutomationRunning()) {
            service.stopUploadAutomation()
            uploadButton.text = getString(R.string.btn_upload)
            Toast.makeText(this, getString(R.string.toast_upload_stopped), Toast.LENGTH_SHORT).show()
            return
        }

        val targetCount = UploadAutomationPrefs.getTargetCount(this)
        uploadButton.text = "0/$targetCount"
        Toast.makeText(this, getString(R.string.toast_upload_started, targetCount), Toast.LENGTH_SHORT).show()

        service.startUploadAutomation(targetCount) { event ->
            when (event) {
                is UploadEvent.Log -> {
                    if (event.message.startsWith("✓") || event.message.startsWith("✗")) {
                        Toast.makeText(this, event.message, Toast.LENGTH_SHORT).show()
                    }
                }
                is UploadEvent.Progress -> {
                    uploadButton.text = "${event.current}/${event.total}"
                    stopAutomationFloatingButton?.text = "${getString(R.string.btn_stop_automation)}\n${event.current}/${event.total}"
                }
                is UploadEvent.Finished -> {
                    uploadButton.text = getString(R.string.btn_upload)
                    stopAutomationFloatingButton?.text = getString(R.string.btn_stop_automation)
                    val reasonText = when (event.reason) {
                        UploadFinishReason.ALL_DONE -> getString(R.string.upload_finish_reason_all_done)
                        UploadFinishReason.MAX_COUNT_REACHED -> getString(R.string.upload_finish_reason_max_count)
                        UploadFinishReason.NO_CANDIDATES -> getString(R.string.upload_finish_reason_no_candidates)
                        UploadFinishReason.STOPPED_ON_FAILURE -> getString(R.string.upload_finish_reason_stopped_on_failure)
                    }
                    showAlertNotification(
                        getString(R.string.alert_title_upload_finished),
                        getString(R.string.alert_msg_upload_finished, event.successCount, event.failCount, reasonText)
                    )
                }
            }
        }
    }

    /**
     * 匯入舊短影音商品名稱（一次性功能）。按下前提：目前畫面必須已經在蝦皮「我的短影音」
     * 的「影片」分頁（格狀縮圖清單畫面）。批次大小在App主畫面設定（VideoImportPrefs）。
     */
    private fun onVideoImportButtonTapped() {
        val service = ShopeeAccessibilityService.instance
        if (service == null) {
            Toast.makeText(this, getString(R.string.toast_need_accessibility), Toast.LENGTH_LONG).show()
            return
        }

        if (service.isVideoImportRunning()) {
            service.stopVideoImport()
            Toast.makeText(this, "已送出停止匯入請求", Toast.LENGTH_SHORT).show()
            return
        }

        val batchSize = VideoImportPrefs.loadBatchSize(this)
        stopAutomationFloatingButton?.text = "${getString(R.string.btn_stop_automation)}\n0/$batchSize"
        Toast.makeText(this, "開始匯入舊短影音商品名稱，目標本批 $batchSize 筆", Toast.LENGTH_SHORT).show()

        service.startVideoImport(batchSize) { event ->
            when (event) {
                is VideoImportEvent.Log -> {
                    Toast.makeText(this, event.message, Toast.LENGTH_SHORT).show()
                }
                is VideoImportEvent.Progress -> {
                    stopAutomationFloatingButton?.text = "${getString(R.string.btn_stop_automation)}\n${event.newCount}/${event.batchTarget}"
                }
                is VideoImportEvent.Finished -> {
                    stopAutomationFloatingButton?.text = getString(R.string.btn_stop_automation)
                    showAlertNotification(
                        "匯入舊短影音結束",
                        "本批次新增 ${event.newlyImportedCount} 筆，資料庫累積共 ${event.totalKnownCount} 筆\n原因：${event.reason}"
                    )
                }
            }
        }
    }

    /**
     * FB上架自動化按鈕（階段3）。按下前提：目前畫面必須已經在FB App「聯盟合作→商品」
     * 分頁（畫面上看得到「搜尋商品、品牌或連結」搜尋框），這段導航目前還沒自動化，
     * 需要先手動切過去。
     */
    private fun onFbUploadButtonTapped(fbUploadButton: TextView) {
        val service = ShopeeAccessibilityService.instance
        if (service == null) {
            Toast.makeText(this, getString(R.string.toast_upload_need_accessibility), Toast.LENGTH_LONG).show()
            return
        }

        if (!UploadAutomationPrefs.isFbUploadEnabled(this)) {
            Toast.makeText(this, getString(R.string.toast_fb_upload_disabled), Toast.LENGTH_LONG).show()
            return
        }

        if (service.isFbUploadAutomationRunning()) {
            service.stopFbUploadAutomation()
            fbUploadButton.text = getString(R.string.btn_fb_upload)
            Toast.makeText(this, getString(R.string.toast_fb_upload_stopped), Toast.LENGTH_SHORT).show()
            return
        }

        // 【2026-08-28修正】原本FB上架固定只跑1支（測試階段刻意先限制），使用者確認FB流程
        // 穩定度已經夠信任，改成跟蝦皮上架共用同一個「本次最多處理支數」設定
        // （UploadAutomationPrefs.getTargetCount），不用各自維護一份支數。
        val targetCount = UploadAutomationPrefs.getTargetCount(this)
        fbUploadButton.text = "0/$targetCount"
        Toast.makeText(this, getString(R.string.toast_fb_upload_started), Toast.LENGTH_SHORT).show()

        service.startFbUploadAutomation(targetCount) { event ->
            when (event) {
                is UploadEvent.Log -> {
                    if (event.message.startsWith("✓") || event.message.startsWith("✗")) {
                        Toast.makeText(this, event.message, Toast.LENGTH_SHORT).show()
                    }
                }
                is UploadEvent.Progress -> {
                    fbUploadButton.text = "${event.current}/${event.total}"
                    stopAutomationFloatingButton?.text = "${getString(R.string.btn_stop_automation)}\n${event.current}/${event.total}"
                }
                is UploadEvent.Finished -> {
                    fbUploadButton.text = getString(R.string.btn_fb_upload)
                    stopAutomationFloatingButton?.text = getString(R.string.btn_stop_automation)
                    val reasonText = when (event.reason) {
                        UploadFinishReason.ALL_DONE -> getString(R.string.upload_finish_reason_all_done)
                        UploadFinishReason.MAX_COUNT_REACHED -> getString(R.string.upload_finish_reason_max_count)
                        UploadFinishReason.NO_CANDIDATES -> getString(R.string.upload_finish_reason_no_candidates)
                        UploadFinishReason.STOPPED_ON_FAILURE -> getString(R.string.upload_finish_reason_stopped_on_failure)
                    }
                    showAlertNotification(
                        getString(R.string.alert_title_fb_upload_finished),
                        getString(R.string.alert_msg_fb_upload_finished, event.successCount, event.failCount, reasonText)
                    )
                }
            }
        }
    }

    private fun onCaptureButtonTapped() {
        val service = ShopeeAccessibilityService.instance
        if (service == null) {
            Toast.makeText(this, getString(R.string.toast_need_accessibility), Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, getString(R.string.toast_capturing), Toast.LENGTH_SHORT).show()
        service.captureCurrentScreen { result ->
            when (result) {
                is CaptureResult.Success -> {
                    val name = result.product.productName ?: getString(R.string.unknown_product)
                    Toast.makeText(this, getString(R.string.toast_captured_success, name), Toast.LENGTH_LONG).show()
                }
                is CaptureResult.Failure -> {
                    Toast.makeText(this, getString(R.string.toast_captured_fail, result.reason), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * 用來提醒「時間到／次數到但沒湊到目標數量」的明顯提醒：
     * 獨立的高優先度通知（會跳出橫幅）+ 震動，跟一般 Toast 分開，
     * 避免使用者切到別的 App 時錯過這個重要訊息。
     */
    private fun showAlertNotification(title: String, message: String) {
        val alertChannelId = "shopee_capture_alert_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                alertChannelId, getString(R.string.notif_channel_alert),
                NotificationManager.IMPORTANCE_HIGH
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }

        val notification = Notification.Builder(this, alertChannelId)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setAutoCancel(true)
            .build()

        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(2, notification)

        Toast.makeText(this, "$title：$message", Toast.LENGTH_LONG).show()

        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 200, 100, 200), -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(longArrayOf(0, 200, 100, 200), -1)
        }
    }

    companion object {
        const val ACTION_STOP = "com.tagcopy.shopeecapture.STOP"
        const val ACTION_STOP_AUTOMATION = "com.tagcopy.shopeecapture.STOP_AUTOMATION"

        // 讓 ShopeeAccessibilityService 在截圖前後可以直接呼叫，隱藏/恢復懸浮視窗，
        // 避免「擷取／自動／偵測」這幾顆按鈕被一起拍進商品圖片裡。
        @Volatile
        private var instance: FloatingButtonService? = null

        // 【2026-08-28修正】原本是單純的開/關，但呼叫端有兩層：外層「整段自動化期間隱藏」
        // （startAutoCapture/startUploadAutomation/startFbUploadAutomation）跟內層「單張商品截圖
        // 前後隱藏」（captureGalleryImages，每擷取完一件商品的圖片輪播就會恢復一次）會互相打架——
        // 內層每處理完一件商品就呼叫一次restoreAfterScreenshot()，會把外層才剛設定好的「整段
        // 自動化都要隱藏」狀態蓋掉，導致自動化明明還在跑（例如20件商品才處理到第1件），浮球卻已
        // 經被內層恢復顯示。改成計數式：hide每呼叫一次count+1並確保隱藏，restore每呼叫一次
        // count-1，只有count真的歸零（代表所有隱藏需求都已經解除）才真的恢復顯示，這樣不管外層
        // 內層呼叫順序、巢狀幾層，都不會有「內層還沒做完，外層卻先被恢復顯示」的狀況。
        @Volatile
        private var hideRequestCount = 0

        // 【2026-08-28新增】使用者反映：原本「自動」按鈕點下去，是留在7顆按鈕的長條清單裡、
        // 只有自己的文字變成進度「0/20」；後來為了不讓浮球擋畫面/被拍進商品照片，
        // 改成整段自動化期間直接把整個懸浮視窗隱藏——但這樣一來，唯一能中途喊停的地方
        // 只剩下拉通知欄，還額外依賴通知權限，實測發現使用者那邊還是常常按不到。改成
        // 「自動化模式」：不是整個隱藏，而是把原本7顆按鈕收合、只留一顆醒目的「停止自動化」
        // 按鈕留在畫面上，隨時點得到，不依賴通知權限；单張商品截圖的那個瞬間（hideRequestCount
        // >0）還是會連這顆停止鈕一起完全隱藏，避免拍進商品照片。
        @Volatile
        private var automationModeActive = false

        @Synchronized
        private fun applyVisibilityState() {
            val svc = instance ?: return
            if (hideRequestCount > 0) {
                // 截圖瞬間：不管是不是自動化模式，統統完全隱藏，確保不會拍進商品照片。
                svc.floatingView?.visibility = View.INVISIBLE
                return
            }
            svc.floatingView?.visibility = View.VISIBLE
            if (automationModeActive) {
                svc.regularButtons.forEach { it.visibility = View.GONE }
                svc.stopAutomationFloatingButton?.visibility = View.VISIBLE
            } else {
                svc.regularButtons.forEach { it.visibility = View.VISIBLE }
                svc.stopAutomationFloatingButton?.visibility = View.GONE
            }
        }

        /** 給showFloatingButton()結尾呼叫的防呆保險，見該處註解。 */
        @Synchronized
        fun applyVisibilityStateNow() {
            applyVisibilityState()
        }

        @Synchronized
        fun hideForScreenshot() {
            hideRequestCount++
            applyVisibilityState()
        }

        @Synchronized
        fun restoreAfterScreenshot() {
            hideRequestCount = (hideRequestCount - 1).coerceAtLeast(0)
            applyVisibilityState()
        }

        /** 整段自動化（擷取／上架／FB上架）開始時呼叫：收合成單一「停止自動化」按鈕。 */
        @Synchronized
        fun enterAutomationMode() {
            automationModeActive = true
            applyVisibilityState()
        }

        /** 整段自動化結束（正常跑完／手動停止／例外）時呼叫：恢復成原本7顆按鈕的長條清單。 */
        @Synchronized
        fun exitAutomationMode() {
            automationModeActive = false
            applyVisibilityState()
        }
    }
}
