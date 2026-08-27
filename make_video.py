#!/usr/bin/env python3
"""
陽春版影片生成腳本（含 TTS 語音旁白）
用法：python make_video.py <商品資料夾路徑>
例如：python make_video.py ~/storage/downloads/CaptionQueue/20260819_210324

會讀取資料夾內的 image_1.jpg ~ image_N.jpg 跟 meta.json（取商品名稱），
用 caption.txt 產生規則模板旁白文案 → Edge TTS 合成語音 → 依語音長度動態調整每張圖片
停留時間，讓影片長度貼合旁白 → ffmpeg 做淡入淡出轉場 + 商品名稱文字疊加 + 混音，
輸出 output.mp4 到同一個資料夾。

若 TTS 失敗（無網路、套件未安裝、caption.txt 讀不到有效文案等），會自動退回原本
「無聲版本」的影片，不會讓腳本中斷。

需求：
    Termux 先跑過 `pkg install ffmpeg`
    pip install edge-tts
    pip install Pillow（原本 ICC 剝離步驟就需要，若已裝過可略過）

generate_narration.py 必須跟本檔放在同一個資料夾。
"""
import asyncio
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from datetime import datetime

try:
    from PIL import Image
except ImportError:
    Image = None

try:
    import edge_tts
except ImportError:
    edge_tts = None

from generate_narration import build_narration_sentences, build_hashtags, load_region

try:
    from ai_narration import generate_ai_sentences
except ImportError:
    generate_ai_sentences = None

# ===== 可調參數 =====
IMAGE_DURATION = 2.5       # 無旁白（退回無聲版本）時，每張圖片停留秒數
MIN_IMAGE_DURATION = 1.5   # 有旁白時，每張圖片最短停留秒數（避免圖太多時每張一閃而過）
MAX_IMAGE_DURATION = 4.0   # 有旁白時，每張圖片最長停留秒數（避免圖太少時單張停太久）
MAX_IMAGES_IN_VIDEO = 10   # 影片最多用幾張圖（圖片太多會導致每張停留時間被壓到最低、字幕還沒看完就換圖）
TRANSITION_DURATION = 0.6  # 淡入淡出轉場秒數
OUTPUT_WIDTH = 1080
OUTPUT_HEIGHT = 1920       # 9:16 直式（蝦皮短影音常見格式）
FONT_SIZE = 42
SUBTITLE_MAX_CHARS_PER_LINE = 14  # 超過這個字數自動換行，避免字幕超出畫面寬度
# 中文字型檔路徑：預設字型通常不含中文字，會顯示成方框。
# 請下載一個支援中文的字型（例如 Noto Sans TC）放到這個路徑，或修改成您自己的字型檔位置。
# 下載指令範例（Termux）：
#   mkdir -p ~/fonts
#   curl -L -o ~/fonts/NotoSansTC-Regular.otf "https://raw.githubusercontent.com/notofonts/noto-cjk/main/Sans/OTF/TraditionalChinese/NotoSansCJKtc-Regular.otf"
FONT_PATH = os.path.expanduser("~/fonts/NotoSansTC-Regular.otf")

# TTS 語音：依 meta.json 的 region 欄位（TW/PH）切換，讀不到 region 時預設 TW
VOICE_MAP = {
    "TW": "zh-TW-HsiaoChenNeural",
    "PH": "en-PH-RosaNeural",
}

# 影片總長度目標區間：語音（進而影片）長度會盡量落在這個範圍內。
# 舊版做法是語音長度超出範圍就用Edge TTS的rate參數調整語速重新合成，但實測發現
# 圖片張數少、文案內容本來就短的商品，語速被迫壓到-30%上限還是不夠15秒，
# 聽起來異常緩慢、不自然。改成語速永遠維持正常（不調整），語音長度不在範圍內時
# 改回頭重新呼叫文案產生器（AI或規則模板）要求多寫/少寫一句，用文案長度而不是
# 語速去配合影片長度目標，只重試一次（避免無限迴圈、AI版還會多花一次API成本）。
TARGET_MIN_DURATION_SEC = 15.0
TARGET_MAX_DURATION_SEC = 18.0
MAX_SENTENCE_COUNT = 4
MIN_SENTENCE_COUNT = 1


