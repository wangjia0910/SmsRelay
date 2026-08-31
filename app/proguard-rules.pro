# SmsRelay 默认未开启混淆（app/build.gradle.kts 中 isMinifyEnabled = false），
# 本文件仅作为 proguardFiles 引用的占位。若将来开启混淆，至少保留以下规则：

# 保留应用自身代码与数据模型
-keep class com.lazy.smsrelay.** { *; }

# 保留 Gson 序列化所需的结构（字段名、无参构造）
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.lazy.smsrelay.data.ModelsKt
-keep class com.lazy.smsrelay.data.** { *; }

# OkHttp / Okio 在 R8 下通常需要的基础保留
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
