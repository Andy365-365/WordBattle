#!/usr/bin/env python3
"""WordBattle 完整自动化测试 v2 - 修复 pending 硬等问题"""
import subprocess
import time
import re
import random
import os
import select as _select
from datetime import datetime

DEVICE = "b054d001"
APK = "/data/wordbattle/app/build/outputs/apk/host/debug/app-host-debug.apk"

# Test mode: "random", "all_correct", "all_wrong"
answer_mode = "all_correct"

def adb(cmd, timeout=15):
    return subprocess.run(f'adb -s {DEVICE} {cmd}', shell=True, capture_output=True, text=True, timeout=timeout)

def tap(x, y):
    adb(f'shell input tap {x} {y}')

def screenshot(name):
    subprocess.run(f'adb -s {DEVICE} exec-out screencap -p > /tmp/{name}.png', shell=True, timeout=10)

def dump_ui():
    adb('shell uiautomator dump /sdcard/ui.xml')
    adb('pull /sdcard/ui.xml /tmp/wb_ui.xml')
    with open('/tmp/wb_ui.xml', 'r') as f:
        return f.read()

def parse_nodes(xml_text):
    nodes = []
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

def find_answer_buttons(nodes):
    buttons = []
    for n in nodes:
        if n['clickable'] != 'true' or n['enabled'] != 'true':
            continue
        b = pb(n['bounds'])
        if not b:
            continue
        px1, py1, px2, py2 = b
        cx, cy = (px1+px2)//2, (py1+py2)//2
        if 600 <= py1 <= 1400 and (px2-px1) > 400:
            buttons.append((cx, cy, f'选项{len(buttons)+1}'))
    return buttons

