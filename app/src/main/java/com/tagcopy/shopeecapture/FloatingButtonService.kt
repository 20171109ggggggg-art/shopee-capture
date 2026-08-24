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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
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

        val notification = Notification.Builder(this, channelId)
            .setContentTitle(getString(R.string.notif_title_running))
            .setContentText(getString(R.string.notif_text_running))
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.btn_stop), stopPending)
            .build()

        startForeground(1, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
        }
        return START_STICKY
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

        container.addView(captureButton)
        container.addView(autoButton)
        container.addView(detectButton)
        container.addView(calibrateButton)

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

        floatingView = container
        floatingViewParams = params
        windowManager?.addView(container, params)
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

        val service = ShopeeAccessibilityService.instance
        if (service == null) {
            Toast.makeText(this, getString(R.string.toast_need_accessibility), Toast.LENGTH_LONG).show()
            return
        }

        val overlay = View(this)
        overlay.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                service.logCalibrationTap(event.rawX, event.rawY)
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
                }
                is AutoCaptureEvent.Finished -> {
                    autoButton.text = getString(R.string.btn_auto)
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

        // 讓 ShopeeAccessibilityService 在截圖前後可以直接呼叫，隱藏/恢復懸浮視窗，
        // 避免「擷取／自動／偵測」這幾顆按鈕被一起拍進商品圖片裡。
        @Volatile
        private var instance: FloatingButtonService? = null

        fun hideForScreenshot() {
            instance?.floatingView?.visibility = View.INVISIBLE
        }

        fun restoreAfterScreenshot() {
            instance?.floatingView?.visibility = View.VISIBLE
        }
    }
}
