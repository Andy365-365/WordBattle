#!/usr/bin/env python3
"""扫描选项按钮 Y 坐标范围 (HTTP 查询 + 同步确认 + 1px步进 + bgTap)"""
import subprocess
import time
import re
import os
import urllib.request
import json

DEVICE = "b054d001"
LOG_RECEIVER = "http://127.0.0.1:8765"

def adb(cmd, timeout=15):
    return subprocess.run(f'adb -s {DEVICE} {cmd}', shell=True, capture_output=True, text=True, timeout=timeout)

def tap(x, y):
    adb(f'shell input tap {x} {y}')

def query_scan_latest():
    """Query log_receiver for latest SCAN line. Returns (count, line_text) or (count, '')."""
    try:
        with urllib.request.urlopen(f'{LOG_RECEIVER}/scan/latest', timeout=5) as resp:
            data = json.loads(resp.read().decode())
            return data['count'], data['latest']
    except Exception as e:
        return -1, ''

def tap_and_wait(y, prev_count):
    """tap and wait for new SCAN line via HTTP query. Returns idx(int) or 'bg' or None(timeout)"""
    tap(540, y)
    start = time.time()
    while time.time() - start < 3:
        count, latest = query_scan_latest()
        if count > prev_count:
            m = re.search(r'\[SCAN\]\s+onAnswer\s+idx=(\d+)', latest)
            if m:
                return int(m.group(1))
            m = re.search(r'\[SCAN\]\s+bgTap\s+y=(\d+)', latest)
            if m:
                return 'bg'
        time.sleep(0.05)
    return None

def check_app_foreground():
    """Check if WordBattle is still in foreground."""
    r = adb('shell dumpsys activity activities | grep "mResumedActivity"', timeout=10)
    return 'com.wordbattle' in r.stdout.lower()

def restore_app_foreground():
    """Restore WordBattle to foreground."""
    print("  [WARN] App not in foreground, restoring...")
    adb('shell am start -n com.wordbattle/.MainActivity')
    time.sleep(3)
    adb('shell input keyevent KEYCODE_HOME')
    time.sleep(0.5)
    adb('shell monkey -p com.wordbattle -c android.intent.category.LAUNCHER 1')
    time.sleep(2)
    ok = check_app_foreground()
    print(f"  [INFO] Foreground restored: {ok}")
    return ok

