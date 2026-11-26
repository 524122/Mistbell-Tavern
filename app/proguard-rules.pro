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

# Compose：不按名反射、无 JNI 绑定，不需要整包 keep。
# 2026-11-26 对比实验（release/universal，R8 minify）：移除下面两条 keep 省下
# 5,120,518 字节（86,055,959 → 80,935,441，各 ABI 一致），且 14 项 release 冒烟
# 全部通过（冷启动、聊天流式/落库/杀进程重开、会话增删、角色/世界书/正则/规则包/
# 扩展包/快捷回复/API 编辑器、Rhino 宿主钩子 JSEXT·hosthook-demo 真实执行、
# HTML 界面 TavernJsBridge 桥接、布局主题引擎 skiko、litehtml 表格渲染、
# 记忆/备份页面），全程无 ClassNotFoundException / NoSuchMethodError /
# JNI DETECTED ERROR / R8 相关 NPE。Compose 官方 consumer 规则已覆盖反射点，
# 故正式移除；如未来引入按名反射 Compose 内部类的依赖再评估恢复。

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

# skiko / skia（布局主题引擎，org.jetbrains.skiko:skiko-android:0.9.47）：JNI 按名查找，必须整包保留。
# 根因（实测）：skiko AAR 不带 consumer proguard 规则；libskiko-android-*.so 里硬编码了
# 38 个 org/jetbrains/skia/**、kotlin/jvm/functions/Function0 与若干 java/** 的类路径字符串，
# native 侧按原名 FindClass，并对类成员做 GetMethodID / GetFieldID（成员名也被按名查找，
# 如 Function0.invoke 与各 struct 字段 ascent/descent 等）。R8 会把这些类改名甚至整类移除
# （实测 mapping：Function0 -> xe0、FontCollection -> gd0、Color4f -> lt；Path / Rect /
# impl.Native / shaper.* / svg.* / skottie.* 等 33 个类被整体删除），导致冷启动
# TavernApplication.onCreate -> EngineBootstrap.init() -> FontCollection.<clinit> 时
# native 侧报 "JNI DETECTED ERROR: java_class == null in call to GetMethodID" 直接 abort。
-keep class org.jetbrains.skia.** { *; }
# skiko 自身类（Library / LibraryLoader / Setup 等）与 skia 同属一套 native 绑定体系，一并按原名保留兜底。
-keep class org.jetbrains.skiko.** { *; }
# native 侧按名取 kotlin.jvm.functions.Function0 的 invoke（.so 符号
# _ZN6kotlin3jvm9functions9Function06invokeE 可证），FunctionN 系列整体保留以防同类查找。
-keep class kotlin.jvm.functions.Function* { *; }

# onnxruntime4j（本地向量嵌入 LocalEmbeddingService）：libonnxruntime4j_jni.so 按名引用 ai/onnxruntime/**，
# 官方 consumer 规则保住了大部分类，但 OrtException 仍被 R8 改名（实测 mapping：-> b61），
# native 在推理出错路径上按原名构造该异常，这里兜底按原名保留。
-keep class ai.onnxruntime.OrtException { *; }

# Rhino（扩展包/规则包 JS 脚本沙箱，org.mozilla:rhino:1.8.0）：解释器内部大量按名反射
# （ClassCache / JavaMembers / MemberBox / LambdaConstructor 等，ContextFactory 里还有
# 硬编码字符串 "org.mozilla.javascript.xmlimpl.XMLLibImpl" 的按名加载），AAR/JAR 不带
# consumer proguard 规则。实测：不 keep 时 R8 把 Context->ny、ScriptRuntime->mj1 等全部改名，
# 扩展包宿主钩子加载即在 Rhino 首次求值时抛嵌套 ExceptionInInitializerError
# （release-only，debug 同流程正常加载并执行 JSEXT·hosthook-demo），整包按原名保留。
-keep class org.mozilla.javascript.** { *; }
# rhino 的 invokedynamic 优化器（optimizer.Bootstrapper 等）按 JDK 类名引用 jdk.dynalink.*，
# Android 上不存在这批类；沙箱固定 optimizationLevel=-1（纯解释器），该路径运行期不会走到，
# 仅编译期 R8 需要 -dontwarn 放行（keep 全包后这些引用变为可见）。
-dontwarn jdk.dynalink.**

# 脚本宿主 API（规则包 TavernHostApi / 扩展包 ExtensionHookApi）：JS 脚本按方法名调用
# tavern.log / getVar / onPrompt / getChatMessages 等，Rhino 经反射（JavaMembers / MemberBox）取方法，
# 所以类名、方法名与签名都必须原样保留。ClassShutter 白名单按类名放行宿主对象
# （RhinoSandbox 取的是运行时类名，这里保留原名是第二道保险）。
# release 实测：不 keep 时宿主类被 R8 改名（mapping 里成了 "zr"）。
-keep class com.mistbell.tavern.android.extension.scripting.TavernHostApi { *; }
-keep class com.mistbell.tavern.android.extension.scripting.ExtensionHookApi { *; }

# Keep line numbers for debugging
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
