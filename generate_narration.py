#!/usr/bin/env python3
"""
規則式旁白文案產生器（混合式方案第1階段：規則模板，免費、離線可跑、不依賴任何 AI API）

從 caption.txt（標題+hashtag格式，內容與蝦皮「複製資訊」相同）抽取商品名稱與賣點關鍵字，
套入句型模板產生較口語化的旁白文案，供 make_video.py 的 TTS 步驟使用。

用法（可單獨測試效果，不會動到影片，方便看規則模板效果好不好）：
    python generate_narration.py <商品資料夾路徑>

被 make_video.py 呼叫時：
    from generate_narration import build_narration, load_region
    text = build_narration(folder)
"""
import json
import os
import random
import re
import sys
import glob

# 通用行銷標籤黑名單：這些不是具體賣點，不適合放進旁白裡念出來
GENERIC_TAGS = {
    "開箱", "新品", "好物分享", "推薦", "熱賣", "限時", "優惠", "折扣",
    "必買", "團購", "特價", "促銷", "上新", "爆款", "人氣", "TikTok", "IG",
    "抖音", "分享", "好物", "旗艦店", "專賣店", "官方店", "直營店", "賣場", "經銷商",
}

# 賣家標題裡常見的促銷話術片段：這些會被「含中文字」規則誤判成賣點詞抓進旁白
# （曾實際發生「嗨！快來看看」被念出來的情況），用子字串比對濾掉
HYPE_SUBSTRINGS = [
    "嗨", "快來", "熱銷", "熱賣", "爆款", "限量", "免運", "下殺", "殺價",
    "直播", "老闆", "秒殺", "搶購", "現貨", "破盤", "跳樓", "超值", "優惠",
]

# 賣點詞長度上限：真正的賣點詞通常簡短，但也要容納「日本東芝 TOSHIBA」這種
# 品牌+產地組合，太長的多半才是混進來的整句行銷文案片段
MAX_FEATURE_LENGTH = 14

# 標題開頭的括號標記，例如「(人臉辨識)」——裡面的字通常是重要賣點，保留下來當候選詞，
# 括號本身則移除
BRACKET_PATTERN = re.compile(r"^[（(]([^）)]+)[）)]\s*")

# 真實caption.txt格式是「嗨！快來看看『商品資訊』，售價只要$X-$Y！立即上蝦皮購物逛逛 => 連結」
# 真正的商品資訊包在『』全形引號內，前後的話術/價格/連結都不是賣點，直接整段忽略
QUOTE_PATTERN = re.compile(r"『(.+?)』")

# 『』內部常見的兩層結構：【品牌/系列】跟「賣點短語」，各自抽出來當賣點候選詞
BRACKET_TAG_PATTERN = re.compile(r"【([^】]+)】")
QUOTE_FEATURE_PATTERN = re.compile(r"「([^」]+)」")

# 商品名稱後面常接著「｜賣場名稱」（如「TM-AC12BZP(AT)｜生活家電旗艦店」），
# ｜之後的賣場名稱不是賣點，予以移除
STORE_SUFFIX_PATTERN = re.compile(r"[|｜].*$")

# 純規格數字詞（4K、800萬、1080P、13吋…），對口語旁白沒幫助，予以濾除
SPEC_TOKEN_PATTERN = re.compile(
    r"^\d+[A-Za-z]*$|^\d+萬$|^\d+P$|^\d+吋$|^\d+cm$|^\d+mm$", re.IGNORECASE
)

# 判斷詞裡是否含中文字：品牌/型號通常是純英數字（如 TP-Link、C260），不含中文，
# 用這個規則自然排除品牌型號、只留下中文賣點詞（如「可旋轉攝影機」「寵物偵測」）
CHINESE_PATTERN = re.compile(r"[\u4e00-\u9fff]")

# 標題裡抓不到 hashtag 類別詞時的備援：常見商品類別關鍵字清單，用子字串比對
CATEGORY_KEYWORDS = [
    "監視器", "攝影機", "空氣清淨機", "清淨機", "除濕機", "電鍋", "衣櫃",
    "收納櫃", "收納箱", "吸塵器", "電風扇", "循環扇", "保溫瓶", "檯燈",
    "相機", "耳機", "背包", "電腦桌", "工作桌", "書桌", "升降桌", "辦公椅",
    "電腦椅", "床墊", "枕頭", "冰箱", "冷氣", "洗衣機", "烘乾機", "掃地機",
    "行李箱", "眼鏡", "手錶", "氣炸鍋", "氣炸烤箱", "烤箱",
]

