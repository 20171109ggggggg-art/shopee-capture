#!/usr/bin/env python3
"""
AI旁白文案產生器（混合式方案第2階段：多供應商AI生成，取代/補強規則模板）

支援 Claude / OpenAI / DeepSeek / Gemini 四家，透過設定檔切換，不綁死單一供應商。
呼叫失敗（沒設定、沒網路、API金鑰失效、逾時等任何原因）一律回傳 None，
由呼叫端（make_video.py）自動退回 generate_narration.py 的規則模板，不會讓批次腳本中斷。

設定檔位置：~/.shopee_ai_config.json（不會被git追蹤，內含API金鑰請自行保管好）
格式範例：
    {
      "provider": "deepseek",
      "api_key": "sk-xxxxxxxxxxxxxxxx",
      "model": "deepseek-chat"
    }

provider 可填：claude / openai / deepseek / gemini
model 欄位可省略，省略時使用下面 DEFAULT_MODELS 的預設值（各家便宜款）。
沒有這個設定檔時，generate_ai_sentences() 直接回傳 None（完全不嘗試連線），
代表預設維持原本純規則模板行為，不用特別關閉什麼開關。

用法（可單獨測試，不會動到影片）：
    python ai_narration.py <商品資料夾路徑>
"""
import json
import os
import random
import sys

try:
    import requests
except ImportError:
    requests = None

from generate_narration import (
    load_caption,
    load_region,
    extract_product_info,
    count_images,
)

CONFIG_PATH = os.path.expanduser("~/.shopee_ai_config.json")

# 各家便宜款預設模型（2026年8月時點；供應商常常改模型名稱，設定檔裡的 model
# 欄位可以覆蓋這裡的預設值，不用改程式碼）
DEFAULT_MODELS = {
    "claude": "claude-haiku-4-5",
    "openai": "gpt-5.6-luna",
    "deepseek": "deepseek-chat",
    "gemini": "gemini-3.1-flash-lite",
}

REQUEST_TIMEOUT_SEC = 15


def determine_ai_sentence_count(num_images: int, max_sentences: int = 4) -> int:
    """
    AI文案專用的句數門檻，比規則模板那組（generate_narration.determine_sentence_count）
    更寬鬆——AI生成的句子品質好、值得讓多圖商品的旁白更飽滿一點，兩條路徑分開設定，
    改這裡不會影響規則模板那邊原本調好的行為。

    上限預設4句：AI單句平均約4.5~5秒，5句常常讓語音總長度衝到23~28秒，
    超出15~18秒目標範圍太多，得靠+50%語速上限硬壓才勉強壓進去，聲音會偏快、不自然；
    4句通常落在18~20秒左右，只需要小幅調速甚至不用調速就能落在目標範圍內。

    【2026-08-29修改】原本4句是寫死的，現在可調（見上方max_sentences參數）。
    另外基礎起始句數也不再直接用區間值（1/2/3/4），改成至少從4句起跳——
    實測發現從2句開始，重試迴圈要一路調到4~5句才夠15秒，等於每次都要重試好幾輪，
    浪費API呼叫次數也拖慢生成速度；改成起始至少4句，讓重試迴圈的起點更接近目標，
    重試次數自然變少。如果max_sentences刻意設得比4低（例如想要精簡文案），
    仍然以max_sentences為準，不會被這個最低起始值蓋過去。
    """
    MIN_BASE_SENTENCES = 4
    if num_images <= 2:
        base = 1
    elif num_images <= 4:
        base = 2
    elif num_images <= 7:
        base = 3
    else:
        base = 4  # 8~10張（目前影片圖片上限10張）
    base = max(base, MIN_BASE_SENTENCES)
    return min(base, max_sentences)


