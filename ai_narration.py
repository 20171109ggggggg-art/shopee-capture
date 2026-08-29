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

    【2026-08-29修改】原本4句是寫死的，改成可調參數max_sentences（來源：設定檔
    ~/.shopee_ai_config.json 的 "max_sentences" 欄位，沒設定就維持預設4，行為不變）。
    各張數區間的基礎句數不變，只是最後統一用max_sentences當作實際上限——
    如果使用者把上限調低（例如設2），連原本張數多也該給3、4句的情況也會一併被壓到2句，
    這是刻意的：使用者調這個參數就是要控制文案長度上限，不該讓區間判斷繞過去。
    """
    if num_images <= 2:
        base = 1
    elif num_images <= 4:
        base = 2
    elif num_images <= 7:
        base = 3
    else:
        base = 4  # 8~10張（目前影片圖片上限10張）
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


def build_prompt(product_info: str, num_sentences: int, region: str) -> str:
    """組出給AI的提示詞，中英文各一版。
    【2026-08-29修改】原本要求AI帶入品牌名稱，現在改成明確禁止提及品牌/型號——
    因為圖片那邊已經改用AI去除商品上的品牌logo，文案這邊也要跟著避開，
    不然畫面已經去了品牌，旁白卻還在講品牌名稱，會兜不起來。"""
    if region == "PH":
        return (
            f"You are a professional voice-over scriptwriter for short product videos targeting "
            f"Filipino Shopee shoppers. Based on the product info below, write {num_sentences} "
            f"conversational narration sentences in natural Taglish (the everyday mix of Tagalog "
            f"and English that Filipinos actually speak/type online — not pure English, and not "
            f"formal/textbook Tagalog).\n\n"
            f"Product info:\n{product_info}\n\n"
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
            f"- Keep each sentence SHORT: 6-9 words total (this is a strict limit — the narration will "
            f"be read aloud and must fit a tight video runtime), avoid repeating the same selling "
            f"point across sentences\n"
            f"- Output exactly {num_sentences} sentences, one per line, no numbering, no quotes, "
            f"no extra explanation\n"
            f"- After the sentences, add one final line starting with \"HASHTAGS:\" followed by exactly "
            f"5 short English hashtag words relevant to this product category (no brand/model names, "
            f"no # symbol, space-separated, e.g. \"HASHTAGS: ShopeeFinds MustHave HomeEssentials "
            f"TechGadget AffiliateFind\")"
        )
    return (
        f"你是短影音商品旁白文案的專業寫手。請根據以下商品資訊，寫出 {num_sentences} 句口語化的旁白文案。\n\n"
        f"商品資訊：\n{product_info}\n\n"
        f"規則：\n"
        f"- 絕對不要提到品牌名稱、型號、產品編號，就算商品資訊裡有寫也不要唸出來，"
        f"改用一般性的方式描述這個商品\n"
        f"- 每句提到1個具體、有辨識度的賣點特徵（從商品資訊裡挑，但不包含品牌/型號），"
        f"避免空泛的形容詞（如「品質優良」「CP值高」）；只有在不會拉長句子的情況下才加第二個賣點\n"
        f"- 語氣自然口語，像朋友介紹商品，不要有「嗨！快來看看」這種業配開場白\n"
        f"- 每句嚴格控制在8~12個中文字（這是硬性上限——旁白要念出來，必須配合較短的影片長度），"
        f"句子之間不要重複相同的賣點\n"
        f"- 直接輸出{num_sentences}句話，每句一行，不要加編號、不要加引號、不要有其他說明文字\n"
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
        json={"contents": [{"parts": [{"text": prompt}]}]},
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
    sentences = cleaned[:expected_count] if cleaned else []
    return sentences, hashtags[:5]


def generate_ai_sentences(folder: str, sentence_count_override: int = None):
    """
    主要對外函式：讀AI設定檔＋商品資料，呼叫對應供應商API生成旁白句子清單與5個hashtag。
    任何一步失敗（沒設定檔/沒套件/連線失敗/回應格式不對/句子數量不足）都回傳 (None, None)，
    呼叫端看到 None 就會自動改用規則模板，不會中斷。
    回傳格式：(sentences: list, hashtags: list)，hashtags 若AI沒給或解析失敗會是空list
    （不是None——句子生成成功但hashtag缺漏時，呼叫端仍可採用AI句子＋退回預設hashtag池）。

    sentence_count_override：指定句數而不是用determine_ai_sentence_count()依圖片張數
    自動判斷。給make_video.py在語音長度不在目標範圍內時，重新要求AI多寫/少寫一句用，
    取代舊版靠調整TTS語速硬湊時間的做法——語速永遠維持正常，改用調整文案長度來配合
    影片長度目標區間。
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
    num_sentences = sentence_count_override if sentence_count_override is not None else determine_ai_sentence_count(num_images, config["max_sentences"])

    prompt = build_prompt(product_info, num_sentences, region)
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

    sentences, hashtags = parse_sentences(raw_text, num_sentences)
    if not sentences:
        print(f"⚠ AI回應解析不到有效句子，改用規則模板")
        return None, None

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
