package me.rerere.rikkahub.plugin.loader

import android.content.Context
import android.util.Log
import com.whl.quickjs.wrapper.JSCallFunction
import com.whl.quickjs.wrapper.QuickJSContext
import com.whl.quickjs.wrapper.QuickJSObject
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import me.rerere.rikkahub.data.service.MemoryBankService
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 插件沙箱
 * 使用QuickJS在隔离环境中执行插件代码
 */
class PluginSandbox(
    private val context: Context,
    private val okHttpClient: OkHttpClient,
    private var memoryBankService: MemoryBankService? = null
) {
    companion object {
        private const val TAG = "PluginSandbox"
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // QuickJS上下文
    private var quickJSContext: QuickJSContext? = null
    
    // 导出的函数名称列表
    private val exportedFunctionNames = mutableSetOf<String>()

    /**
     * 初始化沙箱
     */
    fun initialize() {
        if (quickJSContext != null) return
        
        Log.d(TAG, "Initializing QuickJS sandbox")
        
        quickJSContext = QuickJSContext.create().apply {
            // 设置控制台
            setConsole(object : QuickJSContext.Console {
                override fun log(info: String?) {
                    Log.d(TAG, "[Plugin] $info")
                }

                override fun info(info: String?) {
                    Log.i(TAG, "[Plugin] $info")
                }

                override fun warn(info: String?) {
                    Log.w(TAG, "[Plugin] $info")
                }

                override fun error(info: String?) {
                    Log.e(TAG, "[Plugin] $info")
                }
            })

            // 注入exports对象和同步fetch函数
            evaluate("""
                var exports = {};
                
                // 同步fetch函数 - 由原生代码注入
                var __nativeFetch = null;
                
                // 记忆库桥接函数 - 由原生代码注入
                var __memoryBankBridge = null;
                
                // fetch(url) - 同步HTTP请求，返回类浏览器Response对象
                // 支持 response.json(), response.text(), response.ok, response.status 等
                function fetch(url, options) {
                    if (!__nativeFetch) {
                        throw new Error("fetch is not available: native fetch not injected");
                    }
                    var optsJson = options ? JSON.stringify(options) : "{}";
                    var resultJson = __nativeFetch(url, optsJson);
                    var result = JSON.parse(resultJson);
                    if (!result.success) {
                        throw new Error(result.error || "fetch failed");
                    }
                    // 构建类浏览器 Response 对象
                    var response = {
                        ok: result.ok,
                        status: result.status,
                        headers: result.headers,
                        body: result.body,
                        text: function() { return result.body; },
                        json: function() { return JSON.parse(result.body); }
                    };
                    return response;
                }
            """.trimIndent())

            // 注入原生fetch函数 - 通过OkHttp同步执行HTTP请求
            // 使用 getGlobalObject().setProperty() 将 JSCallFunction 注入到全局对象
            getGlobalObject().setProperty("__nativeFetch", JSCallFunction { args ->
                val url = args[0] as? String ?: ""
                val optionsJson = args[1] as? String ?: "{}"
                try {
                    nativeFetch(url, optionsJson)
                } catch (e: Exception) {
                    Log.e(TAG, "Native fetch error", e)
                    """{"success":false,"error":"${e.message?.replace("\"", "\\\"")}"}"""
                }
            })

            // 注入记忆库桥接函数
            getGlobalObject().setProperty("__memoryBankBridge", JSCallFunction { args ->
                val action = args[0] as? String ?: ""
                val paramsJson = args[1] as? String ?: "{}"
                try {
                    nativeMemoryBankBridge(action, paramsJson)
                } catch (e: Exception) {
                    Log.e(TAG, "MemoryBank bridge error", e)
                    """{"success":false,"error":"${e.message?.replace("\"", "\\\"")}"}"""
                }
            })
        }
        
        Log.d(TAG, "QuickJS sandbox initialized with fetch support")
    }

    /**
     * 使用OkHttp执行同步HTTP请求
     * 在JS线程中调用，会阻塞直到请求完成
     */
    private fun nativeFetch(url: String, optionsJson: String): String {
        Log.d(TAG, "nativeFetch: $url")
        
        return try {
            val options = json.parseToJsonElement(optionsJson) as? JsonObject ?: JsonObject(emptyMap())
            
            val method = (options["method"] as? JsonPrimitive)?.contentOrNull?.uppercase() ?: "GET"
            val headers = options["headers"] as? JsonObject
            val body = options["body"] as? JsonPrimitive
            
            val requestBuilder = Request.Builder().url(url)
            
            // 设置请求头
            headers?.forEach { (key, value) ->
                val headerValue = (value as? JsonPrimitive)?.contentOrNull ?: return@forEach
                requestBuilder.addHeader(key, headerValue)
            }
            
            // 设置请求方法和请求体
            when (method) {
                "GET" -> requestBuilder.get()
                "POST" -> {
                    val requestBody = okhttp3.RequestBody.create(
                        null,
                        body?.contentOrNull ?: ""
                    )
                    requestBuilder.post(requestBody)
                }
                "PUT" -> {
                    val requestBody = okhttp3.RequestBody.create(
                        null,
                        body?.contentOrNull ?: ""
                    )
                    requestBuilder.put(requestBody)
                }
                "DELETE" -> {
                    val requestBody = body?.contentOrNull?.let {
                        okhttp3.RequestBody.create(null, it)
                    }
                    if (requestBody != null) {
                        requestBuilder.delete(requestBody)
                    } else {
                        requestBuilder.delete()
                    }
                }
                else -> requestBuilder.get()
            }
            
            // 使用独立的OkHttpClient，设置较短超时
            val fetchClient = okHttpClient.newBuilder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build()
            
            val response = fetchClient.newCall(requestBuilder.build()).execute()
            val responseBody = response.body?.string() ?: ""
            val statusCode = response.code
            val responseHeaders = response.headers
            
            // 构建响应头JSON
            val headersJson = responseHeaders.names().associateWith { name ->
                responseHeaders.values(name).joinToString(", ")
            }
            
            val result = buildString {
                append("{\"success\":true,")
                append("\"status\":$statusCode,")
                append("\"ok\":${statusCode in 200..299},")
                append("\"headers\":${json.encodeToString(JsonObject.serializer(), JsonObject(headersJson.mapValues { JsonPrimitive(it.value) }))},")
                append("\"body\":${json.encodeToString(JsonPrimitive(responseBody))}")
                append("}")
            }
            
            Log.d(TAG, "nativeFetch response: status=$statusCode, bodyLength=${responseBody.length}")
            result
            
        } catch (e: Exception) {
            Log.e(TAG, "nativeFetch failed: $url", e)
            """{"success":false,"error":"${e.message?.replace("\"", "\\\"")?.replace("\\", "\\\\")}"}"""
        }
    }

    /**
     * 执行JS文件
     */
    fun evaluateFile(file: File) {
        val jsContext = quickJSContext ?: throw IllegalStateException("Sandbox not initialized")
        
        Log.d(TAG, "Evaluating JS file: ${file.name}")
        
        var code = file.readText()
        
        // 预处理：将 async function 转为普通 function
        // 因为 QuickJS evaluate() 是同步的，async 函数的 Promise 无法在同步上下文中被解析
        // 注意：如果函数中使用了 await fetch()，async 会被去掉，fetch 是同步的所以不需要 await
        val asyncRegex = Regex("""\basync\s+function\b""")
        if (asyncRegex.containsMatchIn(code)) {
            Log.d(TAG, "Preprocessing: converting async functions to sync functions")
            code = asyncRegex.replace(code, "function")
        }
        
        // 同时移除 await 关键字（因为 fetch 已经是同步的了）
        val awaitRegex = Regex("""\bawait\s+""")
        if (awaitRegex.containsMatchIn(code)) {
            Log.d(TAG, "Preprocessing: removing await keywords")
            code = awaitRegex.replace(code, "")
        }
        
        jsContext.evaluate(code, file.name)
        
        // 使用 Object.keys(exports) 来获取导出的键名
        exportedFunctionNames.clear()
        try {
            val keysResult = jsContext.evaluate("Object.keys(exports)")
            Log.d(TAG, "Object.keys(exports) result type: ${keysResult?.javaClass?.simpleName}")
            
            when (keysResult) {
                is String -> {
                    val keysStr = keysResult.trim()
                    Log.d(TAG, "Keys result string: $keysStr")
                    if (keysStr.startsWith("[") && keysStr.endsWith("]")) {
                        try {
                            val parsed = json.parseToJsonElement(keysStr)
                            if (parsed is JsonArray) {
                                parsed.forEach { element ->
                                    (element as? JsonPrimitive)?.contentOrNull?.let { key ->
                                        exportedFunctionNames.add(key)
                                        Log.d(TAG, "Found exported key: $key")
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            keysStr.removeSurrounding("[", "]")
                                .split(",")
                                .map { it.trim().removeSurrounding("\"", "\"") }
                                .filter { it.isNotEmpty() }
                                .forEach { key ->
                                    exportedFunctionNames.add(key)
                                    Log.d(TAG, "Found exported key: $key")
                                }
                        }
                    } else if (keysStr.isNotEmpty()) {
                        keysStr.split(",")
                            .map { it.trim().removeSurrounding("\"", "\"") }
                            .filter { it.isNotEmpty() }
                            .forEach { key ->
                                exportedFunctionNames.add(key)
                                Log.d(TAG, "Found exported key: $key")
                            }
                    }
                }
                is QuickJSObject -> {
                    val lengthStr = jsContext.evaluate("(Object.keys(exports)).length")?.toString() ?: "0"
                    val length = lengthStr.toIntOrNull() ?: 0
                    Log.d(TAG, "Keys result is QuickJSObject with length: $length")
                    for (i in 0 until length) {
                        val key = jsContext.evaluate("Object.keys(exports)[$i]")?.toString()
                        if (key != null) {
                            exportedFunctionNames.add(key)
                            Log.d(TAG, "Found exported key: $key")
                        }
                    }
                }
                else -> {
                    Log.w(TAG, "Unexpected keys result type: ${keysResult?.javaClass?.simpleName}, value: $keysResult")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get Object.keys(exports)", e)
        }
        
        // 验证每个导出的键确实是函数
        val validFunctions = mutableSetOf<String>()
        for (key in exportedFunctionNames) {
            try {
                val typeCheck = jsContext.evaluate("typeof exports['$key']")?.toString()
                Log.d(TAG, "exports['$key'] type: $typeCheck")
                if (typeCheck == "function") {
                    validFunctions.add(key)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to check type of exports['$key']", e)
            }
        }
        exportedFunctionNames.clear()
        exportedFunctionNames.addAll(validFunctions)
        
        Log.d(TAG, "Exported functions: $exportedFunctionNames")
    }

    /**
     * 调用导出的函数
     * 注意：必须在 QuickJS 线程上调用（通过 PluginLoader 的 pluginDispatcher）
     */
    fun callFunction(name: String, params: JsonElement): JsonElement {
        val jsContext = quickJSContext ?: throw IllegalStateException("Sandbox not initialized")
        
        if (!exportedFunctionNames.contains(name)) {
            throw IllegalArgumentException("Function '$name' not found in exports. Available: $exportedFunctionNames")
        }
        
        Log.d(TAG, "Calling function: $name with params: $params")
        
        return try {
            // 将参数转换为JSON字符串
            val paramsJson = json.encodeToString(JsonElement.serializer(), params)
            
            // 调用函数并序列化结果
            // 注意：不再额外包装 {success, data}，直接返回函数的原始返回值
            // 插件函数自身负责返回合适的结果格式
            val callCode = """
                (function() {
                    try {
                        var __ret = exports['$name']($paramsJson);
                        if (__ret && typeof __ret.then === 'function') {
                            // Promise - 尝试同步解析（通常不会发生，因为已预处理移除async）
                            var __resolved = null;
                            var __rejected = null;
                            __ret.then(function(v) { __resolved = v; }).catch(function(e) { __rejected = e; });
                            if (__rejected) {
                                return JSON.stringify({success: false, error: __rejected.message || String(__rejected)});
                            }
                            return JSON.stringify(__resolved);
                        }
                        return JSON.stringify(__ret);
                    } catch(e) {
                        return JSON.stringify({success: false, error: e.message || String(e)});
                    }
                })()
            """.trimIndent()
            
            val result = jsContext.evaluate(callCode)
            Log.d(TAG, "Function $name raw result: $result")
            
            // 将结果转换回JsonElement
            jsValueToJsonElement(result)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to call function '$name'", e)
            buildJsonObject {
                put("success", JsonPrimitive(false))
                put("error", JsonPrimitive(e.message ?: "Unknown error"))
            }
        }
    }

    /**
     * 将JS值转换为JsonElement
     */
    private fun jsValueToJsonElement(value: Any?): JsonElement {
        return when (value) {
            null -> JsonNull
            is String -> {
                // 尝试解析为JSON
                try {
                    json.parseToJsonElement(value)
                } catch (e: Exception) {
                    JsonPrimitive(value)
                }
            }
            is Number -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is QuickJSObject -> {
                val jsonStr = value.stringify()
                try {
                    json.parseToJsonElement(jsonStr)
                } catch (e: Exception) {
                    JsonPrimitive(jsonStr)
                }
            }
            else -> JsonPrimitive(value?.toString() ?: "")
        }
    }

    /**
     * 设置记忆库服务引用
     */
    fun setMemoryBankService(service: MemoryBankService?) {
        this.memoryBankService = service
    }

    /**
     * 记忆库桥接 - 由JS插件调用，同步执行记忆库操作
     */
    private fun nativeMemoryBankBridge(action: String, paramsJson: String): String {
        val service = memoryBankService
        if (service == null) {
            return """{"success":false,"error":"记忆库服务未初始化"}"""
        }

        return try {
            runBlocking {
                when (action) {
                    "recall" -> {
                        val params = JSONObject(paramsJson)
                        val query = params.optString("query", "")
                        val count = params.optInt("count", service.recallCount)
                        if (query.isBlank()) {
                            """{"success":false,"error":"query is required"}"""
                        } else {
                            val memories = service.recallMemories(query, count)
                            val memoriesJson = memories.joinToString(",", "[", "]") { mem ->
                                """{"id":${mem.id},"content":${escapeJson(mem.content)},"type":"${mem.type}","createdAt":${mem.createdAt},"role":${mem.role?.let { escapeJson(it) } ?: "null"}}"""
                            }
                            """{"success":true,"memories":$memoriesJson}"""
                        }
                    }

                    "save" -> {
                        val params = JSONObject(paramsJson)
                        val content = params.optString("content", "")
                        if (content.isBlank()) {
                            """{"success":false,"error":"content is required"}"""
                        } else {
                            val memory = service.saveManualMemory(content)
                            """{"success":true,"id":${memory.id},"content":${escapeJson(memory.content)}}"""
                        }
                    }

                    "search" -> {
                        val params = JSONObject(paramsJson)
                        val keyword = params.optString("keyword", "")
                        val type = params.optString("type", "")
                        val limit = params.optInt("limit", 20)
                        val memories = service.searchMemories(keyword, type, limit)
                        val memoriesJson = memories.joinToString(",", "[", "]") { mem ->
                            """{"id":${mem.id},"content":${escapeJson(mem.content)},"type":"${mem.type}","createdAt":${mem.createdAt},"role":${mem.role?.let { escapeJson(it) } ?: "null"}}"""
                        }
                        """{"success":true,"memories":$memoriesJson}"""
                    }

                    "delete" -> {
                        val params = JSONObject(paramsJson)
                        val id = params.optInt("id", -1)
                        if (id < 0) {
                            """{"success":false,"error":"id is required"}"""
                        } else {
                            service.deleteMemory(id)
                            """{"success":true,"id":$id}"""
                        }
                    }

                    else -> """{"success":false,"error":"unknown action: $action"}"""
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "MemoryBank bridge action '$action' failed", e)
            """{"success":false,"error":"${e.message?.replace("\"", "\\\"")?.replace("\\", "\\\\")}"}"""
        }
    }

    /**
     * 转义JSON字符串中的特殊字符
     */
    private fun escapeJson(str: String): String {
        val escaped = str
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return "\"$escaped\""
    }

    /**
     * 注入配置
     */
    fun injectConfig(config: Map<String, JsonElement>) {
        val jsContext = quickJSContext ?: return
        
        val configJson = json.encodeToString(JsonObject.serializer(), JsonObject(config))
        jsContext.evaluate("var config = $configJson;")
    }

    /**
     * 检查是否有指定函数
     */
    fun hasFunction(name: String): Boolean {
        return exportedFunctionNames.contains(name)
    }

    /**
     * 获取所有导出函数名
     */
    fun getExportedFunctionNames(): Set<String> {
        return exportedFunctionNames.toSet()
    }

    /**
     * 销毁沙箱
     */
    fun destroy() {
        exportedFunctionNames.clear()
        quickJSContext?.destroy()
        quickJSContext = null
        Log.d(TAG, "Sandbox destroyed")
    }
}