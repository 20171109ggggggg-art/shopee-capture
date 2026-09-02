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

【2026-09-02新增】遠端生成模式：
如果 ~/.shopee_video_server_config.json 存在且 "enabled": true，改成把每個商品
資料夾打包成zip丟給筆電端的FastAPI服務（main.py）生成，取代本機Termux呼叫
process_folder()。這樣可以用筆電的運算資源，不用佔用手機。
設定檔格式：
    {
      "enabled": true,
      "server_url": "http://100.98.87.55:8000"
    }
沒有這個設定檔、或 "enabled" 不是 true，維持原本行為（本機Termux生成），
不用特別做什麼設定就能沿用舊的使用方式。

帳號分類：不是靠這個設定檔指定帳號（因為同一支手機會切換用多個蝦皮帳號），
而是每個商品資料夾自己的 meta.json 裡的 "account" 欄位（App v1.023起擷取時
會寫入這個欄位）——本腳本處理每個資料夾時直接讀那個資料夾自己的account，
筆電端收到後依帳號分開備份。

筆電斷線/沒開機時的行為：跟「單一商品生成失敗」是不同等級的問題——連不上
筆電代表接下來全部商品都會失敗，不該一支一支各自失敗、浪費時間跑完全部
才發現整批都沒用。偵測到連線失敗（逾時/連不上）會立刻中止整批，在
.progress.json寫入status="error_laptop_unreachable"，讓App讀到後跳出提示，
不會誤判成一般的「本批完成但全部失敗」。

