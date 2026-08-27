package com.tagcopy.shopeecapture

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File

// 簡易模式專用色票，跟MainActivity的開發設定畫面各自獨立，故意不共用避免耦合。
private val SimpleInk = Color(0xFF1C2331)
private val SimpleAccent = Color(0xFFE8622C)
private val SimpleMuted = Color(0xFF8C8880)
private val SimpleGreen = Color(0xFF2E7D32)
private val SimpleBg = Color(0xFFFBF5EE)
private val SimpleDanger = Color(0xFFB3261E)

private enum class SimpleScreen { HOME, CAPTURE, GENERATE, REVIEW, UPLOAD }

class SimpleModeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                SimpleModeRoot()
            }
        }
    }
}

@Composable
private fun SimpleModeRoot() {
    val context = LocalContext.current
    var screen by remember { mutableStateOf(SimpleScreen.HOME) }

    Surface(color = SimpleBg, modifier = Modifier.fillMaxSize()) {
        when (screen) {
            SimpleScreen.HOME -> SimpleHomeScreen(
                context = context,
                onNavigate = { screen = it }
            )
            SimpleScreen.CAPTURE -> CaptureScreen(context, onBack = { screen = SimpleScreen.HOME })
            SimpleScreen.GENERATE -> GenerateVideoScreen(context, onBack = { screen = SimpleScreen.HOME })
            SimpleScreen.REVIEW -> ReviewVideosScreen(context, onBack = { screen = SimpleScreen.HOME })
            SimpleScreen.UPLOAD -> UploadScreen(context, onBack = { screen = SimpleScreen.HOME })
        }
    }
}

@Composable
private fun SimpleHomeScreen(context: Context, onNavigate: (SimpleScreen) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.simple_home_title), fontSize = 26.sp, fontWeight = FontWeight.Bold, color = SimpleInk)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.simple_home_subtitle), fontSize = 14.sp, color = SimpleMuted)
        Spacer(Modifier.height(28.dp))

        HomeActionCard(
            step = "1",
            title = stringResource(R.string.simple_step1_title),
            desc = stringResource(R.string.simple_step1_desc),
            color = SimpleAccent,
            onClick = { onNavigate(SimpleScreen.CAPTURE) }
        )
        Spacer(Modifier.height(14.dp))
        HomeActionCard(
            step = "2",
            title = stringResource(R.string.simple_step2_title),
            desc = stringResource(R.string.simple_step2_desc),
            color = SimpleInk,
            onClick = { onNavigate(SimpleScreen.GENERATE) }
        )
        Spacer(Modifier.height(14.dp))
        HomeActionCard(
            step = "3",
            title = stringResource(R.string.simple_step3_title),
            desc = stringResource(R.string.simple_step3_desc),
            color = Color(0xFF8A9A87),
            onClick = { onNavigate(SimpleScreen.REVIEW) }
        )
        Spacer(Modifier.height(14.dp))
        HomeActionCard(
            step = "4",
            title = stringResource(R.string.simple_step4_title),
            desc = stringResource(R.string.simple_step4_desc),
            color = SimpleGreen,
            onClick = { onNavigate(SimpleScreen.UPLOAD) }
        )

        Spacer(Modifier.height(40.dp))
        Text(
            stringResource(R.string.simple_advanced_settings),
            fontSize = 13.sp,
            color = SimpleMuted,
            modifier = Modifier
                .align(Alignment.End)
                .clickable {
                    context.startActivity(Intent(context, MainActivity::class.java))
                }
        )
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun HomeActionCard(step: String, title: String, desc: String, color: Color, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(color, RoundedCornerShape(22.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(step, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = SimpleInk)
            Spacer(Modifier.height(2.dp))
            Text(desc, fontSize = 13.sp, color = SimpleMuted)
        }
        Text("›", fontSize = 24.sp, color = SimpleMuted)
    }
}

@Composable
private fun SimpleTopBar(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "‹ " + stringResource(R.string.simple_back),
            fontSize = 16.sp,
            color = SimpleAccent,
            modifier = Modifier.clickable { onBack() }
        )
        Spacer(Modifier.width(16.dp))
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = SimpleInk)
    }
}