# 判斷詞裡是否含中文字（用來判斷「品牌型號」該併到哪裡停止：
# 型號通常是純英數字，遇到第一個含中文字的詞就代表換到賣點詞了）
CHINESE_PATTERN = re.compile(r"[\u4e00-\u9fff]")

TEMPLATES_ZH = [
    "這款{category}，{feature1}又能{feature2}，居家必備好物",
    "{category}來了，{feature1}讓生活更輕鬆",
    "這款{category}，{feature1}、{feature2}，現在入手正是時候",
    "想要{feature1}的朋友，這款{category}是不錯的選擇",
    "這款{category}主打{feature1}，{feature2}也一應俱全",
    "居家生活推薦這款{category}，{feature1}真的很實用",
]

# feature2 抽不出來時使用（只有一個賣點詞可用的版本）
TEMPLATES_ZH_SINGLE = [
    "這款{category}，{feature1}，居家必備好物",
    "{category}來了，{feature1}讓生活更輕鬆",
    "想要{feature1}的朋友，這款{category}是不錯的選擇",
    "居家生活推薦這款{category}，{feature1}真的很實用",
]

# 完全抽不到賣點詞時的最終備援（只念類別詞，避免旁白開天窗）
TEMPLATES_ZH_EMPTY = [
    "這款{category}，居家必備好物",
    "居家生活推薦這款{category}",
]

# 菲律賓版（英文），文案品質未經真實PH樣本驗證，建議之後拿實際PH商品的caption.txt測試調整
TEMPLATES_EN = [
    "Check out this {category} — {feature1} and {feature2} in one",
    "Looking for a {category} with {feature1}? This one's got you covered",
    "This {category} brings you {feature1} and {feature2}",
    "This {category} makes {feature1} so much easier",
]

TEMPLATES_EN_SINGLE = [
    "Check out this {category} with {feature1}",
    "This {category} brings you {feature1}",
    "This {category} makes {feature1} so much easier",
]

TEMPLATES_EN_EMPTY = [
    "Check out this {category}, a must-have for your home",
]


def load_caption(folder: str) -> str:
    """讀取 caption.txt（標題+hashtag格式，內容與蝦皮「複製資訊」相同）"""
    path = os.path.join(folder, "caption.txt")
    if not os.path.isfile(path):
        return ""
    with open(path, "r", encoding="utf-8") as f:
        return f.read().strip()


def load_region(folder: str) -> str:
    """讀取 meta.json 的 region 欄位（TW/PH），讀不到時預設 TW"""
    meta_path = os.path.join(folder, "meta.json")
    if os.path.isfile(meta_path):
        try:
            with open(meta_path, "r", encoding="utf-8") as f:
                data = json.load(f)
            region = data.get("region")
            if region in ("TW", "PH"):
                return region
        except Exception:
            pass
    return "TW"


def extract_product_info(caption: str) -> str:
    """
    真實caption.txt格式：「嗨！快來看看『商品資訊』，售價只要$X-$Y！立即上蝦皮購物逛逛 => 連結」
    只取『』內的商品資訊，前後的話術開場白/價格/連結一律丟棄——這樣「嗨！快來看看」這類
    話術從源頭就不會進到後續解析，比事後用黑名單關鍵字濾除更根本可靠。
    抓不到『』（可能是舊格式或例外情況）就退回整段原文，交給後續步驟繼續處理。
    """
    m = QUOTE_PATTERN.search(caption)
    return m.group(1) if m else caption


def split_title_and_tags(text: str):
    """
    把商品資訊文字拆成「標題部分」跟「#hashtag 清單」（若有的話）。
    真實格式通常沒有#標籤，這裡保留#切割是為了相容萬一未來格式有hashtag的情況。
    """
    parts = text.split("#")
    title_part = parts[0].strip()
    tags = []
    for p in parts[1:]:
        p = p.strip()
        if not p:
            continue
        first_word = p.split()[0] if p.split() else p
        tags.append(first_word)
    return title_part, tags


