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

    var isRunning by remember { mutableStateOf(false) }
    // 「停止生成」按下後不是立即殺掉Termux行程，而是寫一個訊號檔案，
    // batch_generate.py會在目前這支影片完成、下一支開始前檢查這個檔案，
    // 看到就自動收尾寫進度並結束，避免一支影片生成到一半被中斷成殘缺檔案。
    var stopRequested by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<TermuxRunner.BatchProgress?>(null) }
    var resultText by remember { mutableStateOf<String?>(null) }

    val captionQueueDir = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        "CaptionQueue"
    )

    LaunchedEffect(isRunning) {
        if (!isRunning) return@LaunchedEffect
        while (isRunning) {
            val p = TermuxRunner.readBatchProgress(captionQueueDir)
            progress = p
            when (p?.status) {
                "done" -> {
                    resultText = context.getString(
                        R.string.simple_generate_done, p.okCount, p.skippedCount, p.errorCount
                    )
                    isRunning = false
                    stopRequested = false
                }
                "stopped" -> {
                    resultText = context.getString(
                        R.string.simple_generate_stopped, p.okCount, p.skippedCount, p.errorCount
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
        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
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
                    // 開始新一批之前，先清掉可能殘留的舊停止訊號檔案（例如上一批是被停止結束的）。
                    try {
                        File(captionQueueDir, ".stop_signal").delete()
                    } catch (e: Exception) { /* 檔案本來就不存在時刪除會失敗，忽略即可 */ }
                    val sent = TermuxRunner.runCommand(
                        context,
                        "cd ~/shopee-capture && python batch_generate.py ~/storage/downloads/CaptionQueue"
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

@Composable
private fun ReviewVideosScreen(context: Context, onBack: () -> Unit) {
    var videos by remember { mutableStateOf(scanVideos(context)) }
    var deleteTarget by remember { mutableStateOf<VideoItem?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        SimpleTopBar(stringResource(R.string.simple_review_title, videos.size), onBack)

        if (videos.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(60.dp))
                Text(stringResource(R.string.simple_no_videos_yet), fontSize = 15.sp, color = SimpleMuted)
            }
        } else {
            LazyColumn(modifier = Modifier.padding(horizontal = 20.dp)) {
                items(videos) { video ->
                    VideoRow(
                        video = video,
                        onPlay = {
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
                        },
                        onDelete = { deleteTarget = video }
                    )
                    Spacer(Modifier.height(10.dp))
                }
                item { Spacer(Modifier.height(20.dp)) }
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
                    target.folder.deleteRecursively()
                    videos = scanVideos(context)
                    deleteTarget = null
                }) { Text(stringResource(R.string.simple_delete), color = SimpleDanger) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.simple_cancel)) }
            }
        )
    }
}

@Composable
private fun VideoRow(video: VideoItem, onPlay: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White, RoundedCornerShape(10.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(video.productName, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = SimpleInk, maxLines = 2)
            Spacer(Modifier.height(4.dp))
            Text(
                if (video.posted) stringResource(R.string.simple_posted) else stringResource(R.string.simple_not_posted),
                fontSize = 12.sp,
                color = if (video.posted) SimpleGreen else SimpleMuted
            )
        }
        TextButton(onClick = onPlay) { Text(stringResource(R.string.simple_play), color = SimpleAccent) }
        TextButton(onClick = onDelete) { Text(stringResource(R.string.simple_delete), color = SimpleDanger) }
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
