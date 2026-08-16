#!/usr/bin/env python3
"""设置页布局诊断：进入 主机设置 页，dump 所有节点（文本/bounds/中心/可点击父）。
用途：核对设置页按钮坐标、排查点击偏移问题（auto_test.py 参数化定位的配套工具）。

用法:
  python3 scripts/dump_setup.py [--device b054d001]

跑完会自动 force-stop App，不留残留状态。
"""
import sys, importlib.util, time, argparse
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.argv = ["auto_test.py"]  # 防止 import auto_test 时消费本脚本的参数
spec = importlib.util.spec_from_file_location("at", str(HERE / "auto_test.py"))
at = importlib.util.module_from_spec(spec)
spec.loader.exec_module(at)

ap = argparse.ArgumentParser(description="WordBattle 设置页布局诊断")
ap.add_argument("--device", default="b054d001")
args = ap.parse_args()
at.DEVICE = args.device

at.adb("shell input keyevent 26"); time.sleep(1)
at.adb("shell am force-stop com.wordbattle"); time.sleep(1)
# MIUI 上 am start 后 App 可能浮不起（mCurrentFocus=null），用 back 清残留 + 多轮 am start 恢复
pos = None
for attempt in range(3):
    at.adb("shell input keyevent 4"); time.sleep(0.5)  # 清掉残留窗口/小窗
    at.adb("shell input keyevent 3"); time.sleep(1)    # 回桌面
    at.adb("shell am start -n com.wordbattle/.MainActivity"); time.sleep(8)
    for _ in range(3):  # uiautomator 偶发拉空，find 前重试
        if at.find_button_center("主机+答题"):
            break
        time.sleep(2)
    pos = at.find_button_center("主机+答题")
    if pos:
        break
    print(f"  第{attempt+1}次尝试未找到'主机+答题'，重试...")
if not pos:
    print("FAIL: 没找到'主机+答题'（主页未就绪？）")
    at.adb("shell am force-stop com.wordbattle")
    sys.exit(1)
at.tap(*pos)
time.sleep(2)

# MIUI 的 uiautomator dump 偶发拉空/缺节点，重试直到出现"主机设置"
nodes = []
for attempt in range(4):
    nodes = at.parse_nodes(at.dump_ui())
    if any(n['text'] == '主机设置' for n in nodes):
        break
    print(f"  dump 第{attempt+1}次未拿到设置页节点，重试...")
    time.sleep(2)
if not any(n['text'] == '主机设置' for n in nodes):
    print(f"FAIL: dump 重试后仍无设置页（节点数 {len(nodes)}）")
    at.adb("shell am force-stop com.wordbattle")
    sys.exit(1)

print(f"设置页节点数: {len(nodes)}")
for n in nodes:
    p = at.pb(n['bounds'])
    if not p:
        continue
    cx, cy = (p[0] + p[2]) // 2, (p[1] + p[3]) // 2
    mark = ""
    if n['text'] in ('5', '10', '20', '30', '120'):
        par = at.find_clickable_parent(nodes, p)
        mark = f"  <--可点击父={par}"
    print(f"  text={n['text']!r:24} bounds=[{p[0]},{p[1]},{p[2]},{p[3]}] center=({cx},{cy}) click={n['clickable']}{mark}")

at.adb("shell am force-stop com.wordbattle")
print("done")
