#!/usr/bin/env python3
"""扫描选项按钮真实 Y 坐标范围 (logcat + 同步确认 + 1px步进)"""
import subprocess
import time
import re

DEVICE = "b054d001"

def adb(cmd, timeout=15):
    return subprocess.run(f'adb -s {DEVICE} {cmd}', shell=True, capture_output=True, text=True, timeout=timeout)

def tap(x, y):
    adb(f'shell input tap {x} {y}')

def flush_logcat():
    subprocess.run(f'adb -s {DEVICE} logcat -c', shell=True)

def wait_for_scan(timeout=3):
    """等待 logcat 中出现 SCAN 日志，返回 idx 或 None"""
    start = time.time()
    while time.time() - start < timeout:
        r = subprocess.run(
            f'adb -s {DEVICE} logcat -d -s SCAN:*, *:S',
            shell=True, capture_output=True, text=True, timeout=5
        )
        for l in reversed(r.stdout.split('\n')):
            m = re.search(r'onAnswer idx=(\d+)', l)
            if m:
                return int(m.group(1))
        time.sleep(0.1)
    return None

def tap_and_wait(y):
    """tap 并等待 logcat 确认，返回 (idx) 或 None"""
    flush_logcat()
    time.sleep(0.05)
    tap(540, y)
    return wait_for_scan(timeout=3)

def main():
    print("=" * 60)
    print("扫描选项按钮 Y 坐标范围 (logcat + 同步确认 + 1px)")
    print("=" * 60)

    # 1. 唤醒
    adb('shell input keyevent 26')
    time.sleep(0.5)

    # 2. 卸载 + 安装
    adb('shell pm uninstall com.wordbattle')
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

    # 10. 阶段1: 1px 扫描，从 Y=700 开始向下，找到4个按钮的首次命中
    print(f"\n阶段1: 1px步进找首次命中...")
    first_hits = {}  # idx -> first_y
    y = 700
    last_idx = None
    for y in range(700, 1351):
        idx = tap_and_wait(y)
        if idx is not None:
            if last_idx is None or idx != last_idx:
                # 首次看到新 idx
                first_hits[idx] = y
                last_idx = idx
                print(f"  选项{idx} 首次命中 Y={y}")
            # 同一 idx 继续往下找
        # 如果连续 50px 没命中任何按钮，且已找到4个，提前停止
        if len(first_hits) == 4 and y > 1250:
            break

    if len(first_hits) < 4:
        print(f"警告: 只找到 {len(first_hits)} 个按钮")

    # 11. 阶段2: 对每个按钮，1px 扫上下边界
    print(f"\n阶段2: 1px步进扫每个按钮的完整范围...")
    all_results = []  # (y, idx_or_None)
    button_ranges = {}

    for idx in sorted(first_hits):
        start_y = first_hits[idx]
        print(f"\n  扫描选项{idx} (起始Y={start_y}):")

        # 向上扫找上边界
        upper = start_y
        for y in range(start_y - 1, 699, -1):
            result = tap_and_wait(y)
            status = f"idx={result}" if result is not None else "未命中"
            if result == idx:
                upper = y
            else:
                break

        # 向下扫找下边界
        lower = start_y
        for y in range(start_y + 1, 1351):
            result = tap_and_wait(y)
            status = f"idx={result}" if result is not None else "未命中"
            if result == idx:
                lower = y
            else:
                break

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