def strip_icc_profiles(images: list[str], work_dir: str) -> list[str]:
    """
    把每張圖片重新存檔到暫存資料夾，徹底剝離 ICC 色彩描述檔。
    根因：輸入的商品截圖（JPEG）帶有內嵌 ICC 描述檔，這段資訊會在 ffmpeg 的
    filter chain 裡被複製附加到「每一個影格」上（不是只在檔頭一次），造成異常
    膨脹的中繼資料，這正是部分手機嚴格的硬體解碼器直接拒絕播放的根本原因。
    用 Pillow 重新存檔可以徹底解決：Image.save() 預設不會保留 icc_profile，
    除非明確傳入，所以只要重新開檔、重新存檔，ICC 資訊就不會進到 ffmpeg 的
    輸入裡，從源頭杜絕問題，比在 ffmpeg 端事後覆蓋色彩標籤更根本、更可靠。
    """
    if Image is None:
        print("⚠ 找不到 Pillow 套件（PIL），無法剝離 ICC 描述檔，可能仍會遇到播放相容性問題")
        print("  建議先執行：pip install Pillow")
        return images
    cleaned = []
    for i, path in enumerate(images):
        out_path = os.path.join(work_dir, f"clean_{i}.jpg")
        with Image.open(path) as img:
            rgb = img.convert("RGB")
            rgb.save(out_path, "JPEG", quality=95)  # 不傳 icc_profile 參數，等於直接丟棄
        cleaned.append(out_path)
    return cleaned


def find_images(folder: str) -> list[str]:
    """依 image_1.jpg, image_2.jpg... 的順序找出所有圖片，跳過 gap（避免有些編號被使用者手動刪掉）"""
    files = []
    i = 1
    while True:
        candidates = [
            os.path.join(folder, f"image_{i}.jpg"),
            os.path.join(folder, f"image_{i}.jpeg"),
            os.path.join(folder, f"image_{i}.png"),
        ]
        found = next((c for c in candidates if os.path.isfile(c)), None)
        if found is None:
            # 連續找不到 3 個編號才真的停止，避免中間單一編號缺漏就整批漏抓
            missing_streak = 0
            j = i
            while missing_streak < 3:
                cands = [
                    os.path.join(folder, f"image_{j}.jpg"),
                    os.path.join(folder, f"image_{j}.jpeg"),
                    os.path.join(folder, f"image_{j}.png"),
                ]
                f = next((c for c in cands if os.path.isfile(c)), None)
                if f:
                    files.append(f)
                    missing_streak = 0
                else:
                    missing_streak += 1
                j += 1
            break
        files.append(found)
        i += 1
    return files


def load_title(folder: str) -> str:
    """優先從 meta.json 讀商品名稱，讀不到就退回空字串（不疊字）"""
    meta_path = os.path.join(folder, "meta.json")
    if not os.path.isfile(meta_path):
        return ""
    try:
        with open(meta_path, "r", encoding="utf-8") as f:
            data = json.load(f)
        # 相容不同可能的欄位命名
        for key in ("productName", "product_name", "name", "title"):
            if key in data and data[key]:
                return str(data[key])
    except Exception as e:
        print(f"  → meta.json 讀取失敗（不影響影片生成，只是不會疊商品名稱）：{e}")
    return ""


def escape_drawtext(text: str) -> str:
    """ffmpeg drawtext 濾鏡對特殊字元的跳脫處理"""
    text = text.replace("\\", "\\\\")
    text = text.replace(":", "\\:")
    text = text.replace("'", "\u2019")  # 直接換成右單引號，避免跳脫問題
    text = text.replace("%", "\\%")
    return text


def wrap_subtitle(text: str) -> str:
    """字幕過長自動換行（每 SUBTITLE_MAX_CHARS_PER_LINE 字插入換行），避免超出畫面寬度"""
    if len(text) <= SUBTITLE_MAX_CHARS_PER_LINE:
        return text
    lines = [text[i:i + SUBTITLE_MAX_CHARS_PER_LINE] for i in range(0, len(text), SUBTITLE_MAX_CHARS_PER_LINE)]
    return "\n".join(lines)


async def _synthesize_async(text: str, voice: str, out_path: str, rate: str = "+0%") -> None:
    communicate = edge_tts.Communicate(text, voice, rate=rate)
    await communicate.save(out_path)


