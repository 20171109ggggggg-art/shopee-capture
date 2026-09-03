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
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.io.File
import kotlinx.coroutines.launch
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

    // 【2026-08-28新增】通知權限（POST_NOTIFICATIONS，Android 13+才是runtime權限）。這個沒開，
    // 懸浮視窗服務的常駐通知（包含「停止自動化」「停止」這兩顆按鈕）就完全不會顯示出來——
    // 使用者會誤以為按鈕消失了或壞掉了，其實是系統根本沒把通知畫出來。用
    // NotificationManagerCompat.areNotificationsEnabled()查，這個API在所有版本都能正常運作
    // （低於Android 13的裝置這個權限預設就是granted，不影響判斷結果）。
    var notificationPermissionGranted by remember {
        mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled())
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationPermissionGranted = granted || NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    // 「允許受限制的設定」是Android 13+對側載App的額外鎖，系統沒有公開API可以查詢目前狀態
    // （見RestrictedSettingsPrefs.kt說明），只能讓使用者自己確認完之後手動勾選回報。
    var restrictedSettingsConfirmed by remember {
        mutableStateOf(RestrictedSettingsPrefs.isConfirmed(context))
    }

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
                notificationPermissionGranted = NotificationManagerCompat.from(context).areNotificationsEnabled()
                restrictedSettingsConfirmed = RestrictedSettingsPrefs.isConfirmed(context)
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

    // 【2026-09-02新增】把所有「必須完成」的權限步驟集中算一次還剩幾個沒做完，
    // 在畫面最上面用一句話提醒使用者還要往下滑完成幾步，不用自己從頭數卡片、
    // 也不用每次回來都重新確認哪些已經打勾——每個狀態變數本身已經會在從系統
    // 設定頁切回來時（見上面的ON_RESUME）自動更新，這裡只是彙整成一個總覽數字。
    // Termux相關的授權（termuxRunCommandGranted）不算進來，因為v1.026起「生成
    // 影片」已經改成App直接呼叫筆電服務，不再需要Termux。
    val remainingStepsCount = listOf(
        accessibilityEnabled, overlayGranted, mediaPermissionGranted,
        allFilesAccessGranted, notificationPermissionGranted, restrictedSettingsConfirmed
    ).count { !it }

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

        if (remainingStepsCount > 0) {
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.setup_steps_remaining, remainingStepsCount),
                fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AccentColor
            )
        }

        Spacer(Modifier.height(28.dp))

        SettingsExportImportCard(context)

        Spacer(Modifier.height(20.dp))

        RegionSettingsCard(context)

        Spacer(Modifier.height(20.dp))

        AccountSettingsCard(context)

        Spacer(Modifier.height(20.dp))

        ServerSettingsCard(context)

        Spacer(Modifier.height(20.dp))

        RestrictedSettingsStepCard(
            confirmed = restrictedSettingsConfirmed,
            onOpenSettings = {
                context.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${context.packageName}")
                    )
                )
            },
            onToggleConfirmed = {
                val newValue = !restrictedSettingsConfirmed
                restrictedSettingsConfirmed = newValue
                RestrictedSettingsPrefs.setConfirmed(context, newValue)
            }
        )

        Spacer(Modifier.height(14.dp))

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
            title = stringResource(R.string.step_notification_title),
            description = stringResource(R.string.step_notification_desc),
            done = notificationPermissionGranted,
            buttonText = stringResource(R.string.btn_grant_permission),
            onClick = {
                if (Build.VERSION.SDK_INT >= 33) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    context.startActivity(intent)
                }
            }
        )

        Spacer(Modifier.height(14.dp))

        SetupStepCard(
            stepNumber = "5",
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
            stepNumber = "6",
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

        BatteryAndAutostartCard(context)

        Spacer(Modifier.height(14.dp))

        TermuxTestCard(context, termuxRunCommandGranted, termuxPermissionLauncher)

        Spacer(Modifier.height(14.dp))

        VideoImportCard(context)

        Spacer(Modifier.height(14.dp))

        AiBackgroundCard(context)

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
fun SettingsExportImportCard(context: android.content.Context) {
    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
        as android.content.ClipboardManager

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White)
            .padding(16.dp)
    ) {
        Text(stringResource(R.string.settings_export_title), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = InkColor)
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.settings_export_desc),
            fontSize = 12.sp, color = MutedColor, lineHeight = 17.sp
        )
        Spacer(Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = {
                    val json = JSONObject().apply {
                        put("account", AccountPrefs.getAccount(context))
                        put("accountHistory", org.json.JSONArray(AccountPrefs.getAccountHistory(context)))
                        put("region", RegionPrefs.getRegion(context).label)
                        put("serverUrl", ServerPrefs.getServerUrl(context))
                        put("geminiApiKey", GeminiApiPrefs.getApiKey(context))
                        put("geminiEnabled", GeminiApiPrefs.isEnabled(context))
                    }
                    clipboard.setPrimaryClip(
                        android.content.ClipData.newPlainText("shopee_capture_settings", json.toString())
                    )
                    Toast.makeText(context, context.getString(R.string.settings_export_done), Toast.LENGTH_SHORT).show()
                },
                shape = RoundedCornerShape(0.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.btn_export_settings), fontSize = 13.sp)
            }
            Spacer(Modifier.width(10.dp))
            Button(
                onClick = {
                    val clip = clipboard.primaryClip
                    val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).text?.toString() else null
                    if (text.isNullOrBlank()) {
                        Toast.makeText(context, context.getString(R.string.settings_import_empty), Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    try {
                        val json = JSONObject(text)
                        json.optString("account", "").takeIf { it.isNotBlank() }
                            ?.let { AccountPrefs.setAccount(context, it) }
                        json.optString("region", "").takeIf { it.isNotBlank() }
                            ?.let { RegionPrefs.setRegion(context, ShopeeRegion.fromLabel(it)) }
                        json.optString("serverUrl", "").takeIf { it.isNotBlank() }
                            ?.let { ServerPrefs.setServerUrl(context, it) }
                        json.optString("geminiApiKey", "").takeIf { it.isNotBlank() }
                            ?.let { GeminiApiPrefs.setApiKey(context, it) }
                        if (json.has("geminiEnabled")) {
                            GeminiApiPrefs.setEnabled(context, json.optBoolean("geminiEnabled", false))
                        }
                        Toast.makeText(context, context.getString(R.string.settings_import_done), Toast.LENGTH_LONG).show()
                    } catch (e: Exception) {
                        Toast.makeText(context, context.getString(R.string.settings_import_failed), Toast.LENGTH_SHORT).show()
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = InkColor),
                shape = RoundedCornerShape(0.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.btn_import_settings), fontSize = 13.sp)
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
fun AccountSettingsCard(context: android.content.Context) {
    var accountText by remember { mutableStateOf(AccountPrefs.getAccount(context)) }
    var history by remember { mutableStateOf(AccountPrefs.getAccountHistory(context)) }
    val scope = rememberCoroutineScope()
    var restoring by remember { mutableStateOf(false) }

    fun saveAccount(value: String) {
        AccountPrefs.setAccount(context, value)
        accountText = AccountPrefs.getAccount(context)
        history = AccountPrefs.getAccountHistory(context)
        Toast.makeText(context, context.getString(R.string.toast_account_saved), Toast.LENGTH_SHORT).show()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White)
            .padding(16.dp)
    ) {
        Text(stringResource(R.string.account_title), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = InkColor)
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.account_desc),
            fontSize = 12.sp, color = MutedColor, lineHeight = 17.sp
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = accountText,
            onValueChange = { accountText = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.account_hint)) },
            shape = RoundedCornerShape(0.dp)
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { saveAccount(accountText) },
            colors = ButtonDefaults.buttonColors(containerColor = InkColor),
            shape = RoundedCornerShape(0.dp),
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            Text(stringResource(R.string.btn_save_account), fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }

        if (history.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Text(
                stringResource(R.string.account_history_label),
                fontSize = 12.sp, color = MutedColor, fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            FlowRowChips(
                options = history,
                selected = accountText,
                onSelect = { picked -> saveAccount(picked) }
            )
        }

        // 【2026-09-02新增】新手機或資料遺失時，把這個帳號之前在筆電上備份過的
        // 防重複資料庫抓回來合併還原，不用整支手機重新走一次擷取歷史。
        Spacer(Modifier.height(14.dp))
        OutlinedButton(
            onClick = {
                if (!ServerPrefs.isConfigured(context)) {
                    Toast.makeText(context, context.getString(R.string.simple_no_server), Toast.LENGTH_SHORT).show()
                    return@OutlinedButton
                }
                restoring = true
                scope.launch {
                    try {
                        val count = RemoteVideoGenerator.restoreDedupHistory(context, accountText)
                        Toast.makeText(
                            context,
                            context.getString(R.string.account_restore_done, count),
                            Toast.LENGTH_LONG
                        ).show()
                    } catch (e: Exception) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.account_restore_failed, e.message ?: ""),
                            Toast.LENGTH_LONG
                        ).show()
                    } finally {
                        restoring = false
                    }
                }
            },
            enabled = !restoring,
            shape = RoundedCornerShape(0.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                if (restoring) stringResource(R.string.account_restoring)
                else stringResource(R.string.account_restore_button),
                fontSize = 14.sp
            )
        }
    }
}

