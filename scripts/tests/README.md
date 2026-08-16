"""归档说明：scripts/tests 下是 auto_test.py 核心逻辑的本地单测（不上设备）。

- test_wait_for.py       GameTcp.wait_for 命中即删除逻辑（陈旧消息打转回归）
- test_wait_for_race.py  wait_for 并发安全（读线程 append vs 主线程迭代）
- test_find_button.py    find_button_under_label 设置页同名按钮锚定定位

运行：python3 scripts/tests/test_wait_for.py （依次）
"""