def is_valid_feature(text: str) -> bool:
    """濾掉混進來的行銷話術片段（如「嗨！快來看看」）跟過長的整句文案"""
    if len(text) > MAX_FEATURE_LENGTH:
        return False
    if any(h in text for h in HYPE_SUBSTRINGS):
        return False
    return True


def clean_title_features(title: str):
    """
    從標題抽取賣點候選詞（不抽品牌型號，品牌型號不會出現在旁白裡）
    - 開頭括號標記（如「(人臉辨識)」）裡的字視為賣點候選詞，括號本身移除
    - 【品牌/系列】跟「賣點短語」兩種括號各自抽出來當候選詞，並從標題中移除
    - 移除「｜賣場名稱」這類尾綴（賣場名不是賣點）
    - 移除純規格數字詞（4K、800萬）
    - 剩下的詞只留「含中文字」的（如「Wi-Fi監視器」「可旋轉攝影機」），品牌型號通常是
      純英數字（TP-Link、C260），自然被排除掉
    - 過濾掉行銷話術片段跟過長字串（見 is_valid_feature）
    """
    features = []
    m = BRACKET_PATTERN.match(title)
    if m:
        features.append(m.group(1).strip())
        title = BRACKET_PATTERN.sub("", title)

    for pat in (BRACKET_TAG_PATTERN, QUOTE_FEATURE_PATTERN):
        for fm in pat.finditer(title):
            features.append(fm.group(1).strip())
        title = pat.sub(" ", title)

    title = STORE_SUFFIX_PATTERN.sub("", title)

    tokens = [t for t in title.split() if t]
    kept = [t for t in tokens if not SPEC_TOKEN_PATTERN.match(t)]
    chinese_tokens = [t for t in kept if CHINESE_PATTERN.search(t)]
    features.extend(chinese_tokens)
    return [f for f in features if is_valid_feature(f)]


def detect_category(title: str, tags: list) -> str:
    """
    判斷商品類別詞：優先用「第一個非通用、非話術的 hashtag」（蝦皮抓下來的標籤通常
    第一個就是類別，例如 #監視器、#清淨機），抓不到才退回標題關鍵字比對，最後才用
    「商品」墊底。
    回傳 (category, remaining_tags)：remaining_tags 是扣掉類別詞後剩下、可再當賣點候選的標籤
    """
    filtered_tags = [t for t in tags if t not in GENERIC_TAGS and is_valid_feature(t)]
    if filtered_tags:
        return filtered_tags[0], filtered_tags[1:]

    for kw in CATEGORY_KEYWORDS:
        if kw in title:
            return kw, []

    return "商品", []


def count_images(folder: str) -> int:
    """
    粗略統計資料夾內 image_N.jpg/jpeg/png 的數量，用來決定旁白要生成幾句話。
    上限跟 make_video.py 的 MAX_IMAGES_IN_VIDEO 一致（10），超過這個數字也不該讓
    旁白句數繼續往上加，否則句數會跟實際影片畫面數量對不上。
    """
    files = set()
    for pattern in ("image_*.jpg", "image_*.jpeg", "image_*.png"):
        files.update(os.path.basename(f) for f in glob.glob(os.path.join(folder, pattern)))
    return min(len(files), 10)


def determine_sentence_count(num_images: int) -> int:
    """
    圖片張數決定旁白句數，讓旁白長度跟著影片長度連動，避免「圖片多但旁白只有
    一兩句話」導致旁白講完後畫面還要繼續播十幾秒安靜畫面的問題。
    門檻拉低、句數上限提高到4句，讓旁白整體更長一些。
    """
    if num_images <= 3:
        return 1
    elif num_images <= 7:
        return 2
    elif num_images <= 12:
        return 3
    else:
        return 4


def pick_features(title_features, tags, max_count=2):
    """合併括號詞＋標題賣點詞＋過濾後的hashtag，去重後取前 max_count 個"""
    candidates = title_features + tags
    seen = set()
    result = []
    for c in candidates:
        c = c.strip()
        if c and c not in seen:
            seen.add(c)
            result.append(c)
        if len(result) >= max_count:
            break
    return result


