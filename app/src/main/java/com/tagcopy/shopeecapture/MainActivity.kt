package com.tagcopy.shopeecapture

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.core.content.ContextCompat
import java.io.File
import org.json.JSONObject

private val InkColor = Color(0xFF1C2331)
private val PaperColor = Color(0xFFFBF7F0)
private val AccentColor = Color(0xFFE8622C)
private val MutedColor = Color(0xFF8C8880)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                RootScreen()
            }
        }
    }
}

@Composable
fun RootScreen() {
    var accessibilityEnabled by remember { mutableStateOf(false) }
    var overlayGranted by remember { mutableStateOf(false) }
    var floatingServiceRunning by remember { mutableStateOf(false) }
    var mediaPermissionGranted by remember { mutableStateOf(false) }
    var allFilesAccessGranted by remember { mutableStateOf(false) }
    var queueItems by remember { mutableStateOf(listOf<QueueItem>()) }

    val context = androidx.compose.ui.platform.LocalContext.current

    val overlayLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        overlayGranted = canDrawOverlays(context)
    }

    // 用來要「讀取相簿權限」（分享面板下載鈕擷取原圖要用到，去 MediaStore 查剛存的檔案時需要這個權限）
    val mediaPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        mediaPermissionGranted = granted
    }

    // Termux的RUN_COMMAND權限是「dangerous」等級（跟相機/定位同一類），必須跑時動態請求，
    // 不是裝App時自動授予的一般權限——一開始漏掉這步，導致背景觸發Termux指令一律送出失敗。
    var termuxRunCommandGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, "com.termux.permission.RUN_COMMAND") ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val termuxPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        termuxRunCommandGranted = granted
    }

    // 從系統設定頁（開啟無障礙服務／懸浮視窗權限）切回這個畫面時，
    // 重新檢查一次狀態 —— 否則「前往設定」的完成勾勾不會更新，要重啟 App 才會抓到。
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                accessibilityEnabled = isAccessibilityServiceEnabled(context)
                overlayGranted = canDrawOverlays(context)
                mediaPermissionGranted = hasMediaPermission(context)
                allFilesAccessGranted = hasAllFilesAccess()
                termuxRunCommandGranted = ContextCompat.checkSelfPermission(
                    context, "com.termux.permission.RUN_COMMAND"
                ) == PackageManager.PERMISSION_GRANTED
                queueItems = loadQueueItems()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        accessibilityEnabled = isAccessibilityServiceEnabled(context)
        overlayGranted = canDrawOverlays(context)
        mediaPermissionGranted = hasMediaPermission(context)
        allFilesAccessGranted = hasAllFilesAccess()
        queueItems = loadQueueItems()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PaperColor)
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Text(stringResource(R.string.app_title), fontSize = 24.sp, fontWeight = FontWeight.Bold, color = InkColor)
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.app_subtitle),
            fontSize = 13.sp, color = MutedColor, lineHeight = 19.sp
        )
        Spacer(Modifier.height(28.dp))

        RegionSettingsCard(context)

        Spacer(Modifier.height(20.dp))

        SetupStepCard(
            stepNumber = "1",
            title = stringResource(R.string.step1_title),
            description = stringResource(R.string.step1_desc),
            done = accessibilityEnabled,
            buttonText = stringResource(R.string.btn_go_to_settings),
            onClick = {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        )

        Spacer(Modifier.height(14.dp))

        SetupStepCard(
            stepNumber = "2",
            title = stringResource(R.string.step2_title),
            description = stringResource(R.string.step2_desc),
            done = overlayGranted,
            buttonText = stringResource(R.string.btn_go_to_settings),
            onClick = {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                )
                overlayLauncher.launch(intent)
            }
        )

        Spacer(Modifier.height(14.dp))

        SetupStepCard(
            stepNumber = "3",
            title = stringResource(R.string.step3_title),
            description = if (floatingServiceRunning) stringResource(R.string.step3_desc_running) else stringResource(R.string.step3_desc_idle),
            done = floatingServiceRunning,
            buttonText = if (floatingServiceRunning) stringResource(R.string.btn_stop) else stringResource(R.string.btn_start),
            onClick = {
                if (!floatingServiceRunning) {
                    context.startService(Intent(context, FloatingButtonService::class.java))
                    floatingServiceRunning = true
                } else {
                    context.stopService(Intent(context, FloatingButtonService::class.java))
                    floatingServiceRunning = false
                }
            },
            enabled = accessibilityEnabled && overlayGranted
        )

        Spacer(Modifier.height(14.dp))

        SetupStepCard(
            stepNumber = "4",
            title = stringResource(R.string.step4_title),
            description = stringResource(R.string.step4_desc),
            done = mediaPermissionGranted,
            buttonText = stringResource(R.string.btn_grant_permission),
            onClick = {
                val permission = if (Build.VERSION.SDK_INT >= 33) {
                    Manifest.permission.READ_MEDIA_IMAGES
                } else {
                    Manifest.permission.READ_EXTERNAL_STORAGE
                }
                mediaPermissionLauncher.launch(permission)
            }
        )

        Spacer(Modifier.height(14.dp))

        SetupStepCard(
            stepNumber = "5",
            title = stringResource(R.string.step5_title),
            description = stringResource(R.string.step5_desc),
            done = allFilesAccessGranted,
            buttonText = stringResource(R.string.btn_go_to_settings),
            onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    )
                    context.startActivity(intent)
                }
            }
        )

        Spacer(Modifier.height(14.dp))

        AutoCaptureSettingsCard(context)

        Spacer(Modifier.height(14.dp))

        UploadAutomationSettingsCard(context)

        Spacer(Modifier.height(14.dp))

        TermuxTestCard(context, termuxRunCommandGranted, termuxPermissionLauncher)

        Spacer(Modifier.height(32.dp))

        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.captured_products_title), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = InkColor)
            Text(
                stringResource(R.string.btn_refresh),
                fontSize = 13.sp,
                color = AccentColor,
                modifier = Modifier.clickable { queueItems = loadQueueItems() }
            )
        }
        Spacer(Modifier.height(12.dp))

        if (queueItems.isEmpty()) {
            Text(stringResource(R.string.empty_queue_text), fontSize = 13.sp, color = MutedColor)
        } else {
            queueItems.forEach { item ->
                QueueItemRow(item)
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

@Composable
fun FlowRowChips(options: List<String>, selected: String, onSelect: (String) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Column {
            options.chunked(2).forEach { row ->
                Row(modifier = Modifier.padding(bottom = 8.dp)) {
                    row.forEach { opt ->
                        val isSelected = opt == selected
                        Box(
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .background(if (isSelected) InkColor else Color.Transparent)
                                .clickable { onSelect(opt) }
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Text(
                                opt,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isSelected) PaperColor else InkColor
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RegionSettingsCard(context: android.content.Context) {
    var region by remember { mutableStateOf(RegionPrefs.getRegion(context)) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White)
            .padding(16.dp)
    ) {
        Text(stringResource(R.string.region_title), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = InkColor)
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.region_desc),
            fontSize = 12.sp, color = MutedColor, lineHeight = 17.sp
        )
        Spacer(Modifier.height(12.dp))
        FlowRowChips(
            options = ShopeeRegion.entries.map { it.label },
            selected = region.label,
            onSelect = { label ->
                region = ShopeeRegion.fromLabel(label)
                RegionPrefs.setRegion(context, region)
            }
        )
    }
}

@Composable
fun AutoCaptureSettingsCard(context: android.content.Context) {
    var config by remember { mutableStateOf(AutoCapturePrefs.load(context)) }
    var countText by remember { mutableStateOf(config.targetCount.toString()) }
    var minDelayText by remember { mutableStateOf((config.minDelayMs / 1000.0).toString()) }
    var maxDelayText by remember { mutableStateOf((config.maxDelayMs / 1000.0).toString()) }

    var minCommissionText by remember { mutableStateOf(config.filter.minCommissionPercent?.toString() ?: "") }
    var maxCommissionText by remember { mutableStateOf(config.filter.maxCommissionPercent?.toString() ?: "") }
    var minPriceText by remember { mutableStateOf(config.filter.minPrice?.toString() ?: "") }
    var maxPriceText by remember { mutableStateOf(config.filter.maxPrice?.toString() ?: "") }
    var minSoldText by remember { mutableStateOf(config.filter.minSoldCount?.toString() ?: "") }
    var maxSoldText by remember { mutableStateOf(config.filter.maxSoldCount?.toString() ?: "") }
    var minPromoterText by remember { mutableStateOf(config.filter.minPromoterCount?.toString() ?: "") }
    var maxPromoterText by remember { mutableStateOf(config.filter.maxPromoterCount?.toString() ?: "") }
    var timeLimitText by remember { mutableStateOf(config.timeLimitMs?.let { (it / 60000).toString() } ?: "") }
    var maxAttemptsEnabled by remember { mutableStateOf(config.maxAttemptsLimitEnabled) }
    var timeLimitEnabled by remember { mutableStateOf(config.timeLimitEnabled) }

    fun persist() {
        val count = countText.toIntOrNull()?.coerceIn(1, 100) ?: config.targetCount
        val minD = ((minDelayText.toDoubleOrNull() ?: 0.9) * 1000).toLong().coerceAtLeast(300)
        val maxD = ((maxDelayText.toDoubleOrNull() ?: 1.8) * 1000).toLong().coerceAtLeast(minD + 100)
        val timeLimit = timeLimitText.toLongOrNull()?.takeIf { it > 0 }?.let { it * 60000 }
        val filter = ProductFilterConfig(
            minCommissionPercent = minCommissionText.toDoubleOrNull(),
            maxCommissionPercent = maxCommissionText.toDoubleOrNull(),
            minPrice = minPriceText.toDoubleOrNull(),
            maxPrice = maxPriceText.toDoubleOrNull(),
            minSoldCount = minSoldText.toIntOrNull(),
            maxSoldCount = maxSoldText.toIntOrNull(),
            minPromoterCount = minPromoterText.toIntOrNull(),
            maxPromoterCount = maxPromoterText.toIntOrNull()
        )
        config = AutoCaptureConfig(count, minD, maxD, filter, timeLimit, maxAttemptsEnabled, timeLimitEnabled)
        AutoCapturePrefs.save(context, config)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White)
            .padding(16.dp)
    ) {
        Text(stringResource(R.string.auto_settings_title), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = InkColor)
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.auto_settings_desc),
            fontSize = 12.sp, color = MutedColor, lineHeight = 17.sp
        )
        Spacer(Modifier.height(14.dp))

        Text(stringResource(R.string.target_count_label), fontSize = 12.sp, color = InkColor, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = countText,
            onValueChange = { countText = it; persist() },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(0.dp)
        )

        Spacer(Modifier.height(16.dp))
        Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.max_attempts_label), fontSize = 12.sp, color = InkColor, fontWeight = FontWeight.Bold)
                Text(
                    stringResource(R.string.max_attempts_desc),
                    fontSize = 11.sp, color = MutedColor
                )
            }
            Switch(
                checked = maxAttemptsEnabled,
                onCheckedChange = { maxAttemptsEnabled = it; persist() }
            )
        }

        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.step_interval_label), fontSize = 12.sp, color = InkColor, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = minDelayText,
                onValueChange = { minDelayText = it; persist() },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text(stringResource(R.string.label_shortest)) },
                shape = RoundedCornerShape(0.dp)
            )
            OutlinedTextField(
                value = maxDelayText,
                onValueChange = { maxDelayText = it; persist() },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text(stringResource(R.string.label_longest)) },
                shape = RoundedCornerShape(0.dp)
            )
        }

        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.filter_title), fontSize = 13.sp, color = InkColor, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.filter_desc),
            fontSize = 11.sp, color = MutedColor
        )

        Spacer(Modifier.height(12.dp))
        FilterRangeRow(stringResource(R.string.filter_commission), minCommissionText, maxCommissionText,
            onMinChange = { minCommissionText = it; persist() },
            onMaxChange = { maxCommissionText = it; persist() }
        )

        Spacer(Modifier.height(10.dp))
        FilterRangeRow(stringResource(R.string.filter_price), minPriceText, maxPriceText,
            onMinChange = { minPriceText = it; persist() },
            onMaxChange = { maxPriceText = it; persist() }
        )

        Spacer(Modifier.height(10.dp))
        FilterRangeRow(stringResource(R.string.filter_sold), minSoldText, maxSoldText,
            onMinChange = { minSoldText = it; persist() },
            onMaxChange = { maxSoldText = it; persist() }
        )

        Spacer(Modifier.height(10.dp))
        FilterRangeRow(stringResource(R.string.filter_promoter), minPromoterText, maxPromoterText,
            onMinChange = { minPromoterText = it; persist() },
            onMaxChange = { maxPromoterText = it; persist() }
        )

        Spacer(Modifier.height(16.dp))
        Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.time_limit_label), fontSize = 12.sp, color = InkColor, fontWeight = FontWeight.Bold)
                Text(
                    stringResource(R.string.time_limit_desc),
                    fontSize = 11.sp, color = MutedColor
                )
            }
            Switch(
                checked = timeLimitEnabled,
                onCheckedChange = { timeLimitEnabled = it; persist() }
            )
        }
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = timeLimitText,
            onValueChange = { timeLimitText = it; persist() },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = timeLimitEnabled,
            label = { Text(stringResource(R.string.time_limit_hint)) },
            shape = RoundedCornerShape(0.dp)
        )

        // 每個欄位的onValueChange其實已經呼叫persist()即時存檔，這顆按鈕主要是讓使用者
        // 明確「按了才安心」——按下去除了再存一次（保險，避免任何漏接的欄位）,
        // 還會跳Toast明確告知「已儲存」，解決使用者反映看不出來有沒有真的存到的疑慮。
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                persist()
                Toast.makeText(context, context.getString(R.string.toast_params_saved), Toast.LENGTH_SHORT).show()
            },
            colors = ButtonDefaults.buttonColors(containerColor = InkColor),
            shape = RoundedCornerShape(0.dp),
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            Text(stringResource(R.string.btn_save_params), fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun TermuxTestCard(
    context: android.content.Context,
    termuxRunCommandGranted: Boolean,
    termuxPermissionLauncher: androidx.activity.result.ActivityResultLauncher<String>
) {
    var testStatus by remember { mutableStateOf("尚未測試") }
    var isPolling by remember { mutableStateOf(false) }
    var pollTarget by remember { mutableStateOf("") } // "echo" 或 "batch"
    var batchProgress by remember { mutableStateOf<TermuxRunner.BatchProgress?>(null) }

    val captionQueueDir = File(
        android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
        "CaptionQueue"
    )

    LaunchedEffect(isPolling, pollTarget) {
        if (!isPolling) return@LaunchedEffect
        while (isPolling) {
            if (pollTarget == "echo") {
                val result = TermuxRunner.getLastResult(context)
                if (result != null) {
                    testStatus = if (result.internalError != null) {
                        "失敗：${result.internalError}"
                    } else {
                        "完成，exitCode=${result.exitCode}\nstdout：${result.stdout}\nstderr：${result.stderr}"
                    }
                    isPolling = false
                }
            } else if (pollTarget == "batch") {
                val progress = TermuxRunner.readBatchProgress(captionQueueDir)
                batchProgress = progress
                if (progress?.status == "done") {
                    testStatus = "生成完成：成功${progress.okCount}／跳過${progress.skippedCount}／失敗${progress.errorCount}"
                    isPolling = false
                }
            }
            kotlinx.coroutines.delay(1500)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White)
            .padding(16.dp)
    ) {
        Text("Termux背景執行測試（開發驗證用）", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = InkColor)
        Spacer(Modifier.height(6.dp))
        Text(
            "驗證App能不能在背景觸發Termux執行指令，不用打開Termux介面。" +
                "前提：Termux已裝好、~/.termux/termux.properties有allow-external-apps=true。",
            fontSize = 12.sp, color = MutedColor, lineHeight = 17.sp
        )
        Spacer(Modifier.height(10.dp))

        Text(
            "Termux安裝狀態：${if (TermuxRunner.isTermuxInstalled(context)) "✓ 已安裝" else "✗ 未安裝"}",
            fontSize = 13.sp, color = InkColor
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "RUN_COMMAND權限：${if (termuxRunCommandGranted) "✓ 已授權" else "✗ 尚未授權"}",
            fontSize = 13.sp, color = InkColor
        )
        Spacer(Modifier.height(10.dp))

        if (!termuxRunCommandGranted) {
            Button(
                onClick = { termuxPermissionLauncher.launch("com.termux.permission.RUN_COMMAND") },
                colors = ButtonDefaults.buttonColors(containerColor = InkColor),
                shape = RoundedCornerShape(0.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("0. 先授權RUN_COMMAND權限（必須先做這步）")
            }
            Spacer(Modifier.height(10.dp))
        }

        Button(
            onClick = {
                if (!termuxRunCommandGranted) {
                    testStatus = "請先授權RUN_COMMAND權限"
                    return@Button
                }
                testStatus = "執行中…"
                pollTarget = "echo"
                val sent = TermuxRunner.runCommand(context, "echo hello-from-termux && sleep 1 && echo done")
                if (sent) {
                    isPolling = true
                } else {
                    testStatus = "送出失敗（Termux可能沒安裝，或RUN_COMMAND權限被拒絕）"
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = AccentColor),
            shape = RoundedCornerShape(0.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("1. 測試基本連線（echo指令）")
        }

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = {
                if (!termuxRunCommandGranted) {
                    testStatus = "請先授權RUN_COMMAND權限"
                    return@Button
                }
                testStatus = "生成中…"
                batchProgress = null
                pollTarget = "batch"
                val sent = TermuxRunner.runCommand(
                    context,
                    "cd ~/shopee-capture && python batch_generate.py ~/storage/downloads/CaptionQueue"
                )
                if (sent) {
                    isPolling = true
                } else {
                    testStatus = "送出失敗（Termux可能沒安裝，或RUN_COMMAND權限被拒絕）"
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
            shape = RoundedCornerShape(0.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("2. 實際觸發生成影片（batch_generate.py）")
        }

        Spacer(Modifier.height(10.dp))

        batchProgress?.let { p ->
            Text(
                "進度：${p.completed}/${p.total}　目前：${p.current}",
                fontSize = 12.sp, color = MutedColor
            )
            Spacer(Modifier.height(4.dp))
        }

        Text(testStatus, fontSize = 12.sp, color = InkColor, lineHeight = 17.sp)
    }
}

@Composable
fun UploadAutomationSettingsCard(context: android.content.Context) {
    var countText by remember { mutableStateOf(UploadAutomationPrefs.getTargetCount(context).toString()) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White)
            .padding(16.dp)
    ) {
        Text(stringResource(R.string.upload_settings_title), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = InkColor)
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.upload_settings_desc),
            fontSize = 12.sp, color = MutedColor, lineHeight = 17.sp
        )
        Spacer(Modifier.height(14.dp))

        Text(stringResource(R.string.upload_target_count_label), fontSize = 12.sp, color = InkColor, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = countText,
            onValueChange = { newValue ->
                countText = newValue
                val count = newValue.toIntOrNull()?.coerceIn(1, 50)
                if (count != null) UploadAutomationPrefs.setTargetCount(context, count)
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(0.dp)
        )
    }
}

@Composable
fun FilterRangeRow(
    label: String,
    minValue: String,
    maxValue: String,
    onMinChange: (String) -> Unit,
    onMaxChange: (String) -> Unit
) {
    Column {
        Text(label, fontSize = 12.sp, color = InkColor, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = minValue,
                onValueChange = onMinChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text(stringResource(R.string.label_min)) },
                shape = RoundedCornerShape(0.dp)
            )
            OutlinedTextField(
                value = maxValue,
                onValueChange = onMaxChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text(stringResource(R.string.label_max)) },
                shape = RoundedCornerShape(0.dp)
            )
        }
    }
}

@Composable
fun SetupStepCard(
    stepNumber: String,
    title: String,
    description: String,
    done: Boolean,
    buttonText: String,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .background(if (done) Color(0xFF8A9A87) else InkColor)
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(if (done) "✓" else stepNumber, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(10.dp))
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = InkColor)
        }
        Spacer(Modifier.height(8.dp))
        Text(description, fontSize = 12.sp, color = MutedColor, lineHeight = 17.sp)
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onClick,
            enabled = enabled,
            shape = RoundedCornerShape(0.dp),
            colors = ButtonDefaults.buttonColors(containerColor = if (done) Color(0xFF8A9A87) else AccentColor)
        ) {
            Text(buttonText, fontSize = 13.sp)
        }
    }
}

