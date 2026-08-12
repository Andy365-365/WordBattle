#!/usr/bin/env python3
"""WordBattle 完整自动化测试"""
import subprocess
import time
import re
import random

DEVICE = "b054d001"
APK = "/data/wordbattle/app/build/outputs/apk/host/debug/app-host-debug.apk"

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

    # Step 2: 卸载
    print("\n[2/12] 卸载旧版...")
    r = adb('shell pm uninstall com.wordbattle')
    print(f"  {'✅' if 'Success' in r.stdout or 'not found' in r.stdout else '⚠️'} {r.stdout.strip()}")

    # Step 3: 安装
    print("\n[3/12] 自动安装...")
    r = subprocess.run('python3 /data/wordbattle/scripts/auto_install.py', shell=True, capture_output=True, text=True, timeout=30)
    print(f"  {'✅ 安装成功' if 'SUCCESS' in r.stdout else '❌ ' + r.stdout.strip()}")
    if 'SUCCESS' not in r.stdout:
        return
    time.sleep(1)

    # Step 4: 启动
    print("\n[4/12] 启动 WordBattle...")
    adb('shell am start -n com.wordbattle/.MainActivity')
    time.sleep(3)
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

    # Step 6: 设置30题（y<900区域）
    print("\n[6/12] 设置30题...")
    pos = find_button_by_y('30', 0)  # 第一个30（题目数下，y较小）
    if pos:
        tap(*pos)
        print(f"  ✅ tap({pos[0]}, {pos[1]})")
    else:
        print("  ❌ 没找到30题按钮")
        return
    time.sleep(0.5)

    # Step 7: 设置30秒等待时间（y>900区域）
    print("\n[7/12] 设置30秒等待时间...")
    pos = find_button_by_y('30', 900)  # 第二个30（答题等待时间下，y较大）
    if pos:
        tap(*pos)
        print(f"  ✅ tap({pos[0]}, {pos[1]})")
    else:
        print("  ❌ 没找到30秒按钮")
        return
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

    # Step 10: 答题循环
    print("\n[10/12] 答题循环...")
    total_answers = 0

    for i in range(50):
        time.sleep(4)
        xml = dump_ui()
        nodes = parse_nodes(xml)

        # 游戏结束的可靠判断：状态包含 REVEAL 或 WAITING 且无答案按钮
        texts = {n['text'] for n in nodes}
        ans_buttons = find_answer_buttons(nodes)
        status = get_status(nodes)
        question = get_question(nodes)

        if not question:
            print(f"  [轮{i}] 未检测到题目，检查状态...")
            if '重新开始' in texts and '主机' in texts:
                # 检查是否真的结束了（有排名无答题按钮）
                if not ans_buttons and status and 'REVEAL' in status:
                    print("  游戏已结束（最后一题揭晓）")
                    break
            continue

        try:
            qnum = int(question.split()[1].split('/')[0])
        except:
            qnum = i

        # 正常答题逻辑
        if 'ANSWERING' in (status or '') and ans_buttons:
            bx, by, btext = random.choice(ans_buttons)
            tap(bx, by)
            total_answers += 1
            print(f"  [题{qnum}] 答题 [{btext}] (共{total_answers}次)")
        elif status and status.startswith('REVEAL'):
            correct = status.split('text=')[-1] if 'text=' in status else '?'
            print(f"  [题{qnum}] 答案: {correct}")
        elif status:
            print(f"  [题{qnum}] {status}")

        if qnum >= 30:
            print("  已到第30题，等待揭晓...")
            time.sleep(12)
            break

    # Step 11: 结果
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
    print(f"\n{'=' * 60}")
    print("测试完成！")
    print(f"{'=' * 60}")

if __name__ == '__main__':
    main()