def load_ai_config():
    """讀取AI設定檔，沒有這個檔案或格式不對就回傳 None（代表不啟用AI，維持規則模板）。
    【2026-08-29新增】可選欄位"max_sentences"：AI旁白句數上限，沒填就用預設值4
    （determine_ai_sentence_count()的預設參數），填了就覆蓋——必須是正整數，
    不是正整數就忽略、退回預設4，不會讓整個設定檔判定失敗。"""
    if not os.path.isfile(CONFIG_PATH):
        return None
    try:
        with open(CONFIG_PATH, "r", encoding="utf-8") as f:
            config = json.load(f)
        provider = config.get("provider")
        api_key = config.get("api_key")
        if not provider or not api_key:
            return None
        model = config.get("model") or DEFAULT_MODELS.get(provider)
        if not model:
            return None
        max_sentences = config.get("max_sentences", 4)
        if not isinstance(max_sentences, int) or max_sentences < 1:
            max_sentences = 4
        return {"provider": provider, "api_key": api_key, "model": model, "max_sentences": max_sentences}
    except Exception:
        return None


def build_prompt(product_info: str, approx_sentences: int, region: str, char_min: int = None, char_max: int = None) -> str:
    """組出給AI的提示詞，中英文各一版。
    【2026-08-29修改】原本要求AI帶入品牌名稱，現在改成明確禁止提及品牌/型號——
    因為圖片那邊已經改用AI去除商品上的品牌logo，文案這邊也要跟著避開，
    不然畫面已經去了品牌，旁白卻還在講品牌名稱，會兜不起來。

    【2026-08-29新增】隨機切入角度：光靠調高API的temperature，實測發現這種主題固定、
    規則寫得很細的短文案提示詞，模型還是很容易收斂到差不多的答案（同一個商品重新生成
    常常逐字相同）。改成每次呼叫都從一組切入角度裡隨機抽一個明確塞進提示詞，強迫AI
    這次要用「這個角度」去寫，跟temperature的隨機性疊加，同一個商品重新生成才會真的
    有感的不一樣，不只是碰運氣。

    【2026-08-30修改】原本用「固定句數」當硬性要求，但句數固定不代表總字數固定
    （每句字數本來就有10~16字的浮動空間），常常句數對了、總長度還是差一截，要
    重試好幾次。改成把「總字數範圍」當成主要的硬性要求，句數只當作自然語氣的
    參考建議，讓AI自己決定要分成幾句才能讓總字數落在範圍內、內容又通順。
    char_min/char_max為None時（理論上不應該發生，make_video.py一定會帶入目標字數）
    退回舊版「大約N句」的提示語氣，不設字數硬性要求，避免程式出錯。
    """
    angle_pool_zh = [
        "這次的切入角度：從「使用前 vs 使用後的差別」下手，強調用了這個商品之後生活哪裡變輕鬆",
        "這次的切入角度：從「一個具體的生活場景」下手（例如某個時間點、某個空間），把商品放進那個畫面裡描述",
        "這次的切入角度：從「別人問起時你會怎麼推薦給朋友」的口吻下手，像在跟朋友聊天分享心得",
        "這次的切入角度：從「最讓人驚訝的一個細節」下手，挑一個最容易讓人「欸真的假的」的特點放在最前面",
        "這次的切入角度：從「解決了什麼煩惱」下手，先點出困擾、再帶出商品怎麼處理掉這個困擾",
        "這次的切入角度：從「感官體驗」下手（摸起來、看起來、用起來的實際感受），少講規格多講感受",
    ]
    angle_pool_en = [
        "This time's angle: focus on the before-vs-after difference once you start using this product",
        "This time's angle: anchor it in one specific everyday moment or space, describing the product inside that scene",
        "This time's angle: write it like you're casually recommending this to a friend who just asked about it",
        "This time's angle: lead with the single most surprising detail about this product",
        "This time's angle: open with the annoyance/problem this solves, then bring in how the product fixes it",
        "This time's angle: focus on sensory experience (how it feels, looks, sounds to use) rather than specs",
    ]
    angle_hint_zh = random.choice(angle_pool_zh)
    angle_hint_en = random.choice(angle_pool_en)

    if region == "PH":
        length_rule_en = (
            f"- The TOTAL character count across ALL sentences combined must fall between "
            f"{char_min} and {char_max} characters (count letters only, not spaces/punctuation) — "
            f"this is a HARD CEILING, not a suggestion — going even slightly over {char_max} is a failure, "
            f"more important than sentence count. Split the content into "
            f"however many sentences feels natural (roughly {approx_sentences}, but adjust freely) to "
            f"hit this total length; don't pad with filler just to reach the count, and don't cut useful "
            f"content just to save characters — instead write each selling point with a bit more or less "
            f"detail to land in range. If you're unsure whether you're within range, err on the shorter "
            f"side rather than risk going over {char_max}"
            if char_min is not None and char_max is not None else
            f"- Output roughly {approx_sentences} sentences"
        )
        return (
            f"You are a professional voice-over scriptwriter for short product videos targeting "
            f"Filipino Shopee shoppers. Based on the product info below, write conversational narration "
            f"in natural Taglish (the everyday mix of Tagalog "
            f"and English that Filipinos actually speak/type online — not pure English, and not "
            f"formal/textbook Tagalog).\n\n"
            f"Product info:\n{product_info}\n\n"
            f"{angle_hint_en}\n\n"
            f"Rules:\n"
            f"- Do NOT mention the brand name, model number, or product code anywhere in the "
            f"sentences, even if they appear in the product info above — describe the product "
            f"generically instead\n"
            f"- Naturally weave in 1 concrete, distinguishing selling point per "
            f"sentence (a specific feature from the product info, excluding brand/model, not a "
            f"vague adjective); only add a second feature if it fits without making the sentence "
            f"longer\n"
            f"- Natural spoken Taglish tone, like a friend recommending something (e.g. mixing words "
            f'like "sobrang", "grabe", "talaga", "kasi", "na", "pa" naturally with English product '
            f'terms), no hype openers like "Hey check this out"\n'
            f"{length_rule_en}, avoid repeating the same selling "
            f"point across sentences\n"
            f"- One sentence per line, no numbering, no quotes, no extra explanation\n"
            f"- After the sentences, add one final line starting with \"HASHTAGS:\" followed by exactly "
            f"5 short English hashtag words relevant to this product category (no brand/model names, "
            f"no # symbol, space-separated, e.g. \"HASHTAGS: ShopeeFinds MustHave HomeEssentials "
            f"TechGadget AffiliateFind\")"
        )

    length_rule_zh = (
        f"- 全部句子加起來的「總字數」請控制在{char_min}~{char_max}個中文字之間（不含標點符號）——"
        f"{char_max}字是絕對上限，寧可少也不要超過，比句數重要。要分成幾句自然分（大約{approx_sentences}"
        f"句左右，但可以自由調整句數），不要為了湊字數硬塞填充詞，也不要為了省字數砍掉有用的內容——"
        f"同一個賣點寫得更詳細或更精簡來調整長度，而不是增減句子裡的實質內容。如果不確定字數有沒有超過，"
        f"請寧可寫少一點，也不要冒著超過{char_max}字上限的風險"
        if char_min is not None and char_max is not None else
        f"- 直接輸出大約{approx_sentences}句話"
    )
    return (
        f"你是短影音商品旁白文案的專業寫手。請根據以下商品資訊，寫出口語化的旁白文案。\n\n"
        f"商品資訊：\n{product_info}\n\n"
        f"{angle_hint_zh}\n\n"
        f"規則：\n"
        f"- 絕對不要提到品牌名稱、型號、產品編號，就算商品資訊裡有寫也不要唸出來，"
        f"改用一般性的方式描述這個商品\n"
        f"- 每句提到1個具體、有辨識度的賣點特徵（從商品資訊裡挑，但不包含品牌/型號），"
        f"避免空泛的形容詞（如「品質優良」「CP值高」）；如果商品本身賣點不多、想不出新的不重複"
        f"賣點，可以針對同一個賣點寫得更完整、更有畫面感（例如加上使用情境、感受）\n"
        f"- 語氣自然口語，像朋友介紹商品，不要有「嗨！快來看看」這種業配開場白\n"
        f"{length_rule_zh}，句子之間不要重複相同的賣點\n"
        f"- 每句一行，不要加編號、不要加引號、不要有其他說明文字\n"
        f"- 句子輸出完後，最後加一行以「HASHTAGS:」開頭，接5個跟這個商品類別相關的中文標籤詞"
        f"（不含品牌/型號名稱、不含#符號、用空格分隔，例如「HASHTAGS: 居家好物 分潤推薦 開箱心得 生活選物 蝦皮好物」）"
    )