@Composable
fun QueueItemRow(item: QueueItem) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White)
            .padding(14.dp)
    ) {
        Text(item.productName ?: stringResource(R.string.unknown_product), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = InkColor)
        Spacer(Modifier.height(4.dp))
        Text(
            item.promoLink ?: stringResource(R.string.no_link),
            fontSize = 12.sp,
            color = MutedColor,
            maxLines = 1
        )
    }
}

data class QueueItem(val productName: String?, val promoLink: String?, val capturedAt: Long)

private fun loadQueueItems(): List<QueueItem> {
    val baseDir = File(
        android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
        "CaptionQueue"
    )
    if (!baseDir.exists()) return emptyList()
    return baseDir.listFiles()
        ?.filter { it.isDirectory }
        ?.mapNotNull { dir ->
            val metaFile = File(dir, "meta.json")
            if (!metaFile.exists()) return@mapNotNull null
            try {
                val json = JSONObject(metaFile.readText())
                QueueItem(
                    productName = json.optString("productName", null),
                    promoLink = json.optString("promoLink", null),
                    capturedAt = json.optLong("capturedAt", 0L)
                )
            } catch (e: Exception) {
                null
            }
        }
        ?.sortedByDescending { it.capturedAt }
        ?: emptyList()
}

fun isAccessibilityServiceEnabled(context: android.content.Context): Boolean {
    val expectedComponentName = "${context.packageName}/${ShopeeAccessibilityService::class.java.name}"
    val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    val splitter = TextUtils.SimpleStringSplitter(':')
    splitter.setString(enabledServices)
    while (splitter.hasNext()) {
        if (splitter.next().equals(expectedComponentName, ignoreCase = true)) return true
    }
    return false
}

fun canDrawOverlays(context: android.content.Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        Settings.canDrawOverlays(context)
    } else {
        true
    }
}

fun hasMediaPermission(context: android.content.Context): Boolean {
    val permission = if (Build.VERSION.SDK_INT >= 33) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

/** 階段2用：scanUploadCandidates() 讀 meta.json 需要「所有檔案存取」權限，
 *  這個特殊權限不是跑一般的 runtime permission dialog，要去系統設定頁單獨開，
 *  也只能用 Environment.isExternalStorageManager() 檢查目前狀態。
 *  Android 11以下沒有這個限制，一律視為已授予。 */
fun hasAllFilesAccess(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        android.os.Environment.isExternalStorageManager()
    } else {
        true
    }
}
