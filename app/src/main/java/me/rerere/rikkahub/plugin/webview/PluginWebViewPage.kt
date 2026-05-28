package me.rerere.rikkahub.plugin.webview

import android.annotation.SuppressLint
import android.net.Uri
import android.util.Base64
import android.util.Log
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.rikkahub.plugin.data.PluginDataStore
import me.rerere.rikkahub.plugin.loader.PluginLoader
import me.rerere.rikkahub.plugin.loader.LoadedPlugin
import me.rerere.rikkahub.plugin.manager.PluginManager
import me.rerere.rikkahub.plugin.model.PluginInfo
import me.rerere.rikkahub.plugin.repository.PluginRepository
import org.json.JSONArray
import org.koin.compose.koinInject
import java.io.File

private const val TAG = "PluginWebViewPage"

/**
 * 插件 WebView 自定义页面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginWebViewPage(
    pluginId: String,
    htmlEntryPath: String,
    pluginManager: PluginManager,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val pluginLoader = koinInject<PluginLoader>()
    val pluginRepository = koinInject<PluginRepository>()
    var webView by remember { mutableStateOf<WebView?>(null) }
    var pendingImageCallback by remember { mutableStateOf<String?>(null) }

    // 获取插件信息
    val plugins by pluginManager.plugins.collectAsStateWithLifecycle()
    val pluginInfo = plugins.find { it.manifest.id == pluginId }

    // 插件数据存储
    val dataStore = remember(pluginId) {
        PluginDataStore(context, pluginId)
    }

    // 图片选择器 - 必须在 Composable 顶层注册
    val pickImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        val callbackId = pendingImageCallback
        if (callbackId != null && uri != null) {
            try {
                val base64 = uriToBase64(context, uri)
                webView?.evaluateJavascript(
                    "if(window.__bridgeCallbacks && window.__bridgeCallbacks['$callbackId'])" +
                            "{window.__bridgeCallbacks['$callbackId']($base64); delete window.__bridgeCallbacks['$callbackId'];}",
                    null
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to process picked image", e)
                webView?.evaluateJavascript(
                    "if(window.__bridgeCallbacks && window.__bridgeCallbacks['$callbackId'])" +
                            "{window.__bridgeCallbacks['$callbackId'](null); delete window.__bridgeCallbacks['$callbackId'];}",
                    null
                )
            }
        } else if (callbackId != null) {
            webView?.evaluateJavascript(
                "if(window.__bridgeCallbacks && window.__bridgeCallbacks['$callbackId'])" +
                        "{window.__bridgeCallbacks['$callbackId'](null); delete window.__bridgeCallbacks['$callbackId'];}",
                null
            )
        }
        pendingImageCallback = null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(pluginInfo?.manifest?.name ?: "插件管理") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(HugeIcons.ArrowLeft01, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (pluginInfo == null) {
                Text(
                    text = "插件不存在",
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.apply {
                                @SuppressLint("SetJavaScriptEnabled")
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                allowFileAccess = true
                                allowContentAccess = true
                                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                databaseEnabled = true
                            }

                            webViewClient = PluginWebViewClient(
                                pluginInfo = pluginInfo,
                                dataStore = dataStore,
                                pluginLoader = pluginLoader,
                                pluginManager = pluginManager,
                                pluginRepository = pluginRepository,
                                onPickImage = { callbackId ->
                                    pendingImageCallback = callbackId
                                    pickImageLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                                onClose = onNavigateBack
                            )

                            webChromeClient = WebChromeClient()

                            // 加载 HTML
                            val htmlFile = File(pluginInfo.directory, htmlEntryPath)
                            if (htmlFile.exists()) {
                                loadUrl("file://${htmlFile.absolutePath}")
                            } else {
                                loadData(
                                    "<html><body><h2>页面文件不存在</h2><p>$htmlEntryPath</p></body></html>",
                                    "text/html",
                                    "UTF-8"
                                )
                            }

                            webView = this@apply
                        }
                    },
                    update = { wv ->
                        webView = wv
                    }
                )
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.destroy()
        }
    }
}

/**
 * 自定义 WebViewClient，通过 bridge:// 协议处理 JS Bridge 请求
 */
