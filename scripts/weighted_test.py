#!/usr/bin/env python3
"""二期加权出题真机端到端验证（08-19）

前提：错题本已注入权重梯度数据（9 词，见 /tmp/inject_test_data.py）。
流程：启动 → 错题本 → 错题练习 → 20 题 → 全程不 tap（全超时，自动推进）
      → 结束页 → 拉设备日志，从 [Practice] Qn 行抓 20 题序列做分布断言。

预期权重（weight=(1+wrongCount)*2^(-小时/24)，现注入数据，总权重≈24.85）：
  history≈10.7  biology≈6.4  geography≈3.0  nod/rest≈1.9  wolf≈1.5
  textbook≈0.75  eraser≈0.5  ready≈0.1

v2.3 保底 + 加权：20 题 = 9 词各保底 1 题 + 11 名额按权重抽：
  history 期望≈1+4.7=5.7  biology≈3.8  geography≈2.3  低权重三词各≈1.x

断言：
  V1 新 VERSION 出现在运行日志
  V2 抽题序列 20 条完整（[Practice] Q1..Q20）
  V3 保底覆盖：9 词全部出现（每词 ≥1 题，确定性断言）
  V4 权重排序：history 单频第一，且 ≥ 每个低权重词（textbook/eraser/ready）
  V5 高权重组 > 低权重组：history+biology 合计 ≥ textbook+eraser+ready 合计
     （期望 9.6 vs 4.2，差 5.4，P 反转 <0.01%）
  V6 20 题全超时（日志"超时" x20），超时推进正常
用法：python3 scripts/weighted_test.py
"""
import subprocess
import time
import sys
import os
import re
from collections import Counter

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import auto_test as at

DEVICE = "b054d001"
ROUNDS = 20
NEW_VERSION = "v2.3-20260819-1709"   # 本次 build.sh 生成，脚本里改这里即可复用
PASS, FAIL = [], []

def check(name, ok, detail=""):
    (PASS if ok else FAIL).append((name, detail))
    print(f"  [{'PASS' if ok else 'FAIL'}] {name} {detail}")

def adb(c):
    return subprocess.run(f"adb -s {DEVICE} {c}", shell=True, capture_output=True, text=True, timeout=30)

