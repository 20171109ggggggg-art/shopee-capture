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

支援優雅停止：App簡易模式「生成影片」畫面按下「停止生成」時，會在<root>底下
建立 .stop_signal 檔案。本腳本在每支影片完成、換下一支之前會檢查這個檔案，
看到就在完成目前這支之後結束（不會生成到一半被腰斬），並刪掉訊號檔案避免
下次啟動時誤判。

generate_narration.py、make_video.py 必須跟本檔放在同一個資料夾。
"""
import os
import sys
import time
import subprocess

from make_video import process_folder


import json


def write_progress(root: str, total: int, completed: int, current_name: str,
                    status: str, ok_count: int, skipped_count: int, error_count: int,
                    ok_names: list = None, skipped_names: list = None,
                    error_items: list = None, elapsed_seconds: float = None) -> None:
    """
    把目前批次進度寫進 <root>/.progress.json，供App簡易模式那邊輪詢顯示進度用
    （App不用等整批跑完，也不用解析stdout，直接讀這個結構化檔案）。
    status: "running"、"done" 或 "stopped"（使用者主動按停止、目前這支完成後結束）。
    寫入失敗（例如沒有寫入權限）只印警告，不影響批次本身。

    ok_names/skipped_names/error_items/elapsed_seconds 只在批次真正結束時
    （done/stopped）才傳入，讓App結束畫面能顯示「哪些商品生成了、哪些是本來就有
    影片被跳過、哪些失敗＋原因」的詳細清單，不是只有數字。跑到一半的中繼write_progress
    呼叫不傳這幾個參數（維持None），避免每支影片都重複寫入整份清單造成不必要的I/O。
    error_items內每筆是[name, 錯誤訊息第一行]，訊息本身可能很長（例如ffmpeg完整輸出），
    只存第一行避免.progress.json檔案過度肥大。
    """
    progress_path = os.path.join(root, ".progress.json")
    payload = {
        "total": total,
        "completed": completed,
        "current": current_name,
        "status": status,
        "okCount": ok_count,
        "skippedCount": skipped_count,
        "errorCount": error_count,
        "updatedAt": time.time()
    }
    if ok_names is not None:
        payload["okNames"] = ok_names
    if skipped_names is not None:
        payload["skippedNames"] = skipped_names
    if error_items is not None:
        payload["errorItems"] = error_items
    if elapsed_seconds is not None:
        payload["elapsedSeconds"] = elapsed_seconds
    try:
        with open(progress_path, "w", encoding="utf-8") as f:
            json.dump(payload, f, ensure_ascii=False)
    except Exception as e:
        print(f"⚠ 寫入進度檔案失敗（{e}），不影響批次本身")


def check_stop_requested(root: str) -> bool:
    """
    檢查App是否送出停止訊號（<root>/.stop_signal檔案）。
    看到就刪除該檔案（避免下次啟動時誤判成又被要求停止）並回傳True。
    """
    stop_path = os.path.join(root, ".stop_signal")
    if os.path.isfile(stop_path):
        try:
            os.remove(stop_path)
        except Exception:
            pass
        return True
    return False


def find_product_folders(root: str) -> list:
    """找出根目錄底下所有商品資料夾（有 image_1.jpg 的才算，避免掃到雜項資料夾）。
    如果 <root>/.selected_ids.txt 存在（App「生成影片」畫面勾選商品後開始生成時會寫入這個檔案，
    一行一個資料夾名稱），只回傳清單裡列出的資料夾——對應「人工選圖後只生成勾選商品」的需求。
    這個檔案不存在時維持原本行為（處理全部），向後相容舊的呼叫方式（例如手動下指令跑整批）。"""
    selected_path = os.path.join(root, ".selected_ids.txt")
    selected_names = None
    if os.path.isfile(selected_path):
        try:
            with open(selected_path, "r", encoding="utf-8") as f:
                selected_names = {line.strip() for line in f if line.strip()}
            os.remove(selected_path)  # 用完即丟，避免下次沒帶清單時被舊檔案誤篩選
        except Exception as e:
            print(f"⚠ 讀取 .selected_ids.txt 失敗（{e}），改為處理全部資料夾")
            selected_names = None

    folders = []
    for name in sorted(os.listdir(root)):
        if selected_names is not None and name not in selected_names:
            continue
        path = os.path.join(root, name)
        if not os.path.isdir(path):
            continue
        if os.path.isfile(os.path.join(path, "image_1.jpg")) or \
           os.path.isfile(os.path.join(path, "image_1.jpeg")) or \
           os.path.isfile(os.path.join(path, "image_1.png")):
            folders.append(path)
    return folders


def acquire_wake_lock() -> bool:
    """
    呼叫termux-wake-lock，讓Termux在批次生成期間不被系統省電策略（例如POCO/HyperOS
    的「智慧限制後台執行」）強制砍掉。根因排查發現生成到一半被砍會留下缺moov atom的
    殘缺output.mp4（不會噴例外、不會被察覺，只有實際播放才會發現壞掉）。過去這件事要
    使用者自己記得手動下termux-wake-lock，現在改成腳本自己在開始生成時就做，不用
    每次都靠使用者記得。裝置沒裝termux-api套件時這個指令會失敗，這裡只印警告不中斷
    批次本身（沒wake lock仍然可以跑，只是失去這層保護）。
    """
    try:
        result = subprocess.run(["termux-wake-lock"], capture_output=True, text=True, timeout=10)
        if result.returncode == 0:
            print("已取得termux-wake-lock，批次生成期間手機不會因省電策略中斷此行程")
            return True
        print(f"⚠ termux-wake-lock執行失敗（returncode={result.returncode}），繼續生成但不受保護：{result.stderr.strip()}")
    except Exception as e:
        print(f"⚠ 找不到termux-wake-lock指令或執行失敗（{e}），可能沒安裝termux-api套件"
              "（pkg install termux-api），繼續生成但不受保護")
    return False


def release_wake_lock() -> None:
    """批次結束（不管成功/停止/例外）都要釋放wake lock，避免手機之後一直被鎖著耗電。"""
    try:
        subprocess.run(["termux-wake-unlock"], capture_output=True, text=True, timeout=10)
    except Exception:
        pass


def main():
    if len(sys.argv) not in (2, 3):
        print("用法：python batch_generate.py <CaptionQueue根目錄> [--force]")
        sys.exit(1)

    root = os.path.expanduser(sys.argv[1])
    force = "--force" in sys.argv[2:]

    if not os.path.isdir(root):
        print(f"錯誤：找不到資料夾 {root}")
        sys.exit(1)

    # 開始新一批之前，先清掉可能殘留的舊停止訊號檔案（例如上一批是被停止結束的，
    # 理論上App端也會在啟動新一批前清過，這裡再保險清一次避免競速狀況）。
    check_stop_requested(root)

    folders = find_product_folders(root)
    if not folders:
        print(f"在 {root} 底下沒有找到任何商品資料夾（需含 image_1.jpg）")
        sys.exit(0)

    print(f"共找到 {len(folders)} 個商品資料夾{'（強制重跑已存在的影片）' if force else ''}")
    print("=" * 60)

    acquire_wake_lock()
    try:
        run_batch(root, folders, force)
    finally:
        release_wake_lock()


def run_batch(root: str, folders: list, force: bool) -> None:
    ok_list = []
    skipped_list = []
    error_list = []
    start_time = time.time()
    total = len(folders)
    stopped = False

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
            result = None

        if result is not None:
            if result["status"] == "ok":
                print(f"✓ 完成：{result['output_path']}")
                ok_list.append(name)
            elif result["status"] == "skipped":
                print(f"— 跳過（{result['message']}）")
                skipped_list.append(name)
            else:
                print(f"✗ 失敗：{result['message']}")
                error_list.append((name, result["message"]))

        # 目前這支（無論成功/跳過/失敗）已經處理完，換下一支之前檢查有沒有收到停止訊號。
        if check_stop_requested(root):
            print("\n收到停止指令，目前這支已完成，批次到此停止")
            write_progress(root, total, idx, "", "stopped",
                            len(ok_list), len(skipped_list), len(error_list),
                            ok_names=ok_list, skipped_names=skipped_list,
                            error_items=[[n, m.strip().splitlines()[-1] if m.strip() else "（無錯誤訊息）"] for n, m in error_list],
                            elapsed_seconds=time.time() - start_time)
            stopped = True
            break

    if not stopped:
        write_progress(root, total, total, "", "done",
                        len(ok_list), len(skipped_list), len(error_list),
                        ok_names=ok_list, skipped_names=skipped_list,
                        error_items=[[n, m.strip().splitlines()[-1] if m.strip() else "（無錯誤訊息）"] for n, m in error_list],
                        elapsed_seconds=time.time() - start_time)

    elapsed = time.time() - start_time
    print("\n" + "=" * 60)
    if stopped:
        print(f"批次已停止（使用者主動中止），共花費 {elapsed / 60:.1f} 分鐘")
    else:
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