def synthesize_sentences(sentences: list, region: str, tmp_dir: str, rate: str = "+0%"):
    """
    逐句呼叫 Edge TTS 合成語音（分開存檔，才能量出每句實際秒數，用來對齊字幕時間）。
    rate 參數可調整語速（如 "+20%" 加快、"-15%" 放慢）——目前make_video.py不會再
    傳入非"+0%"的值（語速永遠維持正常，語音長度改用調整文案句數配合目標區間，
    見 TARGET_MIN/MAX_DURATION_SEC），保留這個參數只是維持函式彈性、方便單獨測試。
    任何一句失敗就整段放棄（回傳 None），呼叫端會退回完全無聲、無字幕的版本——
    不追求部分成功，避免合成到一半、字幕時間對不齊的半殘版本。
    回傳：[{"text":..., "path":..., "duration":...}, ...] 或 None
    """
    if edge_tts is None:
        print("⚠ 找不到 edge-tts 套件，無法生成語音旁白，將輸出無聲版本")
        print("  請先執行：pip install edge-tts")
        return None

    voice = VOICE_MAP.get(region, VOICE_MAP["TW"])
    results = []
    for idx, sentence in enumerate(sentences):
        out_path = os.path.join(tmp_dir, f"narration_{idx}.mp3")
        try:
            asyncio.run(_synthesize_async(sentence, voice, out_path, rate))
        except Exception as e:
            print(f"⚠ 第{idx + 1}句語音生成失敗（{e}），整段旁白改輸出無聲版本")
            return None
        if not (os.path.isfile(out_path) and os.path.getsize(out_path) > 0):
            print(f"⚠ 第{idx + 1}句語音檔案異常，整段旁白改輸出無聲版本")
            return None
        duration = get_audio_duration(out_path)
        if duration <= 0:
            print(f"⚠ 第{idx + 1}句語音時長讀取失敗，整段旁白改輸出無聲版本")
            return None
        results.append({"text": sentence, "path": out_path, "duration": duration})
    return results


def get_audio_duration(path: str) -> float:
    """用 ffprobe 讀取音檔實際長度（秒）"""
    cmd = [
        "ffprobe", "-v", "error",
        "-show_entries", "format=duration",
        "-of", "default=noprint_wrappers=1:nokey=1",
        path,
    ]
    result = subprocess.run(cmd, capture_output=True, text=True)
    try:
        return float(result.stdout.strip())
    except (ValueError, TypeError):
        return 0.0


