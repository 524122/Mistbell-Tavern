package com.mistbell.tavern.android.extension.scripting

import android.util.Log
import org.mozilla.javascript.ClassShutter
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.Function
import org.mozilla.javascript.ScriptableObject
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Rhino 脚本沙箱：单线程执行全部 JS，指令数限流 + 墙钟超时双保险。
 *
 * 安全边界：
 * - `optimizationLevel = -1`（解释模式，避开 Android 不支持的 JVM 字节码生成）
 * - `ClassShutter { false }`：拒绝一切 Java 类访问（Packages/java/反射全部失效）
 * - 每包独立 scope（原型指向 sealed 共享全局），包间不互相污染
 * - 宿主只注入显式 API（tavern 对象）；文件系统/网络能力 Rhino 默认不具备
 *
 * 故障语义：JS 异常/超时/超限一律降级为 [SandboxResult.Failure]（记日志），
 * 调用方按"该钩子无产出"继续——脚本故障绝不掀翻聊天链路。
 */
class RhinoSandbox {
    /** 包级运行时：独立 scope + 初始化评估时收集到的注册函数 + JSON 桥函数 */
    class PackRuntime internal constructor(
        val packId: String,
        val scope: ScriptableObject,
        val registrations: MutableMap<String, Function> = mutableMapOf(),
        var toJsonFn: Function? = null,
    )

    sealed interface SandboxResult<out T> {
        data class Ok<T>(val value: T) : SandboxResult<T>

        data class Failure(val reason: String) : SandboxResult<Nothing>
    }

    private val factory =
        object : ContextFactory() {
            /**
             * 指令计数越阈回调（解释器触发）：直接中止。
             * 生效条件：任务内须 cx.isGenerateObserverCount = true + 设置 instructionObserverThreshold，
             * 且 Context 由本工厂实例创建（makeContext）——静态 enterContext 走全局工厂会失配。
             */
            override fun observeInstructionCount(
                cx: Context?,
                instructionCount: Int,
            ) {
                throw InstructionBudgetExceeded("脚本超出指令预算")
            }

            /** 经本工厂实例创建 Context（保证 observeInstructionCount 的 factory 匹配检查通过） */
            fun create(): Context = makeContext()
        }

    /** 指令预算超限（沙箱内部信号，调用方降级为 Failure） */
    class InstructionBudgetExceeded(message: String) : RuntimeException(message)

