#!/usr/bin/env bash
#
# setup_new_termux.sh — 換新手機／重裝Termux後的一鍵初始化腳本
#
# 用法：
#   1. 先把這個檔案下載進Termux（跟平常下載其他檔案一樣，存到~/storage/downloads/）
#   2. 執行：bash ~/storage/downloads/setup_new_termux.sh
#
# 這個腳本會依序做完之前每次換手機/重裝Termux都會漏掉的環境設定：
#   - 系統套件更新（避免ffmpeg等套件連結錯誤）
#   - 安裝git、python、ffmpeg
#   - 安裝Python套件：edge-tts、Pillow
#   - 設定storage連結（讓Termux能存取手機一般儲存空間，例如Downloads資料夾）
#   - 設定git身份（user.name/user.email）與憑證記憶（之後push不用每次輸入帳密）
#   - clone專案repo（如果還沒clone過）
#   - 提醒手動處理的部分：AI文案設定檔~/.shopee_ai_config.json（含API金鑰，
#     基於安全考量不由腳本自動填入，需要使用者自己貼回內容）
#
# 已經做過的步驟腳本會自動偵測並跳過（例如repo已存在就不重複clone），
# 可以放心重複執行不會出錯。

set -e

echo "===================================================="
echo " 蝦皮分潤自動化 - Termux環境一鍵初始化"
echo "===================================================="
echo ""

# ---------- 1. 更新系統套件 ----------
echo "[1/7] 更新系統套件清單與版本（避免後面裝ffmpeg時發生連結錯誤）..."
pkg upgrade -y
echo "✓ 完成"
echo ""

# ---------- 2. 安裝核心套件 ----------
echo "[2/7] 安裝git、python、ffmpeg..."
pkg install git python ffmpeg -y
echo "✓ 完成"
echo ""

# ---------- 3. 安裝Python套件 ----------
echo "[3/7] 安裝edge-tts、Pillow（影片生成腳本需要）..."
pip install edge-tts Pillow --break-system-packages
echo "✓ 完成"
echo ""

# ---------- 4. storage連結 ----------
echo "[4/7] 設定storage連結（讓Termux能存取手機Downloads等資料夾）..."
echo "  → 接下來可能會跳出系統權限請求，請按「允許」"
termux-setup-storage
sleep 2
echo "✓ 完成（如果沒看到權限對話框，可能是已經設定過了）"
echo ""

# ---------- 5. git身份與憑證設定 ----------
echo "[5/7] 設定git身份與憑證記憶..."
CURRENT_NAME=$(git config --global user.name || true)
CURRENT_EMAIL=$(git config --global user.email || true)
if [ -z "$CURRENT_NAME" ] || [ -z "$CURRENT_EMAIL" ]; then
    echo "  尚未設定git身份，請輸入："
    read -p "  GitHub帳號對應的Email： " GIT_EMAIL
    read -p "  GitHub使用者名稱： " GIT_NAME
    git config --global user.email "$GIT_EMAIL"
    git config --global user.name "$GIT_NAME"
    echo "✓ 身份設定完成"
else
    echo "✓ 已設定過（$CURRENT_NAME / $CURRENT_EMAIL），跳過"
fi
git config --global credential.helper store
echo "✓ 憑證記憶已開啟（下次push只需輸入一次帳密）"
echo ""

# ---------- 6. clone專案repo ----------
echo "[6/7] 檢查專案repo..."
cd ~
if [ -d ~/shopee-capture/.git ]; then
    echo "✓ ~/shopee-capture 已存在，跳過clone"
else
    echo "  clone中：https://github.com/20171109ggggggg-art/shopee-capture.git"
    git clone https://github.com/20171109ggggggg-art/shopee-capture.git
    echo "✓ clone完成"
fi
echo ""

# ---------- 7. AI文案設定檔提醒 ----------
echo "[7/7] AI文案設定檔檢查..."
if [ -f ~/.shopee_ai_config.json ]; then
    echo "✓ ~/.shopee_ai_config.json 已存在，跳過"
else
    echo "⚠ 找不到 ~/.shopee_ai_config.json（AI文案設定檔）"
    echo "  這個檔案含有API金鑰，基於安全考量不由此腳本自動建立。"
    echo "  請手動執行以下指令建立（把YOUR_API_KEY換成實際金鑰）："
    echo ""
    echo "    nano ~/.shopee_ai_config.json"
    echo ""
    echo "  貼上這行（provider可填 claude / openai / deepseek / gemini）："
    echo '    {"provider": "deepseek", "api_key": "YOUR_API_KEY", "model": "deepseek-chat"}'
    echo ""
    echo "  存檔離開：Ctrl+X → Y → Enter"
fi
echo ""

echo "===================================================="
echo " 初始化完成！"
echo "===================================================="
echo ""
echo "還沒做的手動步驟提醒："
echo "  1. 如果上面顯示AI設定檔遺失，請照指示手動建立"
echo "  2. 確認~/.termux/termux.properties裡有 allow-external-apps=true"
echo "     （檢查指令：cat ~/.termux/termux.properties）"
echo "     （沒有的話：mkdir -p ~/.termux && echo \"allow-external-apps=true\" >> ~/.termux/termux.properties && termux-reload-settings）"
echo "  3. App端記得檢查AndroidManifest.xml的<queries>是否包含com.termux/com.shopee.tw/com.shopee.ph"
echo "     （這是App本身的設定，不是Termux這邊的，通常已經在repo裡不用重做）"
