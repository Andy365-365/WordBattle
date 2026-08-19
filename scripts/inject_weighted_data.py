#!/usr/bin/env python3
"""注入加权出题测试数据到真机错题本（6 词 wrongCount 梯度）"""
import subprocess, time, json, sys, re, html

DEVICE = "b054d001"
now_ms = int(time.time() * 1000)

# 权重梯度（weight = (1+wrongCount) * 2^(-hours/24)）
# word, meaning, wrongCount, hoursAgo, expected weight
DATA = [
    # 高权重：wrongCount 大 + 时间近
    ("history",   "历史",        10, 1.0,  11 * 2**(-1/24)  ),  # ≈ 10.67
    ("biology",   "生物",         8, 12.0,  9 * 2**(-12/24)  ),  # ≈  6.36
    ("geography", "地理",         5, 24.0,  6 * 2**(-24/24)  ),  # ≈  3.00
    # 中低权重
    ("textbook",  "教科书，教材，课本", 2, 48.0, 3 * 2**(-48/24)),  # ≈  0.75
    ("eraser",    "橡皮",         3, 72.0,  4 * 2**(-72/24)  ),  # ≈  0.50
    # 最低权重
    ("ready",     "准备好（做某事）的", 1, 108.0, 2 * 2**(-108/24)), # ≈ 0.10
]

def adb(c):
    return subprocess.run(f"adb -s {DEVICE} {c}", shell=True, capture_output=True, text=True)

def run_as(c):
    return subprocess.run(f'adb -s {DEVICE} shell "run-as com.wordbattle {c}"', shell=True, capture_output=True, text=True)

# 读现有数据
r = run_as("cat /data/data/com.wordbattle/shared_prefs/wrong_words.xml")
xml = r.stdout.strip()
# 提取 JSON（内容跨行，DOTALL）
m = re.search(r'wrong_word_list">(.*?)</string>', xml, re.S)
if not m:
    print("FAIL: 没找到现有数据"); sys.exit(1)
json_str = html.unescape(m.group(1))
existing = json.loads(json_str)
print(f"现有 {len(existing)} 条:")
for e in existing:
    print(f"  {e['word']} star={e['starLevel']} wrong={e['wrongCount']}")

# 覆盖语义重建：6 个构造词按梯度值强制重置（防上轮答题污染 wrongCount/lastWrongTime），
# 其余存量词（wolf/nod/rest 等）保持原样。这样每次跑都能拿到同一份干净梯度。
grad_words = {d[0] for d in DATA}
merged = [e for e in existing if e['word'] not in grad_words]
for word, meaning, wc, hrs, _ in DATA:
    merged.append({
        "username": "玩家", "word": word, "meaning": meaning,
        "direction": "EN_TO_ZH", "starLevel": min(wc, 6),
        "wrongCount": wc, "lastWrongTime": int(now_ms - hrs * 3600 * 1000)
    })
print(f"\n合并后 {len(merged)} 条（6 构造词已按梯度覆盖重建）")

# 写回 XML
new_json = json.dumps(merged, ensure_ascii=False)
new_xml = f"""<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="wrong_word_list">{new_json.replace('"', '&quot;')}</string>
</map>"""

# 写文件
with open('/tmp/wrong_words.xml', 'w') as f:
    f.write(new_xml)

adb("push /tmp/wrong_words.xml /data/local/tmp/wrong_words.xml")
run_as("cp /data/local/tmp/wrong_words.xml /data/data/com.wordbattle/shared_prefs/wrong_words.xml")

# 验证
r2 = run_as("cat /data/data/com.wordbattle/shared_prefs/wrong_words.xml")
m2 = re.search(r'wrong_word_list">(.*?)</string>', r2.stdout, re.S)
if m2:
    v = json.loads(html.unescape(m2.group(1)))
    print(f"\n验证: 读到 {len(v)} 条")
    for e in v:
        hrs_ago = (now_ms - e['lastWrongTime']) / 3600000
        w = (1 + e['wrongCount']) * 2**(-hrs_ago / 24)
        print(f"  {e['word']:12s} wrong={e['wrongCount']:2d} {hrs_ago:6.1f}h ago  weight≈{w:.2f}")
else:
    print("FAIL: 写入验证失败"); sys.exit(1)

print("\nDONE")
