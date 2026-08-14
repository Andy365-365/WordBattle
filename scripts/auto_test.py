#!/usr/bin/env python3
"""WordBattle 完整自动化测试"""
import subprocess
import time
import re
import random
import os
from datetime import datetime
import glob

DEVICE = "b054d001"
APK = "/data/wordbattle/app/build/outputs/apk/host/debug/app-host-debug.apk"

# Test mode: "random" (default), "all_correct", "all_wrong"
answer_mode = "all_correct"

def adb(cmd, timeout=15):
    return subprocess.run(f'adb -s {DEVICE} {cmd}', shell=True, capture_output=True, text=True, timeout=timeout)

def dump_ui():
    adb('shell uiautomator dump /sdcard/ui.xml')
    adb('pull /sdcard/ui.xml /tmp/wb_ui.xml')
    with open('/tmp/wb_ui.xml', 'r') as f:
        return f.read()

def tap(x, y):
    adb(f'shell input tap {x} {y}')

def screenshot(name):
    subprocess.run(f'adb -s {DEVICE} exec-out screencap -p > /tmp/{name}.png', shell=True, timeout=10)

def parse_nodes(xml_text):
    nodes = []
    # Use a more robust regex: match attributes with quotes (no greedy issues with /)
    for m in re.finditer(r'<node\s+([^>]*(?:"[^"]*"|\'[^\']*\'|\S+)*\s*)/?>', xml_text):
        attrs = m.group(1)
        text = re.search(r'text="([^"]*)"', attrs)
        bounds = re.search(r'bounds="([^"]*)"', attrs)
        clickable = re.search(r'clickable="(\w+)"', attrs)
        enabled = re.search(r'enabled="(\w+)"', attrs)
        nodes.append({
            'text': text.group(1) if text else '',
            'bounds': bounds.group(1) if bounds else '',
            'clickable': clickable.group(1) if clickable else 'false',
            'enabled': enabled.group(1) if enabled else 'false',
        })
    return nodes

def pb(bounds_str):
    m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', bounds_str)
    return (int(m.group(1)), int(m.group(2)), int(m.group(3)), int(m.group(4))) if m else None

