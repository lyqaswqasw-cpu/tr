package com.example

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions

class LoopLiveApp : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            val options = FirebaseOptions.Builder()
                .setApplicationId("1:725987052105:android:0b0f98bbccb6c874bdcd98")
                .setProjectId("loop-7e3d9")
                .setApiKey("AIzaSyBmUJGFkIPMyN2tOVk3LFe4u7ZLky6CKkQ")
                .setStorageBucket("loop-7e3d9.firebasestorage.app")
                .build()

            FirebaseApp.initializeApp(this, options)
            Log.d("LoopLiveApp", "Firebase initialized successfully manually!")
        } catch (e: Throwable) {
            Log.e("LoopLiveApp", "Failed to initialize Firebase", e)
        }

        // Initialize Notification Channel
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    "private_chats",
                    "الرسائل الخاصة",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "تنبيهات عند تلقي رسائل جديدة في الدردشة الخاصة"
                }
                val notificationManager = getSystemService(NotificationManager::class.java)
                notificationManager?.createNotificationChannel(channel)
                Log.d("LoopLiveApp", "Notification Channel created successfully!")
            }
        } catch (e: Throwable) {
            Log.e("LoopLiveApp", "Failed to create Notification Channel", e)
        }
    }
}
