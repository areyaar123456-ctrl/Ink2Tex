package dev.ink2tex

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import dev.ink2tex.storage.InkDatabase
import dev.ink2tex.storage.InkRepository
import dev.ink2tex.modelmanager.ModelStore
import dev.ink2tex.ocr.LocalOcrEngine

class InkApplication : Application() {
 val database by lazy { InkDatabase.create(this) }
 val repository by lazy { InkRepository(this, database) }
 val models by lazy { ModelStore(this) }
 val engine by lazy { LocalOcrEngine(this, models) }
 override fun onCreate() {
  super.onCreate()
  getSystemService(NotificationManager::class.java).apply {
   createNotificationChannel(NotificationChannel("capture", "Active screen capture", NotificationManager.IMPORTANCE_LOW))
   createNotificationChannel(NotificationChannel("work", "Conversions and model downloads", NotificationManager.IMPORTANCE_LOW))
  }
 }
}
