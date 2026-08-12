# WordBattle 项目状态交接文档

> 生成时间: 2026-08-12 | 当前提交: `b381644` | 版本: v1.2

---

## 1. 项目概述

英语抢答对战游戏，支持主机端出题/答题/计分 + 抢答者端答题。基于 UDP 局域网发现 + TCP 通信，Android Compose 实现。

**GitHub**: `Andy365-365/WordBattle` (branch: `master`)

---

## 2. 技术栈

| 层级 | 技术 |
|------|------|
| 语言 | Kotlin + Compose Multiplatform |
| 构建 | Gradle 8.2 + Kotlin 2.1.x |
| 网络 | UDP Discovery (局域网发现) + TCP (星型通信) |
| 数据 | WordRepository (词库) + UserRepository (玩家管理) |
| 协议 | 自研 TCP Codec (帧头+长度+JSON) + GameMessage (kotlinx.serialization) |
| 构建环境 | Android SDK + JDK 17 |

---

## 3. 项目结构

```
app/src/main/java/com/wordbattle/
├── MainActivity.kt          # 路由: Home → HostSetup → HostWaiting → HostGame / PlayerGame → Result
├── data/
│   ├── Word.kt              # 单词模型 (questionText + 4 options)
│   ├── WordRepository.kt    # 词库 (硬编码词表)
│   ├── UserRepository.kt    # 本地玩家管理
│   └── CrashHandler.kt
├── network/
│   ├── UdpDiscovery.kt      # UDP 广播发现主机 (端口 5353)
│   ├── TcpServer.kt         # 主机端 TCP 服务器 (端口 5353)
│   ├── TcpClient.kt         # 客户端 TCP 连接
│   ├── TcpCodec.kt          # 帧协议: [4字节长度][JSON payload]
│   └── GameMessage.kt       # 协议消息定义 (kotlinx.serialization)
├── game/
│   └── GameEngine.kt        # 游戏状态机: READY→ANSWERING→REVEAL→WAITING→END
├── ui/
│   ├── Screen.kt            # 路由枚举
│   ├── HomeScreen.kt        # 主界面: 主机+答题 / 答题
│   ├── HostSetupScreen.kt   # 主机设置: 方向/题数/答题等待时间
│   ├── HostWaitingScreen.kt # 等待玩家加入
│   ├── HostGameScreen.kt    # 主机游戏界面 (含答题)
│   ├── PlayerJoinScreen.kt  # 客户端加入
│   ├── PlayerGameScreen.kt  # 客户端答题界面
│   ├── ResultScreen.kt      # 结算界面
│   ├── ScoreBoard.kt        # 排名组件
│   ├── UserManageScreen.kt  # 玩家管理
│   └── DebugScreen.kt       # 调试日志
└── debug/
    └── DebugLog.kt          # 日志系统 (VERSION="v1.2")
```

**脚本目录 (`scripts/`)**:
| 脚本 | 用途 |
|------|------|
| `auto_install.py` | MIUI 自动安装 APK (后台 install + 0.5s 延迟 + uiautomator dump + tap 继续安装) |
| `auto_test.py` | 完整自动化测试: 唤醒→卸载→安装→设置30题+30秒→开始→答题30题→结果→息屏 |
| `gen_words.py` | 词库生成脚本 |
| `log_receiver.py` | 调试日志接收 |

---

## 4. 网络协议

### GameMessage 类型

| 消息 | 方向 | 用途 |
|------|------|------|
| `PING` / `PONG` | 双向 | 连接保活 |
| `READY` | client→host | 客户端准备就绪 |
| `GO` | host→all | 发题 (round, question, options[], timer) |
| `ANSWER` | client→host | 提交答案 (playerId, round, optionIndex) |
| `REVEAL` | host→all | 揭晓答案 (round, correctIndex, text, scores[]) |
| `NEXT` | host→all | 下一题/结束通知 |
| `PLAYER_JOIN` | host→all | 玩家加入广播 |
| `START_GAME` | host→all | 游戏开始广播 |

