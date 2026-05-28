package me.rerere.rikkahub.plugin.loader

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import me.rerere.rikkahub.data.service.MemoryBankService
import me.rerere.rikkahub.plugin.model.PluginInfo
import okhttp3.OkHttpClient
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher

/**
 * 插件加载器
 * 负责加载和管理插件生命周期
 */
class PluginLoader(
    private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val memoryBankService: MemoryBankService? = null
) {
    companion object {
        private const val TAG = "PluginLoader"
    }

    // 单线程调度器，确保所有QuickJS操作在同一线程执行
    private val pluginDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread(r, "plugin-quickjs").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    // 已加载的插件缓存
    private val loadedPlugins = mutableMapOf<String, LoadedPlugin>()

    /**
     * 加载插件
     */
    suspend fun loadPlugin(pluginInfo: PluginInfo): Result<LoadedPlugin> = withContext(pluginDispatcher) {
        try {
            // 检查插件是否已加载
            if (loadedPlugins.containsKey(pluginInfo.manifest.id)) {
                doUnloadPlugin(pluginInfo.manifest.id)
            }

            // 检查插件是否启用
            if (!pluginInfo.isEnabled) {
                return@withContext Result.failure(IllegalStateException("Plugin is disabled"))
            }

            // 检查入口文件
            val entryFile = pluginInfo.getEntryFile()
            if (!entryFile.exists()) {
                return@withContext Result.failure(
                    IllegalStateException("Entry file not found: ${pluginInfo.manifest.entry}")
                )
            }

            // 创建沙箱
            val sandbox = PluginSandbox(context, okHttpClient, memoryBankService)
            sandbox.initialize()

            // 注入配置
            sandbox.injectConfig(pluginInfo.config)

            // 执行JS代码
            sandbox.evaluateFile(entryFile)

            // 创建LoadedPlugin
            val loadedPlugin = LoadedPlugin(
                info = pluginInfo,
                sandbox = sandbox
            )

            // 验证工具函数存在
            val exportedNames = sandbox.getExportedFunctionNames()
            Log.i(TAG, "Plugin ${pluginInfo.manifest.id} exported functions: $exportedNames")
            pluginInfo.manifest.tools.forEach { tool ->
                if (!sandbox.hasFunction(tool.name)) {
                    // 工具声明了但exports中没有，记录警告
                    Log.w(TAG, "Tool '${tool.name}' declared in manifest but not found in exports (available: $exportedNames)")
                } else {
                    Log.i(TAG, "Tool '${tool.name}' registered successfully")
                }
            }

            // 缓存
            loadedPlugins[pluginInfo.manifest.id] = loadedPlugin

            Result.success(loadedPlugin)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load plugin ${pluginInfo.manifest.id}", e)
            Result.failure(e)
        }
    }

    /**
     * 内部卸载方法（必须在pluginDispatcher线程调用）
     */
    private fun doUnloadPlugin(pluginId: String) {
        loadedPlugins.remove(pluginId)?.let { plugin ->
            plugin.sandbox.destroy()
            Log.d(TAG, "Unloaded plugin: $pluginId")
        }
    }

    /**
     * 卸载插件
     */
    suspend fun unloadPlugin(pluginId: String) = withContext(pluginDispatcher) {
        doUnloadPlugin(pluginId)
    }

    /**
     * 重新加载插件
     */
    suspend fun reloadPlugin(pluginInfo: PluginInfo): Result<LoadedPlugin> {
        unloadPlugin(pluginInfo.manifest.id)
        return loadPlugin(pluginInfo)
    }

    /**
     * 获取已加载的插件
     */
    fun getLoadedPlugin(pluginId: String): LoadedPlugin? = loadedPlugins[pluginId]

    /**
     * 获取所有已加载的插件
     */
    fun getAllLoadedPlugins(): List<LoadedPlugin> = loadedPlugins.values.toList()

    /**
     * 获取启用的插件
     */
    fun getEnabledPlugins(): List<LoadedPlugin> {
        return loadedPlugins.values.filter { it.info.isEnabled }
    }

    /**
     * 调用插件工具
     */
    suspend fun callTool(pluginId: String, toolName: String, params: JsonElement): Result<JsonElement> {
        return withContext(pluginDispatcher) {
            try {
                val plugin = loadedPlugins[pluginId]
                    ?: return@withContext Result.failure(IllegalStateException("Plugin not loaded: $pluginId"))

                if (!plugin.hasTool(toolName)) {
                    return@withContext Result.failure(IllegalArgumentException("Tool not found: $toolName"))
                }

                val result = plugin.sandbox.callFunction(toolName, params)
                Result.success(result)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to call tool $toolName in plugin $pluginId", e)
                Result.failure(e)
            }
        }
    }

    /**
     * 触发插件事件
     * 遍历所有已加载插件，找到监听指定事件的钩子并调用对应的处理函数
     * @param event 事件名称，如 "message_sent", "message_received", "daily_cron"
     * @param params 事件参数
     */
    suspend fun callEvent(event: String, params: JsonElement) {
        withContext(pluginDispatcher) {
            for (plugin in loadedPlugins.values) {
                if (!plugin.info.isEnabled) continue

                val matchingHooks = plugin.info.manifest.hooks.filter { it.event == event }
                for (hook in matchingHooks) {
                    try {
                        if (plugin.sandbox.hasFunction(hook.handler)) {
                            plugin.sandbox.callFunction(hook.handler, params)
                            Log.d(TAG, "Event '$event' handled by plugin ${plugin.id}.${hook.handler}")
                        } else {
                            Log.w(TAG, "Hook handler '${hook.handler}' not found in plugin ${plugin.id}")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to handle event '$event' in plugin ${plugin.id}.${hook.handler}", e)
                    }
                }
            }
        }
    }

    /**
     * 获取所有声明了 daily_cron 钩子的插件
     * 用于定时任务调度
     */
    fun getPluginsWithDailyCron(): List<Pair<LoadedPlugin, String>> {
        return loadedPlugins.values.filter { it.info.isEnabled }.flatMap { plugin ->
            plugin.info.manifest.hooks
                .filter { it.event == "daily_cron" }
                .map { hook -> plugin to hook.handler }
        }
    }

    /**
     * 卸载所有插件
     */
    suspend fun unloadAll() = withContext(pluginDispatcher) {
        loadedPlugins.keys.toList().forEach { doUnloadPlugin(it) }
    }
}