def build_narration_sentences(folder: str) -> list:
    """
    主要對外函式：讀 caption.txt + meta.json + 圖片張數，回傳規則模板產生的旁白句子清單
    （list，每個元素是一句話，不含句號）。只講商品類別（例如「監視器」「清淨機」），
    不講品牌型號。旁白句數跟圖片張數連動，每句用不重複的賣點詞組合，避免圖片多的
    商品旁白太短、配不上影片長度。
    caption.txt 抽取不到有效內容時，回傳空 list——呼叫端（make_video.py）看到空 list
    就會自動退回無聲版本，不會硬套一個空白旁白。
    """
    caption = load_caption(folder)
    if not caption:
        return []

    region = load_region(folder)
    product_info = extract_product_info(caption)
    title_part, tags = split_title_and_tags(product_info)
    title_features = clean_title_features(title_part)
    category, remaining_tags = detect_category(title_part, tags)

    # 賣點詞若「完全等於」類別詞本身才濾掉（避免旁白重複念到一模一樣的詞）；
    # 只要是「包含類別詞的複合詞」（如「公升類窯烤微電腦氣炸烤箱」包含「氣炸烤箱」）
    # 就保留，因為這種複合詞通常帶有額外有用的描述資訊，整句丟掉反而更浪費
    title_features = [f for f in title_features if f != category]
    remaining_tags = [t for t in remaining_tags if t != category]

    all_features = []
    seen = set()
    for c in title_features + remaining_tags:
        c = c.strip()
        if c and c not in seen:
            seen.add(c)
            all_features.append(c)

    is_en = region == "PH"

    if not all_features:
        # 完全沒賣點詞可用，只念一句類別詞，不用勉強湊多句
        template = random.choice(TEMPLATES_EN_EMPTY if is_en else TEMPLATES_ZH_EMPTY)
        return [template.format(category=category)]

    num_images = count_images(folder)
    sentence_count = determine_sentence_count(num_images)

    # 追蹤整段旁白已經用過的賣點詞（跨句），詞用完就停止生成新句子，
    # 不再循環重複使用——先前版本用itertools.cycle循環，賣點詞很少時
    # 會導致「兩句話一模一樣」的問題（例如只有1個賣點詞卻要生成2句）
    used = set()
    sentences = []
    for _ in range(sentence_count):
        fresh = [f for f in all_features if f not in used]
        if not fresh:
            break  # 賣點詞真的用完了，寧可少一句，不要生成重複句子
        random.shuffle(fresh)
        picked = fresh[:2]
        used.update(picked)

        if len(picked) >= 2:
            template = random.choice(TEMPLATES_EN if is_en else TEMPLATES_ZH)
            sentences.append(template.format(category=category, feature1=picked[0], feature2=picked[1]))
        else:
            template = random.choice(TEMPLATES_EN_SINGLE if is_en else TEMPLATES_ZH_SINGLE)
            sentences.append(template.format(category=category, feature1=picked[0]))

    return sentences


def build_narration(folder: str) -> str:
    """
    向下相容用的包裝函式：回傳整段旁白文字（句子用「。」接起來）。
    make_video.py 現在改用 build_narration_sentences() 取得句子清單，逐句合成語音、
    逐句同步字幕；這個函式保留給 CLI 單獨測試（見 main()）跟其他可能的呼叫端使用。
    """
    sentences = build_narration_sentences(folder)
    return "。".join(sentences)


def main():
    if len(sys.argv) != 2:
        print("用法：python generate_narration.py <商品資料夾路徑>")
        sys.exit(1)

    folder = os.path.expanduser(sys.argv[1])
    if not os.path.isdir(folder):
        print(f"錯誤：找不到資料夾 {folder}")
        sys.exit(1)

    text = build_narration(folder)
    if not text:
        print("（抽取不到有效文案，caption.txt 可能是空的或格式不符）")
    else:
        print(f"→ 判定地區：{load_region(folder)}")
        print(f"→ 圖片張數：{count_images(folder)}（決定旁白句數：{determine_sentence_count(count_images(folder))}句）")
        print("→ 產生的旁白文案：")
        print(text)


if __name__ == "__main__":
    main()
