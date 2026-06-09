## JNI bridge: hev-socks5-tunnel's JNI_OnLoad calls RegisterNatives by string
## name against com.example.hs_disconnect.TProxyBridge. Keep the whole class
## so R8 cannot rename or strip any native method that hev-jni.c registers.
-keep class com.example.hs_disconnect.TProxyBridge { *; }

## Safety net for any future class that owns native methods.
-keepclasseswithmembernames class * {
    native <methods>;
}
