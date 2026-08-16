# WordBattle - 局域网单词抢答游戏

多人 LAN 对战的单词答题应用，基于 TCP/UDP 网络通信，支持一台设备同时担任主机和玩家。

## 功能

- **主机模式**：创建房间，控制出题方向和轮次
- **抢答者模式**：自动发现局域网内的游戏房间，加入抢答
- **UDP 发现**：自动广播和发现局域网主机
- **TCP 通信**：稳定的游戏状态同步和实时答题
- **单机双角色**：主机可以自动加入自己，无需额外设备测试
- **自动化测试支持**：READY 握手机制 + observer（观察者）身份，支持脚本 ADB tap 全自动答题验证

## 技术栈

- Kotlin + Android Compose
- kotlinx.serialization (JSON)
- kotlinx.coroutines
- 网络协议：JSON over TCP (4字节长度前缀) + UDP 广播发现

## 网络协议

| 消息类型 | 方向 | 说明 |
|---------|------|-----|
| ADVERTISE | Host → UDP广播 | 主机广播 |
| JOIN | Client → Host | 加入（`role` 字段：`player` 玩家 / `observer` 观察者，默认 player） |
| WELCOME | Host → Client | 分配玩家ID |
| PREPARE | Host → Broadcast | 准备下一题 |
| READY | Observer → Host | 观察者就绪，主机收到后广播 GO |
| GO | Host → Broadcast | 发送题目 |
| ANSWER | Client → Host | 提交答案（observer 的答案会被忽略） |
| REVEAL | Host → Broadcast | 揭晓答案 |
| SCORE | Host → Broadcast | 更新比分 |
| GAMEOVER | Host → Broadcast | 游戏结束 |

### READY 握手（自动化测试节奏同步）

自动化测试时脚本链路（读 GO → dump 屏幕 → tap）比人的反应慢，若主机固定节奏翻页，tap 会落在错误的题目上。因此：

- **有 observer 在场**：主机发 PREPARE 后等待 READY，收到才广播 GO 并启动答题计时（等待上限 5s，超时照常 GO，防卡死）
- **真人对局（无 observer）**：保持原 500ms 节奏，行为不变

### observer（观察者）

JOIN 时携带 `role: "observer"` 的客户端：

- 收全部广播（PREPARE/GO/REVEAL…），可发 READY
- **不进玩家列表**：不计分、结果页不显示、不占答题名额
- 断线自动清理，不影响后续对局

## 构建

```bash
# 需要 Android SDK (34), JDK 17, Gradle 8.2（无 gradle wrapper，用系统 gradle）
export ANDROID_HOME=/usr/lib/android-sdk
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
/opt/gradle-8.2/bin/gradle :app:assembleDebug
```

构建产物：
- `app/build/outputs/apk/host/debug/app-host-debug.apk` (com.wordbattle)
- `app/build/outputs/apk/client/debug/app-client-debug.apk` (com.wordbattle.client)

单元测试：

```bash
/opt/gradle-8.2/bin/gradle :app:testHostDebugUnitTest
```

版本号约定（`DebugLog.kt` 的 `VERSION`，也是 git tag 格式）：`v<大版本>-<YYYYMMDD>-<HHMM>`，如 `v2.2-20260816-1943`。
每次改动 App 侧代码都更新 VERSION 再编译，日志带版本标记便于定位。

## 自动化测试（ADB + TCP 握手）

`scripts/auto_test.py`：在真机（小米 K20 Pro，USB ADB）上全自动跑一局——
唤醒 → 启动 App → 设置页选参数 → 开始游戏 → 脚本以 **observer** 身份 TCP 连接收 GO（含 correctIdx）→ ADB tap 选项 → 每题校验屏幕与 GO 一致 → 汇总命中数 → 息屏。

```bash
python3 scripts/auto_test.py --rounds 20 --seconds 10
```

参数：

| 参数 | 取值 | 说明 |
|------|------|------|
| `--rounds` | 5/10/20/30 | 题数（默认 10） |
| `--seconds` | 5/10/20/30/120 | 答题等待秒数（默认 5） |
| `--mode` | all_correct/all_wrong/random | tap 策略（默认全对） |
| `--device` | adb 序列号 | 默认 b054d001 |
| `--ip` | 设备 IP | 默认 192.168.50.187 |

依赖 `scripts/log_receiver.py`（App 日志实时拉取，脚本会自动重启它）。

铁律：**必须 ADB tap 模拟真人点击**，脚本禁止 TCP 直发 ANSWER。

配套脚本：

- `scripts/dump_setup.py` — 设置页布局诊断（打印每个按钮 bounds/中心/可点击父）
- `scripts/tests/` — 脚本核心逻辑本地单测（不上设备）：
  - `test_wait_for.py` — GO 消息命中即删除（防陈旧消息打转）
  - `test_wait_for_race.py` — 读线程/主线程共享队列并发安全
  - `test_find_button.py` — 设置页同名按钮标签锚定定位
- 运行：`python3 scripts/tests/test_wait_for.py` 等

## 调试

- 连续长按首页标题 5 次打开 Debug 页面
- 日志导出到 `/storage/emulated/0/Download/ts/wordbattle_debug.log`
- 每条日志带版本标记，确保日志与代码对应

## License

MIT
