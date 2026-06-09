package me.rerere.rikkahub.utils

import android.content.Context
import android.system.Os
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.GZIPInputStream

/**
 * 运行时环境提取器
 * 
 * 负责从 assets 中提取 Python 和 Node.js 运行时到应用私有目录。
 * 运行时文件结构：
 * - assets/runtime/python.tar.gz  →  files/runtime/  (包含 bin/python3, lib/python3.13/ 等)
 * - assets/runtime/node.tar.gz    →  files/runtime/  (包含 bin/node, lib/node_modules/ 等)
 * 
 * 提取后的目录结构：
 * files/runtime/
 * ├── bin/
 * │   ├── python3, pip, node, npm, npx ...
 * ├── lib/
 * │   ├── libpython3.13.so, libnode.so, *.so ...
 * │   ├── python3.13/  (标准库)
 * │   ├── node_modules/  (npm 基础模块)
 * ├── ...
 */
object RuntimeExtractor {

    private const val TAG = "RuntimeExtractor"

    /** 运行时目录名 */
    private const val RUNTIME_DIR_NAME = "runtime"

    /** 标记文件，表示运行时已成功提取 */
    private const val MARKER_FILE = ".runtime_extracted"

    /** assets 中的运行时压缩包路径 */
    private val RUNTIME_ASSETS = listOf(
        "runtime/python.tar.gz",
        "runtime/node.tar.gz"
    )

    /**
     * 获取运行时根目录
     */
    fun getRuntimeDir(context: Context): File {
        return File(context.filesDir, RUNTIME_DIR_NAME)
    }

    /**
     * 检查运行时是否已提取
     */
    fun isRuntimeExtracted(context: Context): Boolean {
        val runtimeDir = getRuntimeDir(context)
        val marker = File(runtimeDir, MARKER_FILE)
        return marker.exists() && runtimeDir.isDirectory
    }

    /**
     * 检查运行时是否可用（assets 中有压缩包或已提取）
     */
    fun isRuntimeAvailable(context: Context): Boolean {
        if (isRuntimeExtracted(context)) return true
        // 检查 assets 中是否有运行时文件
        return try {
            val assets = context.assets.list("runtime") ?: emptyArray()
            assets.any { it.endsWith(".tar.gz") }
        } catch (e: IOException) {
            false
        }
    }

    /**
     * 提取所有运行时（如果尚未提取）
     * 
     * @return true 如果运行时已就绪（已提取或提取成功），false 如果没有可提取的运行时
     */
    fun extractIfNeeded(context: Context): Boolean {
        if (isRuntimeExtracted(context)) {
            Log.d(TAG, "Runtime already extracted, skipping")
            return true
        }

        val runtimeDir = getRuntimeDir(context)
        
        // 检查 assets 中是否有运行时文件
        val availableAssets = try {
            RUNTIME_ASSETS.filter { assetPath ->
                try {
                    context.assets.open(assetPath).close()
                    true
                } catch (e: IOException) {
                    false
                }
            }
        } catch (e: IOException) {
            emptyList()
        }

        if (availableAssets.isEmpty()) {
            Log.d(TAG, "No runtime assets found, runtime will not be available")
            return false
        }

        Log.i(TAG, "Extracting runtime to ${runtimeDir.absolutePath}")
        
        try {
            if (!runtimeDir.exists()) {
                runtimeDir.mkdirs()
            }

            for (assetPath in availableAssets) {
                Log.i(TAG, "Extracting $assetPath ...")
                val startTime = System.currentTimeMillis()
                
                context.assets.open(assetPath).use { assetStream ->
                    GZIPInputStream(assetStream).use { gzipStream ->
                        // 使用 tar 命令解压（比纯 Java 实现快很多）
                        val process = Runtime.getRuntime().exec(
                            arrayOf("tar", "-xf", "-", "-C", runtimeDir.absolutePath)
                        )
                        
                        // 将 gzip 流导入 tar 进程的 stdin
                        val writerThread = Thread {
                            try {
                                val output = process.outputStream
                                val buffer = ByteArray(8192)
                                var bytesRead: Int
                                while (gzipStream.read(buffer).also { bytesRead = it } != -1) {
                                    output.write(buffer, 0, bytesRead)
                                }
                                output.flush()
                                output.close()
                            } catch (e: IOException) {
                                Log.e(TAG, "Error writing to tar process", e)
                            }
                        }
                        writerThread.start()
                        
                        // 读取 tar 的输出（如果有）
                        val outputReader = Thread {
                            try {
                                process.inputStream.bufferedReader().forEachLine { line ->
                                    Log.d(TAG, "tar: $line")
                                }
                            } catch (_: IOException) {}
                        }
                        outputReader.start()
                        
                        // 读取 tar 的错误输出
                        val errorReader = Thread {
                            try {
                                process.errorStream.bufferedReader().forEachLine { line ->
                                    Log.d(TAG, "tar stderr: $line")
                                }
                            } catch (_: IOException) {}
                        }
                        errorReader.start()

                        val exitCode = process.waitFor()
                        writerThread.join(5000)
                        outputReader.join(3000)
                        errorReader.join(3000)
                        
                        val elapsed = System.currentTimeMillis() - startTime
                        if (exitCode == 0) {
                            Log.i(TAG, "Extracted $assetPath in ${elapsed}ms")
                        } else {
                            Log.e(TAG, "Failed to extract $assetPath, exitCode=$exitCode")
                            // 尝试使用 Java 方式解压作为备选
                            Log.i(TAG, "Trying Java-based extraction as fallback...")
                            extractWithJava(context, assetPath, runtimeDir)
                        }
                    }
                }
            }

            // 设置 bin 目录下文件的执行权限
            setExecutablePermissions(runtimeDir)

            // 创建标记文件
            val marker = File(runtimeDir, MARKER_FILE)
            marker.writeText("extracted at ${System.currentTimeMillis()}")
            
            Log.i(TAG, "Runtime extraction completed successfully")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract runtime", e)
            // 清理部分提取的文件
            try {
                runtimeDir.deleteRecursively()
            } catch (_: Exception) {}
            return false
        }
    }

