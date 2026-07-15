/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.service

import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import me.rerere.rikkahub.ANNOUNCEMENT_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.utils.sendNotification

/**
 * 接收 FCM 推送的公告通知。
 *
 * 推送数据约定（data payload，App 在前台/后台均可收到）：
 * - title: 通知标题（可选，缺省为"橘瓣公告"）
 * - body / message: 通知正文
 * - url: 点击跳转链接（可选）
 *
 * Cloud Functions 端发送示例见 functions/index.js
 */
class AnnouncementMessagingService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "AnnouncementFCM"
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        Log.d(TAG, "收到推送: from=${remoteMessage.from}")

        val data = remoteMessage.data
        val title = data["title"] ?: remoteMessage.notification?.title ?: "橘瓣公告"
        val body = data["body"] ?: data["message"] ?: remoteMessage.notification?.body ?: ""

        if (body.isBlank()) {
            Log.w(TAG, "推送正文为空，忽略")
            return
        }

        val clickUrl = data["url"]

        val pendingIntent = if (!clickUrl.isNullOrBlank()) {
            // 带链接：通过 RouteActivity 转交系统浏览器打开
            val intent = Intent(this, RouteActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("announcement_url", clickUrl)
            }
            PendingIntent.getActivity(
                this, System.currentTimeMillis().toInt(),
                intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        } else {
            // 无链接：点击打开 App
            val intent = Intent(this, RouteActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            PendingIntent.getActivity(
                this, System.currentTimeMillis().toInt(),
                intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        sendNotification(
            channelId = ANNOUNCEMENT_NOTIFICATION_CHANNEL_ID,
            notificationId = System.currentTimeMillis().toInt()
        ) {
            this.title = title
            content = body
            autoCancel = true
            useBigTextStyle = true
            contentIntent = pendingIntent
        }
    }

    /**
     * Token 刷新回调。若以后需要"按设备定向推送"，把 token 上报到你的后端即可。
     * 当前只做日志记录。
     */
    override fun onNewToken(token: String) {
        Log.d(TAG, "FCM Token 刷新: $token")
    }
}
