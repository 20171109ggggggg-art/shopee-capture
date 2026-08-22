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


def determine_ai_sentence_count(num_images: int) -> int:
    """
    AI文案專用的句數門檻，比規則模板那組（generate_narration.determine_sentence_count）
    更寬鬆——AI生成的句子品質好、值得讓多圖商品的旁白更飽滿一點，兩條路徑分開設定，
    改這裡不會影響規則模板那邊原本調好的行為。
    上限訂在4句：AI單句平均約4.5~5秒，5句常常讓語音總長度衝到23~28秒，
    超出15~18秒目標範圍太多，得靠+50%語速上限硬壓才勉強壓進去，聲音會偏快、不自然；
    4句通常落在18~20秒左右，只需要小幅調速甚至不用調速就能落在目標範圍內。
    """
    if num_images <= 2:
        return 1
    elif num_images <= 4:
        return 2
    elif num_images <= 7:
        return 3
    else:
        return 4  # 8~10張（目前影片圖片上限10張）


def load_ai_config():
    """讀取AI設定檔，沒有這個檔案或格式不對就回傳 None（代表不啟用AI，維持規則模板）"""
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
        return {"provider": provider, "api_key": api_key, "model": model}
    except Exception:
        return None


def build_prompt(product_info: str, num_sentences: int, region: str) -> str:
    """組出給AI的提示詞，中英文各一版。跟規則模板不同，這裡明確要求帶入品牌名稱跟具體特徵，
    不像規則模板只講類別——AI生成的品牌+特徵敘述夠自然，不會像規則模板那樣生硬，值得善用"""
    if region == "PH":
        return (
            f"You are a professional voice-over scriptwriter for short product videos. "
            f"Based on the product info below, write {num_sentences} conversational narration sentences.\n\n"
            f"Product info:\n{product_info}\n\n"
            f"Rules:\n"
            f"- Naturally weave in the brand name and 1-2 concrete, distinguishing selling points per "
            f"sentence (specific features from the product info), avoid vague adjectives\n"
            f"- Natural spoken tone, like a friend recommending something, no hype openers like "
            f'"Hey check this out"\n'
            f"- Each sentence 10-18 words, avoid repeating the same selling point across sentences\n"
            f"- Output exactly {num_sentences} sentences, one per line, no numbering, no quotes, "
            f"no extra explanation"
        )
    return (
        f"你是短影音商品旁白文案的專業寫手。請根據以下商品資訊，寫出 {num_sentences} 句口語化的旁白文案。\n\n"
        f"商品資訊：\n{product_info}\n\n"
        f"規則：\n"
        f"- 自然帶入品牌名稱，每句提到1~2個具體、有辨識度的賣點特徵（從商品資訊裡挑），"
        f"避免空泛的形容詞（如「品質優良」「CP值高」）\n"
        f"- 語氣自然口語，像朋友介紹商品，不要有「嗨！快來看看」這種業配開場白\n"
        f"- 每句15~25個中文字，句子之間不要重複相同的賣點\n"
        f"- 直接輸出{num_sentences}句話，每句一行，不要加編號、不要加引號、不要有其他說明文字"
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


def parse_sentences(raw_text: str, expected_count: int) -> list:
    """把AI回傳的原始文字拆成句子清單，順便防呆去掉AI可能自己加的編號/引號/多餘空白"""
    lines = [line.strip() for line in raw_text.strip().split("\n") if line.strip()]
    cleaned = []
    for line in lines:
        # 去掉「1. 」「1、」這類編號前綴
        for sep in (". ", "、", ") ", "） "):
            if len(line) > 2 and line[0].isdigit() and sep in line[:4]:
                line = line.split(sep, 1)[1].strip()
                break
        # 去掉頭尾引號
        line = line.strip("「」『』\"'")
        if line:
            cleaned.append(line)
    return cleaned[:expected_count] if cleaned else []


def generate_ai_sentences(folder: str):
    """
    主要對外函式：讀AI設定檔＋商品資料，呼叫對應供應商API生成旁白句子清單。
    任何一步失敗（沒設定檔/沒套件/連線失敗/回應格式不對/句子數量不足）都回傳 None，
    呼叫端看到 None 就會自動改用規則模板，不會中斷。
    """
    config = load_ai_config()
    if not config:
        return None

    if requests is None:
        print("⚠ 找不到 requests 套件，無法呼叫AI文案API，改用規則模板")
        print("  請先執行：pip install requests")
        return None

    caption = load_caption(folder)
    if not caption:
        return None

    product_info = extract_product_info(caption)
    region = load_region(folder)
    num_images = count_images(folder)
    num_sentences = determine_ai_sentence_count(num_images)

    prompt = build_prompt(product_info, num_sentences, region)
    provider = config["provider"]
    call_func = PROVIDER_FUNCS.get(provider)
    if call_func is None:
        print(f"⚠ 不支援的AI供應商「{provider}」，改用規則模板")
        return None

    try:
        raw_text = call_func(prompt, config["api_key"], config["model"])
    except Exception as e:
        print(f"⚠ AI文案生成失敗（{provider}：{e.__class__.__name__} {e}），改用規則模板")
        return None

    sentences = parse_sentences(raw_text, num_sentences)
    if not sentences:
        print(f"⚠ AI回應解析不到有效句子，改用規則模板")
        return None

    return sentences


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
    sentences = generate_ai_sentences(folder)
    if not sentences:
        print("（AI生成失敗，實際跑影片時會自動退回規則模板，這裡僅顯示失敗，不做退回示範）")
    else:
        print(f"→ 產生的旁白文案（{len(sentences)}句）：")
        for s in sentences:
            print(f"    {s}")


if __name__ == "__main__":
    main()
