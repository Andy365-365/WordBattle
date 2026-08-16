# 自动化测试 tap/round 问题 交接文档

**日期**：2026-08-15
**Git Tag**：`before-ready-sync`（修复前基线）
**状态**：已解决（2026-08-16，READY 握手实施，30/30 验证通过；见 commit 记录与 tag `v2.2-20260816-1756`）

---

## 问题现象

自动化测试脚本运行 30 题，得分仅 7 分左右（理论应 30 分）。

## 排查过程与发现

### 第一轮分析（误判）

最初认为是 `handleAnswer` 异步回调导致的 race condition——handleAnswer 在 `nextRound` 之后执行，`currentRound` 已指向下一题。

**修改了代码但效果不明显**：
- `GameEngine.kt`：handleAnswer 改用 `rounds.find { it.round == answer.round }` 而非 `currentRound`
- `HostGameScreen.kt` / `PlayerGameScreen.kt`：onAnswer 签名改为 `(round: Int, choice: Int)`
- `MainActivity.kt`：两处 ANSWER 发送改用回调传的 round

**但 round 仍然从 3 开始**（应为 1）。

### 第二轮分析（正确定位）

**根因**：`roundState.round` 是可变 Compose state。GO 第 1、2 题因脚本没 ready 被 timeout 推进了，等脚本 tap 时，`roundState.round` 已经是 3。

**时间线**：
```
校准阶段（3-5秒）→ 第1题GO广播 → 5秒timeout → REVEAL → nextRound
第2题GO广播 → 5秒timeout → REVEAL → nextRound
第3题GO广播 → 脚本 tap（此时 roundState.round=3）
```

**核心矛盾**：脚本响应慢（校准约 3-5 秒 + sleep(3) = 约 6-8 秒），超过 App 的 5 秒 timeout，导致前 2-3 题被 timeout 推进。

### 第三轮分析（节奏不匹配）

加了 `sleep(3)` 耗时调试，确认：
- sleep(3) 每次确实执行 3.0 秒
- 但 GO 到 ANSWER 的时间间隔并不一致（0秒、3秒、1秒都有）
- **GO 间隔由 App 决定**：答对立即推进，答错等 5 秒 timeout
- **脚本 tap 间隔固定**：约 3-4 秒（sleep(3) + 解析）

两者节奏不完全匹配，导致 round 持续错位。

### 日志丢失问题

最后几题（29、30）的 handleAnswer 日志缺失，原因是脚本测试结束后立即 `force-stop` App，异步回调未完成就被杀掉了。

---

## 已修改的文件

| 文件 | 修改内容 |
|------|---------|
| `app/src/main/java/com/wordbattle/game/GameEngine.kt` | handleAnswer 用 `rounds.find` 定位题目；新增 `rounds` 集合 |
| `app/src/main/java/com/wordbattle/ui/HostGameScreen.kt` | onAnswer 签名改为 `(round: Int, choice: Int)`；加了 `key(roundState.round)` |
| `app/src/main/java/com/wordbattle/ui/PlayerGameScreen.kt` | onAnswer 签名改为 `(round: Int, choice: Int)`；新增 `round` 参数 |
| `app/src/main/java/com/wordbattle/MainActivity.kt` | 两处 onAnswer 回调改为 `{ round, choice -> round = round }` |

**Git Commit**：`0f71ac1 fix: correct ANSWER round tracking`
**Git Tag**：`before-ready-sync`

---

## 讨论的解决方案（未实施）

### 方案：READY 同步机制

**核心思路**：终端先预渲染 UI（不显示）→ 通知主机 ready → 主机发真正的 GO 并开始计时。

**具体流程**：
1. 主机发 PREPARE（含完整题目/选项数据）
2. 终端收到 PREPARE → 内存渲染 UI（不显示到屏幕上）→ 返回 READY
3. 主机收到 READY → 发真正的 GO（含 timer）
4. 终端收到 GO → 显示 UI，开始倒计时

**优势**：5 秒倒计时纯粹是答题时间，不含 UI 渲染时间。

**需改动**：
- GameMessage：PREPARE 消息携带完整题目数据
- TcpServer/TcpClient：处理 READY 的收/发
- GameEngine：收到 GO 后不立即启动 timeout，等 READY 后再启动
- HostGameScreen / PlayerGameScreen：控制 UI 的可见性（用 `if` 控制是否渲染，而非 `enabled`）

---

## 关键证据

- 日志路径：`/data/wordbattle/logs/remote_all_20260815.log`
- 脚本：`/data/wordbattle/scripts/auto_test.py`（当前有 DEBUG 输出）
- 测试版本：`v2.0-20260814-2316`（host 端）