def build_ffmpeg_command(
    images: list[str],
    output_path: str,
    image_duration: float,
    audio_segments: list = None,
    subtitle_segments: list = None,
    target_total_duration: float = None,
) -> list[str]:
    """
    audio_segments: [{"path":..., "duration":...}, ...] 或 None（無旁白時退回無聲版本）
    subtitle_segments: [(start_sec, end_sec, text), ...] 或 None（無旁白就不顯示字幕）
    """
    n = len(images)
    if n == 0:
        raise ValueError("資料夾裡沒有找到任何 image_N.jpg 圖片")

    inputs = []
    for img in images:
        inputs += ["-loop", "1", "-t", str(image_duration + TRANSITION_DURATION), "-i", img]

    # 每張圖片做「模糊背景鋪底 + 原圖完整置中不裁切」，取代原本「放大填滿+置中裁切」。
    # 原本的裁切方式遇到橫式banner圖（賣家常見的宣傳橫幅圖）會裁掉左右兩側大半內容
    # （包括banner上的文字），改成這個做法後任何長寬比的圖片內容都會完整保留。
    # 背景模糊用「先縮小再放大」取代真正的高斯模糊（gblur），因為 gblur 運算量大，
    # 在手機 Termux 跑量產（每天50~200支）會拖慢很多，縮放模糊視覺效果接近、成本低很多。
    # 關鍵：明確指定 out_range=tv（標準有限色域），因為輸入的商品截圖是 JPEG 格式，
    # 帶有完全色域(full-range)標記，若不強制轉換，編碼出來的影片會被標成 yuvj420p
    # 而非標準的 yuv420p。許多 Android 手機的硬體解碼器會直接拒絕播放 yuvj420p 格式的
    # 影片（即使軟體解碼完全正常），這是先前「所有播放器都播放失敗」的真正根因。
    filter_parts = []
    for i in range(n):
        filter_parts.append(
            f"[{i}:v]split=2[bg{i}][fg{i}];"
            f"[bg{i}]scale={OUTPUT_WIDTH}:{OUTPUT_HEIGHT}:force_original_aspect_ratio=increase:out_range=tv,"
            f"crop={OUTPUT_WIDTH}:{OUTPUT_HEIGHT},scale=54:96,scale={OUTPUT_WIDTH}:{OUTPUT_HEIGHT}[bgblur{i}];"
            f"[fg{i}]scale={OUTPUT_WIDTH}:{OUTPUT_HEIGHT}:force_original_aspect_ratio=decrease:out_range=tv[fgfit{i}];"
            f"[bgblur{i}][fgfit{i}]overlay=(W-w)/2:(H-h)/2,setsar=1,fps=30,format=yuv420p[v{i}]"
        )

    # 用 xfade 依序把每張圖接起來
    if n == 1:
        chain_out = "v0"
    else:
        prev = "v0"
        offset = image_duration
        for i in range(1, n):
            out_label = f"x{i}"
            filter_parts.append(
                f"[{prev}][v{i}]xfade=transition=fade:duration={TRANSITION_DURATION}:offset={offset}[{out_label}]"
            )
            prev = out_label
            offset += image_duration
        chain_out = prev

    final_label = chain_out

    video_natural_duration = image_duration * n + TRANSITION_DURATION
    total_duration = target_total_duration if target_total_duration else video_natural_duration

    # 若旁白比圖片轉場播完還長（total_duration > video_natural_duration），
    # 用 tpad 把最後一張圖定格延長，補滿旁白剩下的秒數，避免旁白唸到一半畫面就結束
    extra_hold = max(0.0, total_duration - video_natural_duration)
    if extra_hold > 0.05:
        filter_parts.append(
            f"[{final_label}]tpad=stop_mode=clone:stop_duration={extra_hold}[vpad]"
        )
        final_label = "vpad"

    # 字幕：跟著語音進度逐句換字（取代原本固定顯示的商品標題），每句用 enable
    # 限制只在該句實際播放的時間範圍內顯示，時間點來自每句語音實際合成後的長度
    if subtitle_segments:
        if not os.path.isfile(FONT_PATH):
            print(f"⚠ 找不到中文字型檔（{FONT_PATH}），字幕會顯示成方框，請先下載字型檔（見腳本開頭註解的下載指令）")
        fontfile_escaped = FONT_PATH.replace(":", "\\:")
        for idx, (start, end, text) in enumerate(subtitle_segments):
            safe_text = escape_drawtext(wrap_subtitle(text))
            out_label = f"sub{idx}"
            filter_parts.append(
                f"[{final_label}]drawtext=fontfile='{fontfile_escaped}':text='{safe_text}':"
                f"enable='between(t,{start:.3f},{end:.3f})':fontsize={FONT_SIZE}:fontcolor=white:"
                f"borderw=3:bordercolor=black:x=(w-text_w)/2:y=h-th-80:line_spacing=6[{out_label}]"
            )
            final_label = out_label

    filter_complex_video_parts = list(filter_parts)

    cmd = ["ffmpeg", "-y"] + inputs

    if audio_segments:
        # 真人語音旁白：每句分開合成的音檔依序 -i 進來，用 concat 濾鏡串接成一軌，
        # 再用 apad 補滿到 total_duration（避免圖片被 tpad 定格延長那段時間音軌卻先結束）
        for seg in audio_segments:
            cmd += ["-i", seg["path"]]
        audio_start_index = n
        concat_inputs = "".join(f"[{audio_start_index + idx}:a]" for idx in range(len(audio_segments)))
        filter_complex_video_parts.append(
            f"{concat_inputs}concat=n={len(audio_segments)}:v=0:a=1[acat]"
        )
        filter_complex_video_parts.append(f"[acat]apad=whole_dur={total_duration}[aout]")
        audio_map = "[aout]"
    else:
        # 沒有旁白（TTS失敗或抽不到文案）：退回原本的無聲音軌版本
        cmd += ["-f", "lavfi", "-t", str(total_duration), "-i", "anullsrc=channel_layout=stereo:sample_rate=44100"]
        audio_map = f"{n}:a"

    filter_complex = ";".join(filter_complex_video_parts)

    cmd += [
        "-filter_complex", filter_complex,
        "-map", f"[{final_label}]",
        "-map", audio_map,
        "-t", str(total_duration),
        "-r", "30",
        "-threads", "1",  # 強制單執行緒濾鏡處理＋編碼。根因排查發現手機在連續批次
                           # 處理很多支影片、CPU資源緊繃時，多執行緒編碼器內部可能發生
                           # 執行緒競爭（race condition），產生「ffmpeg exit code正常、
                           # 檔案能寫完，但實際h264位元流已經損毀」的狀況——這種狀況
                           # 完全不會反映在returncode上，只有事後完整解碼驗證
                           # （full_decode_check）才驗得出來，非常難排查。單執行緒犧牲一點
                           # 編碼速度（對15~18秒的短片影響通常是秒級），換取徹底排除這類
                           # 競爭問題的可能性。
        "-avoid_negative_ts", "make_zero",  # 強制所有時間戳從 0 開始，不產生負值
                                             # 音訊編碼器本身有起始延遲，ffmpeg 預設會用 edit list
                                             # (edts/elst) 機制去對齊，但部分 Android 手機硬體解碼器
                                             # 對這種帶負初始時間戳、需要 edit list 調整播放起點的
                                             # 檔案有已知相容性問題，會直接判定異常拒絕播放。
        "-c:v", "libx264",
        "-profile:v", "baseline",  # Baseline Profile：比 High Profile 相容性廣得多，
                                    # 許多中低階或較舊的 Android 手機硬體解碼器只支援 Baseline
        "-level", "3.1",
        "-bf", "0",  # 關閉 B-frames，部分手機硬體解碼器對 B-frames 支援不完整
        "-pix_fmt", "yuv420p",
        "-color_range", "tv",
        "-color_primaries", "bt709",
        "-color_trc", "bt709",
        "-colorspace", "bt709",  # 明確覆蓋色彩描述標籤，排除輸入 JPEG 帶入的 ICC Profile
                                  # side data 對硬體解碼器造成的干擾
        "-map_metadata", "-1",  # 清除多餘的 metadata
        "-c:a", "aac",
        "-movflags", "+faststart",  # 把索引資訊(moov atom)放到檔案最前面，
                                     # 沒有這個標記時部分手機播放器會直接播放失敗
        output_path,
    ]
    return cmd


