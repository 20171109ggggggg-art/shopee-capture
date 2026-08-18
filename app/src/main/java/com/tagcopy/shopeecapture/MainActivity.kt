package com.tagcopy.shopeecapture

import android.content.Intent
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
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
    var queueItems by remember { mutableStateOf(listOf<QueueItem>()) }

    val context = androidx.compose.ui.platform.LocalContext.current

    val overlayLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        overlayGranted = canDrawOverlays(context)
    }

    LaunchedEffect(Unit) {
        accessibilityEnabled = isAccessibilityServiceEnabled(context)
        overlayGranted = canDrawOverlays(context)
        queueItems = loadQueueItems()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PaperColor)
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Text("蝦皮擷取器", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = InkColor)
        Spacer(Modifier.height(6.dp))
        Text(
            "瀏覽到蝦皮分潤商品分享面板後，點懸浮按鈕自動複製連結並截圖商品",
            fontSize = 13.sp, color = MutedColor, lineHeight = 19.sp
        )
        Spacer(Modifier.height(28.dp))

        RegionSettingsCard(context)

        Spacer(Modifier.height(20.dp))

        SetupStepCard(
            stepNumber = "1",
            title = "開啟無障礙服務",
            description = "允許本 App 讀取蝦皮畫面內容（僅限蝦皮，不會讀取其他 App）",
            done = accessibilityEnabled,
            buttonText = "前往設定",
            onClick = {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        )

        Spacer(Modifier.height(14.dp))

        SetupStepCard(
            stepNumber = "2",
            title = "允許顯示在其他 App 之上",
            description = "讓懸浮擷取按鈕能顯示在蝦皮畫面上",
            done = overlayGranted,
            buttonText = "前往設定",
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
            title = "啟動懸浮按鈕",
            description = if (floatingServiceRunning) "運作中，可以切到蝦皮 App 開始使用" else "點擊後切換到蝦皮 App 即可看到懸浮按鈕",
            done = floatingServiceRunning,
            buttonText = if (floatingServiceRunning) "停止" else "啟動",
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

        AutoCaptureSettingsCard(context)

        Spacer(Modifier.height(32.dp))

        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text("已擷取商品", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = InkColor)
            Text(
                "重新整理",
                fontSize = 13.sp,
                color = AccentColor,
                modifier = Modifier.clickable { queueItems = loadQueueItems() }
            )
        }
        Spacer(Modifier.height(12.dp))

        if (queueItems.isEmpty()) {
            Text("還沒有擷取任何商品", fontSize = 13.sp, color = MutedColor)
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
        Text("蝦皮地區", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = InkColor)
        Spacer(Modifier.height(6.dp))
        Text(
            "選擇你使用的蝦皮地區版本，會自動加入當地語言的按鈕文字比對（例如菲律賓版的分享／複製連結字樣），提高辨識成功率",
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
        Text("全自動擷取設定", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = InkColor)
        Spacer(Modifier.height(6.dp))
        Text(
            "在蝦皮商品列表畫面點懸浮視窗的「自動」按鈕，就會依這裡的設定連續擷取多件商品，不用逐一手動點擊",
            fontSize = 12.sp, color = MutedColor, lineHeight = 17.sp
        )
        Spacer(Modifier.height(14.dp))

        Text("目標擷取數量", fontSize = 12.sp, color = InkColor, fontWeight = FontWeight.Bold)
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
                Text("最大嘗試次數限制", fontSize = 12.sp, color = InkColor, fontWeight = FontWeight.Bold)
                Text(
                    "目標數量×6（至少30次），避免篩選條件太嚴格時無止盡翻找",
                    fontSize = 11.sp, color = MutedColor
                )
            }
            Switch(
                checked = maxAttemptsEnabled,
                onCheckedChange = { maxAttemptsEnabled = it; persist() }
            )
        }

        Spacer(Modifier.height(12.dp))
        Text("每步驟間隔秒數（隨機區間，越大越慢但越不像機器操作）", fontSize = 12.sp, color = InkColor, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = minDelayText,
                onValueChange = { minDelayText = it; persist() },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("最短") },
                shape = RoundedCornerShape(0.dp)
            )
            OutlinedTextField(
                value = maxDelayText,
                onValueChange = { maxDelayText = it; persist() },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("最長") },
                shape = RoundedCornerShape(0.dp)
            )
        }

        Spacer(Modifier.height(20.dp))
        Text("篩選條件（留空代表不限制）", fontSize = 13.sp, color = InkColor, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(
            "不符合條件的商品會自動跳過、換下一個，不會被擷取",
            fontSize = 11.sp, color = MutedColor
        )

        Spacer(Modifier.height(12.dp))
        FilterRangeRow("分潤率 %", minCommissionText, maxCommissionText,
            onMinChange = { minCommissionText = it; persist() },
            onMaxChange = { maxCommissionText = it; persist() }
        )

        Spacer(Modifier.height(10.dp))
        FilterRangeRow("商品價格", minPriceText, maxPriceText,
            onMinChange = { minPriceText = it; persist() },
            onMaxChange = { maxPriceText = it; persist() }
        )

        Spacer(Modifier.height(10.dp))
        FilterRangeRow("已售出數量", minSoldText, maxSoldText,
            onMinChange = { minSoldText = it; persist() },
            onMaxChange = { maxSoldText = it; persist() }
        )

        Spacer(Modifier.height(10.dp))
        FilterRangeRow("已推廣者數量", minPromoterText, maxPromoterText,
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
                Text("篩選時間上限", fontSize = 12.sp, color = InkColor, fontWeight = FontWeight.Bold)
                Text(
                    "時間到了還沒湊到目標數量，會自動停止並跳出明顯提醒",
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
            label = { Text("分鐘，留空代表不限制") },
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
                label = { Text("最低") },
                shape = RoundedCornerShape(0.dp)
            )
            OutlinedTextField(
                value = maxValue,
                onValueChange = onMaxChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("最高") },
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
        Text(item.productName ?: "未知商品", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = InkColor)
        Spacer(Modifier.height(4.dp))
        Text(
            item.promoLink ?: "（無連結）",
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