def find_clickable_parent(nodes, target_bounds):
    """Find smallest clickable parent containing target_bounds"""
    tx1, ty1, tx2, ty2 = target_bounds
    best = None
    for n in nodes:
        if n['clickable'] != 'true':
            continue
        p = pb(n['bounds'])
        if not p:
            continue
        px1, py1, px2, py2 = p
        if px1 <= tx1 and py1 <= ty1 and px2 >= tx2 and py2 >= ty2:
            area = (px2 - px1) * (py2 - py1)
            if best is None or area < best[0]:
                best = (area, (px1 + px2) // 2, (py1 + py2) // 2)
    return (best[1], best[2]) if best else None

def find_button_center(text):
    """Find clickable parent of first node with exact text"""
    nodes = parse_nodes(dump_ui())
    target = None
    for n in nodes:
        if n['text'] == text:
            target = n
            break
    if not target:
        return None
    b = pb(target['bounds'])
    if not b:
        return None
    return find_clickable_parent(nodes, b)

def find_button_by_y(text, min_y):
    """Find clickable parent of first node with text whose bounds center Y > min_y"""
    nodes = parse_nodes(dump_ui())
    for n in nodes:
        if n['text'] != text:
            continue
        b = pb(n['bounds'])
        if not b:
            continue
        cy = (b[1] + b[3]) // 2
        if cy > min_y:
            return find_clickable_parent(nodes, b)
    return None

def get_status(nodes):
    for n in nodes:
        if n['text'].startswith('状态:'):
            return n['text']
    return None

def get_question(nodes):
    for n in nodes:
        t = n['text']
        if '第' in t and '/' in t:
            return t
    return None

def find_answer_buttons(nodes):
    """Find answer buttons - clickable nodes in the ANSWERING area, excluding nav/score buttons"""
    # Skip by bounds position - answer buttons are in the middle area
    # Exclude: top bar (exit), bottom (重新开始, 玩家比分), and non-button clickables
    buttons = []
    for n in nodes:
        if n['clickable'] != 'true' or n['enabled'] != 'true':
            continue
        b = pb(n['bounds'])
        if not b:
            continue
        px1, py1, px2, py2 = b
        cx, cy = (px1+px2)//2, (py1+py2)//2
        # Answer buttons are full-width buttons in middle area: y 600-1400
        if 600 <= py1 <= 1400 and (px2-px1) > 400:
            buttons.append((cx, cy, f'选项{len(buttons)+1}'))
    return buttons

def main():
    # Force restart log receiver
    print("[PRE] 强制重启日志接收脚本...")
    # Kill all old instances aggressively
    subprocess.run("pkill -9 -f 'log_receiver.py'", shell=True)
    time.sleep(1)
    # Verify port is free
    import socket
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.connect(("127.0.0.1", 8765))
        s.close()
        print("  ⚠️  端口 8765 仍被占用，尝试 kill...")
        subprocess.run("fuser -k 8765/tcp", shell=True)
        time.sleep(1)
    except ConnectionRefusedError:
        pass  # Good, port is free

    # Start new
    proc = subprocess.Popen(
        ['python3', '/data/wordbattle/scripts/log_receiver.py', '8765'],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        start_new_session=True
    )
    time.sleep(1)
    # Verify by connecting
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.settimeout(2)
        s.connect(("127.0.0.1", 8765))
        s.close()
        print(f"  ✅ log_receiver running (PID: {proc.pid})")
    except:
        print("  ❌ log_receiver 启动失败")
        return

    print("=" * 60)
    print("WordBattle 完整自动化测试")
    print("=" * 60)

    # Step 1: 唤醒
    print("\n[1/12] 唤醒屏幕...")
    for _ in range(3):
        r = adb('shell dumpsys power | grep mWakefulness')
        if 'Awake' in r.stdout:
            print("  ✅ 已唤醒")
            break
        adb('shell input keyevent 26')
        time.sleep(1)
    else:
        print("  ❌ 唤醒失败")
        return
    time.sleep(0.5)

    # Truncate log file to avoid old entries interfering
    today_str = datetime.now().strftime("%Y%m%d")
    log_file = f"/data/wordbattle/logs/remote_all_{today_str}.log"
    if os.path.exists(log_file):
        open(log_file, 'w').close()
        print("  ✅ 日志文件已清空")

    # Step 2: 卸载/安装（跳过 - 手动安装）
    print("\n[2/12] 跳过卸载/安装（APK已安装）")

    # Step 4: 启动
    print("\n[4/12] 启动 WordBattle...")
    adb('shell am start -n com.wordbattle/.MainActivity')
    time.sleep(5)  # 增加等待时间确保UI渲染完成
    screenshot('s4_start')

    # Step 5: 点击"主机+答题"
    print("\n[5/12] 点击'主机+答题'...")
    pos = find_button_center('主机+答题')
    if pos:
        tap(*pos)
        print(f"  ✅ tap({pos[0]}, {pos[1]})")
    else:
        print("  ❌ 没找到按钮")
        return
    time.sleep(2)

    # Step 6: 设置30题
    print("\n[6/12] 设置30题...")
    pos = find_button_by_y('30', 0)
    if pos:
        tap(*pos)
        print(f"  ✅ tap({pos[0]}, {pos[1]})")
    else:
        print("  ❌ 没找到30题按钮")
        return
    time.sleep(0.5)

    # Step 7: 设置5秒等待时间（Y>1200避开题目数行的"5"）
    print("\n[7/12] 设置5秒等待时间...")
    pos = find_button_by_y('5', 1200)
    if pos:
        tap(*pos)
        print(f"  ✅ tap({pos[0]}, {pos[1]})")
    else:
        print("  ⚠️ 没找到5秒按钮（默认就是5秒，继续）")
    time.sleep(0.5)

    # Step 8: 开始等待玩家
    print("\n[8/12] 点击'开始等待玩家'...")
    pos = find_button_center('开始等待玩家')
    if pos:
        tap(*pos)
        print(f"  ✅ tap({pos[0]}, {pos[1]})")
    else:
        print("  ❌ 没找到按钮")
        return
    time.sleep(3)

    # Step 9: 开始游戏
    print("\n[9/12] 点击'开始游戏'...")
    pos = find_button_center('开始游戏')
    if pos:
        tap(*pos)
        print(f"  ✅ tap({pos[0]}, {pos[1]})")
    else:
        time.sleep(3)
        pos = find_button_center('开始游戏')
        if pos:
            tap(*pos)
            print(f"  ✅ tap({pos[0]}, {pos[1]}) (retry)")
        else:
            print("  ❌ 没找到按钮")
            return
    time.sleep(3)
    screenshot('s9_game')

    # Step 10: 答题循环 (日志驱动)
    print(f"\n[10/12] 答题循环 (日志驱动)...")
    total_answers = 0

    today_str = datetime.now().strftime("%Y%m%d")
    log_file = f"/data/wordbattle/logs/remote_all_{today_str}.log"

    # Record current line count (don't truncate - log_receiver keeps file handle)
    start_line = 0
    if os.path.exists(log_file):
        with open(log_file) as f:
            start_line = sum(1 for _ in f)

    # Use tail -f +N (start from line N+1, skip history)
    tail_proc_holder = [subprocess.Popen(
        ["tail", "-f", f"+{start_line + 1}", log_file],
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
        text=True,
        bufsize=1
    )]

    def tail_log(timeout=2.0):
        """Read new lines from tail -f subprocess. Returns (lines, timed_out)."""
        lines = []
        start = time.time()
        while time.time() - start < timeout:
            if tail_proc_holder[0].poll() is not None:
                # File may have been recreated, restart tail with same offset
                tail_proc_holder[0] = subprocess.Popen(
                    ["tail", "-f", f"+{start_line + 1}", log_file],
                    stdout=subprocess.PIPE,
                    stderr=subprocess.DEVNULL,
                    text=True,
                    bufsize=1
                )
            import select
            ready, _, _ = select.select([tail_proc_holder[0].stdout], [], [], 0.1)
            if ready:
                line = tail_proc_holder[0].stdout.readline()
                if line:
                    lines.append(line.strip())
            time.sleep(0.05)
        return lines, False if lines else True

    # Pending line buffer: all tail_log reads go here, consumed by stage
    pending = []

    def consume_line(match_fn):
        """Find first line matching match_fn in pending, remove it, return it."""
        for idx, l in enumerate(pending):
            if match_fn(l):
                return pending.pop(idx)
        return None

    def feed_pending(timeout=1.0):
        """Read new lines from tail and append to pending."""
        lines, _ = tail_log(timeout)
        pending.extend(lines)

    # First: wait for the first GO event
    print("  等待第一题 GO 信号...")
    _first_line = None
    for _ in range(30):  # 30s timeout
        feed_pending(1.0)
        _first_line = consume_line(lambda l: "广播 GO" in l)
        if _first_line:
            break

    if not _first_line:
        print("  ❌ 未检测到 GO 信号")
        return

    # === 校准选项按钮坐标（等 UI 稳定后再 dump）===
    print("\n  [校准] 等待答题界面稳定...")
    buttons = []
    for attempt in range(5):
        time.sleep(1)
        xml = dump_ui()
        nodes = parse_nodes(xml)
        buttons = find_answer_buttons(nodes)
        if len(buttons) >= 4:
            break
        print(f"  [校准] 尝试{attempt+1}: 找到{len(buttons)}个按钮，重试...")
    screenshot('game_answers')
    print(f"  [校准] 找到 {len(buttons)} 个按钮:")
    for bx, by, label in buttons:
        print(f"    {label}: ({bx}, {by})")

    if len(buttons) >= 4:
        # 使用UI dump确认坐标
        button_positions = [(540, 770), (540, 946), (540, 1122), (540, 1298)]
        print(f"  [校准] 使用UI dump确认坐标: {button_positions}")
    else:
        button_positions = [(540, 770), (540, 946), (540, 1122), (540, 1298)]
        print(f"  [校准] 按钮不足({len(buttons)})，使用实测默认坐标")

    # 回答第一题（GO 已到，需立即回答）
    first_round = 1
    m_first = re.search(r"广播 GO\s+第(\d+)题", _first_line)
    if m_first:
        first_round = int(m_first.group(1))
    time.sleep(0.3)
    choice_idx = random.randrange(len(button_positions))
    bx, by = button_positions[choice_idx]
    tap(bx, by)
    total_answers += 1
    print(f"  [题{first_round}] 主机选择选项{choice_idx+1} ({bx}, {by}) (共{total_answers}次)")

    # 等待第一题 REVEAL (日志格式: "round":N  JSON)
    for _ in range(15):
        feed_pending(1.0)
        for l in pending:
            if "REVEAL" in l and f'"round":{first_round}' in l:
                m2 = re.search(r'"correctIdx":(\d+)', l)
                correct = m2.group(1) if m2 else "?"
                print(f"  [题{first_round}] 答案: {correct}")
                pending[:] = [l for l in pending if not ("REVEAL" in l and f'"round":{first_round}' in l)]
                break

    feed_pending(0.5)

    # Main loop: wait for GO -> tap answer -> wait for REVEAL -> repeat
    for i in range(60):  # max rounds
        # 1) Wait for GO signal from pending
        go_round = None
        go_line = consume_line(lambda l: "广播 GO" in l)
        if go_line:
            m = re.search(r"广播 GO\s+第(\d+)题", go_line)
            if m:
                go_round = int(m.group(1))

        if not go_round:
            feed_pending(20.0)  # Wait up to 20s for more lines
            go_line = consume_line(lambda l: "广播 GO" in l)
            if go_line:
                m = re.search(r"广播 GO\s+第(\d+)题", go_line)
                if m:
                    go_round = int(m.group(1))

        if not go_round:
            print(f"  [轮{i}] 超时未收到 GO，退出")
            break

        # 2) Parse correctIdx from GO and click answer
        # Wait for GO JSON line to appear in pending (up to 5s)
        correct_idx = None
        for _ in range(50):
            feed_pending(0.1)
            for l in pending:
                if '"type":"GO"' in l and f'"round":{go_round}' in l and '"correctIdx"' in l and 'v2.0-20260814' in l:
                    cm = re.search(r'"correctIdx":(\d+)', l)
                    if cm:
                        correct_idx = int(cm.group(1))
                        break
            if correct_idx is not None:
                break

        if correct_idx is None:
            print(f"  [警告] 题{go_round} 未获取到 correctIdx，本次随机选择")

        if correct_idx is not None:
            if answer_mode == "all_correct":
                choice_idx = correct_idx
            elif answer_mode == "all_wrong":
                choice_idx = (correct_idx + 1) % 4
            else:
                choice_idx = random.randrange(len(button_positions))
        else:
            choice_idx = random.randrange(len(button_positions))

        time.sleep(0.3)
        bx, by = button_positions[choice_idx]
        tap(bx, by)
        total_answers += 1
        label = {"all_correct": "正确", "all_wrong": "错误", "random": "随机"}.get(answer_mode, "未知")
        print(f"  [题{go_round}] 主机选择选项{choice_idx+1} ({bx}, {by}) [模式:{label}] (共{total_answers}次)")

        # 3) Wait for REVEAL signal (日志格式: "round":N  JSON)
        reveal_line = None
        game_over = False
        for _ in range(15):  # 15s timeout
            feed_pending(1.0)
            # Check for game end
            if any("游戏结束" in l for l in pending):
                game_over = True
                break
            # Peek for REVEAL of current round
            for l in pending:
                if "REVEAL" in l and f'"round":{go_round}' in l:
                    reveal_line = l
                    break
            if reveal_line:
                # Remove it from pending
                pending[:] = [l for l in pending if not ("REVEAL" in l and f'"round":{go_round}' in l)]
                break

        if game_over:
            print("  游戏已结束，退出答题循环")
            break

        if reveal_line:
            m2 = re.search(r'"correctIdx":(\d+)', reveal_line)
            correct = m2.group(1) if m2 else "?"
            print(f"  [题{go_round}] 答案: {correct}")

        # 4) Check if done
        if go_round >= 30:
            print("  已到第30题，等待揭晓...")
            break

        # Feed pending while waiting for next GO (keeps buffer full)
        feed_pending(0.5)

    # Kill tail subprocess (blocks main() exit if not killed)
    tail_proc_holder[0].terminate()
    try:
        tail_proc_holder[0].wait(timeout=3)
    except:
        tail_proc_holder[0].kill()

    # Step 11: 结果
    time.sleep(3)
    print(f"\n{'=' * 60}")
    print(f"答题统计: 共答题 {total_answers} 次")
    screenshot('final')

    xml = dump_ui()
    nodes = parse_nodes(xml)
    for n in nodes:
        t = n['text']
        if '分' in t and t != '分':
            print(f"  比分: {t}")

    # Step 12: 关闭+息屏
    print(f"\n[12/12] 关闭应用+息屏...")
    adb('shell am force-stop com.wordbattle')
    time.sleep(0.5)
    adb('shell input keyevent 26')
    time.sleep(1)
    r = adb('shell dumpsys power | grep mWakefulness')
    print(f"  {'✅' if 'Asleep' in r.stdout else '⚠️'} {r.stdout.strip()}")

    # Pull device log
    print(f"\n[LOG] 拉取设备日志...")
    r_pull = subprocess.run(f"adb -s {DEVICE} pull /storage/emulated/0/Download/ts/wordbattle_debug_host.log /tmp/wb_host.log", shell=True, capture_output=True, text=True)
    if r_pull.returncode == 0:
        print("  ✅ 设备日志已拉取")
        with open('/tmp/wb_host.log', 'r') as f:
            lines = f.readlines()
        print(f"  日志条数: {len(lines)}")
        # Print key lines
        key_lines = [l.strip() for l in lines if 'GO' in l or 'ANSWER' in l or 'REVEAL' in l or 'NEXT' in l or 'skip' in l.lower() or 'miss' in l.lower()]
        print(f"  关键日志 ({len(key_lines)} 条):")
        for l in key_lines[:50]:
            print(f"    {l}")
    else:
        print(f"  ❌ {r_pull.stderr.strip()}")

    # Print remote log summary (daily file)
    today = datetime.now().strftime("%Y%m%d")
    remote_log = f"/data/wordbattle/logs/remote_all_{today}.log"
    if os.path.exists(remote_log):
        with open(remote_log, 'r') as f:
            rlines = f.readlines()
        print(f"\n  网络日志条数: {len(rlines)}")
        key_rlines = [l.strip() for l in rlines if 'GO' in l or 'ANSWER' in l or 'REVEAL' in l or 'NEXT' in l]
        print(f"  关键日志 ({len(key_rlines)} 条):")
        for l in key_rlines[:50]:
            print(f"    {l}")

    print(f"\n{'=' * 60}")
    print("测试完成！")
    print(f"{'=' * 60}")

if __name__ == '__main__':
    main()