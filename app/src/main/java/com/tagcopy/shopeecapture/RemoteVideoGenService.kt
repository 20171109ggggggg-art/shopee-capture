package com.tagcopy.shopeecapture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * 【2026-09-02新增】前景服務：實際執行RemoteVideoGenerator.runBatch()的地方。
 *
 * 用前景服務（不是單純綁在畫面上的coroutine）是刻意的：舊版靠Termux在獨立行程背景
 * 執行batch_generate.py，App畫面切走、螢幕鎖定都不受影響；現在生成邏輯搬進App自己
 * 的行程後，如果只是掛在Compose畫面的coroutineScope上，使用者切到別的App或鎖螢幕時
 * 系統有機會直接把這個coroutine連同生成到一半的批次一起砍掉。前景服務＋常駐通知
 * 可以大幅降低這個風險，行為更接近舊版Termux的可靠度。
 *
 * 進度本身還是照舊寫進<CaptionQueue根目錄>/.progress.json（見RemoteVideoGenerator），
 * 這個服務只負責「讓runBatch()跑在一個系統比較不會殺掉的地方」，不用自己維護額外的
 * 進度狀態或跟Activity之間的溝通管道。
 */
class RemoteVideoGenService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val captionQueuePath = intent?.getStringExtra(EXTRA_CAPTION_QUEUE_DIR)
        val selectedIds = intent?.getStringArrayListExtra(EXTRA_SELECTED_IDS)?.toSet()
        val force = intent?.getBooleanExtra(EXTRA_FORCE, false) ?: false

        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.remote_gen_notification_preparing)))

        if (captionQueuePath.isNullOrBlank() || selectedIds.isNullOrEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }

        job = serviceScope.launch {
            try {
                RemoteVideoGenerator.runBatch(
                    context = applicationContext,
                    captionQueueDir = File(captionQueuePath),
                    selectedFolderNames = selectedIds,
                    force = force
                )
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        job?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(text: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.remote_gen_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.remote_gen_notification_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val EXTRA_CAPTION_QUEUE_DIR = "caption_queue_dir"
        const val EXTRA_SELECTED_IDS = "selected_ids"
        const val EXTRA_FORCE = "force"
        private const val CHANNEL_ID = "remote_video_gen"
        private const val NOTIFICATION_ID = 5501

        /** 啟動前景服務開始生成，folderNames是要處理的商品資料夾名稱清單
         * （對應products清單裡勾選的商品）。*/
        fun start(context: Context, captionQueueDir: File, folderNames: Set<String>, force: Boolean) {
            val intent = Intent(context, RemoteVideoGenService::class.java).apply {
                putExtra(EXTRA_CAPTION_QUEUE_DIR, captionQueueDir.path)
                putStringArrayListExtra(EXTRA_SELECTED_IDS, ArrayList(folderNames))
                putExtra(EXTRA_FORCE, force)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
