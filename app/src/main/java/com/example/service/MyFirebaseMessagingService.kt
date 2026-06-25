package com.example.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import java.net.URL

class MyFirebaseMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        Log.d("FCM", "From: ${remoteMessage.from}")

        // 5. ميزة الحذف النهائي من أجهزة المستخدمين
        // Handle Data Message for Remote Delete
        if (remoteMessage.data.containsKey("action") && remoteMessage.data["action"] == "delete") {
            val notificationId = remoteMessage.data["notificationId"]?.toIntOrNull()
            if (notificationId != null) {
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.cancel(notificationId)
                Log.d("FCM", "Notification $notificationId canceled remotely")
            }
            return
        }

        // 4. استقبال الإشعار ودعم الصورة الاختيارية
        val title = remoteMessage.notification?.title ?: remoteMessage.data["title"]
        val body = remoteMessage.notification?.body ?: remoteMessage.data["body"]
        val imageUrl = remoteMessage.data["image_url"]
        val notificationId = remoteMessage.data["notificationId"]?.toIntOrNull() ?: System.currentTimeMillis().toInt()

        if (title != null && body != null) {
            sendNotification(title, body, imageUrl, notificationId)
        }
    }

    private fun sendNotification(title: String, body: String, imageUrl: String?, notificationId: Int) {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("target_screen", "notifications")
        }
        val pendingIntent = PendingIntent.getActivity(
            this, notificationId, intent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )

        val channelId = "25live_notifications_channel"
        val notificationBuilder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info) // Using system icon as fallback
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)

        if (!imageUrl.isNullOrBlank()) {
            try {
                val url = URL(imageUrl)
                val connection = url.openConnection()
                connection.doInput = true
                connection.connect()
                val input = connection.getInputStream()
                val bitmap = BitmapFactory.decodeStream(input)
                notificationBuilder.setStyle(NotificationCompat.BigPictureStyle().bigPicture(bitmap))
            } catch (e: Exception) {
                Log.e("FCM", "Error loading notification image: ${e.message}")
            }
        }

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "إشعارات تطبيق 25 Live",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "قناة إشعارات البث المباشر والأخبار"
            }
            notificationManager.createNotificationChannel(channel)
        }

        notificationManager.notify(notificationId, notificationBuilder.build())
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d("FCM", "Refreshed token: $token")
    }
}