private class PluginWebViewClient(
    private val pluginInfo: PluginInfo,
    private val dataStore: PluginDataStore,
    private val pluginLoader: PluginLoader,
    private val pluginManager: PluginManager,
    private val pluginRepository: PluginRepository,
    private val onPickImage: (callbackId: String) -> Unit,
    private val onClose: () -> Unit
) : WebViewClient() {
    private val json = Json { ignoreUnknownKeys = true }

    override fun shouldOverrideUrlLoading(
        view: WebView?,
        request: WebResourceRequest?
    ): Boolean {
        val url = request?.url?.toString() ?: return false
        if (url.startsWith("bridge://")) {
            handleBridgeCall(view!!, url)
            return true
        }
        // 只允许 file:// 和 about:blank
        return !url.startsWith("file://") && !url.startsWith("about:blank")
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        // 页面加载完成后注入 Bridge JS 对象
        view?.evaluateJavascript(bridgeJavascript, null)
    }

    private fun handleBridgeCall(webView: WebView, url: String) {
        val uri = Uri.parse(url)
        val method = uri.host ?: return
        val params = uri.queryParameterNames.associateWith { uri.getQueryParameter(it) ?: "" }

        when (method) {
            "getPluginConfig" -> {
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        // 使用注入的单例 repository，避免 DataStore 多实例冲突
                        val savedConfig = pluginRepository.getPluginConfig(pluginInfo.manifest.id)
                        val mergedConfig = mutableMapOf<String, kotlinx.serialization.json.JsonElement>()
                        
                        // 合并 manifest 默认值和已保存配置
                        pluginInfo.manifest.config.forEach { field ->
                            if (savedConfig.containsKey(field.name)) {
                                mergedConfig[field.name] = savedConfig[field.name]!!
                            } else if (field.default != null) {
                                mergedConfig[field.name] = field.default
                            }
                        }
                        
                        // 合入其他已保存但不在 manifest 配置定义中的值
                        savedConfig.forEach { (key, value) ->
                            if (!mergedConfig.containsKey(key)) {
                                mergedConfig[key] = value
                            }
                        }
                        
                        val jsonObj = JsonObject(mergedConfig)
                        val result = json.encodeToString(JsonObject.serializer(), jsonObj)
                        Log.d(TAG, "getPluginConfig for ${pluginInfo.manifest.id}: $result")
                        webView.post {
                            webView.evaluateJavascript(
                                "window.__bridgeResult('${params["callbackId"]}', $result);", null
                            )
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to get plugin config", e)
                        // 返回空配置作为 fallback
                        webView.post {
                            webView.evaluateJavascript(
                                "window.__bridgeResult('${params["callbackId"]}', {});", null
                            )
                        }
                    }
                }
            }

            "getData" -> {
                val key = params["key"] ?: ""
                val value = dataStore.getData(key)
                val result = if (value != null) {
                    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")}\""
                } else "null"
                webView.post {
                    webView.evaluateJavascript(
                        "window.__bridgeResult('${params["callbackId"]}', $result);", null
                    )
                }
            }

            "setData" -> {
                val key = params["key"] ?: ""
                val value = params["value"] ?: ""
                dataStore.setData(key, value)
                webView.post {
                    webView.evaluateJavascript(
                        "window.__bridgeResult('${params["callbackId"]}', true);", null
                    )
                }
            }

            "deleteData" -> {
                val key = params["key"] ?: ""
                dataStore.deleteData(key)
                webView.post {
                    webView.evaluateJavascript(
                        "window.__bridgeResult('${params["callbackId"]}', true);", null
                    )
                }
            }

            "listData" -> {
                val keys = dataStore.listData()
                val jsonArray = JSONArray(keys)
                webView.post {
                    webView.evaluateJavascript(
                        "window.__bridgeResult('${params["callbackId"]}', ${jsonArray.toString()});", null
                    )
                }
            }

            "pickImage" -> {
                val callbackId = params["callbackId"] ?: ""
                onPickImage(callbackId)
            }

            "writeFile" -> {
                val fileName = params["fileName"] ?: ""
                val base64Data = params["data"] ?: ""
                try {
                    val dir = dataStore.getDataDir()
                    val file = File(dir, fileName)
                    val bytes = Base64.decode(base64Data, Base64.DEFAULT)
                    file.writeBytes(bytes)
                    webView.post {
                        webView.evaluateJavascript(
                            "window.__bridgeResult('${params["callbackId"]}', '${file.absolutePath}');", null
                        )
                    }
                } catch (e: Exception) {
                    webView.post {
                        webView.evaluateJavascript(
                            "window.__bridgeResult('${params["callbackId"]}', null);", null
                        )
                    }
                }
            }

            "readFile" -> {
                val fileName = params["fileName"] ?: ""
                try {
                    val dir = dataStore.getDataDir()
                    val file = File(dir, fileName)
                    if (file.exists()) {
                        val bytes = file.readBytes()
                        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                        webView.post {
                            webView.evaluateJavascript(
                                "window.__bridgeResult('${params["callbackId"]}', '$base64');", null
                            )
                        }
                    } else {
                        webView.post {
                            webView.evaluateJavascript(
                                "window.__bridgeResult('${params["callbackId"]}', null);", null
                            )
                        }
                    }
                } catch (e: Exception) {
                    webView.post {
                        webView.evaluateJavascript(
                            "window.__bridgeResult('${params["callbackId"]}', null);", null
                        )
                    }
                }
            }

            "listFiles" -> {
                val dirPath = params["dir"] ?: ""
                val baseDir = if (dirPath.isEmpty()) dataStore.getDataDir()
                              else File(dataStore.getDataDir(), dirPath)
                val files = if (baseDir.exists() && baseDir.isDirectory) {
                    baseDir.listFiles()?.map { it.name } ?: emptyList()
                } else emptyList()
                val jsonArray = JSONArray(files)
                webView.post {
                    webView.evaluateJavascript(
                        "window.__bridgeResult('${params["callbackId"]}', ${jsonArray.toString()});", null
                    )
                }
            }

            "deleteFile" -> {
                val fileName = params["fileName"] ?: ""
                val dir = dataStore.getDataDir()
                val file = File(dir, fileName)
                val result = file.delete()
                webView.post {
                    webView.evaluateJavascript(
                        "window.__bridgeResult('${params["callbackId"]}', $result);", null
                    )
                }
            }

            "close" -> {
                onClose()
            }
            
            "callTool" -> {
                val toolName = params["toolName"] ?: ""
                val toolParams = params["params"] ?: "{}"
                CoroutineScope(Dispatchers.Main).launch {
                    try {
                        // 获取插件工具提供者并调用工具
                        val result = callPluginTool(toolName, toolParams)
                        webView.post {
                            webView.evaluateJavascript(
                                "window.__bridgeResult('${params["callbackId"]}', ${result});", null
                            )
                        }
                    } catch (e: Exception) {
                        val errorResult = """{"success":false,"error":"${e.message}"}"""
                        webView.post {
                            webView.evaluateJavascript(
                                "window.__bridgeResult('${params["callbackId"]}', $errorResult);", null
                            )
                        }
                    }
                }
            }
        }
    }
    
    /**
     * 调用插件工具
     * 通过查找包含该工具的插件并调用
     */
    private suspend fun callPluginTool(toolName: String, params: String): String {
        return try {
            // 查找包含该工具的插件
            val loadedPlugin: LoadedPlugin? = pluginLoader.getAllLoadedPlugins().find { plugin: LoadedPlugin ->
                plugin.info.manifest.tools.any { toolDef -> toolDef.name == toolName }
            }
            
            if (loadedPlugin == null) {
                return """{"success":false,"error":"Tool not found: $toolName"}"""
            }
            
            // 解析参数
            val jsonParams = Json.parseToJsonElement(params)
            
            // 调用工具
            val result = pluginLoader.callTool(
                pluginId = loadedPlugin.id,
                toolName = toolName,
                params = jsonParams
            )
            
            // 返回结果
            return result.fold(
                onSuccess = { jsonElement: kotlinx.serialization.json.JsonElement ->
                    Json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), jsonElement)
                },
                onFailure = { error: Throwable ->
                    """{"success":false,"error":"${error.message}"}"""
                }
            )
        } catch (e: Exception) {
            """{"success":false,"error":"${e.message}"}"""
        }
    }
}

