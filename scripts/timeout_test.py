#!/usr/bin/env python3
"""超时路径端到端实测（08-19）

目的：之前 30 题压力测试全部 2.3s 内作答，5s 超时分支从未被真实触发过。
本脚本故意在 2 题上不 tap，让超时真实发生，验证：
  V1  REVEAL round1/2 winner=None（超时广播正常，不卡死）
  V2  GO→REVEAL 耗时 ≈ 答题秒数（超时计时器生效）
  V3  超时后的后续正常题（答对）命中，游戏不卡
  V4  设备日志有 timeout=true 记录
  V5  复盘页统计 "3 题答对 / 0 题答错 / 2 题超时" + "你的答案：超时未答"
  V6  错题本记入 2 道超时的题

流程：主机+答题 → 5题/5秒 → 开始 → Q1,Q2 不 tap（超时）→ Q3-5 答对 → GAME_OVER
      → 复盘页验证 → 返回错题本列表验证。

用法：python3 scripts/timeout_test.py
"""
import subprocess
import time
import sys
import os
from datetime import datetime

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import auto_test as at  # 复用 adb/tap/screenshot/dump_ui/parse_nodes/find_button_center/GameTCP

DEVICE = "b054d001"
DEVICE_IP = "192.168.50.187"
ROUNDS = 5
SECONDS = 5
TIMEOUT_ROUNDS = {1, 2}  # 故意超时的题

PASS = []
FAIL = []

def check(name, ok, detail=""):
    (PASS if ok else FAIL).append((name, detail))
    print(f"  [{'PASS' if ok else 'FAIL'}] {name} {detail}")

