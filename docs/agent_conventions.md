# WordBattle — Agent 协作约定（稳定规则）

> 项目特有的稳定约定放这里（不放 AI 记忆，记忆只留指针）。
> 进度/下一步看 `docs/交接文档_20260817.md`；真机测试操作细则看 `docs/测试纪律.md`。

## 构建与交付

- 编译一律 `bash scripts/build.sh`（自动更新版本号 + clean + 出 APK），禁止手动 gradle
  （gradle 命令需 export ANDROID_HOME=/usr/lib/android-sdk JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64，
  用 /opt/gradle-8.2/bin/gradle）
- 双 flavor（host/client）：单测任务名必须带 flavor，如 `:app:testHostDebugUnitTest`，
  裸 `:app:testDebugUnitTest` 会报 ambiguous
- 交付三位一体：commit + push + tag（tag 单独 push）；打 tag 前先 commit 未提交改动
- GPU 资源紧张：子 agent 串行（开发→编译→测试）避免 OOM，只开必要 toolsets；
  绝不开任何占 GPU 的容器/进程（工作站只够跑一个 vLLM 容器）

## 工作流

- 需求文档 → 评审对齐 → 技术实现；多要点逐条回应
- 先出方案对齐 → 用户点头 → 才写代码；改完 build.sh 编译 → 真机验证 → commit+push+tag
- 方案用大白话讲思路，不贴代码/文件级改动清单；先对齐再谈实施细节
- 测试前必读 `docs/测试纪律.md` 并逐条执行，收尾逐条核对（含息屏 + 超时恢复）
- 每轮任务结束给一次干净的最终总结（改动/证据/交付物），过程分析不算汇报

## 测试机（Redmi K20 Pro）

- 序列号 b054d001，IP 192.168.50.187，USB ADB
- 已开 USB 调试 / USB 安装 / USB 安全设置 / 无锁屏
- MIUI 怪癖：
  - KEYCODE_WAKEUP 被静默丢弃，亮屏/息屏切换用 KEYCODE_POWER
  - adb install 弹窗靠"USB 安装"开关消除
  - Shizuku 不可用
- 日志：主机跑 `python3 scripts/log_receiver.py` 收 8765 端口 UDP → logs/remote_all_YYYYMMDD.log
- 装包：`python3 scripts/auto_install.py 0.5 <apk路径>`
- 读错题库：`adb shell "run-as com.wordbattle cat /data/data/com.wordbattle/shared_prefs/wrong_words.xml"`
- 收尾铁律：KEYCODE_POWER 息屏（确认 mWakefulness=Asleep）+ screen_off_timeout 恢复 600000
- 测试脚本坑：uiautomator dump ~1s 别在答题窗口前 dump；日志按 mtime 选文件；
  Compose 文本用前缀匹配；方向 RadioButton 在文本左侧（钮右缘贴文本左缘配对）；
  连续多局先 force-stop 防 EADDRINUSE + startGame 0 人

## 词库

- words.json 为单条配对格式（243 条，2026-08-17 起）：word=英文、translation=中文、
  zhDistractors/enDistractors 两套后备
- 重新生成用 `python3 scripts/merge_words.py`（先 --check 校验）
