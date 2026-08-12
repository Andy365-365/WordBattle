#!/usr/bin/env python3
import subprocess, time, sys, xml.etree.ElementTree as ET

DEVICE = "b054d001"
APK = "/data/wordbattle/app/build/outputs/apk/host/debug/app-host-debug.apk"
DELAY = float(sys.argv[1]) if len(sys.argv) > 1 else 1.0

print(f"=== Testing delay: {DELAY}s ===")

# Trigger install in background
p = subprocess.Popen(
    f'adb -s {DEVICE} install -r {APK}',
    shell=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE
)

# Wait
time.sleep(DELAY)

# Dump UI
subprocess.run(f'adb -s {DEVICE} shell uiautomator dump /sdcard/ui.xml', shell=True, capture_output=True)
subprocess.run(f'adb -s {DEVICE} pull /sdcard/ui.xml /tmp/ui_dump.xml', shell=True, capture_output=True)

# Parse XML to find "继续安装" button
tree = ET.parse('/tmp/ui_dump.xml')
root = tree.getroot()

found = False
for node in root.iter('node'):
    if node.get('text') == '继续安装':
        bounds = node.get('bounds')
        clickable = node.get('clickable')
        # Parse bounds "[x1,y1][x2,y2]"
        parts = bounds.replace('[', ' ').replace(']', ' ').replace(',', ' ').split()
        x1, y1, x2, y2 = int(parts[0]), int(parts[1]), int(parts[2]), int(parts[3])
        cx = (x1 + x2) // 2
        cy = (y1 + y2) // 2
        print(f"  [FOUND] Dialog at {DELAY}s, bounds={bounds}, center=({cx}, {cy}), clickable={clickable}")
        # Tap immediately
        subprocess.run(f'adb -s {DEVICE} shell input tap {cx} {cy}', shell=True)
        print(f"  [TAP] ({cx}, {cy})")
        found = True
        break

if not found:
    print(f"  [MISS] Dialog not found at {DELAY}s")

# Wait for result
time.sleep(12)
result = subprocess.run(
    f'adb -s {DEVICE} shell pm path com.wordbattle',
    shell=True, capture_output=True, text=True
)
if 'wordbattle' in result.stdout:
    print(f"  [SUCCESS] WordBattle installed!")
    sys.exit(0)
else:
    print(f"  [FAILED]")
    sys.exit(1)