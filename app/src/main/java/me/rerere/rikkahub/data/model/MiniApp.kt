package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * Mini App 配置，用于保存用户添加的 Telegram 风格 mini app 元信息。
 *
 * @param id 唯一标识
 * @param name 显示名称
 * @param url 加载的网页地址
 * @param icon 图标（可选，留空时使用默认图标）
 * @param description 描述（可选）
 * @param order 排序权重
 */
@Serializable
data class MiniApp(
    val id: Uuid = Uuid.random(),
    val name: String = "",
    val url: String = "",
    val icon: String = "",
    val description: String = "",
    val order: Int = 0,
) {
    companion object
}
