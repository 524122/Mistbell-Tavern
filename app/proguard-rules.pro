# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# Keep data classes used for serialization
-keep @kotlinx.serialization.Serializable class * { *; }
-keepclassmembers class * {
    @kotlinx.serialization.SerialName <fields>;
}

# Keep Room entities
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-keep @androidx.room.Database class * { *; }

# Keep data models
-keep class com.mistbell.tavern.android.data.model.** { *; }
-keep class com.mistbell.tavern.android.data.api.model.** { *; }
-keep class com.mistbell.tavern.android.data.local.entity.** { *; }

# Kotlin serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Rhino 的可选 BEAN 转换器需要桌面 JDK；本沙箱只使用默认的 STRING 转换，不进入该路径。
-dontwarn java.beans.BeanDescriptor
-dontwarn java.beans.BeanInfo
-dontwarn java.beans.IntrospectionException
-dontwarn java.beans.Introspector
-dontwarn java.beans.PropertyDescriptor

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# Compose
-keep class androidx.compose.** { *; }
-keep class androidx.compose.runtime.** { *; }

# Yoga（M1 spike）：JNI native 方法按 C++ 符号（Java_com_facebook_yoga_*）绑定，类/方法不能被改名或剔除。
# AAR 自带的 consumer 规则只 keep @DoNotStrip 成员，这里对 native 宿主类兜底（release 开了 minify）。
-keepclasseswithmembers class com.facebook.yoga.** {
    native <methods>;
}
-keep class com.facebook.yoga.YogaNative { *; }
-keep class com.facebook.yoga.YogaNodeJNIBase { *; }
-keep class com.facebook.yoga.YogaNodeJNIFinalizer { *; }
-keep class com.facebook.yoga.YogaConfigJNIBase { *; }
-keep class com.facebook.yoga.YogaConfigJNIFinalizer { *; }

# litehtml JNI：R8 看不到 native 对 LiteHtmlPaint 静态方法的调用，必须按原名保留。
-keep class com.mistbell.tavern.android.render.litehtml.LiteHtmlRenderer { *; }
-keep class com.mistbell.tavern.android.render.litehtml.LiteHtmlPaint { *; }

# Keep line numbers for debugging
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