    /**
     * 使用纯 Java 方式解压 tar.gz（备选方案）
     */
    private fun extractWithJava(context: Context, assetPath: String, targetDir: File) {
        try {
            context.assets.open(assetPath).use { assetStream ->
                GZIPInputStream(assetStream).use { gzipStream ->
                    // 简单的 tar 解析实现
                    val symlinks = mutableListOf<Pair<File, String>>()
                    val buffer = ByteArray(512)
                    while (true) {
                        val headerRead = readFully(gzipStream, buffer, 512)
                        if (headerRead < 512) break

                        // 检查是否是空块（tar 结尾）
                        if (buffer.all { it == 0.toByte() }) break

                        // 解析文件名
                        val nameBytes = buffer.copyOfRange(0, 100)
                        val name = String(nameBytes).trimEnd('\u0000')
                        if (name.isEmpty()) continue

                        // 解析文件大小（八进制）
                        val sizeBytes = buffer.copyOfRange(124, 136)
                        val sizeStr = String(sizeBytes).trimEnd('\u0000').trim()
                        val fileSize = if (sizeStr.isNotEmpty()) {
                            sizeStr.toLongOrNull(8) ?: 0L
                        } else 0L

                        // 解析文件类型
                        val typeFlag = buffer[156].toInt().toChar()

                        // 解析前缀（用于长文件名）
                        val prefixBytes = buffer.copyOfRange(345, 500)
                        val prefix = String(prefixBytes).trimEnd('\u0000')
                        
                        val fullName = if (prefix.isNotEmpty()) {
                            "$prefix/$name"
                        } else {
                            name
                        }

                        // 去掉开头的 ./ 
                        val cleanName = fullName.removePrefix("./")

                        val targetFile = File(targetDir, cleanName)

                        when (typeFlag) {
                            '5' -> {
                                // 目录
                                targetFile.mkdirs()
                            }
                            '0', '\u0000' -> {
                                // 普通文件
                                targetFile.parentFile?.mkdirs()
                                
                                val contentBlocks = ((fileSize + 511) / 512).toInt()
                                var remaining = fileSize
                                
                                FileOutputStream(targetFile).use { fos ->
                                    val writeBuffer = ByteArray(8192)
                                    while (remaining > 0) {
                                        val toRead = minOf(remaining.toInt(), writeBuffer.size)
                                        val read = gzipStream.read(writeBuffer, 0, toRead)
                                        if (read <= 0) break
                                        fos.write(writeBuffer, 0, read)
                                        remaining -= read
                                    }
                                }
                                
                                // 跳过剩余的填充字节
                                val totalRead = fileSize - remaining
                                val paddingNeeded = ((512 - (totalRead % 512)) % 512).toInt()
                                if (paddingNeeded > 0) {
                                    gzipStream.read(ByteArray(paddingNeeded))
                                }
                            }
                            '2' -> {
                                // 符号链接 - 读取链接目标，延迟处理
                                val linkTargetBytes = buffer.copyOfRange(157, 257)
                                val linkTarget = String(linkTargetBytes).trimEnd('\u0000')
                                symlinks.add(Pair(targetFile, linkTarget))
                                // 跳过数据块
                                skipBlocks(gzipStream, fileSize)
                            }
                            else -> {
                                // 其他类型，跳过数据块
                                skipBlocks(gzipStream, fileSize)
                            }
                        }
                    }

                    // 处理符号链接
                    for ((linkFile, linkTarget) in symlinks) {
                        try {
                            Os.symlink(linkTarget, linkFile.absolutePath)
                            Log.d(TAG, "Created symlink: ${linkFile.name} -> $linkTarget")
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to create symlink: ${linkFile.name} -> $linkTarget: ${e.message}")
                            // 备选方案：如果链接目标存在，复制文件
                            try {
                                val targetSource = File(linkFile.parentFile, linkTarget)
                                if (targetSource.exists()) {
                                    targetSource.copyTo(linkFile, overwrite = true)
                                    Log.d(TAG, "Copied ${targetSource.name} to ${linkFile.name} as symlink fallback")
                                }
                            } catch (copyEx: Exception) {
                                Log.w(TAG, "Fallback copy also failed for ${linkFile.name}: ${copyEx.message}")
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Java-based extraction failed for $assetPath", e)
            throw e
        }
    }

    private fun skipBlocks(stream: java.io.InputStream, size: Long) {
        val blocks = ((size + 511) / 512).toInt()
        val buffer = ByteArray(512)
        repeat(blocks) {
            readFully(stream, buffer, 512)
        }
    }

    private fun readFully(stream: java.io.InputStream, buffer: ByteArray, length: Int): Int {
        var totalRead = 0
        while (totalRead < length) {
            val read = stream.read(buffer, totalRead, length - totalRead)
            if (read <= 0) return totalRead
            totalRead += read
        }
        return totalRead
    }

    /**
     * 设置 bin 目录下所有文件的执行权限
     */
    private fun setExecutablePermissions(runtimeDir: File) {
        val binDir = File(runtimeDir, "bin")
        if (binDir.exists() && binDir.isDirectory) {
            binDir.listFiles()?.forEach { file ->
                file.setExecutable(true, false)
                Log.d(TAG, "Set executable: ${file.name}")
            }
        }
        
        // 某些 .so 文件也可能需要执行权限
        val libDir = File(runtimeDir, "lib")
        if (libDir.exists() && libDir.isDirectory) {
            libDir.listFiles()?.forEach { file ->
                if (file.isFile && file.name.endsWith(".so")) {
                    file.setExecutable(true, false)
                }
            }
        }
    }

    /**
     * 获取运行时环境变量前缀命令
     * 用于在执行命令前设置 PATH 和 LD_LIBRARY_PATH
     */
    fun getEnvPrefix(context: Context): String {
        val runtimeDir = getRuntimeDir(context).absolutePath
        return "export PATH=$runtimeDir/bin:\$PATH LD_LIBRARY_PATH=$runtimeDir/lib HOME=$runtimeDir && "
    }

    /**
     * 获取运行时环境变量前缀（如果运行时已就绪）
     * 首次调用时会尝试提取运行时
     * @return 环境变量前缀字符串，如果运行时不可用则返回 null
     */
    fun getEnvPrefixIfAvailable(context: Context): String? {
        return try {
            if (!isRuntimeExtracted(context)) {
                extractIfNeeded(context)
            }
            if (isRuntimeExtracted(context)) {
                getEnvPrefix(context)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "getEnvPrefixIfAvailable failed", e)
            null
        }
    }

    /**
     * 获取运行时信息（用于调试）
     */
    fun getRuntimeInfo(context: Context): String {
        val runtimeDir = getRuntimeDir(context)
        if (!runtimeDir.exists()) {
            return "Runtime not extracted"
        }

        val sb = StringBuilder()
        sb.appendLine("Runtime dir: ${runtimeDir.absolutePath}")
        sb.appendLine("Extracted: ${isRuntimeExtracted(context)}")

        val binDir = File(runtimeDir, "bin")
        if (binDir.exists()) {
            sb.appendLine("Bin files:")
            binDir.listFiles()?.forEach { file ->
                sb.appendLine("  ${file.name} (${if (file.canExecute()) "executable" else "not executable"}, ${file.length()} bytes)")
            }
        }

        val libDir = File(runtimeDir, "lib")
        if (libDir.exists()) {
            sb.appendLine("Lib files count: ${libDir.listFiles()?.size ?: 0}")
        }

        return sb.toString()
    }
}