def update_meta_video_generated(folder: str, sentences: list = None, hashtags: list = None) -> None:
    """
    影片生成成功後，回寫 meta.json 的 videoGeneratedAt 欄位（ISO時間字串），
    同時把這次實際使用的旁白文案（不管是AI生成還是規則模板）合併成一段文字，
    寫進 narrationText 欄位——上架自動化「撰寫內文」那步要用AI文案，但AI文案
    只在生成影片當下用來做TTS、用完就丟，沒有另外存檔，這裡補上讓上架流程讀得到。
    句子之間改用換行符號分隔（不再用中文句號「。」），因為PH版是英文/Taglish句子，
    句號分隔會混進不必要的中文標點；換行對中英文句子都適用，Kotlin端據此切開句子。
    hashtags 欄位存這次使用的5個標籤（不含#符號），供上架流程「撰寫內文」直接讀取使用，
    不用再自己從商品名稱土法重新拆解。
    這兩個欄位由擷取器App端初始化成null，這裡是唯一負責填入實際值的地方。
    之後的蝦皮上架自動化可以直接掃這個資料夾，找「videoGeneratedAt有值但
    shopeePosted還是false」的就是待上架清單，不用另外維護清單、不用複製移動影片檔案。
    寫入失敗（meta.json不存在、格式錯誤等）只印警告，不影響影片生成本身的成功狀態。
    """
    meta_path = os.path.join(folder, "meta.json")
    if not os.path.isfile(meta_path):
        return
    try:
        with open(meta_path, "r", encoding="utf-8") as f:
            data = json.load(f)
        data["videoGeneratedAt"] = datetime.now().isoformat()
        if sentences:
            data["narrationText"] = "\n".join(sentences)
        if hashtags:
            data["hashtags"] = hashtags
        with open(meta_path, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False)
    except Exception as e:
        print(f"⚠ 回寫 meta.json 的 videoGeneratedAt/narrationText/hashtags 失敗（{e}），不影響影片本身生成結果")


