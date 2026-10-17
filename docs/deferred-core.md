# 依赖 modbus-core 的条目（暂缓清单）

> 来自 UI 对照清单，随各 PR 更新；以最新 PR 描述里的状态为准。

> 2026-10-17 更新：SessionCounters、previewFrame、帧解码三项已在 **application 层**完成（没有改 modbus-core），界面层不再直接引用 `core.rtu`。其中“日志时间基数”一项已由 PR-3 在 application 层（`TrafficRecorder`）解决，不再需要改 core。

> 约束：协议与 modbus-core 不动。下面每一条在 ui/ 内都**没有放假数据、没有放空壳**；列出所需的最小 core/application 改动。

| 条目 | 现状（ui/ 里能做到的） | 所需最小改动 |
|---|---|---|
| **SessionCounters**（会话条 TX/RX/错误计数：G#13、b1-2#2、b4-2#4、b1-5#3） | ✅ **已完成（application 层）**：`DefaultSessionManager` 在发布报文和主站结果处累加，连接时从 0 开始、断开归零；主站会话条右侧显示 TX / RX / 错误，从站页显示本机请求 / 已响应 / 异常 / 外站帧 + 方向不明。“CRC 错”数不到（扫描器丢弃的字节不发布成报文），不显示 | 无 |
| **previewFrame**（b1-3#3 写入帧预览） | ✅ **已完成（application 层）**：`SessionManager.previewFrame()` 和 `WritePlan` 都经 `application/FramePreview.kt` 的 `encodeRequestFrame`，与主站引擎同一个编码器；`ui/` 不再 import `core.rtu` | 无 |
| **UsbDeviceSummary 授权状态**（b1-1#3） | 不属于工作台，未做 | `UsbDeviceSummary` 增 `hasPermission: Boolean` |
| **RegisterChange.source**（b1-5#1、#6 从站“来源”） | ✅ **后端已完成（core）**：`RegisterChange` 带 `source`（外部写 / 广播 / 本地编辑 / 模拟）、旧值、新值、单调时间；`VirtualRegisterBank.lastChange()` 可查每个单元最近一次改值。**界面“最近来源”列已随 PR #9 接入** | 已完成；从站工作台按 `lastChange` 显示来源徽标 |
| **FrameDescriber**（b1-6#5 日志摘要、b6-1#3 字段解码） | ✅ **已完成（application 层）**：原 `ui/traffic/FrameInfo.kt` 的解码部分移到 `application/FrameDescriber.kt`（纯 Kotlin）；界面只做格式化 | 无 |
| **TrafficEvent.id / pairedId**（b6-1#3 请求-响应配对） | 未做 | `TrafficEvent` 增 `id: Long`、`pairedId: Long?` |
| **AddressStyle 第三档“两者”+ 默认值**（b3-2#3、D5） | 工作台跟随现有两档：选 MODICON 才显示 40001；默认（0 基）不显示，和稿面“默认两者”不同 | `UiSettings.AddressStyle` 增 `BOTH`，默认值改 `BOTH`（并做 DataStore 迁移） |
| **日志时间基数**（第 5 项排查） | ✅ 已由 application 层 `TrafficRecorder` 固定墙钟时间；保存也带时间 | application：`DefaultSessionManager` 在 `connect`/`teardown` 时 `_traffic.resetReplayCache()`（`MutableSharedFlow` 支持）；ui：`TrafficViewModel` 在会话 epoch 变化时清空 `history`，并把 `origin` 固定为本次会话第一帧而不是“当前窗口第一帧” |

## 2026-10-17 新增的后端能力（界面进度见 status.md）

| 能力 | 后端状态 | 界面待做 |
|---|---|---|
| **ValueSimulator / SimulationEngine**（spec §5.3，b5-4 / b5-5） | ✅ core `slave/ValueSimulator.kt`、`slave/SimulationEngine.kt`：常量 / 递增（回绕）/ 正弦 / 方波 / 随机 / 序列，叠加随机、限幅；外部写（单播或广播）默认暂停该规则，可选继续覆盖；未映射地址不新建；只经 bank 写、来源标 SIMULATION，不占从站响应协程。从站会话通过 `SessionManager.simulation` 拿到模拟器 | 规则列表、编辑页、面板上的“全部暂停 / 恢复”；规则已随 PR #9 纳入从站配置落盘；手动暂停状态仅在运行时保留 |
| **回显 AUTO 判定** | ✅ FC05 / FC06 帧（请求与应答同形）不再参与“有没有回显”的投票；回显截止时间从写调用返回后起算 | 无 |

