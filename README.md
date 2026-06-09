<div align="right">

**English** | [简体中文](README_zh_CN.md)

</div>

<h1 align="center">HS Disconnect</h1>

<p align="center">
  A one-tap <b>DC trick</b> assistant for <b>Hearthstone</b> on Android — fully local, no server.
</p>

<p align="center">
  <img alt="Platform" src="https://img.shields.io/badge/platform-Android%208.0%2B-3DDC84?logo=android&logoColor=white">
  <img alt="Flutter" src="https://img.shields.io/badge/Flutter-3.8%2B-02569B?logo=flutter&logoColor=white">
</p>

---

## What it does

When you tap the floating button, the app:

1. **Tears down every live TCP/UDP session in one shot** — by issuing `RST` via `SO_LINGER 0` and closing every session tracked by the local SOCKS proxy, which triggers the game client's reconnect logic.
2. **Holds all new connections** for a configurable window (default `3000 ms`, range `1–5000 ms`) — long enough to reliably skip the combat phase in **Battlegrounds**.
3. **Releases new connections** automatically once the timer expires.

The whole process **involves no remote server**. All traffic stays on the device and is routed back out to its original destination through a local SOCKS5 proxy.

## How it works

```
                     ┌──────────────────────────────────────────────┐
                     │                 Your phone                   │
                     │                                              │
  Hearthstone        │   TUN (198.18.0.1/32, route 0/0)             │
  & all other  ─────▶│        │                                     │
  apps' traffic      │        ▼                                     │
                     │   libhev-socks5-tunnel (JNI, native)         │
                     │        │  SOCKS5 over loopback               │
                     │        ▼                                     │
                     │   LocalSocks5Proxy  (127.0.0.1:10808)        │
                     │        │   tracks every live Socket          │
                     │        ▼                                     │
                     └────────┼─────────────────────────────────────┘
                              ▼
                       Real upstream
                  (Blizzard servers, etc.)
```

| Layer       | Component                                                                                                                                                           | Role                                                                                                                                                            |
| ----------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| UI          | [`lib/main.dart`](lib/main.dart)                                                                                                                                    | Flutter control panel: status, permissions, duration & overlay-size sliders                                                                                     |
| Bridge      | [`MainActivity.kt`](android/app/src/main/kotlin/com/example/hs_disconnect/MainActivity.kt)                                                                          | `MethodChannel` between Flutter and Android (`start` / `stop` / `updateSettings` / `getState` / `requestOverlay` / `requestBatteryOptimization`)                |
| VPN core    | [`VpnBlockService.kt`](android/app/src/main/kotlin/com/example/hs_disconnect/VpnBlockService.kt)                                                                    | Extends `android.net.VpnService`, builds the TUN, manages the foreground notification & draggable overlay button                                                |
| SOCKS proxy | [`LocalSocks5Proxy.kt`](android/app/src/main/kotlin/com/example/hs_disconnect/LocalSocks5Proxy.kt)                                                                  | Pure-Kotlin SOCKS5 server (CONNECT + UDP ASSOCIATE) bound to `127.0.0.1:10808`; tracks every active `Socket` / `DatagramSocket` so they can be closed on demand |
| TUN ⇄ SOCKS | [`TProxyBridge.java`](android/app/src/main/java/com/example/hs_disconnect/TProxyBridge.java) + [`libhev-socks5-tunnel`](android/app/src/main/jni/hev-socks5-tunnel) | Native library (lwIP-based) that pulls IP packets off the TUN fd and re-emits them as SOCKS5 streams                                                            |

### The DC trick sequence

When the overlay is tapped (`VpnBlockService.triggerBlock`):

1. `blockGeneration` is bumped; `socksProxy.setBlocked(true)` is called.
2. `LocalSocks5Proxy` snapshots all tracked sessions and closes them with `setSoLinger(true, 0)` — that sends a **TCP RST** instead of a graceful FIN, so the game client sees a hard reset immediately.
3. The proxy's `awaitUnblocked()` gate causes every newly accepted client to `wait()` on a monitor until the block window ends.
4. `TProxyBridge.setBlocked(true)` lets the native side also reject new outbound packets.
5. A `Handler.postDelayed` releases the gate after `durationMs`; the generation check prevents stale callbacks from a previous block from ending the current one.
6. New sessions resume.

### What "no-server" means here

Many DC-trick tools route traffic through a remote acceleration / relay service to insert the delay. This project instead:

- Builds an **on-device VPN** (`addAddress 198.18.0.1`, `addRoute 0.0.0.0/0`), so the Android VPN framework funnels every packet from every other app into our TUN.
- The TUN is then re-emitted **straight back out** through a loopback SOCKS proxy. The "upstream" hop is just the regular OS network stack.
- The app's own package is added via `addDisallowedApplication(packageName)` to avoid routing loops.

