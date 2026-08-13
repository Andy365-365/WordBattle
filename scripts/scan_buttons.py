#!/usr/bin/env python3
"""扫描选项按钮真实 Y 坐标范围 (读远程日志 + 同步确认 + 1px步进 + bgTap)"""
import subprocess
import time
import re
import os

DEVICE = "b054d001"

LOG_FILE = "/data/wordbattle/logs/remote_all_20260813.log"

def adb(cmd, timeout=15):
    return subprocess.run(f'adb -s {DEVICE} {cmd}', shell=True, capture_output=True, text=True, timeout=timeout)

def tap(x, y):
    adb(f'shell input tap {x} {y}')

def get_scan_lines():
    """读远程日志文件，获取 [SCAN] 行"""
    if not os.path.exists(LOG_FILE):
        return []
    with open(LOG_FILE, 'r') as f:
        return [l.strip() for l in f if '[SCAN]' in l]

def tap_and_wait(y, prev_count=0):
    """tap 并等待远程日志中出现新 SCAN 行。返回 idx(int) 或 'bg'(背景) 或 None(超时)"""
    tap(540, y)
    start = time.time()
    while time.time() - start < 3:
        lines = get_scan_lines()
        if len(lines) > prev_count:
            # 有新日志，取最后一条
            last = lines[-1]
            m = re.search(r'\[SCAN\]\s+onAnswer\s+idx=(\d+)', last)
            if m:
                return int(m.group(1))
            m = re.search(r'\[SCAN\]\s+bgTap\s+y=(\d+)', last)
            if m:
                return 'bg'
        time.sleep(0.05)
    return None

def main():
    print("=" * 60)
    print("扫描选项按钮 Y 坐标范围 (远程日志 + 同步确认 + 1px + bgTap)")
    print("=" * 60)

    # 1. 唤醒
    adb('shell input keyevent 26')
    time.sleep(0.5)

    # 2. force-stop 旧版 + 覆盖安装（pm uninstall 在 MIUI 会失败，直接 install -r 覆盖）
    adb('shell am force-stop com.wordbattle')
    time.sleep(0.5)
    adb('shell input keyevent KEYCODE_HOME')
    time.sleep(1)
    r = subprocess.run('python3 /data/wordbattle/scripts/auto_install.py', shell=True, capture_output=True, text=True, timeout=30)
    if 'SUCCESS' not in r.stdout:
        print(f"安装失败: {r.stdout}")
        return
    time.sleep(1)

    # 3. 启动
    adb('shell am start -n com.wordbattle/.MainActivity')
    time.sleep(3)

    # 4. 主机+答题
    tap(540, 1267)
    time.sleep(2)

    # 5. 30题
    tap(776, 1127)
    time.sleep(0.5)

    # 6. 默认3600秒等待时间
    time.sleep(0.5)

    # 7. 开始等待玩家
    tap(540, 1978)
    time.sleep(3)

    # 8. 开始游戏
    tap(540, 1256)
    time.sleep(5)

    # 9. 等待 ANSWERING 状态
    print("等待答题界面...")
    for _ in range(15):
        adb('shell uiautomator dump /sdcard/ui.xml')
        adb('pull /sdcard/ui.xml /tmp/ui.xml')
        with open('/tmp/ui.xml') as f:
            xml = f.read()
        if 'ANSWERING' in xml:
            print("答题界面就绪")
            break
        time.sleep(1)
    else:
        print("超时未进入答题界面")
        return

    # 10. 阶段1: 1px 扫描，Y=0~2400，找到4个按钮的首次命中
    print(f"\n阶段1: 1px步进找首次命中 (Y=0~2400)...")
    first_hits = {}
    last_result = None
    prev_count = len(get_scan_lines())

    for y in range(0, 2401):
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
            print(f"  Y={y} 超时(未命中)")
            last_result = None

        # 更新 prev_count
        prev_count = len(get_scan_lines())

        # 打印进度
        if y % 200 == 0 and y > 0:
            print(f"  [进度] Y={y}, 已找到{len(first_hits)}个按钮")

        if len(first_hits) == 4 and y > 1800:
            print(f"  已找到全部4个按钮，Y={y}，提前停止")
            break

    if len(first_hits) < 4:
        print(f"警告: 只找到 {len(first_hits)} 个按钮")

    # 11. 阶段2: 对每个按钮，1px 扫上下边界
    print(f"\n阶段2: 1px步进扫每个按钮的完整范围...")
    button_ranges = {}

    for idx in sorted(first_hits):
        start_y = first_hits[idx]
        print(f"\n  扫描选项{idx} (起始Y={start_y}):")

        # 向上扫找上边界
        upper = start_y
        for y in range(start_y - 1, -1, -1):
            result = tap_and_wait(y, prev_count)
            if result == idx:
                upper = y
            else:
                break

        # 向下扫找下边界
        lower = start_y
        for y in range(start_y + 1, 2401):
            result = tap_and_wait(y, prev_count)
            if result == idx:
                lower = y
            else:
                break

        prev_count = len(get_scan_lines())
        button_ranges[idx] = (upper, lower)
        print(f"  选项{idx}: Y=[{upper} ~ {lower}] 共{lower-upper+1}px")

    # 输出汇总
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

    adb('shell am force-stop com.wordbattle')
    adb('shell input keyevent 26')
    print("完成")

if __name__ == '__main__':
    main()