def _call_claude(prompt: str, api_key: str, model: str) -> str:
    resp = requests.post(
        "https://api.anthropic.com/v1/messages",
        headers={
            "x-api-key": api_key,
            "anthropic-version": "2023-06-01",
            "content-type": "application/json",
        },
        json={
            "model": model,
            "max_tokens": 400,
            "temperature": 1.0,
            "messages": [{"role": "user", "content": prompt}],
        },
        timeout=REQUEST_TIMEOUT_SEC,
    )
    resp.raise_for_status()
    data = resp.json()
    return data["content"][0]["text"]


def _call_openai(prompt: str, api_key: str, model: str) -> str:
    resp = requests.post(
        "https://api.openai.com/v1/chat/completions",
        headers={"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"},
        json={
            "model": model,
            "messages": [{"role": "user", "content": prompt}],
            "max_tokens": 400,
            "temperature": 1.1,
        },
        timeout=REQUEST_TIMEOUT_SEC,
    )
    resp.raise_for_status()
    data = resp.json()
    return data["choices"][0]["message"]["content"]


def _call_deepseek(prompt: str, api_key: str, model: str) -> str:
    # DeepSeek API 相容 OpenAI 的請求格式，端點不同而已
    resp = requests.post(
        "https://api.deepseek.com/chat/completions",
        headers={"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"},
        json={
            "model": model,
            "messages": [{"role": "user", "content": prompt}],
            "max_tokens": 400,
            # 【2026-08-29新增】DeepSeek官方文件建議一般對話/文案類用1.3，預設值1.0本身
            # 不算低，但實測發現這種主題固定、規則寫得很細的短文案提示詞，模型輸出
            # 收斂得很快，同一個商品重複生成常常逐字相同。調高到1.3增加隨機性，
            # 讓同一個商品每次重新生成的文案有機會不一樣（不影響已經很穩定的句數/
            # 時長重試邏輯，那套是靠時長數字判斷、跟文字內容本身無關）。
            "temperature": 1.3,
        },
        timeout=REQUEST_TIMEOUT_SEC,
    )
    resp.raise_for_status()
    data = resp.json()
    return data["choices"][0]["message"]["content"]


