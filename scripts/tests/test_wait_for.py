"""本地验证 wait_for 命中即删除：模拟 run#3 的陈旧消息场景。"""
import sys, time
from collections import deque

# 复制 GameTcp.wait_for 的逻辑（与 auto_test.py 保持一致）
def wait_for(queue, match_fn, timeout=20.0):
    deadline = time.time() + timeout
    while time.time() < deadline:
        for idx, m in enumerate(queue):
            if match_fn(m):
                queue.remove(m)
                return m
        if time.time() < deadline - 0.1:
            time.sleep(0.05)
    return None

# 模拟：队首是第1轮已消费的 PREPARE/GO（run#3 中的陈旧消息）
q = deque([
    {'type': 'PREPARE', 'round': 1},
    {'type': 'GO', 'round': 1, 'question': 'start', 'correctIdx': 3},
])
is_prepare = lambda m: m.get('type') == 'PREPARE' or (m.get('type') == 'GO' and m.get('correctIdx', -1) >= 0)
is_go = lambda m: m.get('type') == 'GO' and m.get('correctIdx', -1) >= 0

# 轮0：应消费掉这两条
p = wait_for(q, is_prepare, timeout=1)
g = wait_for(q, is_go, timeout=1)
assert p['round'] == 1 and g['question'] == 'start'
assert len(q) == 0, f"轮0后队列应为空, 实际={list(q)}"

# 模拟 App 在轮0结束后广播轮2 的 PREPARE
q.append({'type': 'PREPARE', 'round': 2})
q.append({'type': 'GO', 'round': 2, 'question': 'ocean', 'correctIdx': 1})

# 轮1：绝不能再次返回 round=1 的陈旧消息，必须拿到 round=2
p2 = wait_for(q, is_prepare, timeout=1)
g2 = wait_for(q, is_go, timeout=1)
assert p2['round'] == 2, f"陈旧消息复发! 拿到 round={p2['round']}"
assert g2['question'] == 'ocean'

# 超时行为不变：没有匹配时返回 None
assert wait_for(q, lambda m: m.get('type') == 'GAME_OVER', timeout=0.3) is None

print("全部通过：命中即删除生效，无陈旧消息打转，超时行为正常")
