package com.tagcopy.shopeecapture

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
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
            title = "讀取相簿權限",
            description = "分享面板下載鈕擷取原圖，需要讀取相簿才能抓到剛存下的圖片檔案。",
            done = mediaPermissionGranted,
            buttonText = "授予權限",
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
            title = "所有檔案存取權限",
            description = "上架自動化需要讀取 CaptionQueue 底下每個商品的 meta.json，這類一般檔案沒開此權限會被系統擋掉（EACCES），影片與圖片存取不受影響。",
            done = allFilesAccessGranted,
            buttonText = "前往設定",
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

private fun isAccessibilityServiceEnabled(context: android.content.Context): Boolean {
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

private fun canDrawOverlays(context: android.content.Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        Settings.canDrawOverlays(context)
    } else {
        true
    }
}

private fun hasMediaPermission(context: android.content.Context): Boolean {
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
private fun hasAllFilesAccess(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        android.os.Environment.isExternalStorageManager()
    } else {
        true
    }
}