def _call_gemini(prompt: str, api_key: str, model: str) -> str:
    resp = requests.post(
        f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent",
        params={"key": api_key},
        headers={"Content-Type": "application/json"},
        json={
            "contents": [{"parts": [{"text": prompt}]}],
            "generationConfig": {"temperature": 1.1},
        },
        timeout=REQUEST_TIMEOUT_SEC,
    )
    resp.raise_for_status()
    data = resp.json()
    return data["candidates"][0]["content"]["parts"][0]["text"]


PROVIDER_FUNCS = {
    "claude": _call_claude,
    "openai": _call_openai,
    "deepseek": _call_deepseek,
    "gemini": _call_gemini,
}


def parse_sentences(raw_text: str, expected_count: int) -> tuple:
    """
    把AI回傳的原始文字拆成 (句子清單, hashtag清單) 兩份，順便防呆去掉AI可能自己加的
    編號/引號/多餘空白。以「HASHTAGS:」開頭的那一行單獨抽出來當hashtag清單
    （用空白切開、去掉可能誤加的#符號），其餘行才是旁白句子。
    抓不到HASHTAGS那行時，hashtag清單回傳空list，呼叫端會退回規則模板的預設標籤池。

    【2026-08-30修改】原本會用cleaned[:expected_count]把句子清單截斷到指定句數，
    但現在改成用「目標總字數」控制文案長度、句數只是自然語氣的參考建議，AI實際
    分成幾句可能跟expected_count不一樣（例如字數範圍需要5句才裝得下，AI給了5句），
    截斷會把後面的句子內容整個砍掉、讓總字數對不上目標，違背改用字數控制的本意。
    改成不截斷，回傳全部解析到的句子。expected_count參數保留給呼叫端記錄／除錯用，
    不再影響回傳結果。
    """
    lines = [line.strip() for line in raw_text.strip().split("\n") if line.strip()]
    hashtags = []
    sentence_lines = []
    for line in lines:
        if line.upper().startswith("HASHTAGS:"):
            tag_part = line.split(":", 1)[1] if ":" in line else ""
            hashtags = [t.strip().lstrip("#") for t in tag_part.split() if t.strip().lstrip("#")]
        else:
            sentence_lines.append(line)

    cleaned = []
    for line in sentence_lines:
        # 去掉「1. 」「1、」這類編號前綴
        for sep in (". ", "、", ") ", "） "):
            if len(line) > 2 and line[0].isdigit() and sep in line[:4]:
                line = line.split(sep, 1)[1].strip()
                break
        # 去掉頭尾引號
        line = line.strip("「」『』\"'")
        if line:
            cleaned.append(line)
    return cleaned, hashtags[:5]