/** 嘗試找出已安裝的蝦皮App套件名稱（TW或PH版），找不到回傳null */
private fun findShopeePackage(context: Context): String? {
    for (pkg in listOf("com.shopee.tw", "com.shopee.ph")) {
        try {
            context.packageManager.getPackageInfo(pkg, 0)
            return pkg
        } catch (e: Exception) { /* 這個套件沒裝，試下一個 */ }
    }
    return null
}

private fun openShopeeApp(context: Context) {
    val pkg = findShopeePackage(context) ?: return
    context.packageManager.getLaunchIntentForPackage(pkg)?.let { context.startActivity(it) }
}

/**
 * 開啟蝦皮App前，如果無障礙服務跟懸浮視窗權限都已經開了，就順手啟動浮動按鈕服務，
 * 使用者不用再自己跑去「進階設定」按第3步驟的啟動鈕。FloatingButtonService是
 * 一般Started Service、Android系統本身保證同時只有一個實例，重複呼叫startService
 * 只會再觸發一次onStartCommand（不會重跑onCreate、不會重複疊加懸浮視窗），
 * 所以這裡不用額外判斷「是否已經在跑」，每次開蝦皮App都呼叫一次即可。
 * 權限沒開齊的狀況畫面上已經有WarningBanner提醒，這裡就不重複跳提示。
 */
private fun openShopeeAppWithFloatingButton(context: Context) {
    if (isAccessibilityServiceEnabled(context) && canDrawOverlays(context)) {
        context.startService(Intent(context, FloatingButtonService::class.java))
    }
    openShopeeApp(context)
}

// ========== 步驟1：擷取商品 ==========

@Composable
private fun CaptureScreen(context: Context, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        SimpleTopBar(stringResource(R.string.simple_step1_title), onBack)
        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            InstructionCard(
                lines = listOf(
                    stringResource(R.string.simple_capture_instr_1),
                    stringResource(R.string.simple_capture_instr_2),
                    stringResource(R.string.simple_capture_instr_3),
                    stringResource(R.string.simple_capture_instr_4)
                )
            )
            Spacer(Modifier.height(24.dp))
            val accessibilityOn = isAccessibilityServiceEnabled(context)
            if (!accessibilityOn) {
                WarningBanner(stringResource(R.string.simple_warn_need_accessibility_capture))
                Spacer(Modifier.height(16.dp))
            }
            BigActionButton(
                text = stringResource(R.string.simple_open_shopee),
                color = SimpleAccent,
                enabled = findShopeePackage(context) != null,
                onClick = { openShopeeAppWithFloatingButton(context) }
            )
            if (findShopeePackage(context) == null) {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.simple_shopee_not_found), fontSize = 13.sp, color = SimpleDanger)
            }
        }
    }
}

// ========== 步驟2：生成影片 ==========

// 判定「進度卡住」的門檻：單支影片TTS+ffmpeg實測常要1~3分鐘，設6分鐘留足夠安全邊界，
// 避免正常處理中的影片被誤判成卡住。
private const val STALE_THRESHOLD_SECONDS = 360

// 完成/停止畫面的名稱清單每類最多列出這麼多筆，避免單日量大（可能上百支）時
// 文字塞爆整個畫面；超過的部分改用「…等其餘N支」摘要帶過。
private const val DETAIL_LIST_MAX_ITEMS = 20

/**
 * 判斷一筆.progress.json的內容是不是「卡住的舊資料」——status還是"running"，
 * 但updatedAt距離現在已經超過STALE_THRESHOLD_SECONDS沒更新過，代表寫入這份進度的
 * Python行程多半已經在背景被系統砍掉、或因為未預期的錯誤中斷，沒能正常收尾寫入
 * done/stopped狀態。缺updatedAt欄位（例如讀到舊版腳本寫的檔案，預設值0.0）一律視為
 * 非常舊、直接判定為卡住。
 */
private fun isStaleProgress(p: TermuxRunner.BatchProgress?): Boolean {
    if (p == null || p.status != "running") return false
    val nowSeconds = System.currentTimeMillis() / 1000.0
    return (nowSeconds - p.updatedAt) > STALE_THRESHOLD_SECONDS
}

