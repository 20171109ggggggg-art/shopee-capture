#!/usr/bin/env python3
"""
批次影片生成腳本
用法：python batch_generate.py <CaptionQueue根目錄>
例如：python batch_generate.py ~/storage/downloads/CaptionQueue

會掃描根目錄底下每一個商品資料夾（例如 20260819_210324/），已經有 output.mp4 的
資料夾會自動跳過（不重跑），沒有的才呼叫 make_video.py 的邏輯生成。單一支失敗不會
中斷整批，跑完會印出成功/跳過/失敗的統計清單。

如果想強制重跑「已經有 output.mp4」的資料夾，加上 --force：
    python batch_generate.py ~/storage/downloads/CaptionQueue --force

generate_narration.py、make_video.py 必須跟本檔放在同一個資料夾。
"""
import os
import sys
import time

from make_video import process_folder


import json


def write_progress(root: str, total: int, completed: int, current_name: str,
                    status: str, ok_count: int, skipped_count: int, error_count: int) -> None:
    """
    把目前批次進度寫進 <root>/.progress.json，供App簡易模式那邊輪詢顯示進度用
    （App不用等整批跑完，也不用解析stdout，直接讀這個結構化檔案）。
    status: "running" 或 "done"。寫入失敗（例如沒有寫入權限）只印警告，不影響批次本身。
    """
    progress_path = os.path.join(root, ".progress.json")
    try:
        with open(progress_path, "w", encoding="utf-8") as f:
            json.dump({
                "total": total,
                "completed": completed,
                "current": current_name,
                "status": status,
                "okCount": ok_count,
                "skippedCount": skipped_count,
                "errorCount": error_count,
                "updatedAt": time.time()
            }, f, ensure_ascii=False)
    except Exception as e:
        print(f"⚠ 寫入進度檔案失敗（{e}），不影響批次本身")


def find_product_folders(root: str) -> list:
    """找出根目錄底下所有商品資料夾（有 image_1.jpg 的才算，避免掃到雜項資料夾）"""
    folders = []
    for name in sorted(os.listdir(root)):
        path = os.path.join(root, name)
        if not os.path.isdir(path):
            continue
        if os.path.isfile(os.path.join(path, "image_1.jpg")) or \
           os.path.isfile(os.path.join(path, "image_1.jpeg")) or \
           os.path.isfile(os.path.join(path, "image_1.png")):
            folders.append(path)
    return folders


def main():
    if len(sys.argv) not in (2, 3):
        print("用法：python batch_generate.py <CaptionQueue根目錄> [--force]")
        sys.exit(1)

    root = os.path.expanduser(sys.argv[1])
    force = "--force" in sys.argv[2:]

    if not os.path.isdir(root):
        print(f"錯誤：找不到資料夾 {root}")
        sys.exit(1)

    folders = find_product_folders(root)
    if not folders:
        print(f"在 {root} 底下沒有找到任何商品資料夾（需含 image_1.jpg）")
        sys.exit(0)

    print(f"共找到 {len(folders)} 個商品資料夾{'（強制重跑已存在的影片）' if force else ''}")
    print("=" * 60)

    ok_list = []
    skipped_list = []
    error_list = []
    start_time = time.time()
    total = len(folders)

    write_progress(root, total, 0, "", "running", 0, 0, 0)

    for idx, folder in enumerate(folders, 1):
        name = os.path.basename(folder)
        print(f"\n[{idx}/{len(folders)}] {name}")
        print("-" * 60)
        write_progress(root, total, idx - 1, name, "running",
                        len(ok_list), len(skipped_list), len(error_list))
        try:
            result = process_folder(folder, force=force)
        except Exception as e:
            print(f"✗ 發生未預期的錯誤：{e}")
            error_list.append((name, str(e)))
            continue

        if result["status"] == "ok":
            print(f"✓ 完成：{result['output_path']}")
            ok_list.append(name)
        elif result["status"] == "skipped":
            print(f"— 跳過（{result['message']}）")
            skipped_list.append(name)
        else:
            print(f"✗ 失敗：{result['message']}")
            error_list.append((name, result["message"]))

    write_progress(root, total, total, "", "done",
                    len(ok_list), len(skipped_list), len(error_list))

    elapsed = time.time() - start_time
    print("\n" + "=" * 60)
    print(f"批次完成，共花費 {elapsed / 60:.1f} 分鐘")
    print(f"  成功：{len(ok_list)} 支")
    print(f"  跳過（已存在）：{len(skipped_list)} 支")
    print(f"  失敗：{len(error_list)} 支")
    if error_list:
        print("\n失敗清單：")
        for name, msg in error_list:
            first_line = msg.strip().splitlines()[-1] if msg.strip() else "（無錯誤訊息）"
            print(f"  - {name}：{first_line}")


if __name__ == "__main__":
    main()