def main():
    # Force restart log receiver
    print("[PRE] 强制重启日志接收脚本...")
    subprocess.run("pkill -9 -f 'log_receiver.py'", shell=True)
    time.sleep(1)
    import socket
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.connect(("127.0.0.1", 8765))
        s.close()
        print("  ! 端口 8765 仍被占用，尝试 kill...")
        subprocess.run("fuser -k 8765/tcp", shell=True)
        time.sleep(1)
    except ConnectionRefusedError:
        pass

    proc = subprocess.Popen(
        ['python3', '/data/wordbattle/scripts/log_receiver.py', '8765'],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True
    )
    time.sleep(1)
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.settimeout(2)
        s.connect(("127.0.0.1", 8765))
        s.close()
        print(f"  OK log_receiver running (PID: {proc.pid})")
    except:
        print("  FAIL log_receiver 启动失败")
        return

    print("=" * 60)
    print("WordBattle 完整自动化测试 v2")
    print("=" * 60)

    # Step 1: 唤醒
    print("\n[1/12] 唤醒屏幕...")
    for _ in range(3):
        r = adb('shell dumpsys power | grep mWakefulness')
        if 'Awake' in r.stdout:
            print("  OK 已唤醒")
            break
        adb('shell input keyevent 26')
        time.sleep(1)
    else:
        print("  FAIL 唤醒失败")
        return
    time.sleep(0.5)

    today_str = datetime.now().strftime("%Y%m%d")
    log_file = f"/data/wordbattle/logs/remote_all_{today_str}.log"
    if os.path.exists(log_file):
        open(log_file, 'w').close()
        print("  OK 日志文件已清空")

    # Step 2: 跳过
    print("\n[2/12] 跳过卸载/安装")

    # Step 4: 启动
    print("\n[4/12] 启动 WordBattle...")
    adb('shell am start -n com.wordbattle/.MainActivity')
    time.sleep(8)  # 增加等待时间确保UI渲染完成
    screenshot('s4_start')

    # Step 5: 主机+答题
    print("\n[5/12] 点击'主机+答题'...")
    pos = find_button_center('主机+答题')
    if pos:
        tap(*pos)
        print(f"  OK tap({pos[0]}, {pos[1]})")
    else:
        print("  FAIL 没找到按钮")
        return
    time.sleep(2)

    # Step 6: 30题
    print("\n[6/12] 设置30题...")
    pos = find_button_by_y('30', 0)
    if pos:
        tap(*pos)
        print(f"  OK tap({pos[0]}, {pos[1]})")
    else:
        print("  FAIL 没找到30题按钮")
        return
    time.sleep(0.5)

    # Step 7: 15秒答题时间
    print("\n[7/12] 设置15秒答题时间...")
    pos = find_button_by_y('15', 1200)
    if pos:
        tap(*pos)
        print(f"  OK tap({pos[0]}, {pos[1]})")
    else:
        print("  WARN 没找到15秒按钮")
    time.sleep(0.5)

    # Step 8: 开始等待玩家
    print("\n[8/12] 点击'开始等待玩家'...")
    pos = find_button_center('开始等待玩家')
    if pos:
        tap(*pos)
        print(f"  OK tap({pos[0]}, {pos[1]})")
    else:
        print("  FAIL 没找到按钮")
        return
    time.sleep(3)

    # Step 9: 开始游戏
    print("\n[9/12] 点击'开始游戏'...")
    pos = find_button_center('开始游戏')
    if pos:
        tap(*pos)
        print(f"  OK tap({pos[0]}, {pos[1]})")
    else:
        time.sleep(3)
        pos = find_button_center('开始游戏')
        if pos:
            tap(*pos)
            print(f"  OK tap({pos[0]}, {pos[1]}) (retry)")
        else:
            print("  FAIL 没找到按钮")
            return
    time.sleep(3)
    screenshot('s9_game')

    # ===== 日志驱动答题 =====
    print(f"\n[10/12] 答题循环 (日志驱动)...")
    total_answers = 0

    # tail -f 从当前行开始
    start_line = 0
    if os.path.exists(log_file):
        with open(log_file) as f:
            start_line = sum(1 for _ in f)

    tail_proc_holder = [subprocess.Popen(
        ["tail", "-f", f"+{start_line + 1}", log_file],
        stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
        text=True, bufsize=1
    )]

    # === 核心改动：行级即时读取，不再批量硬等 ===
    pending = []

    def read_lines(timeout=1.0):
        """读取 tail 输出，有新行就返回，不等满 timeout。
        返回 (lines, timed_out_bool)。
        一旦读到至少一行就立即返回；只有完全没数据时才等满 timeout。"""
        lines = []
        start = time.time()
        while time.time() - start < timeout:
            if tail_proc_holder[0].poll() is not None:
                tail_proc_holder[0] = subprocess.Popen(
                    ["tail", "-f", f"+{start_line + 1}", log_file],
                    stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                    text=True, bufsize=1
                )
            ready, _, _ = _select.select([tail_proc_holder[0].stdout], [], [], 0.1)
            if ready:
                line = tail_proc_holder[0].stdout.readline()
                if line:
                    lines.append(line.strip())
            # 一旦读到行就立即返回，不再硬等
            if lines:
                return lines, False
            time.sleep(0.05)
        return lines, True

    def drain(timeout=0.0):
        """不等待，把 tail buffer 里已有的行全部读完。"""
        lines = []
        while True:
            r, _, _ = _select.select([tail_proc_holder[0].stdout], [], [], 0.01)
            if not r:
                break
            line = tail_proc_holder[0].stdout.readline()
            if line:
                lines.append(line.strip())
            else:
                break
        pending.extend(lines)

    def wait_for(match_fn, timeout=10.0):
        """等待 pending 中出现匹配的行。找到即返回该行并移除。
        用 read_lines(短超时) 循环，确保不硬等。"""
        deadline = time.time() + timeout
        while time.time() < deadline:
            # 先检查 pending 里已有
            for idx, l in enumerate(pending):
                if match_fn(l):
                    return pending.pop(idx)
            # 读新数据（短超时，有数据就返回）
            lines, _ = read_lines(0.5)
            pending.extend(lines)
        return None

    def wait_and_clean(match_fn, timeout=10.0):
        """wait_for 的旧写法兼容：从 pending 中找匹配并删除所有匹配行。"""
        line = wait_for(match_fn, timeout)
        return line

    # === 校准选项按钮坐标 ===
    print("  [校准] 等待答题界面稳定...")
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

    button_positions = [(540, 770), (540, 946), (540, 1122), (540, 1298)]

    # ===== 主循环：直接监听 GO JSON，跳过"广播 GO"中间步 =====
    print("  等待第一题 GO 信号...")

    # 等 GO JSON: "type":"GO" + "correctIdx"
    first_go = wait_for(lambda l: '"type":"GO"' in l and '"correctIdx"' in l, timeout=30.0)
    if not first_go:
        print("  FAIL 未检测到 GO 信号")
        return

    first_round_m = re.search(r'"round":(\d+)', first_go)
    first_round = int(first_round_m.group(1)) if first_round_m else 1

    # 等客户端 UI 渲染完毕（日志 "收到: GO" = 按钮已渲染可点）
    ui_ready = wait_for(lambda l: '收到: GO' in l, timeout=2.0)
    if ui_ready:
        drain()
    # Compose 重渲染按钮需要时间，等渲染完成后 tap
    time.sleep(3)

    # 回答第一题
    first_correct_m = re.search(r'"correctIdx":(\d+)', first_go)
    if answer_mode == "all_correct" and first_correct_m:
        choice_idx = int(first_correct_m.group(1))
    elif answer_mode == "all_wrong" and first_correct_m:
        choice_idx = (int(first_correct_m.group(1)) + 1) % 4
    else:
        choice_idx = random.randrange(len(button_positions))

    bx, by = button_positions[choice_idx]
    tap(bx, by)
    total_answers += 1
    label = {"all_correct": "正确", "all_wrong": "错误", "random": "随机"}.get(answer_mode, "未知")
    print(f"  [题{first_round}] 选项{choice_idx+1} ({bx},{by}) [模式:{label}] (共{total_answers}次)")

    # 等第一题 REVEAL
    reveal = wait_for(lambda l: "REVEAL" in l and f'"round":{first_round}' in l, timeout=15.0)
    if reveal:
        cm = re.search(r'"correctIdx":(\d+)', reveal)
        correct = cm.group(1) if cm else "?"
        winner_m = re.search(r'"winner":"([^"]+)"', reveal)
        status = f"答案: {correct} ({'命中' if winner_m else '超时'})"
        print(f"  [题{first_round}] {status}")

    # drain 清理
    drain()

    # === 主循环 ===
    for i in range(60):
        # 1) 直接等 GO JSON
        go_json = wait_for(lambda l: '"type":"GO"' in l and '"correctIdx"' in l, timeout=20.0)
        if not go_json:
            print(f"  [轮{i}] 超时未收到 GO，退出")
            break

        go_round_m = re.search(r'"round":(\d+)', go_json)
        go_round = int(go_round_m.group(1)) if go_round_m else (i + 2)

        # drain 已有的行
        drain()

        # 等客户端 UI 渲染完毕（"GO: 题目=XXX" 出现 = 按钮已渲染可点）
        ui_ready = wait_for(lambda l: 'GO: 题目=' in l and f'选项数=' in l, timeout=5.0)
        if ui_ready:
            drain()
        else:
            print(f"  [警告] 题{go_round} 未等到 UI 渲染日志")
        # Compose 重渲染按钮需要时间，等渲染完成后 tap
        time.sleep(3)

        # 2) 提取 correctIdx
        cm = re.search(r'"correctIdx":(\d+)', go_json)
        if cm:
            correct_idx = int(cm.group(1))
            if answer_mode == "all_correct":
                choice_idx = correct_idx
            elif answer_mode == "all_wrong":
                choice_idx = (correct_idx + 1) % 4
            else:
                choice_idx = random.randrange(len(button_positions))
        else:
            choice_idx = random.randrange(len(button_positions))
            print(f"  [警告] 题{go_round} 未获取 correctIdx，随机选择")

        # 3) tap (UI 已渲染，直接点)
        bx, by = button_positions[choice_idx]
        tap(bx, by)
        total_answers += 1
        print(f"  [题{go_round}] 选项{choice_idx+1} ({bx},{by}) [模式:{label}] (共{total_answers}次)")

        # 4) 等 REVEAL
        reveal = wait_for(lambda l: "REVEAL" in l and f'"round":{go_round}' in l, timeout=15.0)
        game_over = any("游戏结束" in l for l in pending) if not reveal else False

        if game_over:
            print("  游戏已结束，退出答题循环")
            break

        if reveal:
            cm2 = re.search(r'"correctIdx":(\d+)', reveal)
            correct = cm2.group(1) if cm2 else "?"
            winner_m = re.search(r'"winner":"([^"]+)"', reveal)
            status = f"答案: {correct} ({'命中' if winner_m else '超时'})"
            print(f"  [题{go_round}] {status}")

        if go_round >= 30:
            print("  已到第30题，等待揭晓...")
            # drain 剩余日志
            drain()
            break

        # drain 到下一轮 GO 出现之前
        drain()

    # Kill tail
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

    # 拉取设备日志
    r_pull = subprocess.run(f"adb -s {DEVICE} pull /storage/emulated/0/Download/ts/wordbattle_debug_host.log /tmp/wb_host.log",
                            shell=True, capture_output=True, text=True)
    if r_pull.returncode == 0:
        with open('/tmp/wb_host.log', 'r') as f:
            lines = f.readlines()
        key_lines = [l.strip() for l in lines if any(k in l for k in ['GO','ANSWER','REVEAL','skip','miss'])]
        print(f"\n  设备日志关键行 ({len(key_lines)}):")
        for l in key_lines[:50]:
            print(f"    {l}")

    # 网络日志
    remote_log = log_file
    if os.path.exists(remote_log):
        with open(remote_log, 'r') as f:
            rlines = f.readlines()
        key_rlines = [l.strip() for l in rlines if any(k in l for k in ['GO','ANSWER','REVEAL'])]
        print(f"\n  网络日志关键行 ({len(key_rlines)}):")
        for l in key_rlines[:50]:
            print(f"    {l}")

    print(f"\n{'=' * 60}")
    print("测试完成！")
    print(f"{'=' * 60}")

if __name__ == '__main__':
    main()