def is_valid_video(path: str) -> bool:
    """
    用ffprobe驗證output.mp4是不是完整可播放的檔案，不是只檢查「存不存在」。
    背景由來：使用者實測發現Termux行程被系統（POCO/HyperOS省電策略）中途砍掉時，
    ffmpeg會留下一個「檔案存在但沒寫完」的殘缺output.mp4（典型症狀是缺moov atom，
    ffprobe會報"moov atom not found"），過去process_folder()只檢查檔案是否存在，
    這種殘缺檔案會被誤判成「已生成過」永遠跳過、不會自動重新生成，只能靠使用者手動
    發現播放失敗、手動刪除。這裡改用ffprobe實際探測：讀不到基本格式資訊（returncode
    非0，或缺影像串流，或時長<=0）都視為無效檔案。逾時30秒視為異常也判定無效
    （正常探測應該是瞬間完成的，逾時代表檔案本身有問題卡住ffprobe）。

    後續發現：純ffprobe（只讀取容器層級的格式/串流資訊，不實際解碼內容）驗證通過的
    檔案，仍可能在手機上播放失敗——實測有2支影片重新生成後ffprobe完全驗證通過
    （時長正常、有影像串流），但手動播放依然失敗。這代表問題出在「內容解碼」層級
    （例如某幾個影格的資料本身損毀、音訊串流資料有問題），是ffprobe單純讀取
    metadata不會發現的，必須實際跑一次完整解碼才驗得出來。因此這裡多加一層
    full_decode_check：用`ffmpeg -f null -`把整支影片從頭到尾實際解碼一次，
    stderr有任何內容（-v error只會印真正的解碼錯誤，不會有其他雜訊）就視為無效。
    這一層比純ffprobe慢（要花跟影片時長差不多的時間，不是瞬間），但只在做這個
    完整性驗證時執行一次，相對批次生成一支動辄1~3分鐘的成本可以接受。
    """
    if not os.path.isfile(path) or os.path.getsize(path) == 0:
        return False
    try:
        result = subprocess.run(
            ["ffprobe", "-v", "error", "-print_format", "json",
             "-show_format", "-show_streams", path],
            capture_output=True, text=True, timeout=30
        )
    except Exception:
        return False
    if result.returncode != 0:
        return False
    try:
        data = json.loads(result.stdout)
    except Exception:
        return False
    duration = float(data.get("format", {}).get("duration", 0) or 0)
    has_video_stream = any(s.get("codec_type") == "video" for s in data.get("streams", []))
    if duration <= 0 or not has_video_stream:
        return False
    return full_decode_check(path)


def full_decode_check(path: str) -> bool:
    """
    把整支影片實際解碼一次（不只讀取容器metadata），抓出ffprobe驗證抓不到的
    內容層級損毀（例如某幾個影格資料壞掉、音訊串流本身有問題）。
    用`-v error`只讓真正的解碼錯誤印到stderr，沒有任何雜訊；stderr有內容
    或指令逾時，都視為解碼異常、判定檔案無效。
    逾時門檻60秒：正常影片15~18秒，30fps解碼速度遠快於即時播放速度，
    60秒對正常檔案綽綽有餘，卡住超過這個時間本身就代表檔案有問題。
    """
    try:
        result = subprocess.run(
            ["ffmpeg", "-v", "error", "-i", path, "-f", "null", "-"],
            capture_output=True, text=True, timeout=60
        )
    except Exception:
        return False
    if result.returncode != 0:
        return False
    if result.stderr.strip():
        return False
    return True