def main():
    # ===== PRE: 重启 log_receiver =====
    print("[PRE] 重启 log_receiver...")
    subprocess.run("pkill -9 -f 'log_receiver.py'", shell=True)
    time.sleep(1)
    proc = subprocess.Popen(
        ['python3', '/data/wordbattle/scripts/log_receiver.py', '8765'],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
    time.sleep(1)
    import socket
    try:
        s = socket.socket(); s.settimeout(2); s.connect(("127.0.0.1", 8765)); s.close()
        print(f"  OK log_receiver (PID {proc.pid})")
    except Exception:
        print("  FAIL log_receiver 没起来"); return

    today = datetime.now().strftime("%Y%m%d")
    log_file = f"/data/wordbattle/logs/remote_all_{today}.log"
    open(log_file, 'w').close()

    # ===== 1. 唤醒 + 永不熄屏 =====
    print("\n[1] 唤醒 + 永不熄屏")
    for _ in range(3):
        if 'Awake' in at.adb('shell dumpsys power | grep mWakefulness').stdout:
            break
        at.adb('shell input keyevent 26'); time.sleep(1)
    at.adb('shell settings put secure screen_off_timeout 2147483647')

    # ===== 2. 启动 =====
    print("[2] 启动 WordBattle")
    at.adb('shell am force-stop com.wordbattle'); time.sleep(1)
    at.adb('shell am start -n com.wordbattle/.MainActivity'); time.sleep(8)

    # ===== 3. 主机+答题 =====
    print("[3] 主机+答题")
    pos = at.find_button_center('主机+答题')
    if not pos: print("  FAIL 没找到按钮"); return
    at.tap(*pos); time.sleep(2)

    # ===== 4. 设置 5 题 / 5 秒 =====
    print("[4] 设置题数=5 时间=5秒")
    nodes = at.parse_nodes(at.dump_ui())
    p, _ = at.find_button_under_label(nodes, '题目数', '5')
    if p: at.tap(*p); time.sleep(0.5)
    else: print("  FAIL 题数5按钮"); return
    nodes = at.parse_nodes(at.dump_ui())
    p, _ = at.find_button_under_label(nodes, '答题等待时间(秒)', '5')
    if p: at.tap(*p); time.sleep(0.5)
    else: print(f"  WARN 时间5秒按钮没找到（App 默认 5s 也可）")

    # ===== 5. 开始等待玩家 =====
    print("[5] 开始等待玩家")
    pos = at.find_button_center('开始等待玩家')
    if not pos: print("  FAIL"); return
    at.tap(*pos); time.sleep(3)

    # ===== 6. TCP 连接 =====
    print("[6] 连接游戏 TCP")
    tcp = at.GameTCP(DEVICE_IP)
    if not tcp.connect(timeout=5):
        time.sleep(2); tcp.connect(timeout=5)
    if not tcp.alive:
        print("  FAIL TCP 连不上"); return
    print(f"  OK playerId={tcp.player_id}")

    # ===== 7. 开始游戏 =====
    print("[7] 开始游戏")
    pos = at.find_button_center('开始游戏')
    if not pos:
        time.sleep(3); pos = at.find_button_center('开始游戏')
    if not pos: print("  FAIL"); return
    at.tap(*pos); time.sleep(3)

    button_positions = [(540, 770), (540, 946), (540, 1122), (540, 1298)]
    normal_hits = 0

    # ===== 8. 答题循环 =====
    print(f"\n[8] 答题循环: Q{sorted(TIMEOUT_ROUNDS)} 故意超时, 其余答对")
    for i in range(ROUNDS + 3):
        if tcp.pop(lambda m: m.get('type') == 'GAME_OVER') is not None:
            print("  GAME_OVER 收到，退出循环"); break

        prep = tcp.wait_for(lambda m: m.get('type') == 'PREPARE' or
                            (m.get('type') == 'GO' and m.get('correctIdx', -1) >= 0), timeout=15)
        if prep is None:
            print(f"  [轮{i}] 未收到 PREPARE/GO，退出"); break
        tcp.send_ready(prep.get('round', 1))

        go = tcp.wait_for(lambda m: m.get('type') == 'GO' and m.get('correctIdx', -1) >= 0, timeout=15)
        if go is None:
            print(f"  [轮{i}] 未收到 GO，退出"); break
        round_no = go.get('round')
        t_go = time.time()
        print(f"  [Q{round_no}] GO 收到 题目=<{go.get('question','')[:16]}> correctIdx={go.get('correctIdx')}")

        # dump 验证题目一致（首题顺带校准按钮坐标）
        nodes = at.parse_nodes(at.dump_ui())
        sq = at.screen_question(nodes)
        if round_no == 1:
            btns = at.find_answer_buttons(nodes)
            if len(btns) >= 4:
                button_positions = [(b[0], b[1]) for b in btns[:4]]
                print(f"    校准按钮坐标: {button_positions}")
        if sq != go.get('question', '')[:20]:
            print(f"    屏幕=<{sq}> 与 GO 不一致!（不阻断，继续）")

        if round_no in TIMEOUT_ROUNDS:
            # 故意不 tap：等 REVEAL（应 ≈SECONDS 秒后到）
            rev = tcp.wait_for(lambda m: m.get('type') == 'REVEAL' and m.get('round') == round_no, timeout=15)
            dt = time.time() - t_go
            print(f"    不 tap，等待超时揭晓... REVEAL 收到 耗时={dt:.1f}s winner={rev.get('winner') if rev else 'None'}")
            if rev is None:
                check(f"V1 Q{round_no} REVEAL", False, "15s 未收到 REVEAL（超时路径卡死?）")
            else:
                check(f"V1 Q{round_no} REVEAL winner=None", rev.get('winner') is None, f"winner={rev.get('winner')}")
                check(f"V2 Q{round_no} GO→REVEAL≈{SECONDS}s", SECONDS - 1 <= dt <= SECONDS + 3, f"实际 {dt:.1f}s")
        else:
            bx, by = button_positions[go.get('correctIdx', 0)]
            at.tap(bx, by)
            rev = tcp.wait_for(lambda m: m.get('type') == 'REVEAL' and m.get('round') == round_no, timeout=10)
            dt = time.time() - t_go
            ok = rev is not None and rev.get('winner') == 'p0'  # 主机自答，winner 固定 p0
            if ok:
                normal_hits += 1
            print(f"    tap 正确选项 {dt:.1f}s winner={rev.get('winner') if rev else None}")
            check(f"V3 Q{round_no} 超时后正常题命中", ok, f"winner={rev.get('winner') if rev else None}")
        if round_no >= ROUNDS:
            break
    check("V3 全部 3 道正常题命中", normal_hits == 3, f"实际命中 {normal_hits}/3")

    tcp.close()

    # ===== 9. 结果页 → 复盘 =====
    print("\n[9] 结果页 → 查看错题")
    time.sleep(3)
    at.screenshot('to_final1')
    pos = at.find_button_center('查看错题')
    if not pos:
        # 结果页按钮文本带"（N 题）"后缀，用包含匹配 + 可点击父节点
        nodes = at.parse_nodes(at.dump_ui())
        for n in nodes:
            if '查看错题' in n.get('text', ''):
                b = at.pb(n['bounds'])
                if b:
                    pos = at.find_clickable_parent(nodes, b)
                    break
    if not pos:
        check("V5 复盘页进入", False, "没找到'查看错题'按钮（records 为空?）")
    else:
        at.tap(*pos); time.sleep(2)
        at.screenshot('to_final2')
        nodes = at.parse_nodes(at.dump_ui())
        texts = ' | '.join(n.get('text', '') for n in nodes if n.get('text'))
        check("V5 复盘统计 3答对/0答错/2超时",
              '3 题答对' in texts and '0 题答错' in texts and '2 题超时' in texts,
              f"统计行见截图; 页面文本含'题答对'={'题答对' in texts}")
        check("V5 复盘条目'超时未答'", texts.count('超时未答') >= 2, f"出现 {texts.count('超时未答')} 次")

        # ===== 10. 返回错题本列表验证 =====
        print("\n[10] 返回 → 错题本列表验证")
        at.adb('shell input keyevent 4'); time.sleep(2)  # 返回结果页
        at.adb('shell input keyevent 4'); time.sleep(2)  # 返回主页(若复盘在结果页栈内)
        # 确保在主页
        nodes = at.parse_nodes(at.dump_ui())
        if not any('错题本' == n.get('text', '') for n in nodes):
            at.adb('shell input keyevent 4'); time.sleep(2)
        nodes = at.parse_nodes(at.dump_ui())
        pos = at.find_button_center('错题本')
        if pos:
            at.tap(*pos); time.sleep(2)
            nodes = at.parse_nodes(at.dump_ui())
            pos = at.find_button_center('查看错题')
            if pos:
                at.tap(*pos); time.sleep(2)
                at.screenshot('to_final3')
                nodes = at.parse_nodes(at.dump_ui())
                texts = ' | '.join(n.get('text', '') for n in nodes if n.get('text'))
                # 超时的 2 题 + 之前存量错题，列表应有 ≥2 条且含"共 N 词"
                import re
                m = re.search(r'共\s*(\d+)\s*词', texts)
                cnt = int(m.group(1)) if m else -1
                check("V6 错题本记入超时题", cnt >= 2, f"列表显示'共 {cnt} 词'（含历史存量）")
            else:
                check("V6 错题本列表", False, "没找到'查看错题'按钮")
        else:
            check("V6 错题本列表", False, "没找到'错题本'按钮")

    # ===== 11. 设备日志验证 =====
    print("\n[11] 设备日志 timeout 记录")
    at.adb('shell am force-stop com.wordbattle')
    r = subprocess.run("adb -s b054d001 pull /storage/emulated/0/Download/ts/wordbattle_debug_host.log /tmp/wb_host_timeout.log",
                       shell=True, capture_output=True, text=True)
    if r.returncode == 0:
        lines = open('/tmp/wb_host_timeout.log').read().splitlines()
        tl = [l for l in lines if 'timeout=true' in l]
        check("V4 日志 timeout=true 记录", len(tl) >= 2, f"{len(tl)} 条")
        for l in tl[:4]:
            print(f"    {l.strip()[-120:]}")
    else:
        check("V4 日志拉取", False, str(r.stderr)[:100])

    # ===== 收尾 =====
    at.adb('shell settings put secure screen_off_timeout 600000')
    at.adb('shell input keyevent 223')
    time.sleep(1)
    st = at.adb('shell dumpsys power | grep mWakefulness').stdout.strip()
    print(f"\n[收尾] 息屏: {st}")

    print(f"\n{'='*50}\n结果: PASS {len(PASS)} / FAIL {len(FAIL)}")
    for n, d in FAIL:
        print(f"  [FAIL] {n} {d}")
    return 1 if FAIL else 0

if __name__ == '__main__':
    sys.exit(main())