def build_compress_prompt(sentences: list, char_min: int, char_max: int, region: str) -> str:
    """
    跟build_prompt()不同：這個不是從商品資訊重新生成一份全新文案，而是把AI
    已經寫好、但太長的sentences原文丟回去，明確要求「保留內容跟切入角度不變、
    單純把字數壓縮到目標範圍」。用在retry收斂階段，是精確的編輯任務，比
    「憑空預測自己這次會寫多長」容易遵守得多。
    """
    joined = "\n".join(sentences)
    if region == "PH":
        return (
            f"Here is an existing product narration script:\n\n{joined}\n\n"
            f"It's currently too long. Rewrite it to be more concise, keeping the same selling "
            f"points, tone, and Taglish language style, but reduce the TOTAL character count "
            f"across all lines combined to between {char_min} and {char_max} characters "
            f"(count letters only, not spaces/punctuation). You may reduce the number of "
            f"sentences if needed, but do not add new content or switch to a different angle — "
            f"just tighten and shorten the existing wording.\n"
            f"Output one sentence per line, no numbering, no quotes, no extra explanation, and "
            f"do not include a HASHTAGS line (the hashtags from before stay unchanged)."
        )
    return (
        f"以下是目前的商品旁白文案：\n\n{joined}\n\n"
        f"這段文案目前字數太多。請保留原本的賣點內容、語氣跟切入角度不變，把「全部句子加起來"
        f"的總字數」精簡壓縮到{char_min}~{char_max}個中文字之間（不含標點符號）——可以視情況"
        f"減少句數，但不要新增沒出現過的內容、不要換成新的切入角度，單純把現有的話講得更精簡。\n"
        f"每句一行，不要加編號、不要加引號、不要有其他說明文字，這次不用輸出HASHTAGS那行"
        f"（標籤沿用前一版不變）。"
    )


def compress_ai_sentences(folder: str, sentences: list, char_min: int, char_max: int):
    """
    把sentences（AI剛寫的、但太長的文案）連同目標字數範圍丟回AI做壓縮改寫，
    不是重新呼叫generate_ai_sentences()生成全新的一份。任何一步失敗都回傳
    None，呼叫端會視同壓縮失敗、維持原本文案跟語音。
    回傳格式：sentences: list（不含hashtags——這次呼叫刻意不重新生成hashtags，
    沿用壓縮前的那份，避免文案改短了、標籤卻對不上）。
    """
    config = load_ai_config()
    if not config:
        return None
    if requests is None:
        return None

    region = load_region(folder)
    prompt = build_compress_prompt(sentences, char_min, char_max, region)
    provider = config["provider"]
    call_func = PROVIDER_FUNCS.get(provider)
    if call_func is None:
        return None

    try:
        raw_text = call_func(prompt, config["api_key"], config["model"])
    except Exception as e:
        print(f"⚠ 壓縮文案呼叫AI發生未預期錯誤（{e.__class__.__name__}）")
        return None

    new_sentences, _ = parse_sentences(raw_text, len(sentences))
    return new_sentences if new_sentences else None