That means **zero bandwidth cost, no account, no third-party server**.

## Features

- **Always-on VPN tunnel** with a single SOCKS5 hop on `127.0.0.1:10808` — no data leaves the device for relay.
- **Draggable floating overlay** that survives screen rotation, remembers its `(x, y)` position, and shows blocking state with a colour swap.
- **Configurable hold window** — slider (`500–5000 ms`) or manual numeric input.
- **Configurable overlay size** — slider (`40–120 dp`).
- **Foreground service** (`specialUse`) + persistent notification with a one-tap `Stop` action.
- **Permission self-check** — the UI surfaces overlay permission and battery-optimisation status and deep-links to the system settings page for each.

## Project layout

```
hs_disconnect/
├─ lib/main.dart                                  Flutter UI
├─ pubspec.yaml                                   Flutter manifest
└─ android/app/
   ├─ build.gradle.kts                            Hooks ndk-build into preBuild
   └─ src/main/
      ├─ AndroidManifest.xml                      VPN + overlay + FGS perms
      ├─ kotlin/com/example/hs_disconnect/
      │  ├─ MainActivity.kt                       MethodChannel host
      │  ├─ VpnBlockService.kt                    VpnService + overlay
      │  └─ LocalSocks5Proxy.kt                   SOCKS5 server
      ├─ java/com/example/hs_disconnect/
      │  └─ TProxyBridge.java                     JNI binding
      ├─ jni/hev-socks5-tunnel/
      └─ jniLibs/<abi>/libhev-socks5-tunnel.so
```

## Build & run

### Prerequisites

| Tool        | Version            |
| ----------- | ------------------ |
| Flutter SDK | `>= 3.8.1`         |
| Android SDK | API 34 recommended |
| JDK         | 11                 |

### Steps

```bash
# 1. Clone the repository
git clone https://github.com/Thisisseanxu/HS_disconnect.git
cd HS_disconnect

# 2. Fetch Flutter deps
flutter pub get

# 3. Build & install on a connected device
flutter run --release
```

The `buildHevNative` task registered in [`android/app/build.gradle.kts`](android/app/build.gradle.kts) invokes `ndk-build` against [`android/app/src/main/jni/Android.mk`](android/app/src/main/jni/Android.mk) on every `preBuild`, producing the `.so` files for all four ABIs (`armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`). **No extra command is required.**

> [!NOTE]
> On Windows the task automatically picks `ndk-build.cmd`; on macOS / Linux it uses `ndk-build`.

## Permissions

| Permission                                              | Why it's needed                                          |
| ------------------------------------------------------- | -------------------------------------------------------- |
| `INTERNET`                                              | Open the upstream sockets that replace the cut ones.     |
| `BIND_VPN_SERVICE` (via `android.net.VpnService`)       | Capture all device traffic into our TUN.                 |
| `SYSTEM_ALERT_WINDOW`                                   | Draw the floating button on top of Hearthstone.          |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` | Keep the VPN alive while the game is in the foreground.  |
| `POST_NOTIFICATIONS`                                    | Show the persistent service notification (Android 13+).  |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`                  | Reduce the odds of the OS killing the service mid-match. |

The app does **not** request location, contacts, storage, or any analytics.

## FAQ

<details>
<summary><b>Is this a cheat / does it break Blizzard ToS?</b></summary>

Triggering a network reconnect by dropping your own device's TCP sessions has the same effect as toggling airplane mode at the right moment — something that happens all the time on mobile networks, and which the game is built to tolerate. That said, you are responsible for how you use this tool. The author makes no warranty and takes no position on competitive integrity.

</details>

<details>
<summary><b>Will it affect other apps' network?</b></summary>

Yes — for now. While the service is running, every app's traffic flows through the local SOCKS5 hop, so tapping the overlay drops their live connections too. Stop the service from the control page or the notification when you're done.

</details>

<details>
<summary><b>Does the VPN need root or Magisk?</b></summary>

No. It uses the standard `VpnService` API available on every Android since 4.0.

</details>

## Acknowledgements

- [`hev-socks5-tunnel`](https://github.com/heiher/hev-socks5-tunnel) — the lwIP-based TUN ⇄ SOCKS5 engine that does the heavy lifting on the native side.
- Initial scaffold generated with OpenAI Codex; community contributions and PRs welcome.

## License

This project is released under the **[GNU General Public License v3.0](https://www.gnu.org/licenses/gpl-3.0.html)** (GPL-3.0-or-later).

That choice keeps the project as open as possible while preventing it from being silently rolled into a closed-source paid product:

The vendored [`android/app/src/main/jni/hev-socks5-tunnel`](android/app/src/main/jni/hev-socks5-tunnel) directory continues to be governed by its own upstream license (see its `LICENSE` file). The combined work, as distributed in this repository, is licensed under GPL-3.0-or-later.