/**
 * 把done/stopped狀態的批次結果組成詳細文字：標題行（成功/跳過/失敗支數）之後，
 * 依序附上耗時、本次新生成的商品名稱清單、跳過（已有影片不重跑）的商品名稱清單、
 * 失敗的商品名稱＋原因清單。讓使用者一眼看出「這次到底是重新生成了還是全部跳過」，
 * 不用再靠猜的。每類清單超過DETAIL_LIST_MAX_ITEMS筆就截斷、改顯示還剩幾筆。
 */
private fun buildDetailedResultText(context: Context, headerText: String, p: TermuxRunner.BatchProgress): String {
    val sb = StringBuilder(headerText)
    p.elapsedSeconds?.let { elapsed ->
        val minutes = (elapsed / 60).toInt()
        val seconds = (elapsed % 60).toInt()
        sb.append("\n").append(context.getString(R.string.simple_generate_elapsed, minutes, seconds))
    }
    fun appendSection(headerRes: Int, items: List<String>) {
        if (items.isEmpty()) return
        sb.append("\n\n").append(context.getString(headerRes, items.size))
        items.take(DETAIL_LIST_MAX_ITEMS).forEach { sb.append("\n・").append(it) }
        val remaining = items.size - DETAIL_LIST_MAX_ITEMS
        if (remaining > 0) {
            sb.append("\n").append(context.getString(R.string.simple_generate_list_more, remaining))
        }
    }
    appendSection(R.string.simple_generate_ok_list_header, p.okNames)
    appendSection(R.string.simple_generate_skipped_list_header, p.skippedNames)
    if (p.errorItems.isNotEmpty()) {
        sb.append("\n\n").append(context.getString(R.string.simple_generate_error_list_header, p.errorItems.size))
        p.errorItems.take(DETAIL_LIST_MAX_ITEMS).forEach { (name, msg) ->
            sb.append("\n・").append(name).append("：").append(msg)
        }
        val remaining = p.errorItems.size - DETAIL_LIST_MAX_ITEMS
        if (remaining > 0) {
            sb.append("\n").append(context.getString(R.string.simple_generate_list_more, remaining))
        }
    }
    return sb.toString()
}