def generate_ai_sentences(folder: str, sentence_count_override: int = None, char_count_range: tuple = None):
    """
    主要對外函式：讀AI設定檔＋商品資料，呼叫對應供應商API生成旁白句子清單與5個hashtag。
    任何一步失敗（沒設定檔/沒套件/連線失敗/回應格式不對/句子數量不足）都回傳 (None, None)，
    呼叫端看到 None 就會自動改用規則模板，不會中斷。
    回傳格式：(sentences: list, hashtags: list)，hashtags 若AI沒給或解析失敗會是空list
    （不是None——句子生成成功但hashtag缺漏時，呼叫端仍可採用AI句子＋退回預設hashtag池）。

    【2026-08-30修改】主要控制參數改成char_count_range（目標總字數範圍的tuple，
    例如(68, 90)）——make_video.py的retry迴圈現在改用調整目標字數而不是調句數，
    因為句數固定不代表總字數固定，字數才是真正決定語音長度的因素。
    sentence_count_override保留但只用來在字數提示旁邊給AI一個「大約幾句」的自然
    語氣參考（不再是句數上限的硬性截斷依據），沒給的話用determine_ai_sentence_count()
    依圖片張數自動判斷。
    """
    config = load_ai_config()
    if not config:
        return None, None

    if requests is None:
        print("⚠ 找不到 requests 套件，無法呼叫AI文案API，改用規則模板")
        print("  請先執行：pip install requests")
        return None, None

    caption = load_caption(folder)
    if not caption:
        return None, None

    region = load_region(folder)
    product_info = extract_product_info(caption, region)
    num_images = count_images(folder)
    approx_sentences = sentence_count_override if sentence_count_override is not None else determine_ai_sentence_count(num_images, config["max_sentences"])

    char_min, char_max = char_count_range if char_count_range else (None, None)
    prompt = build_prompt(product_info, approx_sentences, region, char_min, char_max)
    provider = config["provider"]
    call_func = PROVIDER_FUNCS.get(provider)
    if call_func is None:
        print(f"⚠ 不支援的AI供應商「{provider}」，改用規則模板")
        return None, None

    try:
        raw_text = call_func(prompt, config["api_key"], config["model"])
    except Exception as e:
        print(f"⚠ AI文案生成失敗（{provider}：{e.__class__.__name__} {e}），改用規則模板")
        return None, None

    sentences, hashtags = parse_sentences(raw_text, approx_sentences)
    if not sentences:
        print(f"⚠ AI回應解析不到有效句子，改用規則模板")
        return None, None

    if char_min is not None:
        actual_chars = sum(len(s) for s in sentences)
        print(f"→ AI文案總字數：{actual_chars}字（目標{char_min}~{char_max}字，{len(sentences)}句）")

    return sentences, hashtags


def main():
    if len(sys.argv) != 2:
        print("用法：python ai_narration.py <商品資料夾路徑>")
        sys.exit(1)

    folder = os.path.expanduser(sys.argv[1])
    if not os.path.isdir(folder):
        print(f"錯誤：找不到資料夾 {folder}")
        sys.exit(1)

    config = load_ai_config()
    if not config:
        print(f"沒有找到設定檔（{CONFIG_PATH}），或設定檔內容不完整")
        print("請建立這個檔案，格式範例：")
        print('  {"provider": "deepseek", "api_key": "你的金鑰", "model": "deepseek-chat"}')
        sys.exit(1)

    print(f"→ 使用供應商：{config['provider']}，模型：{config['model']}")
    sentences, hashtags = generate_ai_sentences(folder)
    if not sentences:
        print("（AI生成失敗，實際跑影片時會自動退回規則模板，這裡僅顯示失敗，不做退回示範）")
    else:
        print(f"→ 產生的旁白文案（{len(sentences)}句）：")
        for s in sentences:
            print(f"    {s}")
        print(f"→ 產生的hashtag（{len(hashtags or [])}個）：{hashtags}")


if __name__ == "__main__":
    main()
