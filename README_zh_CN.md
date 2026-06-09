<div align="right">

[English](README.md) | **简体中文**

</div>

<h1 align="center">炉石拔线助手</h1>

<p align="center">
  面向 <b>《炉石传说》</b>的安卓一键拔线小工具，纯本地，无服务器
</p>

<p align="center">
  <img alt="Platform" src="https://img.shields.io/badge/%E5%B9%B3%E5%8F%B0-Android%208.0%2B-3DDC84?logo=android&logoColor=white">
  <img alt="Flutter" src="https://img.shields.io/badge/Flutter-3.8%2B-02569B?logo=flutter&logoColor=white">
</p>

---

## 它能做什么

点击悬浮窗按钮后，应用会：

1. **一键断开当前所有 TCP/UDP 会话**——通过 `SO_LINGER 0` 发送 `RST`，并关闭本地 SOCKS 中追踪到的所有会话。从而触发游戏的重连逻辑
2. **挂起所有新连接** 一段可配置的时间（默认 `3000 毫秒`，可调 `1–5000 毫秒`），可让酒馆战旗稳定的跳过对战阶段
3. **计时结束后自动放行新连接**

整个过程**没有任何远端服务器**，所有流量都留在本机，并通过本地 SOCKS5 代理重新发送至原始目标。

## 工作原理

```
                     ┌──────────────────────────────────────────────┐
                     │                  你的手机                    │
                     │                                              │
  炉石传说与         │   TUN 网卡 (198.18.0.1/32, route 0/0)        │
  其它所有应用 ─────▶│        │                                     │
  的流量             │        ▼                                     │
                     │   libhev-socks5-tunnel (JNI 原生库)          │
                     │        │  Loopback 上的 SOCKS5                │
                     │        ▼                                     │
                     │   LocalSocks5Proxy  (127.0.0.1:10808)        │
                     │        │   追踪每一个活跃 Socket             │
                     │        ▼                                     │
                     └────────┼─────────────────────────────────────┘
                              ▼
                       真实上游服务器
                       （暴雪服务器等）
```

| 层          | 组件                                                                                                                                                                | 作用                                                                                                                                                |
| ----------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------- |
| UI          | [`lib/main.dart`](lib/main.dart)                                                                                                                                    | Flutter 控制面板：状态、权限、断网时长与悬浮按钮大小的滑条                                                                                          |
| 桥接        | [`MainActivity.kt`](android/app/src/main/kotlin/com/example/hs_disconnect/MainActivity.kt)                                                                          | Flutter 与原生侧的 `MethodChannel`（`start` / `stop` / `updateSettings` / `getState` / `requestOverlay` / `requestBatteryOptimization`）            |
| VPN 核心    | [`VpnBlockService.kt`](android/app/src/main/kotlin/com/example/hs_disconnect/VpnBlockService.kt)                                                                    | 继承 `android.net.VpnService`，负责建立 TUN、维护前台通知与可拖动的悬浮按钮                                                                         |
| SOCKS 代理  | [`LocalSocks5Proxy.kt`](android/app/src/main/kotlin/com/example/hs_disconnect/LocalSocks5Proxy.kt)                                                                  | 纯 Kotlin 实现的 SOCKS5 服务器（支持 CONNECT 与 UDP ASSOCIATE），监听 `127.0.0.1:10808`，跟踪每一个活跃的 `Socket` / `DatagramSocket`，便于随时关闭 |
| TUN ⇄ SOCKS | [`TProxyBridge.java`](android/app/src/main/java/com/example/hs_disconnect/TProxyBridge.java) + [`libhev-socks5-tunnel`](android/app/src/main/jni/hev-socks5-tunnel) | 基于 lwIP 的原生库，从 TUN 文件描述符读取 IP 数据包并以 SOCKS5 流的形式重新发出                                                                     |

### "拔线" 的完整流程

当悬浮按钮被点击（`VpnBlockService.triggerBlock`）：

1. `blockGeneration` 自增，调用 `socksProxy.setBlocked(true)`。
2. `LocalSocks5Proxy` 快照当前所有会话，对它们调用 `setSoLinger(true, 0)` 后关闭——这会发送 **TCP RST** 而不是优雅的 FIN，让游戏客户端立刻感知"硬重置"。
3. 代理的 `awaitUnblocked()` 等待门会让此后每一个新接入的客户端在 monitor 上 `wait()`，直到挂起时段结束。
4. `TProxyBridge.setBlocked(true)` 让原生侧同样拒绝新的出站数据包。
5. `Handler.postDelayed` 在 `durationMs` 之后释放等待门；通过 generation 校验防止上一轮挂起的回调误结束当前轮。
6. 新会话恢复。

### 什么是"无服务器"

很多拔线工具需要连接到加速服务，中间经过远程服务器转发。本项目则：

- 在本地建立**设备级 VPN**（`addAddress 198.18.0.1`、`addRoute 0.0.0.0/0`），通过Android VPN将全局数据包都灌入软件的 TUN。
- TUN 数据再被 **原路重新发出** 到 loopback 上的 SOCKS 代理。完全走系统正常的网络栈。
- 应用自身通过 `addDisallowedApplication(packageName)` 排除在外，避免环路。

