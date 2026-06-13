# Local patches for `hev-socks5-tunnel`

The vendored copy under [`hev-socks5-tunnel/`](hev-socks5-tunnel/) is **upstream
release `2.14.3`** (commit
[`da33382c`](https://github.com/heiher/hev-socks5-tunnel/commit/da33382c7282b4e764408535704f3cd96fea9a14))
with a small set of local additions, recorded below.

Without these additions the JNI symbols `TProxySetBlocked` and `TProxyIsRunning`
referenced by
[`TProxyBridge.java`](../java/com/thisisseanxu/hs_disconnect/TProxyBridge.java) would
fail to link at runtime.

## Summary of changes

| File | Change |
| --- | --- |
| `src/hev-socks5-tunnel.h` | Add public declarations for `hev_socks5_tunnel_set_blocked` and `hev_socks5_tunnel_is_running`. |
| `src/hev-socks5-tunnel.c` | Define the two new functions (`is_running` returns the existing `run` flag; `set_blocked` is currently a stub kept for future native-side gating). Also drops an unused `#include <signal.h>`. |
| `src/hev-jni.c` | Register the two new JNI methods (`TProxySetBlocked(Z)V`, `TProxyIsRunning()Z`) on `TProxyBridge`. |

## Upgrading the vendored copy

1. Fetch the new upstream tag into a scratch directory:
   ```bash
   git clone --depth 1 --branch <new-tag> \
     https://github.com/heiher/hev-socks5-tunnel.git /tmp/hev-upstream
   ```
2. Replace the contents of [`hev-socks5-tunnel/`](hev-socks5-tunnel/) with the
   scratch tree (mind that several headers under `include/` and
   `src/core/include/` are stored as **symlinks** upstream — on Windows you may
   want to resolve them to regular files as the current copy does).
3. Re-apply the patch block below — `git apply` it from this directory:
   ```bash
   git apply LOCAL_PATCHES.md   # not directly; copy the diff into a .patch file first
   ```
4. Rebuild and smoke-test on a device — make sure
   `System.loadLibrary("hev-socks5-tunnel")` still succeeds and that tapping the
   overlay still triggers the DC sequence.
5. Bump the base version recorded at the top of this file.

## The patch

```diff
--- a/src/hev-socks5-tunnel.h
+++ b/src/hev-socks5-tunnel.h
@@ -17,6 +17,8 @@

 int hev_socks5_tunnel_run (void);
 void hev_socks5_tunnel_stop (void);
+void hev_socks5_tunnel_set_blocked (int value);
+int hev_socks5_tunnel_is_running (void);

 void hev_socks5_tunnel_stats (size_t *tx_packets, size_t *tx_bytes,
                               size_t *rx_packets, size_t *rx_bytes);
--- a/src/hev-socks5-tunnel.c
+++ b/src/hev-socks5-tunnel.c
@@ -9,7 +9,6 @@

 #include <errno.h>
 #include <assert.h>
-#include <signal.h>
 #include <string.h>
 #include <sys/ioctl.h>

@@ -322,6 +319,18 @@
     hev_tunnel_del_task (tun_fd, task_lwip_io);
 }

+void
+hev_socks5_tunnel_set_blocked (int value)
+{
+    (void)value;
+}
+
+int
+hev_socks5_tunnel_is_running (void)
+{
+    return READ_ONCE (run);
+}
+
 static void
 lwip_timer_task_entry (void *data)
 {
--- a/src/hev-jni.c
+++ b/src/hev-jni.c
@@ -18,6 +18,7 @@
 #include <string.h>

 #include "hev-main.h"
+#include "hev-socks5-tunnel.h"

 #include "hev-jni.h"

@@ -52,12 +53,16 @@
                                   jint fd);
 static void native_stop_service (JNIEnv *env, jobject thiz);
 static jlongArray native_get_stats (JNIEnv *env, jobject thiz);
+static void native_set_blocked (JNIEnv *env, jobject thiz, jboolean blocked);
+static jboolean native_is_running (JNIEnv *env, jobject thiz);

 static JNINativeMethod native_methods[] = {
     { "TProxyStartService", "(Ljava/lang/String;I)V",
       (void *)native_start_service },
     { "TProxyStopService", "()V", (void *)native_stop_service },
     { "TProxyGetStats", "()[J", (void *)native_get_stats },
+    { "TProxySetBlocked", "(Z)V", (void *)native_set_blocked },
+    { "TProxyIsRunning", "()Z", (void *)native_is_running },
 };

 static void
@@ -148,6 +153,18 @@
     pthread_mutex_unlock (&mutex);
 }

+static void
+native_set_blocked (JNIEnv *env, jobject thiz, jboolean blocked)
+{
+    hev_socks5_tunnel_set_blocked (blocked ? 1 : 0);
+}
+
+static jboolean
+native_is_running (JNIEnv *env, jobject thiz)
+{
+    return hev_socks5_tunnel_is_running () ? JNI_TRUE : JNI_FALSE;
+}
+
 static jlongArray
 native_get_stats (JNIEnv *env, jobject thiz)
 {
```