    private val executor: ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "js-sandbox").apply { isDaemon = true }
        }

    @Volatile
    private var sharedGlobal: ScriptableObject? = null

    private val runtimes = mutableMapOf<String, PackRuntime>()

    /** 任务提交到沙箱线程并限时等待；超时/异常降级为 Failure。 */
    @Suppress("TooGenericExceptionCaught") // 沙箱边界必须兜住任意 JS 侧异常形态（含 Rhino 内部错误）
    private fun <T> submit(
        instructionBudget: Int,
        task: (Context) -> T,
    ): SandboxResult<T> {
        val future =
            executor.submit(
                Callable {
                    val cx = factory.create()
                    // makeContext 只创建不关联线程，必须显式 enter，否则 Context.exit() 报
                    // "Calling Context.exit without previous Context.enter"
                    Context.enter(cx)
                    try {
                        cx.optimizationLevel = -1
                        cx.languageVersion = Context.VERSION_ES6
                        // 仅放行宿主对象自身与不可变值包装类（NativeJavaObject 包装需 ClassShutter 批准）；
                        // 其余 Java 类一律不可见——Packages/java/反射/JavaAdapter 对脚本全部失效
                        cx.setClassShutter(
                            ClassShutter { className -> className in ALLOWED_HOST_CLASSES },
                        )
                        // 指令计数中止失控脚本：解释器开计数 + 越阈回调（factory 内 throw）
                        cx.isGenerateObserverCount = true
                        cx.instructionObserverThreshold = instructionBudget
                        SandboxResult.Ok(task(cx)) as SandboxResult<T>
                    } finally {
                        Context.exit()
                    }
                },
            )
        return try {
            future.get(WALL_CLOCK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            future.cancel(true)
            Log.w(TAG, "JS 执行失败（已降级）：${e.cause?.message ?: e.message}")
            SandboxResult.Failure(e.cause?.message ?: e.message ?: "unknown")
        }
    }

    private fun ensureGlobal(cx: Context): ScriptableObject {
        sharedGlobal?.let { return it }
        val global = cx.initStandardObjects()
        // 再封一道：显式删除 Java 互操作入口（ClassShutter 之外的表层防护）
        listOf("Packages", "java", "org", "com", "netscape", "JavaAdapter", "load").forEach { name ->
            if (global.has(name, global)) global.delete(name)
        }
        sharedGlobal = global
        return global
    }

    /**
     * 初始化包运行时：建立 scope、注入 hostObject 与 JSON 桥函数、评估脚本源码。
     *
     * [grantedMethods] 非空时走 PermissionBroker 装配（扩展包宿主钩子）：宿主对象不直接
     * 绑定为 `tavern`，而是经 [TAVERN_BROKER_SOURCE] 引导片只把授权方法绑进 `tavern`
     * 对象——未授权方法在对象上不存在（VS Code 命名空间供给模式，调用即 TypeError）。
     * null（规则包脚本包）保持原行为：整个宿主对象可见（其类本身只含无能力面的注册 API）。
     */
    fun loadPack(
        packId: String,
        scriptSource: String,
        hostObject: Any,
        grantedMethods: List<String>? = null,
    ): SandboxResult<PackRuntime> {
        val result =
            submit(INIT_INSTRUCTION_BUDGET) { cx ->
                val global = ensureGlobal(cx)
                val scope = cx.newObject(global) as ScriptableObject
                scope.prototype = global
                if (grantedMethods == null) {
                    ScriptableObject.putProperty(scope, "tavern", Context.javaToJS(hostObject, scope))
                } else {
                    ScriptableObject.putProperty(scope, HOST_SLOT, Context.javaToJS(hostObject, scope))
                    ScriptableObject.putProperty(scope, GRANTED_SLOT, grantedMethodsJson(grantedMethods))
                    // 引导片是 IIFE 表达式：参数在调用期捕获进闭包的活动作用域，
                    // 评估完即从包 scope 删掉槽位——脚本再无路径摸到裸宿主对象
                    val tavern = cx.evaluateString(scope, TAVERN_BROKER_SOURCE, "tavern-broker.js", 1, null)
                    ScriptableObject.deleteProperty(scope, HOST_SLOT)
                    ScriptableObject.deleteProperty(scope, GRANTED_SLOT)
                    if (tavern == null || tavern == org.mozilla.javascript.UniqueTag.NOT_FOUND) {
                        error("tavern 引导片未产出宿主对象")
                    }
                    ScriptableObject.putProperty(scope, "tavern", tavern)
                }
                val runtime = PackRuntime(packId, scope)
                runtimes[packId] = runtime
                // JSON 桥：脚本返回值经此函数 stringify（宿主导出参统一 JSON 文本）
                cx.evaluateString(scope, TO_JSON_FN_SOURCE, "bridge.js", 1, null)
                runtime.toJsonFn = scope.get(TO_JSON_FN_NAME, scope) as? Function
                cx.evaluateString(scope, scriptSource, "$packId.js", 1, null)
                runtime
            }
        return when (result) {
            is SandboxResult.Ok -> result
            is SandboxResult.Failure -> {
                runtimes.remove(packId)
                result
            }
        }
    }

    fun unloadPack(packId: String) {
        runtimes.remove(packId)
    }

    /** 调用包内已注册函数（按注册名），返回其字符串结果；无此注册/失败返回 null。 */
    fun callRegistered(
        packId: String,
        registration: String,
        args: Array<Any?>,
    ): String? = (callRegisteredForResult(packId, registration, args) as? SandboxResult.Ok)?.value

    /**
     * 全结果版（测试可见失败原因）：JS 返回值非字符串时宿主侧 JSON.stringify——
     * 脚本可自由返回对象/数组/原始值，不必手写 JSON.stringify。
     */
    internal fun callRegisteredForResult(
        packId: String,
        registration: String,
        args: Array<Any?>,
    ): SandboxResult<String?> {
        val fn =
            runtimes[packId]?.registrations?.get(registration)
                ?: return SandboxResult.Failure("未找到注册：$packId/$registration")
        val runtime = runtimes.getValue(packId)
        return submit(CALL_INSTRUCTION_BUDGET) { cx ->
            val raw = fn.call(cx, runtime.scope, runtime.scope, args)
            // Java 宿主方法返回的 String 会被包装成 NativeJavaObject，先拆包再判定
            val unwrapped = (raw as? org.mozilla.javascript.NativeJavaObject)?.unwrap() ?: raw
            when (unwrapped) {
                null -> null
                is String -> unwrapped
                else -> {
                    // 宿主侧 JSON.stringify：脚本可自由返回对象/数组，不必手写 JSON.stringify
                    val toJson = runtime.toJsonFn
                    toJson?.call(cx, runtime.scope, runtime.scope, arrayOf(unwrapped))?.let { Context.toString(it) }
                        ?: Context.toString(unwrapped)
                }
            }
        }
    }

    /** 收集注册（宿主 API 调用）：把 JS 函数句柄记入包运行时。 */
    fun register(
        packId: String,
        registration: String,
        fn: Function,
    ) {
        runtimes[packId]?.registrations?.put(registration, fn)
    }

    fun hasRegistration(
        packId: String,
        registration: String,
    ): Boolean = runtimes[packId]?.registrations?.containsKey(registration) == true

    /** 包当前的全部注册名（管理器据此接入动态斜杠命令/事件） */
    fun registrationsOf(packId: String): Set<String> = runtimes[packId]?.registrations?.keys?.toSet() ?: emptySet()

    fun shutdown() {
        executor.shutdownNow()
    }

    companion object {
        private const val TAG = "RhinoSandbox"

        /** JSON 桥函数：脚本返回值经它 stringify（宿主与 JS 之间出入参统一为 JSON 文本） */
        private const val TO_JSON_FN_NAME = "__tavernToJson"
        private const val TO_JSON_FN_SOURCE = "function __tavernToJson(v) { return JSON.stringify(v); }"

        /** 初始化评估的指令预算（脚本顶层只应做注册，50 万足够） */
        private const val INIT_INSTRUCTION_BUDGET = 500_000

        /** 单次钩子调用的指令预算 */
        private const val CALL_INSTRUCTION_BUDGET = 200_000
        private const val WALL_CLOCK_TIMEOUT_MS = 3_000L

        /** ClassShutter 白名单：宿主对象类 + 不可变值包装类；其余 Java 类对脚本不可见 */
        private val ALLOWED_HOST_CLASSES =
            setOf(
                "com.mistbell.tavern.android.extension.scripting.TavernHostApi",
                "com.mistbell.tavern.android.extension.scripting.ExtensionHookApi",
                "java.lang.String",
                "java.lang.Integer",
                "java.lang.Long",
                "java.lang.Double",
                "java.lang.Boolean",
            )

        /** broker 装配期宿主对象 / 授权清单在包 scope 里的临时槽位名（评估后即删） */
        private const val HOST_SLOT = "__tavernHost"
        private const val GRANTED_SLOT = "__tavernGranted"

        private fun grantedMethodsJson(methods: List<String>): String =
            methods.joinToString(",", "[", "]") { method ->
                ScriptRuntimeManager.jsonString(method)
            }

        /**
         * PermissionBroker 引导片（扩展包宿主钩子专用，P3）：
         * 输入 = 裸宿主对象 + 授权方法名 JSON 数组；输出 = 只含已授权方法的 `tavern` 对象。
         * - `log` 恒可用（无能力面）；其余方法逐个按授权清单条件装配——未授权方法不在对象上，
         *   `typeof tavern.x === 'undefined'`、调用即 TypeError
         * - 返回值统一 `String()` 归一为 JS 字符串（脚本不必再做 Java 字符串拆包）
         * - 入参出参一律 JSON 文本，与规则包脚本契约一致
         */
        private val TAVERN_BROKER_SOURCE =
            """
            (function (host, grantedJson) {
              var granted = JSON.parse(grantedJson);
              var has = function (name) { return granted.indexOf(name) >= 0; };
              var t = {};
              t.log = function (m) { host.log(m === null || m === undefined ? null : String(m)); };
              if (has('onPrompt')) t.onPrompt = function (fn) { host.onPrompt(fn); };
              if (has('onReply')) t.onReply = function (fn) { host.onReply(fn); };
              if (has('on')) t.on = function (event, fn) { host.on(String(event), fn); };
              if (has('getVar')) t.getVar = function (key) { return String(host.getVar(String(key))); };
              if (has('setVar')) t.setVar = function (key, value) { host.setVar(String(key), value === null || value === undefined ? '' : String(value)); };
              if (has('getVariables')) t.getVariables = function () { return String(host.getVariables()); };
              if (has('insertOrAssignVariables')) t.insertOrAssignVariables = function (json) { host.insertOrAssignVariables(String(json)); };
              if (has('getChatMessages')) t.getChatMessages = function (limit) { return String(host.getChatMessages(Number(limit) > 0 ? Math.floor(Number(limit)) : 1)); };
              if (has('getLastMessageId')) t.getLastMessageId = function () { return String(host.getLastMessageId()); };
              if (has('setChatMessages')) t.setChatMessages = function (payloadJson) { return String(host.setChatMessages(String(payloadJson))); };
              if (has('createChatMessage')) t.createChatMessage = function (payloadJson) { return String(host.createChatMessage(String(payloadJson))); };
              if (has('deleteChatMessages')) t.deleteChatMessages = function (idsJson) { return String(host.deleteChatMessages(String(idsJson))); };
              if (has('triggerGenerate')) t.triggerGenerate = function () { return String(host.triggerGenerate()); };
              if (has('stopGeneration')) t.stopGeneration = function () { host.stopGeneration(); };
              return t;
            })(__tavernHost, __tavernGranted);
            """.trimIndent()
    }
}
