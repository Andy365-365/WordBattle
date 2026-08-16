"""本地验证 find_button_under_label：模拟设置页（题目数行 + 答题时间行，含同名按钮歧义）。
模拟布局依据 2026-08-16 真实 dump：
  题目数行按钮 y 中心 ~1127：'5' x中128 | '10' x中331 | '20' x中552 | '30' x中776
  答题时间行按钮 y 中心 ~1431：'5' x中154 | '10' x中396 | '20' x中638 | '30' x中880 | '120' x中1019
"""
import sys, importlib.util
from pathlib import Path
spec = importlib.util.spec_from_file_location("at", str(Path(__file__).resolve().parent.parent / "auto_test.py"))
at = importlib.util.module_from_spec(spec)
spec.loader.exec_module(at)

# 构造节点（bounds 格式 [x1,y1][x2,y2]，高 96 与真实一致）
def node(text, cx, cy):
    return {"text": text, "bounds": f"[{cx-80},{cy-48}][{cx+80},{cy+48}]",
            "clickable": "true", "contentDescription": ""}

fake_nodes = (
    [node("题目数", 270, 1000),
     node("5", 128, 1127), node("10", 331, 1127), node("20", 552, 1127), node("30", 776, 1127)] +
    [node("答题等待时间(秒)", 270, 1304),
     node("5", 154, 1431), node("10", 396, 1431), node("20", 638, 1431), node("30", 880, 1431), node("120", 1019, 1431)]
)

# 桩掉 dump_ui，避免真机调用
at.dump_ui = lambda: "fake"
# 桩掉 parse_nodes 直接返回模拟节点
at.parse_nodes = lambda xml: fake_nodes

def check(label, btn, want_label_row, want_x):
    pos, b = at.find_button_under_label(fake_nodes, want_label_row, btn)
    assert pos, f"{label}: 没找到按钮!"
    cx, cy = pos
    row = "题目数行" if cy < 1250 else "答题时间行"
    ok_row = "题目数行" if want_label_row == "题目数" else "答题时间行"
    status = "OK" if (row == ok_row and abs(cx - want_x) < 20) else "FAIL"
    print(f"  {status}: {label} -> tap({cx},{cy}) [{row}]")
    return status == "OK"

allok = True
for v in ["5", "10", "20", "30"]:
    allok &= check(f"题数={v}", v, "题目数", {"5":128,"10":331,"20":552,"30":776}[v])
for v in ["5", "10", "20", "30"]:
    allok &= check(f"答题时间={v}秒", v, "答题等待时间(秒)", {"5":154,"10":396,"20":638,"30":880}[v])

# '120' 不应误匹配 '10'（文本精确匹配）
pos, _ = at.find_button_under_label(fake_nodes, "答题等待时间(秒)", "10")
assert pos and pos[0] == 396, f"'120' 干扰了 '10' 的匹配: {pos}"
print("  OK: '120' 不误伤 '10'（精确文本匹配）")

# 标签不存在时返回 (None, None)
pos, b = at.find_button_under_label(fake_nodes, "不存在的标签", "5")
assert pos is None
print("  OK: 标签缺失安全返回 None")

print("全部通过" if allok else "存在失败!")
sys.exit(0 if allok else 1)