def process_folder(folder: str, force: bool = False) -> dict:
    """
    處理單一商品資料夾：生成影片。回傳結果字典，方便單支模式跟批次模式共用同一套邏輯。
    {"status": "ok"|"skipped"|"error", "message": str, "output_path": str}
    force=False 時，如果 output.mp4 已存在「且驗證完整」就跳過（批次模式用來避免重複
    重跑已完成的影片）。如果檔案存在但驗證失敗（殘缺／損毀），視同不存在直接重新生成，
    不用使用者手動介入刪除。
    """
    images = find_images(folder)
    if not images:
        return {"status": "error", "message": "資料夾裡沒有找到 image_N.jpg 圖片", "output_path": None}

    output_path = os.path.join(folder, "output.mp4")
    if os.path.isfile(output_path) and not force:
        if is_valid_video(output_path):
            return {"status": "skipped", "message": "output.mp4 已存在", "output_path": output_path}
        else:
            print(f"→ 偵測到既有output.mp4無法通過驗證（容器結構或內容解碼異常，可能是上次生成中途被中斷或內容本身損毀），視為未生成、重新生成")

    if len(images) > MAX_IMAGES_IN_VIDEO:
        print(f"→ 資料夾有 {len(images)} 張圖，超過影片上限 {MAX_IMAGES_IN_VIDEO} 張，只取前 {MAX_IMAGES_IN_VIDEO} 張")
        images = images[:MAX_IMAGES_IN_VIDEO]

    region = load_region(folder)
    print(f"→ 找到 {len(images)} 張圖片")
    print(f"→ 判定地區：{region}")
    print(f"→ 輸出路徑：{output_path}")

    sentences = None
    hashtags = None
    used_ai = False
    if generate_ai_sentences:
        try:
            sentences, hashtags = generate_ai_sentences(folder)
            if sentences:
                used_ai = True
                print(f"→ AI文案生成成功（{len(sentences)}句）：")
        except Exception as e:
            print(f"⚠ AI文案生成發生未預期錯誤（{e.__class__.__name__}），改用規則模板")
    if not sentences:
        sentences = build_narration_sentences(folder)
        if sentences:
            print(f"→ 產生旁白文案（規則模板，{len(sentences)}句）：")
    if sentences:
        for s in sentences:
            print(f"    {s}")
    else:
        print("→ 抽取不到有效旁白文案（caption.txt 空白或格式不符），將輸出無聲版本")

    if not hashtags:
        hashtags = build_hashtags(folder)
    print(f"→ 標籤（{len(hashtags)}個）：{' '.join('#' + h for h in hashtags)}")

    with tempfile.TemporaryDirectory() as tmp_dir:
        audio_segments = None
        subtitle_segments = None
        total_audio_duration = 0.0

        if sentences:
            print("→ 語音合成中（Edge TTS，逐句合成，需要網路連線）…")
            audio_segments = synthesize_sentences(sentences, region, tmp_dir)
            if audio_segments:
                total_audio_duration = sum(seg["duration"] for seg in audio_segments)
                print(f"→ 語音總時長：{total_audio_duration:.1f} 秒（{len(audio_segments)}句，正常語速）")

                # 語音長度不在目標範圍內時，語速永遠維持正常，改回頭重新呼叫文案產生器
                # 要求多寫/少寫一句，只重試一次（不無限循環湊時間，重試後不管有沒有真的
                # 落在範圍內都直接採用，避免無限追求完美拖慢批次速度、AI版還會一直燒額度）。
                if not (TARGET_MIN_DURATION_SEC <= total_audio_duration <= TARGET_MAX_DURATION_SEC):
                    direction = "太短" if total_audio_duration < TARGET_MIN_DURATION_SEC else "太長"
                    delta = 1 if total_audio_duration < TARGET_MIN_DURATION_SEC else -1
                    new_count = max(MIN_SENTENCE_COUNT, min(MAX_SENTENCE_COUNT, len(sentences) + delta))
                    if new_count != len(sentences):
                        print(f"→ 語音總長度{direction}（目標{TARGET_MIN_DURATION_SEC:.0f}~{TARGET_MAX_DURATION_SEC:.0f}秒，"
                              f"實際{total_audio_duration:.1f}秒），改為{new_count}句重新產生文案…")
                        retry_sentences = None
                        retry_hashtags = None
                        if used_ai and generate_ai_sentences:
                            try:
                                retry_sentences, retry_hashtags = generate_ai_sentences(
                                    folder, sentence_count_override=new_count
                                )
                            except Exception as e:
                                print(f"⚠ 重新呼叫AI文案發生未預期錯誤（{e.__class__.__name__}），維持原本文案")
                        elif not used_ai:
                            retry_sentences = build_narration_sentences(folder, sentence_count_override=new_count)

                        if retry_sentences and len(retry_sentences) != len(sentences):
                            print(f"→ 重新產生文案成功（{len(retry_sentences)}句）：")
                            for s in retry_sentences:
                                print(f"    {s}")
                            retry_audio = synthesize_sentences(retry_sentences, region, tmp_dir)
                            if retry_audio:
                                sentences = retry_sentences
                                if retry_hashtags:
                                    hashtags = retry_hashtags
                                audio_segments = retry_audio
                                total_audio_duration = sum(seg["duration"] for seg in audio_segments)
                                print(f"→ 重新合成後語音總時長：{total_audio_duration:.1f} 秒")
                            else:
                                print("⚠ 重新合成語音失敗，維持原本文案與語音")
                        else:
                            print("→ 沒能拿到不同句數的文案（可能是賣點詞不夠或已達句數上限/下限），維持原本文案與語音")
                    else:
                        print(f"→ 語音總長度{direction}，但句數已經在{MIN_SENTENCE_COUNT}~{MAX_SENTENCE_COUNT}句的邊界，不重試")

                subtitle_segments = []
                t = 0.0
                for seg in audio_segments:
                    subtitle_segments.append((t, t + seg["duration"], seg["text"]))
                    t += seg["duration"]

        n = len(images)
        if audio_segments:
            ideal = (total_audio_duration - TRANSITION_DURATION) / n
            image_duration = min(MAX_IMAGE_DURATION, max(MIN_IMAGE_DURATION, ideal))
            video_natural_duration = image_duration * n + TRANSITION_DURATION
            target_total_duration = max(video_natural_duration, total_audio_duration)
            print(f"→ 依語音長度調整每張圖片停留時間為 {image_duration:.2f} 秒")
        else:
            image_duration = IMAGE_DURATION
            target_total_duration = image_duration * n + TRANSITION_DURATION

        print("→ 剝離圖片 ICC 描述檔（避免部分手機播放器相容性問題）…")
        clean_images = strip_icc_profiles(images, tmp_dir)

        cmd = build_ffmpeg_command(
            clean_images, output_path,
            image_duration=image_duration,
            audio_segments=audio_segments,
            subtitle_segments=subtitle_segments,
            target_total_duration=target_total_duration,
        )
        result = subprocess.run(cmd, capture_output=True, text=True)

    if result.returncode != 0:
        return {"status": "error", "message": result.stderr[-1000:], "output_path": None}

    # 過去這裡完全忽略stderr內容——只要returncode是0（正常結束）就直接視為成功。
    # 但根因排查發現：ffmpeg exit code正常、檔案也寫得完整，不代表內容一定沒問題
    # （例如多執行緒編碼器在資源緊繃時可能產生內部異常但仍正常收尾）。這種情況下
    # stderr往往會留下線索，過去因為只在失敗時才讀取，這些線索永遠被吃掉、無從
    # 排查起。現在不管成功與否，只要stderr有內容就印出來（不影響回傳成功狀態，
    # 只是留下診斷紀錄）。
    if result.stderr.strip():
        print(f"⚠ ffmpeg正常結束但stderr有內容，留供除錯參考：\n{result.stderr.strip()[-1000:]}")

    update_meta_video_generated(folder, sentences, hashtags)
    return {"status": "ok", "message": "", "output_path": output_path}


def main():
    if len(sys.argv) != 2:
        print("用法：python make_video.py <商品資料夾路徑>")
        sys.exit(1)

    folder = os.path.expanduser(sys.argv[1])
    if not os.path.isdir(folder):
        print(f"錯誤：找不到資料夾 {folder}")
        sys.exit(1)

    # CLI 單支模式：即使 output.mp4 已存在也強制重新生成（force=True），
    # 因為使用者是明確指定這個資料夾要處理，通常是想重跑或測試
    result = process_folder(folder, force=True)

    if result["status"] == "error":
        print("✗ 影片生成失敗，錯誤訊息：")
        print(result["message"])
        sys.exit(1)

    print(f"✓ 影片生成完成：{result['output_path']}")


if __name__ == "__main__":
    main()
