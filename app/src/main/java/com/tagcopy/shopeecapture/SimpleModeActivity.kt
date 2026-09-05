package com.tagcopy.shopeecapture

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
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

    // 【2026-08-28新增】原本子畫面（擷取／生成影片／檢視影片／上架）沒有攔截手機實體返回鍵，
    // 導致在子畫面按返回鍵會直接整個App跳出（Activity被finish），不是使用者預期的「先回首頁」。
    // 只在非首頁時攔截返回鍵、把screen切回HOME；已經在首頁時放行給系統預設行為
    // （enabled=false時BackHandler形同不存在），此時按返回鍵才是真的離開App，這才是正常預期。
    androidx.activity.compose.BackHandler(enabled = screen != SimpleScreen.HOME) {
        screen = SimpleScreen.HOME
    }

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

        Spacer(Modifier.height(14.dp))
        HomeActionCard(
            step = "5",
            title = stringResource(R.string.simple_advanced_settings),
            desc = stringResource(R.string.simple_step5_desc),
            color = SimpleMuted,
            onClick = { context.startActivity(Intent(context, MainActivity::class.java)) }
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

/**
 * 【2026-08-29新增】「生成影片」畫面的商品清單資料：讀取CaptionQueue底下每個商品資料夾的
 * 基本資訊（名稱、圖片清單、是否已經選過圖、是否已經有影片），給下面的勾選清單跟人工選圖畫面用。
 */
private data class GenerateQueueItem(
    val folder: File,
    val productName: String?,
    val imagePaths: List<File>,
    val hasVideo: Boolean,
    val selectionDone: Boolean,
    val aiProcessed: Boolean
)

private fun loadCapturedProducts(root: File): List<GenerateQueueItem> {
    if (!root.exists()) return emptyList()
    return root.listFiles()
        ?.filter { it.isDirectory }
        ?.mapNotNull { dir ->
            val metaFile = File(dir, "meta.json")
            if (!metaFile.exists()) return@mapNotNull null
            val name = try {
                JSONObject(metaFile.readText()).optString("productName", null)
            } catch (e: Exception) {
                null
            }
            val images = (1..20).mapNotNull { i ->
                listOf("jpg", "jpeg", "png")
                    .map { ext -> File(dir, "image_$i.$ext") }
                    .firstOrNull { it.exists() }
            }
            if (images.isEmpty()) return@mapNotNull null
            GenerateQueueItem(
                folder = dir,
                productName = name,
                imagePaths = images,
                hasVideo = File(dir, "output.mp4").exists(),
                selectionDone = File(dir, ".image_selection_done").exists(),
                aiProcessed = File(dir, ".ai_processed").exists()
            )
        }
        ?.sortedByDescending { it.folder.lastModified() }
        ?: emptyList()
}

/** 清單裡單一商品的一列：打勾決定要不要納入這次生成批次，點整列（打勾框以外的地方）進去選圖。 */
/**
 * 正確做法的縮圖解碼：先用inJustDecodeBounds讀出圖片實際尺寸，算出剛好夠用的縮小倍率
 * 再正式解碼一次。回傳(Bitmap?, 失敗原因字串?)——原本直接吞掉例外訊息，這次改成把
 * 實際失敗原因也回傳出去，直接顯示在畫面上，不用另外抓debug log才能排查。
 */
private fun decodeSampledBitmap(path: String, targetSize: Int): Pair<android.graphics.Bitmap?, String?> {
    val file = java.io.File(path)
    if (!file.exists()) return null to "檔案不存在"
    if (file.length() == 0L) return null to "檔案大小0byte"
    return try {
        val boundsOpts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(path, boundsOpts)
        if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) {
            return null to "讀不到圖片尺寸(${file.length()}bytes)"
        }
        var sampleSize = 1
        var halfWidth = boundsOpts.outWidth / 2
        var halfHeight = boundsOpts.outHeight / 2
        while (halfWidth / sampleSize >= targetSize && halfHeight / sampleSize >= targetSize) {
            sampleSize *= 2
        }
        val decodeOpts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val bitmap = android.graphics.BitmapFactory.decodeFile(path, decodeOpts)
        if (bitmap == null) bitmap to "decodeFile回傳null(${boundsOpts.outWidth}x${boundsOpts.outHeight})"
        else bitmap to null
    } catch (e: Exception) {
        null to "${e.javaClass.simpleName}:${e.message}"
    }
}

