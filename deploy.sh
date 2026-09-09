#!/data/data/com.termux/files/usr/bin/bash
#
# 【2026-09-07新增】部署自動化腳本，取代原本每次要手動打的：
# cd → cp x N → sed改版本號 → grep核對 → git add → git commit → git push
#
# 用法：
#   ./deploy.sh "commit訊息（不用加版本號，腳本會自動在前面補上）" 檔名1 [檔名2 ...]
# 例如：
#   ./deploy.sh "修正XXX導致的YYY" SimpleModeActivity.kt RemoteVideoGenerator.kt
#
# 腳本會自動：
# 1. 從 ~/storage/downloads 找對應檔名，比對repo裡實際路徑（用find找，不用手動打路徑）
#    【2026-09-09新增】檔名可以帶版本號後綴（例如SimpleModeActivity_v1.076.kt），
#    會自動忽略"_vX.XXX"這段還原成原始檔名再去repo裡找，不用手動改名
# 2. 複製前後核對bytes數，對不上直接中止（不會硬幹下去，避免複製到不完整/舊快取的檔案）
# 3. 自動讀取build.gradle.kts目前的versionCode/versionName，自動+1
#    （不用每次手動指定舊/新字串，避免比對不到「靜默不生效」這個踩過的坑）
# 4. commit訊息自動補上版本號前綴（例如 "v1.076: 你打的訊息"）
# 5. git add / commit / push 一次做完
#
# 任何一步失敗（找不到檔案、bytes對不上、版本號抓不到）都會直接停止，
# 不會留下「改了一半」的狀態。

set -e

if [ $# -lt 2 ]; then
    echo "用法：./deploy.sh \"commit訊息\" 檔名1 [檔名2 ...]"
    echo "例如：./deploy.sh \"修正XXX問題\" SimpleModeActivity.kt"
    exit 1
fi

cd ~/shopee-capture

commit_msg="$1"
shift
files=("$@")

downloads_dir=~/storage/downloads
gradle_file="app/build.gradle.kts"

# 自動讀取目前版本號，不用手動指定
current_code=$(grep -oP 'versionCode\s*=\s*\K[0-9]+' "$gradle_file")
current_name=$(grep -oP 'versionName\s*=\s*"\K[0-9.]+' "$gradle_file")
if [ -z "$current_code" ] || [ -z "$current_name" ]; then
    echo "❌ 讀不到目前的versionCode/versionName，中止部署（沒有動任何東西）"
    exit 1
fi
new_code=$((current_code + 1))
new_name=$(echo "$current_name" | awk -F. '{$NF=$NF+1; print}' OFS=.)
echo "版本號：$current_code ($current_name) -> $new_code ($new_name)"
echo ""

dest_paths=()
for f in "${files[@]}"; do
    src="$downloads_dir/$f"
    if [ ! -f "$src" ]; then
        echo "❌ Download資料夾裡找不到 $f，中止部署（沒有動任何東西）"
        exit 1
    fi
    # 檔名若帶版本號後綴（例如 SimpleModeActivity_v1.076.kt），
    # 比對repo時忽略這段後綴，還原成SimpleModeActivity.kt再找
    ext="${f##*.}"
    name_noext="${f%.*}"
    base_name=$(echo "$name_noext" | sed -E 's/_v[0-9]+(\.[0-9]+)*$//')
    base="${base_name}.${ext}"
    dest=$(find app/src/main/java -name "$base" | head -n 1)
    if [ -z "$dest" ]; then
        echo "❌ repo裡找不到叫 $base 的檔案（由 $f 還原），中止部署（沒有動任何東西）"
        exit 1
    fi
    src_size=$(wc -c < "$src")
    cp "$src" "$dest"
    dest_size=$(wc -c < "$dest")
    if [ "$src_size" != "$dest_size" ]; then
        echo "❌ $f 複製後bytes數對不上（來源$src_size / 目的$dest_size），中止部署"
        exit 1
    fi
    echo "✓ $f -> $dest（$dest_size bytes，核對OK）"
    dest_paths+=("$dest")
done

echo ""
sed -i "s/versionCode = $current_code/versionCode = $new_code/; s/versionName = \"$current_name\"/versionName = \"$new_name\"/" "$gradle_file"
echo "版本號更新結果："
grep -n "versionCode\|versionName" "$gradle_file"
echo ""

git add "${dest_paths[@]}" "$gradle_file"
git commit -m "v$new_name: $commit_msg"
git push

echo ""
echo "✅ 部署完成：v$new_name"