def main():
    print("=" * 60)
    print("扫描选项按钮 Y 坐标范围 (HTTP + 同步确认 + 1px + bgTap)")
    print("=" * 60)

    # 0. Reset log_receiver scan counter
    print("重置 log_receiver SCAN 计数器...")
    try:
        # Kill and restart to reset counter
        os.system('pkill -f "python3 /data/wordbattle/scripts/log_receiver.py"')
        time.sleep(1)
        os.system('nohup python3 /data/wordbattle/scripts/log_receiver.py 8765 > /dev/null 2>&1 &')
        time.sleep(2)
        count, _ = query_scan_latest()
        print(f"  log_receiver count: {count}")
    except Exception as e:
        print(f"  [WARN] log_receiver reset failed: {e}")

    # 1. Wake screen
    print("检查屏幕状态...")
    r = adb('shell dumpsys power | grep "mWakefulness"')
    if 'Asleep' in r.stdout:
        print("  屏幕已息屏，点亮...")
        adb('shell input keyevent 26')
        time.sleep(1)
    else:
        print("  屏幕已亮")

    # 2. force-stop + install
    print("安装 APK...")
    adb('shell am force-stop com.wordbattle')
    time.sleep(0.5)
    adb('shell input keyevent KEYCODE_HOME')
    time.sleep(1)
    r = subprocess.run('python3 /data/wordbattle/scripts/auto_install.py', shell=True, capture_output=True, text=True, timeout=30)
    if 'SUCCESS' not in r.stdout:
        print(f"安装失败: {r.stdout}")
        return
    print("  安装成功")
    time.sleep(1)

    # 3. Start app
    print("启动 App...")
    adb('shell am start -n com.wordbattle/.MainActivity')
    time.sleep(3)

    # 4. Navigate to game
    print("进入答题界面...")
    tap(540, 1267)   # Host mode
    time.sleep(2)
    tap(776, 1127)   # 30 questions
    time.sleep(0.5)
    tap(540, 1978)   # Start waiting
    time.sleep(3)
    tap(540, 1256)   # Start game
    time.sleep(5)

    # 5. Wait for ANSWERING state (using uiautomator dump)
    print("等待答题界面...")
    found = False
    for i in range(15):
        try:
            adb('shell uiautomator dump /sdcard/ui.xml')
            adb('pull /sdcard/ui.xml /tmp/ui.xml')
            with open('/tmp/ui.xml') as f:
                xml = f.read()
            if 'ANSWERING' in xml:
                print(f"  答题界面就绪 (尝试 {i+1})")
                found = True
                break
        except:
            pass
        time.sleep(1)
    if not found:
        print("  超时未进入答题界面")
        return

    # 6. Phase 1: 1px scan, Y=0~2400
    print(f"\n阶段1: 1px步进找首次命中 (Y=0~2400)...")
    first_hits = {}
    last_result = None
    prev_count, _ = query_scan_latest()

    for y in range(0, 2401):
        # Every 50 taps, use swipe instead of tap to refresh MIUI user-activity timer
        if y > 0 and y % 50 == 0:
            adb(f'shell input swipe 540 {y} 540 {y} 1')
            print(f"  [refresh] Y={y}")
            result = tap_and_wait(y, prev_count)
            if result is not None:
                if isinstance(result, int):
                    if last_result is None or (isinstance(last_result, int) and result != last_result):
                        first_hits[result] = y
                        print(f"  选项{result} 首次命中 Y={y}")
                    last_result = result
                else:
                    last_result = 'bg'
            else:
                last_result = None
                print(f"  Y={y} 超时(未命中)")
            prev_count = query_scan_latest()[0]
            continue

        # Also check foreground every 200 taps as fallback
        if y > 0 and y % 200 == 0 and not check_app_foreground():
            print(f"  [WARN] Y={y}: App not in foreground, restoring...")
            restore_app_foreground()

        result = tap_and_wait(y, prev_count)
        if result is not None:
            if isinstance(result, int):
                if last_result is None or (isinstance(last_result, int) and result != last_result):
                    first_hits[result] = y
                    print(f"  选项{result} 首次命中 Y={y}")
                last_result = result
            else:
                last_result = 'bg'
        else:
            if y < 50 or y > 1800:
                print(f"  Y={y} 超时(未命中)")
            last_result = None

        prev_count = query_scan_latest()[0]

        if y % 200 == 0 and y > 0:
            print(f"  [进度] Y={y}, 已找到{len(first_hits)}个按钮")

        if len(first_hits) == 4 and y > 1800:
            print(f"  已找到全部4个按钮，Y={y}，提前停止")
            break

    if len(first_hits) < 4:
        print(f"警告: 只找到 {len(first_hits)} 个按钮")

    # 7. Phase 2: boundary scan for each button
    print(f"\n阶段2: 1px步进扫每个按钮的完整范围...")
    button_ranges = {}

    for idx in sorted(first_hits):
        start_y = first_hits[idx]
        print(f"\n  扫描选项{idx} (起始Y={start_y}):")

        # Upper boundary
        upper = start_y
        for y in range(start_y - 1, -1, -1):
            result = tap_and_wait(y, prev_count)
            if result == idx:
                upper = y
            else:
                break

        # Lower boundary
        lower = start_y
        for y in range(start_y + 1, 2401):
            result = tap_and_wait(y, prev_count)
            if result == idx:
                lower = y
            else:
                break

        prev_count = query_scan_latest()[0]
        button_ranges[idx] = (upper, lower)
        print(f"  选项{idx}: Y=[{upper} ~ {lower}] 共{lower-upper+1}px")

    # 8. Summary
    print(f"\n{'='*60}")
    print(f"扫描结果:")
    print(f"{'='*60}")

    for idx in sorted(button_ranges):
        upper, lower = button_ranges[idx]
        center = (upper + lower) // 2
        print(f"  选项{idx}: Y=[{upper} ~ {lower}] 共{lower-upper+1}px 中心={center}")

    print(f"\n建议 tap 坐标 (X=540):")
    centers = []
    for idx in sorted(button_ranges):
        upper, lower = button_ranges[idx]
        cy = (upper + lower) // 2
        centers.append((540, cy))
        print(f"  选项{idx}: (540, {cy})")

    print(f"\n全部: {centers}")

    # 9. Cleanup: turn off screen
    adb('shell am force-stop com.wordbattle')
    r = adb('shell dumpsys power | grep "mWakefulness"')
    if 'Awake' in r.stdout:
        print("关闭屏幕...")
        adb('shell input keyevent 26')
    print("完成")

if __name__ == '__main__':
    main()