@Composable
private fun ProductSelectRow(
    product: GenerateQueueItem,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onClickImages: () -> Unit
) {
    val context = LocalContext.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White)
            .clickable { onClickImages() }
            .padding(10.dp)
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Spacer(Modifier.width(4.dp))
        val thumb = product.imagePaths.firstOrNull()
        val bitmap = remember(thumb?.path) {
            thumb?.let { decodeSampledBitmap(it.path, 48).first }
        }
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                contentScale = ContentScale.Crop
            )
            Spacer(Modifier.width(10.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                product.productName ?: product.folder.name,
                fontSize = 13.sp, color = SimpleInk, fontWeight = FontWeight.Bold,
                maxLines = 2
            )
            Spacer(Modifier.height(4.dp))
            Text(
                buildString {
                    append("${product.imagePaths.size}張圖")
                    if (product.selectionDone) append(" · 已選圖")
                    if (product.selectionDone && GeminiApiPrefs.isEnabled(context)) {
                        append(if (product.aiProcessed) " · 已AI改圖" else " · 待AI改圖")
                    }
                    if (product.hasVideo) append(" · 已有影片")
                },
                fontSize = 11.sp, color = SimpleMuted
            )
        }
    }
}

/**
 * 人工選圖畫面：列出這個商品擷取到的所有圖片，點擊圖片切換打勾／取消。確認後：
 * 打勾的圖片如果AI換背景是開啟狀態就送去改圖（改圖失敗保留原圖，不中斷整批），
 * 沒打勾的圖片直接刪除——只有這裡選定的圖片會留下來，之後生成影片只會用到這幾張。
 */
