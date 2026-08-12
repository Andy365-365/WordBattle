#!/usr/bin/env python3
"""WordBattle 自动化测试脚本 - 完成一轮30题的测试"""
import subprocess
import time
import xml.etree.ElementTree as ET
import sys

DEVICE = "b054d001"

def dump_ui():
    """Dump UI hierarchy and return parsed tree"""
    subprocess.run(f'adb -s {DEVICE} shell uiautomator dump /sdcard/ui.xml', 
                   shell=True, capture_output=True)
    subprocess.run(f'adb -s {DEVICE} pull /sdcard/ui.xml /tmp/wb_ui.xml', 
                   shell=True, capture_output=True)
    return ET.parse('/tmp/wb_ui.xml').getroot()

def find_text(root, text):
    """Find node with exact text and return its center coordinates"""
    for node in root.iter('node'):
        if node.get('text') == text:
            bounds = node.get('bounds', '')
            parts = bounds.replace('[', ' ').replace(']', ' ').replace(',', ' ').split()
            if len(parts) == 4:
                return (int(parts[0])+int(parts[2]))//2, (int(parts[1])+int(parts[3]))//2
    return None

def find_text_contains(root, text):
    """Find node containing text and return its center coordinates"""
    for node in root.iter('node'):
        if text in node.get('text', ''):
            bounds = node.get('bounds', '')
            parts = bounds.replace('[', ' ').replace(']', ' ').replace(',', ' ').split()
            if len(parts) == 4:
                return (int(parts[0])+int(parts[2]))//2, (int(parts[1])+int(parts[3]))//2
    return None

def tap(x, y):
    """Tap at coordinates"""
    subprocess.run(f'adb -s {DEVICE} shell input tap {x} {y}', shell=True)

def screenshot(name):
    """Take screenshot"""
    subprocess.run(f'adb -s {DEVICE} exec-out screencap -p > /tmp/{name}.png', shell=True)

def get_current_question():
    """Get current question number from UI"""
    root = dump_ui()
    for node in root.iter('node'):
        text = node.get('text', '')
        if '第' in text and '/' in text:
            return text
    return None

def get_status():
    """Get current game status"""
    root = dump_ui()
    for node in root.iter('node'):
        text = node.get('text', '')
        if '状态:' in text:
            return text
    return None

def wait_for_text(text, timeout=30):
    """Wait for text to appear in UI"""
    start = time.time()
    while time.time() - start < timeout:
        root = dump_ui()
        for node in root.iter('node'):
            if node.get('text') == text:
                return True
        time.sleep(0.5)
    return False

def main():
    print("=== WordBattle 自动化测试 ===")
    
    # 等待游戏开始
    print("等待游戏开始...")
    time.sleep(2)
    
    # 监控游戏进度
    last_question = None
    max_question = 30
    
    for i in range(max_question + 5):  # 额外5次以防万一
        time.sleep(2)
        
        # 获取当前题目
        question = get_current_question()
        status = get_status()
        
        if question:
            print(f"[{time.strftime('%H:%M:%S')}] {question} | {status}")
            screenshot(f'wb_q{i}')
            
            # 检查是否完成
            if '第 30/30 题' in question or i >= max_question:
                print("等待最后一题完成...")
                time.sleep(5)
                break
            
            last_question = question
        else:
            print(f"[{time.strftime('%H:%M:%S')}] 未检测到题目，检查游戏状态...")
            screenshot(f'wb_check{i}')
            break
    
    # 检查游戏是否结束
    print("\n检查游戏结束状态...")
    root = dump_ui()
    texts = [node.get('text', '') for node in root.iter('node')]
    print(f"当前界面文本: {[t for t in texts if t]}")
    
    # 如果有"重新开始"按钮，说明游戏结束了
    if '重新开始' in texts:
        print("✅ 游戏完成！")
        screenshot('wb_complete')
    else:
        print("⚠️  游戏可能未正常结束")
        screenshot('wb_incomplete')

if __name__ == '__main__':
    main()