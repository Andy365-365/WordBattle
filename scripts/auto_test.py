#!/usr/bin/env python3
"""WordBattle 完整自动化测试 v2 - 修复 pending 硬等问题"""
import subprocess
import time
import re
import random
import os
import select as _select
import socket
import struct
import threading
import json as _json
import argparse
from collections import deque
from datetime import datetime

# ===== 可配置参数（命令行覆盖，见 main 的 argparse）=====
DEFAULTS = {
    "device": "b054d001",
    "ip": "192.168.50.187",       # 手机 IP（WordBattle 主机端 TCP 5201）
    "rounds": 10,                 # 题数（设置页可选 5/10/20/30）
    "seconds": 5,                 # 答题等待秒数（设置页可选 5/10/20/30/120）
    "mode": "all_correct",        # 答题模式: all_correct / all_wrong / random
}

APK = "/data/wordbattle/app/build/outputs/apk/host/debug/app-host-debug.apk"

# 运行时实际生效的参数（main 里由 argparse 填充）
DEVICE = DEFAULTS["device"]
DEVICE_IP = DEFAULTS["ip"]
answer_mode = DEFAULTS["mode"]

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

def find_button_under_label(nodes, label_text, btn_text, max_gap=320):
    """通用按钮定位：找标签文字，在其正下方 max_gap 像素内找目标文本按钮。
    解决设置页多 Row 同名按钮歧义（题数行和答题时间行都有 5/10/20/30）。
    返回 (center_xy, 按钮bounds)；找不到返回 (None, None)。"""
    lb = None
    for n in nodes:
        if n['text'] == label_text:
            lb = pb(n['bounds'])
            if lb:
                break
    if not lb:
        return None, None
    label_bottom = lb[3]
    best = None
    for n in nodes:
        if n['text'] != btn_text:
            continue
        b = pb(n['bounds'])
        if not b:
            continue
        cy = (b[1] + b[3]) // 2
        if label_bottom <= cy <= label_bottom + max_gap:
            if best is None or cy < (best[1][1] + best[1][3]) // 2:
                best = ((b[0] + b[2]) // 2, b)
    if not best:
        return None, None
    pos = find_clickable_parent(nodes, best[1]) or best
    return pos, best[1]

def dump_setup_page(label_text):
    """诊断：打印设置页标签及其下方所有按钮（文本/bounds/中心），用于核对定位。"""
    nodes = parse_nodes(dump_ui())
    lb = None
    for n in nodes:
        if n['text'] == label_text:
            lb = pb(n['bounds'])
            break
    if not lb:
        print(f"  [诊断] 未找到标签: {label_text}")
        return
    print(f"  [诊断] 标签 '{label_text}' bounds={[lb[0],lb[1],lb[2],lb[3]]}")
    for n in nodes:
        if n['text'] not in ('5', '10', '20', '30', '120'):
            continue
        b = pb(n['bounds'])
        if not b:
            continue
        cy = (b[1] + b[3]) // 2
        if lb[3] <= cy <= lb[3] + 320:
            cd = n.get('contentDescription', '')
            print(f"    按钮 '{n['text']}' bounds=[{b[0]},{b[1]},{b[2]},{b[3]}] center=({(b[0]+b[2])//2},{cy}) clickable={n['clickable']} desc={cd!r}")

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

def screen_question(nodes):
    """从 UI dump 提取屏幕上当前显示的题目文本 (前20字)"""
    for n in nodes:
        if n['text'].startswith('题目:'):
            return n['text'].replace('题目:', '').strip()[:20]
    # 无答题区时找状态
    for n in nodes:
        if n['text'].startswith('状态:'):
            return f"[无按钮区] {n['text']}"
    return "?"


class GameTCP:
    """以玩家身份连接主机 TCP 5201（只做 READY 握手信号线，答题仍走 ADB tap）。
    帧格式: 4字节大端长度前缀 + UTF-8 JSON（与 TcpCodec.kt 一致）。"""
    def __init__(self, ip, port=5201):
        self.ip = ip
        self.port = port
        self.sock = None
        self.queue = deque()
        self.player_id = None
        self.lock = threading.Lock()
        self.alive = False

    def connect(self, timeout=5):
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            s.settimeout(timeout)
            s.connect((self.ip, self.port))
            self.sock = s
            self.alive = True
            # 关键：JOIN/WELCOME 握手在 reader 启动前完成（单线程，无 socket 竞争）。
            # 之前 reader 先启动，与主线程并发 recv 同一 socket，帧头错位导致整条流解析失败。
            # 以观察者身份 JOIN：收 PREPARE/GO/REVEAL 信号、回 READY 触发握手，
            # 但不进玩家列表（不计分、结果页不显示）。
            self._send({"type": "JOIN", "t": "JOIN", "playerId": "", "name": "auto-bot", "role": "observer"})
            msg = self._recv_one(3)
            if msg and msg.get('type') == 'WELCOME':
                self.player_id = msg.get('playerId')
            s.settimeout(None)
            t = threading.Thread(target=self._reader, daemon=True)
            t.start()
            print(f"  [TCP] 连接成功 playerId={self.player_id} 首条消息={msg.get('type') if msg else None}")
            return self.player_id is not None
        except Exception as e:
            print(f"  [TCP] 连接失败: {e}")
            self.alive = False
            return False

    def _send(self, obj):
        data = _json.dumps(obj, ensure_ascii=False).encode('utf-8')
        with self.lock:
            self.sock.sendall(struct.pack('>I', len(data)) + data)

    def send_ready(self, round_no):
        try:
            self._send({"type": "READY", "t": "READY", "playerId": self.player_id or "p1", "round": round_no})
            return True
        except Exception:
            return False

    def _recv_one(self, timeout):
        try:
            self.sock.settimeout(timeout)
            hdr = b''
            while len(hdr) < 4:
                chunk = self.sock.recv(4 - len(hdr))
                if not chunk:
                    return None
                hdr += chunk
            (ln,) = struct.unpack('>I', hdr)
            body = b''
            while len(body) < ln:
                chunk = self.sock.recv(ln - len(body))
                if not chunk:
                    return None
                body += chunk
            self.sock.settimeout(None)
            return _json.loads(body.decode('utf-8'))
        except Exception:
            return None

    def _reader(self):
        while self.alive and self.sock:
            msg = self._recv_one(30)
            if msg is None:
                break
            self.queue.append(msg)

    def wait_for(self, match_fn, timeout=20.0):
        """从 TCP 队列取匹配消息（毫秒级）；命中即从队列删除（防止陈旧消息反复命中），
        未匹配的消息保留在队列中（供后续 GAME_OVER 等检测）。
        超时返回 None，由调用方退回日志路径。"""
        deadline = time.time() + timeout
        while time.time() < deadline:
            # 先取快照再迭代：读线程并发 append 不会触发 "deque mutated during iteration"
            snapshot = list(self.queue)
            for m in snapshot:
                if match_fn(m):
                    try:
                        self.queue.remove(m)  # 命中即删除，杜绝陈旧消息打转
                    except ValueError:
                        continue  # 已被其他等待者消费，继续找下一个
                    return m
            if time.time() < deadline - 0.1:
                time.sleep(0.05)
        return None

    def pop(self, match_fn):
        # wait_for 命中即删除，这里直接透传
        return self.wait_for(match_fn, timeout=0.01)

    def close(self):
        self.alive = False
        try:
            if self.sock:
                self.sock.close()
        except Exception:
            pass

def main():
    # ===== 命令行参数 =====
    global DEVICE, DEVICE_IP, answer_mode
    ap = argparse.ArgumentParser(description="WordBattle 自动化测试（TCP 握手 + ADB tap）")
    ap.add_argument("--rounds", type=int, default=DEFAULTS["rounds"],
                    choices=[5, 10, 20, 30], help="题数（设置页可选值，默认 10）")
    ap.add_argument("--seconds", type=int, default=DEFAULTS["seconds"],
                    choices=[5, 10, 20, 30, 120], help="答题等待秒数（默认 5）")
    ap.add_argument("--mode", default=DEFAULTS["mode"],
                    choices=["all_correct", "all_wrong", "random"], help="答题模式（默认 all_correct）")
    ap.add_argument("--device", default=DEFAULTS["device"], help="ADB 设备序列号")
    ap.add_argument("--ip", default=DEFAULTS["ip"], help="手机 IP（TCP 5201）")
    args = ap.parse_args()
    DEVICE = args.device
    DEVICE_IP = args.ip
    answer_mode = args.mode
    rounds = args.rounds
    seconds = args.seconds
    print(f"参数: 题数={rounds} 答题秒数={seconds} 模式={answer_mode} 设备={DEVICE} IP={DEVICE_IP}")

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
    adb('shell am force-stop com.wordbattle')
    time.sleep(1)
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

    # Step 6: 设置题数（以"题目数"标签锚定下方按钮，消除与答题时间行同名按钮的歧义）
    print(f"\n[6/12] 设置{rounds}题...")
    setup_nodes = parse_nodes(dump_ui())
    pos, _b = find_button_under_label(setup_nodes, '题目数', str(rounds))
    if pos:
        tap(*pos)
        print(f"  OK tap({pos[0]}, {pos[1]})")
    else:
        dump_setup_page('题目数')
        print("  FAIL 没找到题数按钮")
        return
    time.sleep(0.5)

    # Step 7: 设置答题时间（以"答题等待时间(秒)"标签锚定下方按钮）
    print(f"\n[7/12] 设置{seconds}秒答题时间...")
    setup_nodes = parse_nodes(dump_ui())
    pos, _b = find_button_under_label(setup_nodes, '答题等待时间(秒)', str(seconds))
    if pos:
        tap(*pos)
        print(f"  OK tap({pos[0]}, {pos[1]})")
    else:
        dump_setup_page('答题等待时间(秒)')
        print(f"  WARN 没找到答题时间的{seconds}秒按钮（将使用App默认5秒）")
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

    # Step 8.5: 连接游戏 TCP（READY 握手信号线；答题仍走 ADB tap）
    print("\n[8.5] 连接游戏 TCP 5201 (READY 握手)...")
    game_tcp = GameTCP(DEVICE_IP)
    if not game_tcp.connect(timeout=5):
        time.sleep(2)
        game_tcp.connect(timeout=5)
    if game_tcp.alive:
        print(f"  OK 已连接, playerId={game_tcp.player_id}")
    else:
        print("  WARN TCP 连不上，退回日志驱动模式")

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

    # ===== 答题日志兜底（tail -f）=====
    print(f"\n[10/12] 答题日志兜底就绪...")

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
    # 握手模式下第一题 GO 前屏幕停留在"准备中"（无按钮），校准改为在第一题 dump 验证时完成
    screenshot('game_answers')
    button_positions = [(540, 770), (540, 946), (540, 1122), (540, 1298)]
    label = {"all_correct": "正确", "all_wrong": "错误", "random": "随机"}.get(answer_mode, "未知")

    def now_ms():
        return time.strftime('%H:%M:%S') + f".{int((time.time() % 1) * 1000):03d}"

    def pick_choice(correct_idx, n_opts):
        if answer_mode == "all_correct":
            return correct_idx
        if answer_mode == "all_wrong":
            return (correct_idx + 1) % n_opts
        return random.randrange(n_opts)

    def get_prepare(timeout=15.0):
        if game_tcp.alive:
            m = game_tcp.wait_for(
                lambda x: x.get('type') == 'PREPARE' or (x.get('type') == 'GO' and x.get('correctIdx', -1) >= 0),
                timeout=timeout)
            if m is not None:
                # GO 先于 PREPARE 被看到 = 脚本连接晚、错过了本轮 PREPARE（本轮窗口可能已错过）
                return {'type': 'PREPARE', 'round': m.get('round', -1), 'go_already': m.get('type') == 'GO'}
        # TCP 失效 → 日志兜底（日志中 PREPARE 不带轮次号，READY 不依赖轮次号）
        line = wait_for(lambda l: 'broadcast: PREPARE' in l or '收到: PREPARE' in l, timeout=5.0)
        if line:
            return {'type': 'PREPARE', 'round': -1}
        return None

    def get_go(timeout=15.0):
        if game_tcp.alive:
            m = game_tcp.wait_for(lambda x: x.get('type') == 'GO' and x.get('correctIdx', -1) >= 0, timeout=timeout)
            if m is not None:
                return m
        line = wait_for(lambda l: '"type":"GO"' in l and '"correctIdx"' in l, timeout=10.0)
        if line:
            r = re.search(r'"round":(\d+)', line)
            q = re.search(r'"question":"([^"]*)"', line)
            c = re.search(r'"correctIdx":(\d+)', line)
            return {'round': int(r.group(1)) if r else -1,
                    'question': q.group(1) if q else '',
                    'correctIdx': int(c.group(1)) if c else -1}
        return None

    def verify_and_tap(go, round_no, is_first):
        """GO 后 dump 屏幕，验证题目与 GO 一致再 tap（不一致会重试 dump）。
        返回 (result, choice_idx)；result ∈ hit/miss。"""
        nonlocal button_positions
        go_q = go.get('question', '')
        t_go = time.time()
        for attempt in range(3):
            try:
                nodes = parse_nodes(dump_ui())
            except Exception as e:
                print(f"  [OBS-SCRIPT] T={now_ms()} [题{round_no}] dump失败: {e}")
                time.sleep(0.3)
                continue
            sq = screen_question(nodes)
            ok = (sq == go_q[:20]) if go_q else False
            if is_first:
                btns = find_answer_buttons(nodes)
                if len(btns) >= 4:
                    button_positions = [(b[0], b[1]) for b in btns[:4]]
                    print(f"  [校准] 使用实际按钮坐标: {button_positions}")
            if ok:
                choice_idx = pick_choice(go.get('correctIdx', 0), 4)
                bx, by = button_positions[choice_idx]
                dt = time.time() - t_go
                print(f"  [OBS-SCRIPT] T={now_ms()} [题{round_no}] 屏幕=<{sq}> GO期望=<{go_q[:20]}> 一致=是 dump+渲染={dt:.1f}s")
                print(f"  [OBS-SCRIPT] T={now_ms()} [题{round_no}] tap({bx},{by}) choice={choice_idx} round={round_no}")
                tap(bx, by)
                return 'hit', choice_idx
            print(f"  [OBS-SCRIPT] T={now_ms()} [题{round_no}] 屏幕=<{sq}> GO期望=<{go_q[:20]}> 一致=否 重试{attempt+1}")
            time.sleep(0.3)
        return 'miss', -1

    # ===== 主循环：TCP 握手驱动（PREPARE→READY→GO→tap），日志作兜底 =====
    total_answers = 0
    rounds_played = 0
    hits = 0
    print(f"\n[10/12] 答题循环 (TCP握手驱动) tcp={'已连接' if game_tcp.alive else '未连接→日志兜底'}...")
    for i in range(rounds + 5):  # 防御上限：题数+5 圈余量
        # 游戏结束？
        if game_tcp.alive and game_tcp.pop(lambda m: m.get('type') == 'GAME_OVER') is not None:
            print("  游戏结束，退出答题循环")
            break

        # 1) 等 PREPARE
        prep = get_prepare(timeout=15.0)
        if prep is None:
            if game_tcp.alive and game_tcp.pop(lambda m: m.get('type') == 'GAME_OVER') is not None:
                print("  游戏结束（PREPARE 超时后检测到 GAME_OVER），退出答题循环")
            else:
                print(f"  [轮{i}] 未收到 PREPARE，退出")
            break

        # 2) 回 READY（App 收到后才广播 GO、才开始倒计时）
        # go_already=True：错过了 PREPARE、GO 已发出（脚本连接晚）。补发 READY 供 App 消费丢弃，
        # 本轮窗口可能已错过，由 dump 验证如实报告 miss，下一轮恢复握手。
        sent = False
        if game_tcp.alive:
            sent = game_tcp.send_ready(prep.get('round', 1))
            if prep.get('go_already'):
                print(f"  [OBS-SCRIPT] T={now_ms()} [轮{i}] ! 错过PREPARE，GO已发出，本轮可能超时")
        print(f"  [OBS-SCRIPT] T={now_ms()} [轮{i}] PREPARE round={prep.get('round')} READY已发={sent}")

        # 3) 等 GO
        go = get_go(timeout=15.0)
        if go is None:
            print(f"  [轮{i}] 未收到 GO，退出")
            break
        round_no = go.get('round', i + 1)
        print(f"  [OBS-SCRIPT] T={now_ms()} [轮{i}] GO收到 round={round_no} 题目=<{go.get('question', '')[:20]}> correctIdx={go.get('correctIdx')}")

        # 4) dump 验证屏幕题目一致后 tap
        result, choice_idx = verify_and_tap(go, round_no, is_first=(rounds_played == 0))
        total_answers += 1
        rounds_played += 1

        # 5) 等 REVEAL 确认命中
        rev = None
        if game_tcp.alive:
            rev = game_tcp.wait_for(lambda m: m.get('type') == 'REVEAL' and m.get('round') == round_no, timeout=8.0)
        if rev is None:
            line = wait_for(lambda l: "REVEAL" in l and f'"round":{round_no}' in l, timeout=5.0)
            if line:
                rev = {'winner': 'p0' if '"winner":"p0"' in line else None}
        winner = rev.get('winner') if rev else None
        if result == 'hit':
            status = f"命中 (winner={winner})" if winner else "屏幕一致但未判命中"
            if winner:
                hits += 1
            print(f"  [题{round_no}] 选项{choice_idx + 1} [模式:{label}] -> {status}")
        else:
            print(f"  [题{round_no}] 屏幕不一致，本轮 tap 已跳过 (miss)")

        if round_no >= rounds:
            print(f"  已到第{rounds}题，等待结果...")
            break

    print(f"  答题统计: 命中 {hits}/{rounds_played}")

    game_tcp.close()

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