@Composable
private fun ImageSelectionContent(context: Context, product: GenerateQueueItem, onDone: () -> Unit) {
    // 預設全不選：使用者自己挑要保留的幾張，比全選後再取消不要的更符合預期。
    var chosen by remember { mutableStateOf(emptySet<String>()) }
    var errorText by remember { mutableStateOf<String?>(null) }

    androidx.activity.compose.BackHandler(enabled = true) { onDone() }

    Column(modifier = Modifier.fillMaxSize()) {
        SimpleTopBar(product.productName ?: "選擇圖片", onDone)
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            InstructionCard(
                lines = listOf(
                    "點選圖片可以打勾／取消，只有打勾的圖片會保留下來，其餘直接刪除（預設全部不勾選）",
                    if (GeminiApiPrefs.isEnabled(context))
                        "確認選圖後不會馬上處理，全部商品選完後到浮球按「AI改圖」，會一次背景批次處理"
                    else
                        "AI換背景目前是關閉狀態，確認後只會保留打勾的圖片，不經過AI改圖"
                )
            )
            Spacer(Modifier.height(16.dp))

            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.height((((product.imagePaths.size + 2) / 3) * 130).dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(product.imagePaths) { file ->
                    val isChosen = chosen.contains(file.name)
                    val decodeResult = remember(file.path) { decodeSampledBitmap(file.path, 120) }
                    val bitmap = decodeResult.first
                    val errorReason = decodeResult.second
                    Box(
                        modifier = Modifier
                            .size(120.dp)
                            .background(Color(0xFFE0DCD4))
                            .clickable {
                                chosen = if (isChosen) chosen - file.name else chosen + file.name
                            }
                    ) {
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            // 解碼失敗時把檔名跟實際失敗原因都顯示出來，直接從畫面截圖
                            // 就能排查，不用另外抓debug log。
                            Text(
                                "${file.name}\n${errorReason ?: "未知原因"}",
                                fontSize = 9.sp,
                                color = SimpleDanger,
                                modifier = Modifier.align(Alignment.Center).padding(4.dp)
                            )
                        }
                        if (!isChosen) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.55f))
                            )
                        }
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(4.dp)
                                .size(22.dp)
                                .background(
                                    if (isChosen) SimpleAccent else Color.White,
                                    RoundedCornerShape(11.dp)
                                )
                        ) {
                            if (isChosen) {
                                Text(
                                    "✓", color = Color.White, fontSize = 14.sp,
                                    modifier = Modifier.align(Alignment.Center)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            errorText?.let {
                Text(it, color = SimpleDanger, fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
            }

            BigActionButton(
                text = "確認選圖",
                color = SimpleInk,
                enabled = chosen.isNotEmpty(),
                onClick = {
                    errorText = null
                    try {
                        applyImageSelection(product.folder, product.imagePaths, chosen)
                        onDone()
                    } catch (e: Exception) {
                        errorText = "處理失敗：${e.message}"
                    }
                }
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * 實際執行選圖結果：沒打勾的刪除，打勾的重新依序編號成image_1.jpg、image_2.jpg...。
 *
 * 【2026-08-30修正】原本選圖確認後會在這裡直接同步呼叫AI改圖，選一個商品就要等AI跑完
 * 才能選下一個，幾十個商品要一直卡在等待——改成選圖只負責「決定留哪幾張、重新編號」，
 * 純本地檔案操作、幾乎不用等；AI改圖拆成獨立的背景批次工作（見ShopeeAccessibilityService.
 * startAiImageBatch()，浮球新增「AI改圖」按鈕觸發），可以先把所有商品的圖都選完，
 * 再一次讓AI批次在背景跑、同時處理多張加快速度，不用選一個等一個。
 *
 * 【2026-08-29修正，仍然保留】原本刪除後維持原編號，若使用者剛好取消勾選image_1，
 * 資料夾會沒有image_1.jpg，導致make_video.py／batch_generate.py的find_product_folders()／
 * find_images()直接判定「找不到商品資料夾」整批被忽略，已修正為選圖確認後強制重新編號。
 */
private fun applyImageSelection(
    folder: File,
    allImages: List<File>,
    chosenNames: Set<String>
) {
    val kept = mutableListOf<File>()
    for (file in allImages) {
        if (file.name !in chosenNames) {
            file.delete()
        } else {
            kept.add(file)
        }
    }

    // 重新編號：kept是依原本編號由小到大排的，第k個（0-index）的目標編號是k+1，
    // 一定 <= 它原本的編號（因為前面最多k個檔案被跳過/已重新命名挪走），所以依序
    // 處理不會發生「要改的目標檔名還被別的檔案佔用」的衝突，不需要額外用暫存檔名。
    kept.forEachIndexed { index, file ->
        val newIndex = index + 1
        val ext = file.extension.ifBlank { "jpg" }
        val newFile = File(folder, "image_$newIndex.$ext")
        if (file.path != newFile.path) {
            file.renameTo(newFile)
        }
    }

    File(folder, ".image_selection_done").createNewFile()
    // 選圖結果變了（不管是第一次選還是重新選過），舊的AI處理標記不再有效，
    // 讓它重新排進下一次AI改圖批次的待處理清單。
    File(folder, ".ai_processed").delete()
}

/**
 * 【2026-08-30新增，2026-09-03改成最多3張，2026-09-04新增查詢共用商品】自動選圖＋
 * AI改圖的整合流程，取代原本「手動九宮格選圖」+「浮球AI改圖批次按鈕」兩個分開的
 * 手動步驟。在「生成影片」畫面按下「開始生成影片」時，對每個勾選但還沒處理完的
 * 商品依序做：
 * 0.【2026-09-04新增】選圖/改圖都還沒做完的商品：先用meta.json的promoLink查詢
 *   筆電共用資料夾，看有沒有其他帳號已經處理過同一個商品（筆電端用連結解析出的
 *   「店鋪ID_商品ID」精準比對，不是用商品名稱模糊比對）。有找到就直接套用那批圖片
 *   （自動沿用，不彈窗詢問），標記選圖+AI改圖都完成，跳過下面1、2兩步驟，省下
 *   Gemini API辨識/改圖的時間與費用。查不到（連結解析失敗、沒有其他帳號處理過、
 *   或連不上筆電）就靜靜退回下面原本的流程，不會顯示錯誤或中斷這個商品的處理。
 * 1. 還沒選圖的商品：把候選圖片（最多10張）都讀進來，呼叫GeminiImageSelector自動挑出
 *    最多3張最完整的（商品本身候選圖不夠3張、或AI判斷合格的不到3張，就選幾張算幾張）。
 *    成功就只留這幾張（其餘刪除、依序重新命名成image_1/2/3），標記選圖完成；
 *    失敗（沒網路/API錯誤/AI回應解析不到）就跳過這個商品、記錄失敗原因，不會用猜的
 *    隨便選一張——使用者之後可以自己點進「人工選圖」畫面手動處理。
 * 2. 選圖完成但還沒AI改圖、且AI換背景功能有開啟的商品：對這幾張圖各自呼叫
 *    GeminiImageEditor改背景，標記AI改圖完成。單張改圖失敗保留該張原圖繼續走，
 *    不影響這個商品能不能繼續生成影片（只是那張背景沒換成功而已）。
 *    這幾張改好的圖，連同商品名稱/連結，會額外同步一份到筆電的共用資料夾（見
 *    RemoteVideoGenerator.uploadSharedProductImages()），方便之後其他帳號擷取到
 *    同一個商品時能重複利用。套用共用圖片的商品（第0步）本來就是從共用資料夾
 *    拿來的，不會再同步回去。
 * 3. 已經選圖+AI改圖都完成的商品：不用重新處理，直接算成功。
 *
 * onStatus：即時回報目前處理到哪個商品、哪個步驟，給UI顯示簡短文字用。
 * 回傳(成功可以送去生成影片的資料夾名稱清單, 失敗清單(資料夾名稱, 原因))。
 */
private const val SHARED_IMAGE_POOL_SIZE = 3

private suspend fun runAutoSelectAndEditPipeline(
    context: Context,
    products: List<GenerateQueueItem>,
    onStatus: (String) -> Unit
): Pair<List<String>, List<Pair<String, String>>> {
    val succeeded = mutableListOf<String>()
    val failed = mutableListOf<Pair<String, String>>()
    val aiEnabled = GeminiApiPrefs.isEnabled(context)
    val apiKey = GeminiApiPrefs.getApiKey(context)
    val editPrompt = GeminiApiPrefs.getPrompt(context)
    // 【2026-09-05新增】AI改圖供應商可能是Gemini或ChatGPT（設定畫面選單切換），
    // 選圖辨識（GeminiImageSelector）不受影響、固定用Gemini，只有「換背景」這步
    // 依此分派給對應的object呼叫。
    val imageEditProvider = GeminiApiPrefs.getImageEditProvider(context)
    val openAiApiKey = GeminiApiPrefs.getOpenAiApiKey(context)

    products.forEachIndexed { idx, product ->
        val label = product.productName ?: product.folder.name
        val progressPrefix = "(${idx + 1}/${products.size}) $label"
        var currentImages = product.imagePaths
        var justSelected = false
        var usedSharedProduct = false

        val alreadyDone = product.selectionDone && File(product.folder, ".ai_processed").exists()
        if (!alreadyDone && ServerPrefs.isConfigured(context)) {
            val promoLink = try {
                JSONObject(File(product.folder, "meta.json").readText()).optString("promoLink", "")
            } catch (e: Exception) {
                ""
            }
            val region = try {
                JSONObject(File(product.folder, "meta.json").readText()).optString("region", "TW")
            } catch (e: Exception) {
                "TW"
            }
            if (promoLink.isNotBlank()) {
                onStatus("$progressPrefix：查詢共用圖片")
                usedSharedProduct = try {
                    RemoteVideoGenerator.applySharedProductImages(context, region, promoLink, product.folder)
                } catch (e: Exception) {
                    false
                }
                if (usedSharedProduct) {
                    onStatus("$progressPrefix：套用共用圖片")
                    File(product.folder, ".image_selection_done").createNewFile()
                    File(product.folder, ".ai_processed").createNewFile()
                    currentImages = (1..SHARED_IMAGE_POOL_SIZE).mapNotNull { n ->
                        product.folder.listFiles { f -> f.nameWithoutExtension == "image_$n" }?.firstOrNull()
                    }
                }
            }
        }

        if (!usedSharedProduct && !product.selectionDone) {
            onStatus("$progressPrefix：辨識圖片中")
            val bitmaps = currentImages.map { decodeSampledBitmap(it.path, 1024).first }
            if (bitmaps.any { it == null } || apiKey.isBlank()) {
                val reason = if (apiKey.isBlank()) "尚未設定Gemini API Key" else "有圖片讀取失敗"
                failed.add(product.folder.name to "辨識圖片失敗：$reason")
                return@forEachIndexed
            }
            val nonNullBitmaps: List<android.graphics.Bitmap> = bitmaps.filterNotNull()
            val result = GeminiImageSelector.selectBestImages(nonNullBitmaps, apiKey, SHARED_IMAGE_POOL_SIZE)
            if (!result.success || result.selectedIndexes.isEmpty()) {
                failed.add(product.folder.name to "辨識圖片失敗：${result.errorMessage ?: "未知錯誤"}")
                return@forEachIndexed
            }
            val chosenFiles = result.selectedIndexes.map { currentImages[it] }
            applyImageSelection(product.folder, currentImages, chosenFiles.map { it.name }.toSet())
            currentImages = (1..chosenFiles.size).mapNotNull { n ->
                product.folder.listFiles { f -> f.nameWithoutExtension == "image_$n" }?.firstOrNull()
            }
            justSelected = true
        }

        val aiProcessedNow = File(product.folder, ".ai_processed").exists()
        if (!usedSharedProduct && aiEnabled && !aiProcessedNow) {
            onStatus("$progressPrefix：AI改圖中")
            currentImages.forEach { targetFile ->
                if (targetFile.exists()) {
                    val original = android.graphics.BitmapFactory.decodeFile(targetFile.path)
                    if (original != null) {
                        val editResult = when (imageEditProvider) {
                            ImageEditProvider.GEMINI -> {
                                val r = GeminiImageEditor.editBackground(original, apiKey, editPrompt)
                                Triple(r.success, r.editedBitmap, r.errorMessage)
                            }
                            ImageEditProvider.CHATGPT -> {
                                val r = OpenAiImageEditor.editBackground(original, openAiApiKey, editPrompt)
                                Triple(r.success, r.editedBitmap, r.errorMessage)
                            }
                        }
                        val (editSuccess, editedBitmap, _) = editResult
                        if (editSuccess && editedBitmap != null) {
                            java.io.FileOutputStream(targetFile).use { out ->
                                editedBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
                            }
                        }
                        // 改圖失敗（result.success=false）就保留原圖繼續走，不算這個商品失敗，
                        // 跟原本浮球批次按鈕的行為一致。
                    }
                }
            }
            File(product.folder, ".ai_processed").createNewFile()
            justSelected = true
        }

        if (justSelected && !usedSharedProduct && ServerPrefs.isConfigured(context)) {
            onStatus("$progressPrefix：同步共用資料夾")
            try {
                val region = try {
                    JSONObject(File(product.folder, "meta.json").readText()).optString("region", "TW")
                } catch (e: Exception) {
                    "TW"
                }
                val promoLink = try {
                    JSONObject(File(product.folder, "meta.json").readText()).optString("promoLink", "")
                } catch (e: Exception) {
                    ""
                }
                RemoteVideoGenerator.uploadSharedProductImages(
                    context = context,
                    account = AccountPrefs.getAccount(context),
                    region = region,
                    productName = product.productName ?: product.folder.name,
                    productLink = promoLink,
                    images = currentImages.filter { it.exists() }
                )
            } catch (e: Exception) {
                // 共用資料夾同步失敗不影響本次生成，只是這批圖沒能提供給其他帳號共用而已。
            }
        }

        succeeded.add(product.folder.name)
    }

    return succeeded to failed
}

@Composable
private fun GenerateVideoScreen(context: Context, onBack: () -> Unit) {
    var serverConfigured by remember { mutableStateOf(ServerPrefs.isConfigured(context)) }

    val captionQueueDir = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        "CaptionQueue"
    )

    // 【2026-08-29新增】商品清單＋人工選圖：imagePickerFolder不是null時，整個畫面換成
    // 該商品的選圖畫面（見ImageSelectionContent），選完或按返回會回到這個清單畫面。
    // 注意：這裡刻意不用「return」提早結束函式——Compose規則要求remember/LaunchedEffect
    // 這類hook在同一個Composable裡每次重組都要以同樣順序被呼叫，如果依條件提早return，
    // 會導致下面那些hook在切換畫面時時而被呼叫、時而被跳過，破壞Compose內部的slot對應，
    // 可能導致狀態錯亂。改成所有hook照樣無條件宣告，只在畫面最終要「畫什麼」的地方分支。
    var products by remember { mutableStateOf(loadCapturedProducts(captionQueueDir)) }
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    // 【2026-08-30新增】自動辨識選圖＋AI改圖流程的進行中狀態：isPreparing為true時
    // 按鈕顯示這個逐步文字（辨識圖片中/AI改圖中，見runAutoSelectAndEditPipeline），
    // 這個階段完全在App內（Kotlin）跑，還沒交給Termux，跟isRunning（Termux跑批次
    // 生成）是先後接續的兩段不同狀態。prepareErrors記錄辨識失敗被跳過的商品，
    // 跑完這階段後顯示給使用者，引導去人工選圖畫面處理。
    var isPreparing by remember { mutableStateOf(false) }
    var preparingStatus by remember { mutableStateOf("") }
    var prepareErrors by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    val coroutineScope = rememberCoroutineScope()
    var imagePickerFolder by remember { mutableStateOf<File?>(null) }
    val pickedProduct = imagePickerFolder?.let { picked -> products.find { it.folder.path == picked.path } }

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
                initialProgress?.status == "error_laptop_unreachable" -> context.getString(
                    R.string.simple_generate_laptop_unreachable,
                    initialProgress.completed, initialProgress.total
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
                p?.status == "error_laptop_unreachable" -> {
                    resultText = context.getString(
                        R.string.simple_generate_laptop_unreachable, p.completed, p.total
                    )
                    isRunning = false
                    stopRequested = false
                }
            }
            kotlinx.coroutines.delay(1500)
        }
    }

    if (pickedProduct != null) {
        ImageSelectionContent(
            context = context,
            product = pickedProduct,
            onDone = {
                imagePickerFolder = null
                products = loadCapturedProducts(captionQueueDir)
            }
        )
        return
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

            // 【2026-08-29新增】商品清單：勾選要生成影片的商品，點整列（打勾框以外）進去選圖。
            Text("已擷取商品", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = SimpleInk)
            Spacer(Modifier.height(4.dp))
            Text(
                "點商品進去選擇要保留的圖片，打勾要生成影片的商品後按下面的按鈕開始生成",
                fontSize = 12.sp, color = SimpleMuted
            )
            Spacer(Modifier.height(12.dp))
            if (products.isEmpty()) {
                Text("目前沒有已擷取的商品", fontSize = 13.sp, color = SimpleMuted)
            } else {
                products.forEach { product ->
                    ProductSelectRow(
                        product = product,
                        checked = selectedIds.contains(product.folder.name),
                        onCheckedChange = { checked ->
                            selectedIds = if (checked) selectedIds + product.folder.name else selectedIds - product.folder.name
                        },
                        onClickImages = { imagePickerFolder = product.folder }
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
            Spacer(Modifier.height(20.dp))

            if (!serverConfigured) {
                WarningBanner(stringResource(R.string.simple_no_server))
                Spacer(Modifier.height(12.dp))
                BigActionButton(
                    text = stringResource(R.string.simple_go_to_settings),
                    color = SimpleInk,
                    onClick = { context.startActivity(Intent(context, MainActivity::class.java)) }
                )
                Spacer(Modifier.height(16.dp))
            }

            BigActionButton(
                text = when {
                    isPreparing -> preparingStatus.ifBlank { "處理中" }
                    isRunning -> stringResource(R.string.simple_generating)
                    else -> stringResource(R.string.simple_start_generate)
                },
                color = SimpleInk,
                enabled = serverConfigured && !isRunning && !isPreparing && selectedIds.isNotEmpty(),
                onClick = {
                    resultText = null
                    progress = null
                    prepareErrors = emptyList()
                    stopRequested = false
                    coroutineScope.launch {
                        isPreparing = true
                        // 【2026-08-30新增】先對勾選的商品跑「自動辨識選圖＋AI改圖」，
                        // 完全在App內完成（不用Termux），跑完才把成功的商品名單交給
                        // Termux做文案生成＋影片編碼。辨識失敗的商品不會出現在最終清單裡，
                        // 使用者可以事後點進「人工選圖」畫面自己處理再重新勾選生成。
                        val selectedProducts = products.filter { it.folder.name in selectedIds }
                        val (readyIds, errors) = runAutoSelectAndEditPipeline(context, selectedProducts) { status ->
                            preparingStatus = status
                        }
                        prepareErrors = errors
                        products = loadCapturedProducts(captionQueueDir)
                        isPreparing = false

                        if (readyIds.isEmpty()) {
                            resultText = "所有勾選的商品都辨識失敗，沒有商品可以生成，請改用人工選圖"
                            return@launch
                        }

                        // 【2026-08-29新增】把這次勾選要生成的商品資料夾名稱寫進清單檔案，
                        // batch_generate.py讀到這個檔案就只會處理清單裡列出的商品（見該檔案
                        // find_product_folders()的說明），對應「商品列表勾選要生成的商品」需求。
                        try {
                            File(captionQueueDir, ".selected_ids.txt").writeText(readyIds.joinToString("\n"))
                        } catch (e: Exception) { /* 寫入失敗就照舊由batch_generate.py處理全部資料夾 */ }
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
                        val sent = try {
                            RemoteVideoGenService.start(context, captionQueueDir, readyIds.toSet(), force = false)
                            true
                        } catch (e: Exception) {
                            false
                        }
                        if (sent) {
                            isRunning = true
                        } else {
                            resultText = context.getString(R.string.simple_generate_start_failed)
                        }
                    }
                }
            )

            if (prepareErrors.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                WarningBanner(
                    "以下商品辨識失敗已跳過，請改用人工選圖：\n" +
                        prepareErrors.joinToString("\n") { (folderName, reason) ->
                            val name = products.find { it.folder.name == folderName }?.productName ?: folderName
                            "・$name（$reason）"
                        }
                )
            }

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
                        Text(
                            if (p.step.isNotBlank()) "${p.current}：${p.step}" else p.current,
                            fontSize = 13.sp, color = SimpleMuted
                        )
                    }
                    if (p.errorCount > 0 && p.status == "running") {
                        Spacer(Modifier.height(4.dp))
                        Text("已有 ${p.errorCount} 支失敗，詳情待完成後顯示", fontSize = 12.sp, color = SimpleDanger)
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
 * 【2026-09-04重大安全修正】原本這裡在scanFile()之前，會先對這個檔案的原始路徑呼叫
 * contentResolver.delete()，目的是想「先清掉MediaStore裡可能殘留的壞索引，逼系統
 * 建立一筆全新乾淨的記錄」。但已經證實：在有完整儲存權限（MANAGE_EXTERNAL_STORAGE）
 * 的情況下，對一個檔案的實際路徑呼叫contentResolver.delete()，系統會把「實體檔案」
 * 一併刪除，不只是清掉索引——這正是ShopeeAccessibilityService.kt裡
 * registerVideoInMediaStore()這個函式的註解記錄過的同一個坑，那邊已經改成「用暫時
 * 副本，不動原始檔案」修好了，但這裡是後來獨立寫的，沒有沿用那套安全寫法，又踩了
 * 一次，導致使用者在「檢查影片」畫面點播放時，實際的output.mp4被意外刪除。
 * 已確認造成過真實的影片檔案遺失，這裡直接拿掉delete()這一步，只保留單純重新掃描
 * （不強迫先清除舊索引）。代價是某些殘缺過的影片可能仍需要多重新整理一次才會顯示
 * 正常時長，但這遠比再次刪掉使用者的影片檔案安全。
 */
private suspend fun rescanAndWait(context: Context, file: File) {
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
                                    // 【2026-09-05新增】外部影片播放器App常常依「網址」快取畫面內容，
                                    // 同一支output.mp4即使內容已經重新生成過，播放器可能還是顯示
                                    // 舊的快取結果，不是我們App本身的檔案/程式邏輯出問題。修法：
                                    // 每次播放前先複製成帶時間戳記的全新暫存檔名，讓外部播放器
                                    // 每次拿到的都是從沒看過的新網址，強迫它讀取最新內容，不會受
                                    // 舊快取影響。先清掉這個資料夾裡先前留下的暫存副本，避免累積。
                                    video.folder.listFiles { f -> f.name.startsWith(".preview_") }
                                        ?.forEach { it.delete() }
                                    val previewFile = File(video.folder, ".preview_${System.currentTimeMillis()}.mp4")
                                    video.videoFile.copyTo(previewFile, overwrite = true)
                                    val uri: Uri = FileProvider.getUriForFile(
                                        context,
                                        "com.tagcopy.shopeecapture.fileprovider",
                                        previewFile
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
