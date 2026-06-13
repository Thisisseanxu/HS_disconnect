package com.thisisseanxu.hs_disconnect;

public final class TProxyBridge {
    private static native void TProxyStartService(String configPath, int fd);
    private static native void TProxyStopService();
    private static native long[] TProxyGetStats();
    private static native void TProxySetBlocked(boolean blocked);
    private static native boolean TProxyIsRunning();

    static {
        System.loadLibrary("hev-socks5-tunnel");
    }

    private TProxyBridge() {}

    public static void start(String configPath, int fd) {
        TProxyStartService(configPath, fd);
    }

    public static void stop() {
        TProxyStopService();
    }

    public static long[] stats() {
        return TProxyGetStats();
    }

    public static void setBlocked(boolean blocked) {
        TProxySetBlocked(blocked);
    }

    public static boolean isRunning() {
        return TProxyIsRunning();
    }
}
