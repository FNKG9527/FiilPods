# FiilPods 混淆规则

# libxposed 入口与 Hook 类名不能被裁剪
-keep class moe.chenxy.fiilpods.hook.** { *; }
-keep class moe.chenxy.fiilpods.pods.** { *; }

# ===== Parcelable 广播数据类：必须 keep 住「类名 + CREATOR」=====
#
# 血的教训（2026-10-26 新机点开即崩）：
#   BadParcelableException: ClassNotFoundException when unmarshalling: zd
#
# BatteryParams / PodParams 这类 @Parcelize 数据类是通过 Broadcast Intent 的 extras
# 跨进程传递的。Intent 在 IPC 时只序列化「类名字符串 + 字段」，反序列化那一端要拿这个
# 类名去自己的 ClassLoader 里找类。
#
# release 构建开了 R8，如果不 keep，写入端会把类名改写成 `zd` 这种短名；
# 而接收端（例如刚覆盖安装的 debug 包，或者反过来）根本不存在 `zd`，
# 于是 ClassNotFoundException → BadParcelableException → 整个 App 直接崩掉。
#
# 覆盖安装、模块与 UI 版本不一致、蓝牙进程里残留旧注入代码，都会触发。
# 所以这一组必须整包 keep，连成员一起（@Parcelize 生成的 CREATOR 也不能被删）。
-keep class moe.chenxy.fiilpods.utils.miuiStrongToast.data.** { *; }

# Parcelable 的 writeToParcel / CREATOR / describeContents 不能被优化掉
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
    public static **[] newArray(int);
}

# ===== kotlinx.serialization =====
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# 枚举的 values()/valueOf() 被 Compose/序列化反射使用
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