@Composable
private fun GenerateVideoScreen(context: Context, onBack: () -> Unit) {
    var termuxGranted by remember {
        mutableStateOf(
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, "com.termux.permission.RUN_COMMAND"
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted -> termuxGranted = granted }

    val captionQueueDir = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        "CaptionQueue"
    )
    // 進畫面當下先讀一次目前實際的進度檔案，用它來決定畫面初始狀態——
    // Termux背景執行不受App畫面切換影響，之前的版本每次重進畫面isRunning都從false
    // 重新開始，導致使用者切到別的畫面再回來時，明明背景還在生成，畫面卻顯示
    // 「開始生成影片」看起來像沒在跑，容易誤導使用者重複啟動或誤以為卡住。
    val initialProgress = remember { TermuxRunner.readBatchProgress(captionQueueDir) }
    val isInitiallyStale = remember { isStaleProgress(initialProgress) }

    var isRunning by remember { mutableStateOf(initialProgress?.status == "running" && !isInitiallyStale) }
    // 「停止生成」按下後不是立即殺掉Termux行程，而是寫一個訊號檔案，
    // batch_generate.py會在目前這支影片完成、下一支開始前檢查這個檔案，
    // 看到就自動收尾寫進度並結束，避免一支影片生成到一半被中斷成殘缺檔案。
    // 同樣道理，如果使用者按下停止後就切走畫面，重進來時訊號檔案可能還沒被腳本吃掉，
    // 這裡也一併還原狀態，避免按鈕又變回可以按（重複按不會出錯，但畫面顯示才會準確）。
    var stopRequested by remember { mutableStateOf(File(captionQueueDir, ".stop_signal").exists()) }
    var progress by remember { mutableStateOf(initialProgress) }
    // 如果重進畫面時發現上一批其實已經在背景跑完了（使用者切走那段時間完成的），
    // 直接把結果顯示出來，不會因為畫面重建就把「已經完成」的訊息憑空吃掉。
    // 如果status是"running"但updatedAt已經停在很久以前（行程被系統砍掉、沒能正常收尾
    // 寫入done/stopped），視為卡住的殘留資料，顯示提醒而不是照單全收讓畫面一直轉。
    // done/stopped的結果文字改用buildDetailedResultText()組成詳細清單（本次新生成/跳過/
    // 失敗各是哪些商品），不是只顯示三個數字。
    var resultText by remember {
        mutableStateOf(
            when {
                initialProgress?.status == "running" && isInitiallyStale -> context.getString(
                    R.string.simple_generate_stale,
                    initialProgress.completed, initialProgress.total, STALE_THRESHOLD_SECONDS / 60
                )
                initialProgress?.status == "done" -> buildDetailedResultText(
                    context,
                    context.getString(
                        R.string.simple_generate_done,
                        initialProgress.okCount, initialProgress.skippedCount, initialProgress.errorCount
                    ),
                    initialProgress
                )
                initialProgress?.status == "stopped" -> buildDetailedResultText(
                    context,
                    context.getString(
                        R.string.simple_generate_stopped,
                        initialProgress.okCount, initialProgress.skippedCount, initialProgress.errorCount
                    ),
                    initialProgress
                )
                else -> null
            }
        )
    }

    LaunchedEffect(isRunning) {
        if (!isRunning) return@LaunchedEffect
        while (isRunning) {
            val p = TermuxRunner.readBatchProgress(captionQueueDir)
            progress = p
            when {
                p?.status == "running" && isStaleProgress(p) -> {
                    resultText = context.getString(
                        R.string.simple_generate_stale, p.completed, p.total, STALE_THRESHOLD_SECONDS / 60
                    )
                    isRunning = false
                    stopRequested = false
                }
                p?.status == "done" -> {
                    resultText = buildDetailedResultText(
                        context,
                        context.getString(
                            R.string.simple_generate_done, p.okCount, p.skippedCount, p.errorCount
                        ),
                        p
                    )
                    isRunning = false
                    stopRequested = false
                }
                p?.status == "stopped" -> {
                    resultText = buildDetailedResultText(
                        context,
                        context.getString(
                            R.string.simple_generate_stopped, p.okCount, p.skippedCount, p.errorCount
                        ),
                        p
                    )
                    isRunning = false
                    stopRequested = false
                }
            }
            kotlinx.coroutines.delay(1500)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        SimpleTopBar(stringResource(R.string.simple_step2_title), onBack)
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            InstructionCard(
                lines = listOf(
                    stringResource(R.string.simple_generate_instr_1),
                    stringResource(R.string.simple_generate_instr_2)
                )
            )
            Spacer(Modifier.height(24.dp))

            if (!termuxGranted) {
                WarningBanner(stringResource(R.string.simple_need_termux_permission))
                Spacer(Modifier.height(12.dp))
                BigActionButton(
                    text = stringResource(R.string.simple_grant_termux_permission),
                    color = SimpleInk,
                    onClick = { permissionLauncher.launch("com.termux.permission.RUN_COMMAND") }
                )
                Spacer(Modifier.height(16.dp))
            }

            if (!TermuxRunner.isTermuxInstalled(context)) {
                WarningBanner(stringResource(R.string.simple_no_termux))
                Spacer(Modifier.height(16.dp))
            }

            BigActionButton(
                text = if (isRunning) stringResource(R.string.simple_generating) else stringResource(R.string.simple_start_generate),
                color = SimpleInk,
                enabled = termuxGranted && !isRunning && TermuxRunner.isTermuxInstalled(context),
                onClick = {
                    resultText = null
                    progress = null
                    stopRequested = false
                    // 開始新一批之前，先清掉可能殘留的舊訊號檔案／舊進度檔案：
                    // .stop_signal是上一批若是被停止結束留下的；.progress.json如果不清掉，
                    // 新的Python腳本要花一點時間（啟動bash、cd、python直譯器初始化、掃描
                    // 資料夾）才會真正蓋過去，這段空窗期內App第一次輪詢仍會讀到「舊的、
                    // 真的過期的」進度檔——如果那份舊檔案剛好是卡住偵測抓到的異常中斷，
                    // isRunning會被誤判成false打回黑色按鈕，使用者要連按好幾次才會踩到
                    // 「新腳本已經蓋過舊檔案」的時機點。清掉舊檔案後，第一次輪詢會讀到null
                    // （檔案不存在），不會觸發卡住偵測，正常等到新腳本真正開始寫入。
                    try {
                        File(captionQueueDir, ".stop_signal").delete()
                        File(captionQueueDir, ".progress.json").delete()
                    } catch (e: Exception) { /* 檔案本來就不存在時刪除會失敗，忽略即可 */ }
                    // 使用者實測發現：批次生成有時會在中途整個沒有留下任何痕跡地停止
                    // （不是正常的done/stopped，也不是crash訊息），完全無法判斷是python
                    // 本身出錯、還是被系統（省電策略／記憶體不足OOM）強制砍掉行程。過去
                    // 腳本的stdout/stderr沒有被導向任何持久化的檔案，一旦行程被砍就什麼
                    // 證據都沒留下，只能憑猜的。這裡改成把輸出導向一個log檔案（每次執行
                    // 覆蓋前一份，避免累積佔空間），下次再發生類似狀況時，直接看這份log
                    // 最後幾行——如果最後一行剛好停在某支影片處理到一半、後面就完全沒有
                    // 任何輸出，那就是被系統砍掉的鐵證（正常結束/正常錯誤都會印出對應
                    // 訊息，不會憑空消失）。
                    val sent = TermuxRunner.runCommand(
                        context,
                        "cd ~/shopee-capture && python -u batch_generate.py ~/storage/downloads/CaptionQueue " +
                            "> ~/storage/downloads/batch_generate_last_run.log 2>&1"
                    )
                    if (sent) {
                        isRunning = true
                    } else {
                        resultText = context.getString(R.string.simple_generate_start_failed)
                    }
                }
            )

            if (isRunning) {
                Spacer(Modifier.height(12.dp))
                BigActionButton(
                    text = stringResource(R.string.simple_stop_generate),
                    color = SimpleDanger,
                    enabled = !stopRequested,
                    onClick = {
                        try {
                            captionQueueDir.mkdirs()
                            File(captionQueueDir, ".stop_signal").createNewFile()
                            stopRequested = true
                        } catch (e: Exception) { /* 寫入失敗就靜默忽略，使用者仍可等它自然跑完 */ }
                    }
                )
                if (stopRequested) {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.simple_stop_requested), fontSize = 13.sp, color = SimpleMuted)
                }
            }

            Spacer(Modifier.height(20.dp))

            // 轉動指示：跟progress區塊分開獨立顯示，因為isRunning=true的當下progress可能
            // 還沒有第一次輪詢結果（剛按下開始那一瞬間），這裡只要isRunning就先讓使用者
            // 看到明確的「還在跑」視覺回饋，不用等進度資料到位。isRunning變false（完成或
            // 停止）時這個轉動動畫會立刻消失，跟下方resultText的文字說明一起構成
            // 「有轉動＝還在生成／沒轉動＋文字＝已經停止」的明確狀態指示。
            if (isRunning) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = SimpleAccent
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stringResource(R.string.simple_generating),
                        fontSize = 14.sp, color = SimpleInk, fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.height(16.dp))
            }

            progress?.let { p ->
                if (p.total > 0) {
                    Text(
                        stringResource(R.string.simple_progress_label, p.completed, p.total),
                        fontSize = 15.sp, color = SimpleInk, fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { if (p.total > 0) p.completed.toFloat() / p.total else 0f },
                        modifier = Modifier.fillMaxWidth().height(10.dp),
                        color = SimpleAccent
                    )
                    if (p.current.isNotBlank() && p.status == "running") {
                        Spacer(Modifier.height(6.dp))
                        Text(stringResource(R.string.simple_processing_now), fontSize = 13.sp, color = SimpleMuted)
                    }
                }
            }

            resultText?.let {
                Spacer(Modifier.height(16.dp))
                Text(it, fontSize = 15.sp, color = SimpleInk, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ========== 步驟3：檢查影片 ==========

private data class VideoItem(val folder: File, val productName: String, val videoFile: File, val posted: Boolean)

private fun scanVideos(context: Context): List<VideoItem> {
    val root = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        "CaptionQueue"
    )
    if (!root.isDirectory) return emptyList()
    val result = mutableListOf<VideoItem>()
    root.listFiles()?.sortedByDescending { it.lastModified() }?.forEach { dir ->
        if (!dir.isDirectory) return@forEach
        val videoFile = File(dir, "output.mp4")
        if (!videoFile.isFile) return@forEach
        val metaFile = File(dir, "meta.json")
        var name = dir.name
        var posted = false
        if (metaFile.isFile) {
            try {
                val json = JSONObject(metaFile.readText())
                json.optString("productName", "").takeIf { it.isNotBlank() }?.let { name = it }
                posted = json.optBoolean("shopeePosted", false)
            } catch (e: Exception) { /* 讀不到就用資料夾名稱代替，不影響列表顯示 */ }
        }
        result.add(VideoItem(dir, name, videoFile, posted))
    }
    return result
}

/**
 * 用MediaScannerConnection.scanFile()強制系統重新掃描這個影片檔案的媒體資訊，並真的
 * 等掃描完成的callback觸發後才返回（不是掃了就不管）。
 *
 * 背景：使用者實測發現，影片如果第一次生成時曾經是殘缺檔案（例如中途被系統砍掉），
 * 即使後來偵測到並重新生成成功，某些手機的影片播放器拿到content://網址後仍會回頭
 * 查MediaStore資料庫比對這個檔案路徑的中繼資料（例如時長），如果Android系統當初
 * 第一次幫這個路徑建立索引時抓到的是壞掉的舊資訊，即使檔案內容早就換成好的，
 * 播放器仍可能照著這份舊索引判斷拒絕播放。第一版修正呼叫scanFile()但沒有等待
 * 掃描真正完成就直接開啟播放器（fire-and-forget），時機沒對上，等於沒修到；
 * 這裡改成用suspendCancellableCoroutine真的等callback觸發後才繼續，逾時3秒
 * 就放棄等待、直接嘗試開啟播放器（避免掃描本身卡住或失敗時把使用者卡住）。
 *
 * 另外光靠scanFile()「重新掃描」不保證一定會完整覆蓋舊紀錄裡的壞欄位（部分廠牌
 * ROM對已存在的MediaStore項目可能只做部分欄位更新），這裡先主動刪除MediaStore
 * 裡任何跟這個路徑對應的舊紀錄，確保沒有殘留的壞中繼資料可以被撿到，再讓
 * scanFile()建立一筆全新、乾淨的索引，比單純重新掃描更保險。刪除失敗（例如
 * 該路徑本來就沒有MediaStore紀錄）不影響後續流程。
 */
private suspend fun rescanAndWait(context: Context, file: File) {
    try {
        context.contentResolver.delete(
            android.provider.MediaStore.Files.getContentUri("external"),
            "${android.provider.MediaStore.Files.FileColumns.DATA}=?",
            arrayOf(file.absolutePath)
        )
    } catch (e: Exception) { /* 沒有舊紀錄可刪或刪除失敗都不影響後續掃描 */ }
    withTimeoutOrNull(3000) {
        suspendCancellableCoroutine<Unit> { cont ->
            android.media.MediaScannerConnection.scanFile(
                context, arrayOf(file.absolutePath), arrayOf("video/mp4")
            ) { _, _ ->
                if (cont.isActive) cont.resumeWith(Result.success(Unit))
            }
        }
    }
}

@Composable
private fun ReviewVideosScreen(context: Context, onBack: () -> Unit) {
    var videos by remember { mutableStateOf(scanVideos(context)) }
    var deleteTarget by remember { mutableStateOf<VideoItem?>(null) }
    val coroutineScope = rememberCoroutineScope()

    // 多選刪除：selectionMode開啟後，清單每一列改顯示勾選框，點列本身切換勾選狀態
    // （不用另外找空間放checkbox），長按任一列可以直接進入多選模式並預先勾選該列。
    // 用資料夾絕對路徑字串當作勾選集合的key，同一支影片只會對應唯一路徑，不會混淆。
    var selectionMode by remember { mutableStateOf(false) }
    var selectedPaths by remember { mutableStateOf(setOf<String>()) }
    var batchDeleteConfirm by remember { mutableStateOf(false) }

    fun exitSelectionMode() {
        selectionMode = false
        selectedPaths = emptySet()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (selectionMode) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.simple_cancel),
                    fontSize = 16.sp,
                    color = SimpleAccent,
                    modifier = Modifier.clickable { exitSelectionMode() }
                )
                Spacer(Modifier.width(16.dp))
                Text(
                    stringResource(R.string.simple_selected_count, selectedPaths.size),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = SimpleInk,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    if (selectedPaths.size < videos.size) stringResource(R.string.simple_select_all)
                    else stringResource(R.string.simple_deselect_all),
                    fontSize = 14.sp,
                    color = SimpleAccent,
                    modifier = Modifier.clickable {
                        selectedPaths = if (selectedPaths.size < videos.size) {
                            videos.map { it.folder.absolutePath }.toSet()
                        } else {
                            emptySet()
                        }
                    }
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "‹ " + stringResource(R.string.simple_back),
                    fontSize = 16.sp,
                    color = SimpleAccent,
                    modifier = Modifier.clickable { onBack() }
                )
                Spacer(Modifier.width(16.dp))
                Text(
                    stringResource(R.string.simple_review_title, videos.size),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = SimpleInk,
                    modifier = Modifier.weight(1f)
                )
                if (videos.isNotEmpty()) {
                    Text(
                        stringResource(R.string.simple_select),
                        fontSize = 14.sp,
                        color = SimpleAccent,
                        modifier = Modifier.clickable { selectionMode = true }
                    )
                }
            }
        }

        if (videos.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(60.dp))
                Text(stringResource(R.string.simple_no_videos_yet), fontSize = 15.sp, color = SimpleMuted)
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).padding(horizontal = 20.dp)
            ) {
                items(videos) { video ->
                    val path = video.folder.absolutePath
                    VideoRow(
                        video = video,
                        selectionMode = selectionMode,
                        isSelected = path in selectedPaths,
                        onPlay = {
                            coroutineScope.launch {
                                try {
                                    rescanAndWait(context, video.videoFile)
                                } catch (e: Exception) { /* 掃描本身失敗不影響後續嘗試播放 */ }
                                try {
                                    val uri: Uri = FileProvider.getUriForFile(
                                        context,
                                        "com.tagcopy.shopeecapture.fileprovider",
                                        video.videoFile
                                    )
                                    val intent = Intent(Intent.ACTION_VIEW).apply {
                                        setDataAndType(uri, "video/mp4")
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(intent)
                                } catch (e: Exception) { /* 沒有影片播放器可開啟時忽略，不中斷畫面 */ }
                            }
                        },
                        onDelete = { deleteTarget = video },
                        onToggleSelect = {
                            selectedPaths = if (path in selectedPaths) selectedPaths - path else selectedPaths + path
                        },
                        onLongPress = {
                            if (!selectionMode) {
                                selectionMode = true
                                selectedPaths = setOf(path)
                            }
                        }
                    )
                    Spacer(Modifier.height(10.dp))
                }
                item { Spacer(Modifier.height(if (selectionMode) 80.dp else 20.dp)) }
            }
        }

        if (selectionMode && selectedPaths.isNotEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                BigActionButton(
                    text = stringResource(R.string.simple_delete_selected, selectedPaths.size),
                    color = SimpleDanger,
                    onClick = { batchDeleteConfirm = true }
                )
            }
        }
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.simple_delete_confirm_title)) },
            text = { Text(target.productName) },
            confirmButton = {
                TextButton(onClick = {
                    // 【2026-08-28修正】原本是整個資料夾一起刪(deleteRecursively)，改成只刪
                    // output.mp4這支影片檔案本身。這個畫面的用途是「檢查有沒有壞掉的影片、
                    // 刪掉讓它可以重新生成」，資料夾本來就會在蝦皮+FB都上架完後自動整個清掉
                    // (deleteFolderIfFullyPosted)，這裡刪整個資料夾等於連圖片/meta.json/文案
                    // 都一起沒了，重新生成時就要重新擷取，不是原意。只刪影片檔，
                    // batch_generate.py下次執行時判斷「output.mp4不存在」就會自動補生成。
                    target.videoFile.delete()
                    videos = scanVideos(context)
                    deleteTarget = null
                }) { Text(stringResource(R.string.simple_delete), color = SimpleDanger) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.simple_cancel)) }
            }
        )
    }

    if (batchDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { batchDeleteConfirm = false },
            title = { Text(stringResource(R.string.simple_batch_delete_confirm_title, selectedPaths.size)) },
            confirmButton = {
                TextButton(onClick = {
                    // 同單支刪除的修正：只刪影片檔案本身，資料夾其他資料留著。
                    videos.filter { it.folder.absolutePath in selectedPaths }
                        .forEach { it.videoFile.delete() }
                    videos = scanVideos(context)
                    batchDeleteConfirm = false
                    exitSelectionMode()
                }) { Text(stringResource(R.string.simple_delete), color = SimpleDanger) }
            },
            dismissButton = {
                TextButton(onClick = { batchDeleteConfirm = false }) { Text(stringResource(R.string.simple_cancel)) }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VideoRow(
    video: VideoItem,
    selectionMode: Boolean,
    isSelected: Boolean,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
    onToggleSelect: () -> Unit,
    onLongPress: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(10.dp))
            .combinedClickable(
                onClick = { if (selectionMode) onToggleSelect() },
                onLongClick = onLongPress
            )
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selectionMode) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .background(
                        if (isSelected) SimpleAccent else Color(0xFFE8E4DC),
                        RoundedCornerShape(6.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (isSelected) Text("✓", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(14.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(video.productName, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = SimpleInk, maxLines = 2)
            Spacer(Modifier.height(4.dp))
            Text(
                if (video.posted) stringResource(R.string.simple_posted) else stringResource(R.string.simple_not_posted),
                fontSize = 12.sp,
                color = if (video.posted) SimpleGreen else SimpleMuted
            )
        }
        if (!selectionMode) {
            TextButton(onClick = onPlay) { Text(stringResource(R.string.simple_play), color = SimpleAccent) }
            TextButton(onClick = onDelete) { Text(stringResource(R.string.simple_delete), color = SimpleDanger) }
        }
    }
}

// ========== 步驟4：開始上架 ==========

@Composable
private fun UploadScreen(context: Context, onBack: () -> Unit) {
    val pendingCount = remember { scanVideos(context).count { !it.posted } }

    Column(modifier = Modifier.fillMaxSize()) {
        SimpleTopBar(stringResource(R.string.simple_step4_title), onBack)
        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            InstructionCard(
                lines = listOf(
                    stringResource(R.string.simple_capture_instr_1),
                    stringResource(R.string.simple_upload_instr_2),
                    stringResource(R.string.simple_upload_instr_3)
                )
            )
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.simple_pending_count, pendingCount),
                fontSize = 15.sp, fontWeight = FontWeight.Bold, color = SimpleInk
            )
            Spacer(Modifier.height(24.dp))

            val accessibilityOn = isAccessibilityServiceEnabled(context)
            if (!accessibilityOn) {
                WarningBanner(stringResource(R.string.simple_warn_need_accessibility_upload))
                Spacer(Modifier.height(16.dp))
            }

            BigActionButton(
                text = stringResource(R.string.simple_open_shopee),
                color = SimpleGreen,
                enabled = findShopeePackage(context) != null,
                onClick = { openShopeeAppWithFloatingButton(context) }
            )
        }
    }
}

// ========== 共用小元件 ==========

@Composable
private fun InstructionCard(lines: List<String>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(10.dp))
            .padding(16.dp)
    ) {
        lines.forEachIndexed { index, line ->
            Text(line, fontSize = 14.sp, color = SimpleInk, lineHeight = 20.sp)
            if (index != lines.lastIndex) Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun WarningBanner(text: String) {
    Text(
        text,
        fontSize = 13.sp,
        color = SimpleDanger,
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFFDECEA), RoundedCornerShape(8.dp))
            .padding(12.dp)
    )
}

@Composable
private fun BigActionButton(text: String, color: Color, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = color),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().height(54.dp)
    ) {
        Text(text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
}
