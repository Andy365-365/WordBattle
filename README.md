# WordBattle - 局域网单词抢答游戏

多人 LAN 对战的单词答题应用，基于 TCP/UDP 网络通信，支持一台设备同时担任主机和玩家。

## 功能

- **主机模式**：创建房间，控制出题方向和轮次
- **抢答者模式**：自动发现局域网内的游戏房间，加入抢答
- **UDP 发现**：自动广播和发现局域网主机
- **TCP 通信**：稳定的游戏状态同步和实时答题
- **单机双角色**：主机可以自动加入自己，无需额外设备测试

## 技术栈

- Kotlin + Android Compose
- kotlinx.serialization (JSON)
- kotlinx.coroutines
- 网络协议：JSON over TCP (4字节长度前缀) + UDP 广播发现

## 网络协议

| 消息类型 | 方向 | 说明 |
|---------|------|-----|
| ADVERTISE | Host → UDP广播 | 主机广播 |
| JOIN | Client → Host | 玩家加入 |
| WELCOME | Host → Client | 分配玩家ID |
| PREPARE | Host → Broadcast | 准备下一题 |
| GO | Host → Broadcast | 发送题目 |
| ANSWER | Client → Host | 提交答案 |
| REVEAL | Host → Broadcast | 揭晓答案 |
| SCORE | Host → Broadcast | 更新比分 |
| GAMEOVER | Host → Broadcast | 游戏结束 |

## 构建

```bash
# 需要 Android SDK 34, JDK 17, Gradle 8.2
./gradlew :app:assembleDebug
```

构建产物：
- `app-host-debug.apk` (com.wordbattle)
- `app-client-debug.apk` (com.wordbattle.client)

## 调试

- 连续长按首页标题 5 次打开 Debug 页面
- 日志导出到 `/storage/emulated/0/Download/ts/wordbattle_debug.log`
- 每条日志带版本标记，确保日志与代码对应

## License

MIT