@Composable
fun ServerSettingsCard(context: android.content.Context) {
    var urlText by remember { mutableStateOf(ServerPrefs.getServerUrl(context)) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White)
            .padding(16.dp)
    ) {
        Text(stringResource(R.string.server_title), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = InkColor)
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.server_desc),
            fontSize = 12.sp, color = MutedColor, lineHeight = 17.sp
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = urlText,
            onValueChange = { urlText = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.server_hint)) },
            shape = RoundedCornerShape(0.dp)
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                ServerPrefs.setServerUrl(context, urlText)
                urlText = ServerPrefs.getServerUrl(context)
                Toast.makeText(context, context.getString(R.string.toast_server_saved), Toast.LENGTH_SHORT).show()
            },
            colors = ButtonDefaults.buttonColors(containerColor = InkColor),
            shape = RoundedCornerShape(0.dp),
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            Text(stringResource(R.string.btn_save_server), fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
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

/**
 * 一次性功能：如果蝦皮帳號在改用本App之前，已經用別的工具上架過大量短影音（例如舊帳號
 * 遷移過來），這些商品全部處於「已收藏」狀態，未來會被本App的擷取器當成全新商品重複處理。
 * 這裡改成掃描蝦皮「我的短影音」的「影片」分頁那個真正的已發布清單，把讀到的商品名稱
 * 灌進既有的防重複資料庫，之後擷取器掃描候選清單時會自動跳過——不用改動任何既有邏輯。
 * 詳細原理見 ShopeeAccessibilityService.startVideoImport() 的說明。
 */
@Composable
fun VideoImportCard(context: android.content.Context) {
    var batchSizeText by remember { mutableStateOf(VideoImportPrefs.loadBatchSize(context).toString()) }

    fun persist() {
        val n = batchSizeText.toIntOrNull()?.coerceAtLeast(1) ?: VideoImportPrefs.DEFAULT_BATCH_SIZE
        VideoImportPrefs.saveBatchSize(context, n)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White)
            .padding(16.dp)
    ) {
        Text("匯入舊短影音商品名稱（避免重複擷取）", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = InkColor)
        Spacer(Modifier.height(6.dp))
        Text(
            "如果蝦皮帳號在改用本App之前，已經用別的工具上架過大量短影音，這些商品都已收藏、" +
                "會被擷取器誤判成新商品。這裡改掃描「我的短影音」實際發布過的清單，把商品名稱" +
                "灌進防重複資料庫，之後就會自動跳過。",
            fontSize = 12.sp, color = MutedColor, lineHeight = 17.sp
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "這裡只設定「每次要新增幾筆」，實際開始請切到蝦皮App「我的短影音」的「影片」分頁" +
                "（格狀縮圖清單畫面），點懸浮球上的「匯入影音」按鈕。",
            fontSize = 12.sp, color = MutedColor, lineHeight = 17.sp
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = batchSizeText,
            onValueChange = {
                batchSizeText = it.filter { c -> c.isDigit() }
                persist()
            },
            label = { Text("每次要新增幾筆", fontSize = 12.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * AI換背景（測試功能）：保留商品本體，只換背景，用Nano Banana 2 Lite（Gemini API）處理。
 * 預設關閉，開啟後每次擷取商品時，圖片存檔前會先送去Gemini API改圖；呼叫失敗一律靜默改用
 * 原圖，不會影響原本的擷取流程。
 */
@Composable
fun AiBackgroundCard(context: android.content.Context) {
    var apiKey by remember { mutableStateOf(GeminiApiPrefs.getApiKey(context)) }
    var enabled by remember { mutableStateOf(GeminiApiPrefs.isEnabled(context)) }
    var prompt by remember { mutableStateOf(GeminiApiPrefs.getPrompt(context)) }
    var apiKeyVisible by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White)
            .padding(16.dp)
    ) {
        Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("AI換背景（測試功能）", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = InkColor)
            Switch(
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    GeminiApiPrefs.setEnabled(context, it)
                }
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "保留商品本體不變，只把背景換掉（用Google Nano Banana 2 Lite）。改成「生成影片」畫面裡" +
                "人工選圖時觸發——點進某個商品挑選要保留的圖片後，打勾的圖片會送去AI改圖；改圖失敗" +
                "（沒網路、Key錯誤、額度用完）會自動改用原圖，不影響選圖結果。",
            fontSize = 12.sp, color = MutedColor, lineHeight = 17.sp
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "還沒有API Key？瀏覽器打開 aistudio.google.com/apikey，用Google帳號登入後點" +
                "「Create API key」，複製產生的金鑰貼到下面欄位即可。有免費額度可以先試用。",
            fontSize = 12.sp, color = MutedColor, lineHeight = 17.sp
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = apiKey,
            onValueChange = {
                apiKey = it
                GeminiApiPrefs.setApiKey(context, it)
            },
            label = { Text("Gemini API Key", fontSize = 12.sp) },
            singleLine = true,
            visualTransformation = if (apiKeyVisible) androidx.compose.ui.text.input.VisualTransformation.None
                else androidx.compose.ui.text.input.PasswordVisualTransformation(),
            trailingIcon = {
                TextButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                    Text(if (apiKeyVisible) "隱藏" else "顯示", fontSize = 11.sp)
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))

        OutlinedTextField(
            value = prompt,
            onValueChange = {
                prompt = it
                GeminiApiPrefs.setPrompt(context, it)
            },
            label = { Text("換背景提示詞（可自行調整）", fontSize = 12.sp) },
            minLines = 3,
            modifier = Modifier.fillMaxWidth()
        )
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
fun BatteryAndAutostartCard(context: android.content.Context) {
    // 【2026-08-28新增】使用者反映App在背景跑上架/FB上架時，行程會被系統強制關掉、
    // log整個沒有收尾。這張卡片幫忙處理兩件事：
    // 1. 電池優化排除——Android官方有提供的API，可以直接跳系統對話框請求，比去設定裡層層找快。
    // 2. 自啟動——沒有官方API（Android刻意不開放，避免App自己解鎖背景常駐被惡意濫用），
    //    這裡只能依廠牌（小米/紅米/POCO、OPPO、VIVO、華為等）嘗試導頁到對應的設定頁面，
    //    最後「允許」還是要使用者自己手動點。抓不到對應廠牌時，退回App本身的系統設定頁。
    val isIgnoringBatteryOptimizations = remember {
        mutableStateOf(isIgnoringBatteryOptimizations(context))
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White)
            .padding(16.dp)
    ) {
        Text(stringResource(R.string.battery_autostart_title), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = InkColor)
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.battery_autostart_desc),
            fontSize = 12.sp, color = MutedColor, lineHeight = 17.sp
        )
        Spacer(Modifier.height(14.dp))

        SetupStepCard(
            stepNumber = "電",
            title = stringResource(R.string.battery_optimization_title),
            description = stringResource(R.string.battery_optimization_desc),
            done = isIgnoringBatteryOptimizations.value,
            buttonText = stringResource(R.string.btn_go_to_settings),
            onClick = {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                    context.startActivity(intent)
                } catch (e: Exception) {
                    try {
                        val fallback = Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${context.packageName}")
                        )
                        context.startActivity(fallback)
                    } catch (_: Exception) { }
                }
            }
        )

        Spacer(Modifier.height(14.dp))

        SetupStepCard(
            stepNumber = "啟",
            title = stringResource(R.string.autostart_title),
            description = stringResource(R.string.autostart_desc),
            done = false,
            buttonText = stringResource(R.string.btn_go_to_settings),
            onClick = { openAutostartSettings(context) }
        )
    }
}