### TCP Codec

```
帧格式: [4 bytes length (big-endian)][JSON payload]
```

---

## 5. 游戏流程 (GameEngine 状态机)

```
READY (准备)
  → 所有客户端 READY 后触发
  → 分发 GO 消息 (题目+选项+倒计时)

ANSWERING (答题中)
  → 主机和客户端均可答题 (onAnswer → broadcast ANSWER)
  → startTimeout 倒计时 (默认5秒, 可在设置中改)

REVEAL (揭晓)
  → 倒计时结束或全部答完
  → 广播 REVEAL (正确答案 + 各玩家得分)

WAITING (等待)
  → 等待所有客户端确认

循环回到 READY，直到达到 totalRounds → END
```

**关键公平性设计**：主机和客户端通过 GO 网络消息同时获取题目+选项，绝不使用本地数据提前渲染。

---

## 6. 主机设置参数

| 参数 | 选项 | 默认 |
|------|------|------|
| 翻译方向 | 英→中 / 中→英 | 英→中 |
| 题目数 | 5 / 10 / 20 / 30 | 10 |
| 答题等待时间 | 5 / 10 / 20 / 30 (秒) | 5 |

---

## 7. 构建与部署

### 构建命令

```bash
cd /data/wordbattle
export ANDROID_HOME=/usr/lib/android-sdk
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
/opt/gradle-8.2/bin/gradle :app:assembleDebug
```

### 产物

| Variant | APK 路径 |
|---------|----------|
| Host | `app/build/outputs/apk/host/debug/app-host-debug.apk` |
| Client | `app/build/outputs/apk/client/debug/app-client-debug.apk` |

### 安装到测试设备

```bash
python3 /data/wordbattle/scripts/auto_install.py
```

自动处理 MIUI 的"继续安装"确认对话框。

### 完整自动化测试

```bash
python3 /data/wordbattle/scripts/auto_test.py
```

执行: 唤醒→卸载→安装→设置(30题+30秒)→开始→自动答题30题→结果→息屏。
上次测试得分: 7分 (随机选择，30题)。

---

## 8. 测试环境

| 项目 | 详情 |
|------|------|
| 服务器 | Ubuntu 24.04, 2x RTX 3090 |
| 测试设备 | 小米 Redmi K20 Pro (M2007J17C) |
| 序列号 | `b054d001` |
| IP | `192.168.50.187` |
| ADB | USB 连接 |
| MIUI 特性 | `input keyevent 26` 唤醒/息屏; `input keyevent 264` 无效 |

---

## 9. 已知的 MIUI 特殊处理

1. **安装确认弹窗**：即使"USB安装"已开启，MIUI 仍弹出确认对话框。`auto_install.py` 通过后台 install + 0.5s 延迟 + uiautomator dump 精确 tap 解决。
2. **屏幕唤醒**：`input keyevent 26` (KEYCODE_POWER) 可切换亮/息屏；`keyevent 264` (WAKEUP) 被 MIUI 静默丢弃。
3. **Compose UI**：`uiautomator dump` 中 Button 的 clickable 属性在父节点（无 text），按钮文本在子节点（clickable=false）。自动化脚本需要查找最小 clickable parent。

---

## 10. Kotlin 序列化注意事项

所有用于 `kotlinx.serialization` 的 data class 必须标注 `@Serializable`，否则运行时报 `Serializer not found`。

---

## 11. 版本化配置

项目使用 Git 管理，push 到 `Andy365-365/WordBattle` (master 分支)。

---

## 12. 待办/已知问题

1. **词库规模有限**：当前硬编码词表，无 OCR 导入。
2. **仅单设备测试**：完整主机+客户端联调需第二台设备。
3. **答题脚本为随机选择**：`auto_test.py` 随机点选项，不验证答案正确性。