/**
 * Bridge JavaScript 代码，注入到 WebView 中
 * 提供异步 Promise 风格的 API
 */
private const val bridgeJavascript = """
(function() {
    if (window.Bridge) return;
    
    window.__bridgeCallbacks = {};
    window.__bridgeResultId = 0;
    
    window.__bridgeResult = function(callbackId, result) {
        if (callbackId && window.__bridgeCallbacks[callbackId]) {
            try {
                window.__bridgeCallbacks[callbackId](result);
            } catch(e) {
                console.error('Bridge callback error:', e);
            }
            delete window.__bridgeCallbacks[callbackId];
        }
    };
    
    function bridgeCall(method, params) {
        return new Promise(function(resolve, reject) {
            var callbackId = 'cb_' + (++window.__bridgeResultId);
            window.__bridgeCallbacks[callbackId] = resolve;
            
            var url = 'bridge://' + method + '?callbackId=' + encodeURIComponent(callbackId);
            for (var key in params) {
                if (params.hasOwnProperty(key)) {
                    url += '&' + encodeURIComponent(key) + '=' + encodeURIComponent(String(params[key]));
                }
            }
            
            // 使用 iframe 方式避免页面跳转
            var iframe = document.createElement('iframe');
            iframe.style.display = 'none';
            iframe.src = url;
            document.body.appendChild(iframe);
            setTimeout(function() {
                document.body.removeChild(iframe);
            }, 100);
        });
    }
    
    window.Bridge = {
        getPluginConfig: function() {
            return bridgeCall('getPluginConfig', {});
        },
        getData: function(key) {
            return bridgeCall('getData', {key: key});
        },
        setData: function(key, value) {
            return bridgeCall('setData', {key: key, value: value});
        },
        deleteData: function(key) {
            return bridgeCall('deleteData', {key: key});
        },
        listData: function() {
            return bridgeCall('listData', {});
        },
        pickImage: function() {
            return bridgeCall('pickImage', {});
        },
        callTool: function(toolName, params) {
            return bridgeCall('callTool', {toolName: toolName, params: params || '{}'});
        },
        writeFile: function(fileName, base64Data) {
            return bridgeCall('writeFile', {fileName: fileName, data: base64Data});
        },
        readFile: function(fileName) {
            return bridgeCall('readFile', {fileName: fileName});
        },
        listFiles: function(dirPath) {
            return bridgeCall('listFiles', {dir: dirPath || ''});
        },
        deleteFile: function(fileName) {
            return bridgeCall('deleteFile', {fileName: fileName});
        },
        close: function() {
            bridgeCall('close', {});
        }
    };
    
    console.log('Bridge API initialized');
})();
"""

private fun uriToBase64(context: android.content.Context, uri: Uri): String {
    val inputStream = context.contentResolver.openInputStream(uri) ?: return "null"
    val bytes = inputStream.readBytes()
    inputStream.close()
    return "\"" + Base64.encodeToString(bytes, Base64.NO_WRAP) + "\""
}