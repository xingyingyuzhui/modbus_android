# Modbus Mobile

Android 离线 Modbus RTU 主站 / 从站调试器。协议侧有多站总线帧扫描和回显跟踪。界面已包含连接、主从站工作台、组件面板、曲线、通信日志和设置。真机和多站总线未测。

规范只看 `docs/spec.md`。`research/` 没有放进仓库。

## 工具链

| 项 | 版本 |
|---|---|
| JDK | 17 |
| Gradle | 9.6.0 |
| Android Gradle Plugin | 9.4.1 |
| Kotlin | 2.4.21 |
| Compose BOM | 2026.09.00 |
| compileSdk | 37.2 |
| targetSdk | 37 |
| minSdk | 26 |
| usb-serial-for-android | 3.11.0 |
| kotlinx-coroutines | 1.11.0 |

稳定 API 是 37。`compileSdk` 用平台 `android-37.2`，因为当前 Compose BOM、Navigation 和 Lifecycle 要求至少编译到 API 37。AGP 9.4 的 `targetSdk` 只接受主版本，所以目标版本是 37。

## 目录

```
app/            Compose、USB、前台服务、会话
modbus-core/    纯 JVM 协议栈，不能依赖 android.*
docs/           V5.1 规格、路线、审阅记录、变更说明、界面计划（docs/ui-plan.md）
```

## 构建

```bash
./gradlew :modbus-core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

演示模式不需要转换器。连接页选「演示设备」：主站可读 Unit 1 的 HR0–9（0、10、20…90）；从站的 HR0 每 5 秒被演示主站改一次。

本地有 SDK/JDK 时可直接执行上述命令；若环境变量中的 Gradle 缓存路径指向未挂载外置盘，可在本次命令前设置 `GRADLE_USER_HOME="$HOME/.gradle"`。一次只运行一个本地构建，完成后执行 `./gradlew --stop`。模拟器允许清理旧测试包后重装；截图仅留本地。

## 本期没有做

FTDI 延迟界面、Room 历史落库、工程整体导入导出、Hilt，以及任何网络权限。曲线和数据类型转换已在 app 的 `application/panel/` 实现，曲线样本仅存内存；日志保存和曲线快照可用。多站扫描和分块回显已在协议核心里实现；真机和多站总线未测。