批次跑完（不管成功/中止）後，會額外把手機的防重複資料庫
（captured_names/captured_history.jsonl）同步備份一份到筆電（跟哪個帳號無關，
這份資料庫本身是跨帳號共用的一份紀錄），失敗只印警告不影響本次批次結果。
"""
import os
import sys
import time
import subprocess
import zipfile
import io

try:
    import requests
except ImportError:
    requests = None

from make_video import process_folder, is_valid_video


import json

REMOTE_CONFIG_PATH = os.path.expanduser("~/.shopee_video_server_config.json")
DEDUP_HISTORY_PATH = os.path.expanduser("~/shopee-capture/captured_history.jsonl")


class ServerUnreachableError(Exception):
    """筆電服務連不上（斷線/沒開機/逾時），要中止整批，不能像單支失敗那樣繼續下一支。"""
    pass


def load_remote_config():
    """讀取遠端生成設定檔，沒有這個檔案、格式不對、或enabled不是true，都回傳None
    （代表維持原本Termux本機生成），確保這個功能是選配的、不會影響既有使用方式。"""
    if not os.path.isfile(REMOTE_CONFIG_PATH):
        return None
    try:
        with open(REMOTE_CONFIG_PATH, "r", encoding="utf-8") as f:
            config = json.load(f)
        if not config.get("enabled"):
            return None
        server_url = str(config.get("server_url", "")).rstrip("/")
        if not server_url:
            print("⚠ 遠端生成設定檔缺少server_url，改用本機生成")
            return None
        return {"server_url": server_url}
    except Exception as e:
        print(f"⚠ 讀取遠端生成設定檔失敗（{e}），改用本機生成")
        return None


def _read_account_from_meta(folder: str) -> str:
    """讀該商品資料夾meta.json裡的account欄位（App v1.023起擷取時會寫入），供傳給
    筆電服務做備份分類用。舊資料/讀取失敗都歸類成「未分類帳號」，不會讓程式出錯中斷。"""
    meta_path = os.path.join(folder, "meta.json")
    try:
        with open(meta_path, "r", encoding="utf-8") as f:
            meta = json.load(f)
        account = meta.get("account")
        if isinstance(account, str) and account.strip():
            return account.strip()
    except Exception:
        pass
    return "未分類帳號"


def _zip_folder(folder: str) -> bytes:
    """把商品資料夾整包壓成zip（在記憶體中組，不落地暫存檔案），直接沿用資料夾
    原本的內容（image_*.jpg、caption.txt、link.txt、meta.json等），對應筆電端
    main.py本來就設計成「整個資料夾zip起來丟過去」的介面，不用另外拆表單欄位。"""
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as zf:
        for root, _dirs, files in os.walk(folder):
            for name in files:
                full_path = os.path.join(root, name)
                arcname = os.path.relpath(full_path, folder)
                zf.write(full_path, arcname)
    buf.seek(0)
    return buf.read()


def process_folder_remote(folder: str, server_url: str, force: bool = False, timeout: int = 300) -> dict:
    """
    透過筆電端FastAPI服務生成影片，取代本機process_folder()。回傳格式刻意跟
    process_folder()一致：{"status": "ok"/"error", "message":..., "output_path":...}，
    讓run_batch()不用分辨這支是本機還是遠端生成的結果，呼叫端邏輯不用重寫。

    連線失敗（逾時/連不上/DNS解析失敗等網路層級問題）會丟出ServerUnreachableError，
    由run_batch()決定整批中止；跟「筆電有回應、但這支商品處理過程本身失敗」
    （伺服器回應非200）是不同情況，後者視同一般單支失敗，回傳status=error，
    批次繼續處理下一支——只有真的連不上才需要整批中止，不是每個失敗都要中止。
    """
    if requests is None:
        raise ServerUnreachableError("找不到 requests 套件，無法連線筆電服務（pip install requests）")

    name = os.path.basename(folder)

    # 跳過判斷比照process_folder()本機邏輯：不是只看output.mp4存不存在，還要驗證
    # 它是不是一支完整可播放的影片（容器結構＋內容解碼都正常），避免因為上次生成
    # 到一半被中斷留下的殘缺檔案被誤判成「已完成」而永遠不會重新生成。
    output_path = os.path.join(folder, "output.mp4")
    if os.path.isfile(output_path) and not force:
        if is_valid_video(output_path):
            return {"status": "skipped", "message": "output.mp4 已存在", "output_path": output_path}
        print(f"→ [{name}] 偵測到既有output.mp4無法通過驗證，視為未生成、重新透過筆電生成")

    account = _read_account_from_meta(folder)

    try:
        zip_bytes = _zip_folder(folder)
    except Exception as e:
        return {"status": "error", "message": f"打包資料夾失敗：{e.__class__.__name__} {e}", "output_path": None}

    try:
        resp = requests.post(
            f"{server_url}/generate-video",
            files={"product_zip": ("product.zip", zip_bytes, "application/zip")},
            data={"account": account, "folder_name": name},
            timeout=timeout,
        )
    except requests.exceptions.RequestException as e:
        raise ServerUnreachableError(f"連線筆電服務失敗（{server_url}）：{e.__class__.__name__} {e}")

    if resp.status_code != 200:
        try:
            detail = resp.json().get("message", resp.text[:200])
        except Exception:
            detail = resp.text[:200]
        return {"status": "error", "message": f"筆電服務回報生成失敗：{detail}", "output_path": None}

    output_path = os.path.join(folder, "output.mp4")
    try:
        with open(output_path, "wb") as f:
            f.write(resp.content)
    except Exception as e:
        return {"status": "error", "message": f"寫入影片檔案失敗：{e.__class__.__name__} {e}", "output_path": None}

    print(f"→ [{name}] 遠端生成成功（帳號：{account}）")
    return {"status": "ok", "message": "遠端生成成功", "output_path": output_path}


def backup_dedup_history(server_url: str) -> None:
    """批次結束後，把手機防重複資料庫同步備份一份到筆電。這份資料庫是跨帳號共用的
    單一檔案，不屬於某個特定帳號，所以不像商品資料那樣依帳號分類——備份失敗只印
    警告，不影響本次批次已經完成的結果（避免因為這個附加動作失敗就讓使用者誤以為
    整批都失敗了）。"""
    if requests is None or not os.path.isfile(DEDUP_HISTORY_PATH):
        return
    try:
        with open(DEDUP_HISTORY_PATH, "rb") as f:
            resp = requests.post(
                f"{server_url}/backup-dedup",
                files={"history_file": ("captured_history.jsonl", f, "application/octet-stream")},
                timeout=60,
            )
        if resp.status_code == 200:
            print("→ 防重複資料庫已同步備份到筆電")
        else:
            print(f"⚠ 防重複資料庫備份失敗（筆電回應狀態碼{resp.status_code}），不影響本次批次結果")
    except Exception as e:
        print(f"⚠ 防重複資料庫備份失敗（{e.__class__.__name__} {e}），不影響本次批次結果")

LOCK_FILE_NAME = ".batch_running.lock"


def _pid_is_alive(pid: int) -> bool:
    """檢查指定PID的行程是不是還活著。用os.kill(pid, 0)不會真的送訊號，只是問系統
    這個PID還在不在，行程不存在會丟ProcessLookupError；PermissionError代表PID存在
    但不是我們能操作的行程（例如系統行程剛好重用了這個PID），一樣視為「還活著」，
    保守起見不要誤判成已死掉而讓兩批同時跑。"""
    try:
        os.kill(pid, 0)
        return True
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    except Exception:
        return True


def acquire_batch_lock(root: str) -> str:
    """
    防止同一個CaptionQueue根目錄被兩個batch_generate.py行程同時處理，導致兩邊
    的ffmpeg搶著寫同一個output.mp4路徑、互相覆蓋讓檔案整個消失的問題。
    做法：在root底下放一個記錄PID的鎖檔案，啟動時檢查：
      - 沒有鎖檔案 → 直接建立，正常啟動
      - 有鎖檔案但裡面的PID已經不存在（上次可能異常中斷、沒清乾淨）→ 視為過期鎖，
        清掉重建，不會因為殘留的鎖檔案卡死之後所有批次
      - 有鎖檔案且PID還活著 → 代表真的有另一批在跑，直接印錯誤訊息並結束，
        不繼續往下執行
    回傳鎖檔案完整路徑，供main()結束時release_batch_lock()釋放用。
    """
    lock_path = os.path.join(root, LOCK_FILE_NAME)
    if os.path.isfile(lock_path):
        try:
            existing_pid = int(open(lock_path, "r").read().strip())
        except (ValueError, OSError):
            existing_pid = None

        if existing_pid is not None and _pid_is_alive(existing_pid):
            print(f"錯誤：偵測到另一個批次生成行程正在執行中（PID {existing_pid}）。")
            print("同一批商品資料夾不能同時被兩個批次處理，否則會互相搶著寫同一支")
            print("output.mp4，導致影片檔案損毀或消失。請等目前那個批次跑完，")
            print("或確認它已經不在執行後再重新啟動。")
            sys.exit(1)
        else:
            print(f"→ 偵測到過期的批次鎖檔案（PID {existing_pid} 已不存在），清除後繼續")

    with open(lock_path, "w") as f:
        f.write(str(os.getpid()))
    return lock_path


def release_batch_lock(lock_path: str) -> None:
    try:
        if os.path.isfile(lock_path):
            os.remove(lock_path)
    except OSError as e:
        print(f"⚠ 清除批次鎖檔案失敗（不影響本次結果，下次啟動會自動判定為過期鎖清除）：{e}")


def write_progress(root: str, total: int, completed: int, current_name: str,
                    status: str, ok_count: int, skipped_count: int, error_count: int,
                    ok_names: list = None, skipped_names: list = None,
                    error_items: list = None, elapsed_seconds: float = None,
                    step: str = "") -> None:
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

    【2026-08-30新增】step：目前這支影片處理到哪個子步驟（例如「文案生成中」
    「影片運鏡生成中」），由make_video.py透過callback即時回報，讓App畫面不再只顯示
    「生成中」，能看到實際卡在哪一步——尤其方便判斷是卡在需要網路的AI文案/語音合成，
    還是本機運算的影片編碼。
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
        "step": step,
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

    lock_path = acquire_batch_lock(root)
    acquire_wake_lock()
    try:
        run_batch(root, folders, force)
    finally:
        release_wake_lock()
        release_batch_lock(lock_path)


def run_batch(root: str, folders: list, force: bool) -> None:
    ok_list = []
    skipped_list = []
    error_list = []
    start_time = time.time()
    total = len(folders)
    stopped = False
    laptop_unreachable = False

    remote_config = load_remote_config()
    if remote_config:
        print(f"→ 遠端生成模式已啟用（{remote_config['server_url']}）")

    write_progress(root, total, 0, "", "running", 0, 0, 0)

    for idx, folder in enumerate(folders, 1):
        name = os.path.basename(folder)
        print(f"\n[{idx}/{len(folders)}] {name}")
        print("-" * 60)
        write_progress(root, total, idx - 1, name, "running",
                        len(ok_list), len(skipped_list), len(error_list))

        # 【2026-08-30新增】即時回報子步驟：process_folder內部在關鍵階段（文案生成、
        # 影片編碼）會呼叫這個callback，這裡收到就立刻重寫一次進度檔案，讓App畫面
        # 顯示的不只是「第幾支/共幾支」，還能看到目前這支卡在哪個步驟。
        def report_step(step_text: str, _name=name, _idx=idx) -> None:
            write_progress(root, total, _idx - 1, _name, "running",
                            len(ok_list), len(skipped_list), len(error_list),
                            step=step_text)

        try:
            if remote_config:
                report_step("上傳給筆電生成中")
                result = process_folder_remote(folder, remote_config["server_url"], force=force)
            else:
                result = process_folder(folder, force=force, step_callback=report_step)
        except ServerUnreachableError as e:
            # 連不上筆電代表接下來全部商品都會失敗，不是單一商品的問題，
            # 立刻中止整批，不繼續浪費時間跑完剩下的商品。
            print(f"✗ 連不上筆電服務，中止整批：{e}")
            write_progress(root, total, idx - 1, name, "error_laptop_unreachable",
                            len(ok_list), len(skipped_list), len(error_list),
                            ok_names=ok_list, skipped_names=skipped_list,
                            error_items=[[n, m.strip().splitlines()[-1] if m.strip() else "（無錯誤訊息）"] for n, m in error_list],
                            elapsed_seconds=time.time() - start_time)
            laptop_unreachable = True
            break
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

    if not stopped and not laptop_unreachable:
        write_progress(root, total, total, "", "done",
                        len(ok_list), len(skipped_list), len(error_list),
                        ok_names=ok_list, skipped_names=skipped_list,
                        error_items=[[n, m.strip().splitlines()[-1] if m.strip() else "（無錯誤訊息）"] for n, m in error_list],
                        elapsed_seconds=time.time() - start_time)

    # 批次結束（不管成功/停止/筆電斷線中止）都嘗試同步備份防重複資料庫，這個動作
    # 跟本批次的商品生成結果無關，即使本批次失敗大半，已經處理成功的擷取紀錄
    # 還是值得備份，失敗只印警告不影響上面已經寫好的批次結果。
    if remote_config:
        backup_dedup_history(remote_config["server_url"])

    elapsed = time.time() - start_time
    print("\n" + "=" * 60)
    if laptop_unreachable:
        print(f"批次因連不上筆電服務而中止，共花費 {elapsed / 60:.1f} 分鐘")
    elif stopped:
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