def main():
    # ===== 1. 息屏设置 + 唤醒 =====
    print("[1] 唤醒 + 永不熄屏")
    at.adb('shell settings put secure screen_off_timeout 2147483647')
    for _ in range(3):
        if 'Awake' in at.adb('shell dumpsys power | grep mWakefulness').stdout:
            break
        at.adb('shell input keyevent 26'); time.sleep(1)
    st = at.adb('shell dumpsys power | grep mWakefulness').stdout.strip()
    print(f"  {st}")

    # ===== 2. 启动 =====
    print("[2] 启动 WordBattle")
    # 记录本次会话起点（日志按时间戳过滤，防轮转文件混入旧日志）
    dev_time0 = adb("shell date '+%H:%M:%S'").stdout.strip()
    print(f"  会话起点: {dev_time0}")
    at.adb('shell am force-stop com.wordbattle'); time.sleep(1)
    at.adb('shell am start -n com.wordbattle/.MainActivity'); time.sleep(8)

    # ===== 3. 进入错题练习 =====
    print("[3] 错题本 → 错题练习 → 20 题")
    pos = at.find_button_center('错题本')
    if not pos: print("  FAIL 没找到'错题本'"); return 1
    at.tap(*pos); time.sleep(2)
    pos = at.find_button_center('错题练习')
    if not pos: print("  FAIL 没找到'错题练习'"); return 1
    at.tap(*pos); time.sleep(2)
    pos = at.find_button_center(f'{ROUNDS}')
    if not pos: print(f"  FAIL 没找到'{ROUNDS} 题'按钮"); return 1
    at.tap(*pos)
    print(f"  已点'{ROUNDS} 题'，进入答题（全程不 tap，等超时自动推进）")

    # ===== 4. 等结束页：20 题 x (10s 超时 + 2s 反馈) ≈ 240s，轮询"练习完成" =====
    print("[4] 等待结束页（约 4 分钟）...")
    done = False
    t0 = time.time()
    while time.time() - t0 < 330:
        time.sleep(10)
        nodes = at.parse_nodes(at.dump_ui())
        texts = ' | '.join(n.get('text', '') for n in nodes if n.get('text'))
        if '练习完成' in texts:
            done = True
            break
        # 顺带打印进度
        m = re.search(r'第\s*(\d+)\s*/\s*(\d+)\s*题', texts)
        if m: print(f"    进度 第 {m.group(1)}/{m.group(2)} 题")
    check("结束页出现（20 题推进不卡死）", done, f"耗时 {time.time()-t0:.0f}s")
    at.screenshot('wt_done')

    # ===== 5. 拉日志验证 =====
    # 注：App 写设备本地文件依赖 MANAGE_EXTERNAL_STORAGE（本测试机未授予），
    # 因此以主机 log_receiver 的 UDP 日志为权威数据源（格式: [HH:MM:SS] [ver][tag] [L] msg）
    print("[5] 读主机 UDP 日志")
    adb('shell am force-stop com.wordbattle')
    from datetime import datetime
    host_log = f"/data/wordbattle/logs/remote_all_{datetime.now().strftime('%Y%m%d')}.log"
    if not os.path.exists(host_log):
        print(f"  FAIL 找不到主机日志 {host_log}（log_receiver 没跑?）"); return 1
    all_lines = open(host_log).read().splitlines()
    # 只取本次会话的日志（行首 [HH:MM:SS] >= 会话起点；今天无跨午夜）
    lines = []
    for l in all_lines:
        m = re.match(r'\[(\d{2}:\d{2}:\d{2})\]', l)
        if m and m.group(1) >= dev_time0:
            lines.append(l)
    print(f"  会话内日志 {len(lines)}/{len(all_lines)} 条")

    # V1 版本号
    ver_lines = [l for l in lines if NEW_VERSION in l]
    check("V1 新 VERSION 在运行日志", len(ver_lines) > 0,
          f"{len(ver_lines)} 条; 样例={ver_lines[0][:70] if ver_lines else '?'}")

    # V2 抽题序列
    picked = []
    for l in lines:
        m = re.search(r'\[Practice\] Q(\d+): 题目=(\S+)', l)
        if m: picked.append((int(m.group(1)), m.group(2)))
    picked.sort()
    seq = [w for _, w in picked]
    rounds_ok = [n for n, _ in picked] == list(range(1, ROUNDS + 1))
    check("V2 抽题序列 20 条完整", rounds_ok, f"实际 {len(picked)} 条: {seq}")

    c = Counter(seq)
    print("    分布:", dict(c.most_common()))

    if len(seq) == ROUNDS:
        # 词池 = 6 构造词 + 3 存量词（wolf/nod/rest），应全部出现（保底，确定性）
        c = Counter(seq)
        pool_words = {'history', 'biology', 'geography', 'textbook', 'eraser', 'ready',
                      'wolf', 'nod', 'rest'}
        missing = [w for w in pool_words if c.get(w, 0) < 1]
        check("V3 保底覆盖：9 词全部出现（每词≥1题）", not missing,
              f"缺席={missing} 分布={dict(c.most_common())}")
        # V4 权重排序：最高权重词 history 单频第一，且不低于任何低权重词
        top_word, top_cnt = c.most_common(1)[0]
        lows_ok = all(c.get('history', 0) >= c.get(w, 0) for w in ('textbook', 'eraser', 'ready'))
        check("V4 history 单频第一且>=每个低权重词",
              top_word == 'history' and lows_ok,
              f"最高频 {top_word}={top_cnt}; history={c.get('history',0)} textbook={c.get('textbook',0)} eraser={c.get('eraser',0)} ready={c.get('ready',0)}")
        # V5 高权重组 vs 低权重组（期望 9.6 vs 4.2，差 5.4，P 反转 <0.01%）
        hi = c.get('history', 0) + c.get('biology', 0)
        low = c.get('textbook', 0) + c.get('eraser', 0) + c.get('ready', 0)
        check("V5 高权重组>低权重组", hi > low, f"高={hi} 低={low}")
    else:
        check("V3-V5 分布断言（跳过，序列不全）", False, "")

    # V6 全超时
    to_lines = [l for l in lines if '[Practice] 超时' in l]
    check("V6 20 题全超时正常推进", len(to_lines) >= ROUNDS, f"日志超时记录 {len(to_lines)} 条")

    # ===== 收尾 =====
    at.adb('shell settings put secure screen_off_timeout 600000')
    at.adb('shell input keyevent 26')
    time.sleep(1)
    st = at.adb('shell dumpsys power | grep mWakefulness').stdout.strip()
    print(f"\n[收尾] {st}")

    print(f"\n{'='*50}\n结果: PASS {len(PASS)} / FAIL {len(FAIL)}")
    for n, d in FAIL:
        print(f"  [FAIL] {n} {d}")
    return 1 if FAIL else 0

if __name__ == '__main__':
    sys.exit(main())