fun isIgnoringBatteryOptimizations(context: android.content.Context): Boolean {
    val powerManager = context.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
        ?: return false
    return powerManager.isIgnoringBatteryOptimizations(context.packageName)
}

fun openAutostartSettings(context: android.content.Context) {
    val manufacturer = Build.MANUFACTURER.lowercase()
    val candidates = when {
        manufacturer.contains("xiaomi") -> listOf(
            Intent().setClassName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
        )
        manufacturer.contains("oppo") -> listOf(
            Intent().setClassName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            Intent().setClassName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity")
        )
        manufacturer.contains("vivo") -> listOf(
            Intent().setClassName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")
        )
        manufacturer.contains("huawei") -> listOf(
            Intent().setClassName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
        )
        else -> emptyList()
    }
    for (intent in candidates) {
        try {
            context.startActivity(intent)
            return
        } catch (_: Exception) { }
    }
    try {
        val fallback = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}")
        )
        context.startActivity(fallback)
    } catch (_: Exception) { }
}

@Composable
fun UploadAutomationSettingsCard(context: android.content.Context) {
    var countText by remember { mutableStateOf(UploadAutomationPrefs.getTargetCount(context).toString()) }
    // 【2026-08-28新增】FB上架功能總開關：關掉之後，蝦皮批次跑完不會自動啟動FB接續，
    // 懸浮視窗的「FB上架」按鈕也會直接提示已關閉、不執行。使用者已在使用FB上架接龍，
    // 所以預設是開啟的，這裡是給需要暫時停用時用的（例如FB上架流程還在調整、先不想被自動觸發）。
    var fbUploadEnabled by remember { mutableStateOf(UploadAutomationPrefs.isFbUploadEnabled(context)) }

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

        Spacer(Modifier.height(16.dp))
        Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.fb_upload_enabled_label), fontSize = 12.sp, color = InkColor, fontWeight = FontWeight.Bold)
                Text(
                    stringResource(R.string.fb_upload_enabled_desc),
                    fontSize = 11.sp, color = MutedColor
                )
            }
            Switch(
                checked = fbUploadEnabled,
                onCheckedChange = {
                    fbUploadEnabled = it
                    UploadAutomationPrefs.setFbUploadEnabled(context, it)
                }
            )
        }
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
fun RestrictedSettingsStepCard(
    confirmed: Boolean,
    onOpenSettings: () -> Unit,
    onToggleConfirmed: () -> Unit
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
                    .background(if (confirmed) Color(0xFF8A9A87) else InkColor)
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(if (confirmed) "✓" else "!", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.step_restricted_title), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = InkColor)
        }
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.step_restricted_desc), fontSize = 12.sp, color = MutedColor, lineHeight = 17.sp)
        Spacer(Modifier.height(12.dp))
        Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = onOpenSettings,
                shape = RoundedCornerShape(0.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentColor)
            ) {
                Text(stringResource(R.string.btn_go_to_settings), fontSize = 13.sp)
            }
            Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                modifier = Modifier.clickable { onToggleConfirmed() }
            ) {
                Checkbox(checked = confirmed, onCheckedChange = { onToggleConfirmed() })
                Text(stringResource(R.string.step_restricted_confirm_label), fontSize = 12.sp, color = InkColor)
            }
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
