package me.rerere.rikkahub.plugin.manager

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import me.rerere.rikkahub.data.service.DailySummaryService
import me.rerere.rikkahub.plugin.loader.PluginLoader
import me.rerere.rikkahub.plugin.model.PluginInfo
import me.rerere.rikkahub.plugin.repository.PluginRepository
import me.rerere.rikkahub.plugin.scanner.PluginScanner
import java.io.File

/**
 * 插件管理器
 * 统一管理插件的生命周期
 */
class PluginManager(
    private val context: Context,
    private val scanner: PluginScanner,
    private val loader: PluginLoader,
    private val repository: PluginRepository
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    // 插件列表状态
    private val _plugins = MutableStateFlow<List<PluginInfo>>(emptyList())
    val plugins: StateFlow<List<PluginInfo>> = _plugins.asStateFlow()

    // 加载状态
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        // 初始化时扫描并加载插件
        CoroutineScope(Dispatchers.IO).launch {
            refreshPlugins()
        }
    }

    /**
     * 刷新插件列表
     */
    suspend fun refreshPlugins() {
        _isLoading.value = true
        try {
            // 1. 扫描插件
            val scannedPlugins = scanner.scanPlugins()

            // 2. 加载已保存的配置和启用状态
            val pluginsWithConfig = scannedPlugins.map { plugin ->
                val savedConfig = repository.getPluginConfig(plugin.manifest.id)
                val isEnabled = repository.isPluginEnabled(plugin.manifest.id)
                plugin.copy(
                    config = savedConfig,
                    isEnabled = isEnabled
                )
            }

            // 3. 更新状态
            _plugins.value = pluginsWithConfig

            // 4. 加载启用的插件（跳过已加载的）
            pluginsWithConfig.filter { it.isEnabled }.forEach { plugin ->
                if (loader.getLoadedPlugin(plugin.manifest.id) == null) {
                    loadPlugin(plugin)
                }
            }

            // 5. 更新 daily_cron 调度（插件加载后可能有新的 daily_cron 钩子）
            DailySummaryService.rescheduleIfEnabled(context)
        } finally {
            _isLoading.value = false
        }
    }

    /**
     * 加载单个插件
     */
    private suspend fun loadPlugin(plugin: PluginInfo) {
        loader.loadPlugin(plugin).fold(
            onSuccess = {
                // 加载成功
            },
            onFailure = { error ->
                // 更新插件状态为错误
                updatePluginState(plugin.manifest.id) {
                    it.copy(
                        isEnabled = false,
                        loadError = error.message
                    )
                }
            }
        )
    }

    /**
     * 导入插件
     */
    suspend fun importPlugin(uri: Uri): Result<PluginInfo> {
        return try {
            // 1. 从ZIP导入
            val result = scanner.importFromZip(uri)
            
            result.fold(
                onSuccess = { pluginInfo ->
                    // 2. 保存到仓库
                    repository.savePlugin(pluginInfo)
                    
                    // 3. 尝试加载
                    loadPlugin(pluginInfo)
                    
                    // 4. 刷新列表
                    refreshPlugins()
                    
                    Result.success(pluginInfo)
                },
                onFailure = { error ->
                    Result.failure(error)
                }
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 删除插件
     */
    suspend fun deletePlugin(pluginId: String): Boolean {
        return try {
            // 1. 卸载插件
            loader.unloadPlugin(pluginId)
            
            // 2. 删除目录
            val deleted = scanner.deletePlugin(pluginId)
            
            // 3. 从仓库移除配置
            repository.removePlugin(pluginId)
            
            // 4. 刷新列表
            refreshPlugins()
            
            deleted
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 切换插件启用状态
     */
    suspend fun togglePlugin(pluginId: String, enabled: Boolean) {
        val plugin = _plugins.value.find { it.manifest.id == pluginId } ?: return
        
        if (enabled) {
            // 启用插件
            loadPlugin(plugin.copy(isEnabled = true))
        } else {
            // 禁用插件
            loader.unloadPlugin(pluginId)
        }
        
        // 保存状态
        repository.setPluginEnabled(pluginId, enabled)
        
        // 更新状态
        updatePluginState(pluginId) { it.copy(isEnabled = enabled) }

        // 更新 daily_cron 调度（启用/禁用可能影响 daily_cron 钩子）
        DailySummaryService.rescheduleIfEnabled(context)
    }

    /**
     * 更新插件配置
     */
    suspend fun updatePluginConfig(pluginId: String, config: Map<String, JsonElement>) {
        // 保存配置
        repository.savePluginConfig(pluginId, config)
        
        // 更新状态
        updatePluginState(pluginId) { it.copy(config = config) }
        
        // 重新加载插件（如果已启用）
        val plugin = _plugins.value.find { it.manifest.id == pluginId }
        if (plugin?.isEnabled == true) {
            loader.unloadPlugin(pluginId)
            loadPlugin(plugin.copy(config = config))
        }
    }

    /**
     * 获取插件配置
     */
    suspend fun getPluginConfig(pluginId: String): Map<String, JsonElement> {
        return repository.getPluginConfig(pluginId)
    }

    /**
     * 获取插件目录
     */
    fun getPluginsDirectory(): File {
        return scanner.pluginsDir
    }

    /**
     * 获取插件信息
     */
    fun getPlugin(pluginId: String): PluginInfo? {
        return _plugins.value.find { it.manifest.id == pluginId }
    }

    /**
     * 重新加载所有插件
     */
    suspend fun reloadAllPlugins() {
        loader.unloadAll()
        refreshPlugins()
    }

    /**
     * 更新插件状态
     */
    private fun updatePluginState(pluginId: String, transform: (PluginInfo) -> PluginInfo) {
        _plugins.value = _plugins.value.map { plugin ->
            if (plugin.manifest.id == pluginId) {
                transform(plugin)
            } else {
                plugin
            }
        }
    }
}