因此**不消耗额外流量、不需要账号、没有第三方服务器**

## 功能特性

- **常驻 VPN 隧道**，只走 `127.0.0.1:10808` 上的本地 SOCKS5 一跳——数据不会发往任何外部服务器中转。
- **可拖动的悬浮按钮**，支持屏幕旋转、记忆 `(x, y)` 位置，并通过颜色区分状态
- **可配置的拔线时长**——滑条（`500–5000 毫秒`）或手动输入。
- **可配置的悬浮按钮大小**——滑条（`40–120 dp`）。
- **前台服务**（`specialUse`）+ 常驻通知，支持一键 `停止`。
- **权限自检**——主界面直接展示悬浮窗权限、电池优化白名单状态，并可一键跳转系统设置。

## 项目结构

```
hs_disconnect/
├─ lib/main.dart                                  Flutter 界面
├─ pubspec.yaml                                   Flutter 配置
└─ android/app/
   ├─ build.gradle.kts                            在 preBuild 阶段触发 ndk-build
   └─ src/main/
      ├─ AndroidManifest.xml                      VPN + 悬浮窗 + 前台服务权限
      ├─ kotlin/com/example/hs_disconnect/
      │  ├─ MainActivity.kt                       MethodChannel 宿主
      │  ├─ VpnBlockService.kt                    VpnService + 悬浮窗
      │  └─ LocalSocks5Proxy.kt                   SOCKS5 服务器
      ├─ java/com/example/hs_disconnect/
      │  └─ TProxyBridge.java                     JNI 绑定
      ├─ jni/hev-socks5-tunnel/
      └─ jniLibs/<abi>/libhev-socks5-tunnel.so
```

## 编译与运行

### 前置依赖

| 工具        | 版本        |
| ----------- | ----------- |
| Flutter SDK | `>= 3.8.1`  |
| Android SDK | 推荐 API 34 |
| JDK         | 11          |

### 步骤

```bash
# 1. 克隆仓库
git clone https://github.com/Thisisseanxu/HS_disconnect.git
cd HS_disconnect

# 2. 拉取 Flutter 依赖
flutter pub get

# 3. 在连接的设备上构建并安装
flutter run --release
```

[`android/app/build.gradle.kts`](android/app/build.gradle.kts) 中注册的 `buildHevNative` 任务会在每次 `preBuild` 时调用 `ndk-build`，依据 [`android/app/src/main/jni/Android.mk`](android/app/src/main/jni/Android.mk) 产出四个 ABI（`armeabi-v7a`、`arm64-v8a`、`x86`、`x86_64`）的 `.so` 文件，**无需额外手动命令**。

> [!NOTE]
> Windows 会自动选用 `ndk-build.cmd`，macOS / Linux 则使用 `ndk-build`。

## 权限说明

| 权限                                                    | 用途                                   |
| ------------------------------------------------------- | -------------------------------------- |
| `INTERNET`                                              | 打开替换原会话的上游 socket。          |
| `BIND_VPN_SERVICE`（来自 `android.net.VpnService`）     | 把全机流量纳入我们的 TUN。             |
| `SYSTEM_ALERT_WINDOW`                                   | 在炉石传说之上绘制悬浮按钮。           |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` | 让 VPN 在游戏在前台时一直存活。        |
| `POST_NOTIFICATIONS`                                    | 显示常驻服务通知（Android 13+ 必需）。 |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`                  | 降低对局中途被系统杀掉服务的概率。     |

应用 **不会** 请求位置、联系人、存储或任何统计上报权限。

## 常见问题

<details>
<summary><b>这算不算外挂 / 会不会违反暴雪条款？</b></summary>

通过断开自己设备上的 TCP 会话来触发一次网络重连，本质上和"在合适的时机切换一下飞行模式"是同一种效果——这是移动网络下一直发生的事情，游戏本身就有容错。话虽如此，你需要为自己的使用方式负责。作者不对此提供任何担保，也不对竞技公平性表态。

</details>

<details>
<summary><b>会影响其它应用上网吗？</b></summary>

目前会。服务运行期间，所有应用的流量都会经由本地 SOCKS5 一跳。点击悬浮按钮拔线时，它们的连接也会一起被掐。不需要时请从控制页或通知里停止服务。

</details>

<details>
<summary><b>需要 root 或 Magisk 吗？</b></summary>

不需要。它使用的是 Android 4.0 起就有的标准 `VpnService` API。

</details>

## 致谢

- [`hev-socks5-tunnel`](https://github.com/heiher/hev-socks5-tunnel)——基于 lwIP 的 TUN ⇄ SOCKS5 引擎，本项目原生侧的主力。
- 初始脚手架由 OpenAI Codex 生成，欢迎社区贡献与 PR。

## 许可

本项目以 **[GNU 通用公共许可证 v3.0](https://www.gnu.org/licenses/gpl-3.0.html)**（GPL-3.0-or-later）发布。

子目录 [`android/app/src/main/jni/hev-socks5-tunnel`](android/app/src/main/jni/hev-socks5-tunnel) 继续受其上游许可证约束（详见其自带的 `LICENSE` 文件）。作为整体分发的合并作品，本仓库整体以 GPL-3.0-or-later 授权。
