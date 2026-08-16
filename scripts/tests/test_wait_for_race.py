"""并发压力测试：读线程持续 append，主线程循环 wait_for（快照版），
复现 run5 的 'deque mutated during iteration' 场景。"""
import threading, time
from collections import deque

def wait_for_snap(queue, match_fn, timeout=20.0):
    deadline = time.time() + timeout
    while time.time() < deadline:
        snapshot = list(queue)          # 先取快照，迭代快照而非活队列
        for m in snapshot:
            if match_fn(m):
                try:
                    queue.remove(m)     # 命中即删（按值，存在才删）
                except ValueError:
                    continue            # 已被并发消费，继续找下一个
                return m
        if time.time() < deadline - 0.1:
            time.sleep(0.05)
    return None

queue = deque()
stop = False
errors = []

def reader():
    n = 0
    while not stop:
        n += 1
        queue.append({'type': 'PREPARE', 'round': n})
        queue.append({'type': 'GO', 'round': n, 'question': f'q{n}', 'correctIdx': 0})
        time.sleep(0.001)   # 高频插入，最大化撞车概率

t = threading.Thread(target=reader, daemon=True)
t.start()

# 主线程：模拟答题循环，消费 30 轮 PREPARE+GO，每轮间隔极小
is_prep = lambda m: m.get('type') == 'PREPARE'
is_go = lambda m: m.get('type') == 'GO'
rounds = 0
last_round = 0
try:
    for _ in range(30):
        p = wait_for_snap(queue, is_prep, timeout=5)
        g = wait_for_snap(queue, is_go, timeout=5)
        if p is None or g is None:
            errors.append("超时")
            break
        assert g['round'] == p['round'] and g['round'] > last_round, \
            f"轮次错乱: prep={p['round']} go={g['round']} last={last_round}"
        last_round = g['round']
        rounds += 1
except RuntimeError as e:
    errors.append(f"迭代中变更异常: {e}")
stop = True
t.join(timeout=1)

# 超时行为：没有 GAME_OVER 时返回 None
assert wait_for_snap(queue, lambda m: m.get('type') == 'GAME_OVER', timeout=0.3) is None

if errors:
    print(f"失败: {errors}, 完成 {rounds} 轮")
else:
    print(f"全部通过：30 轮并发消费无异常、轮次严格递增、超时行为正常")
