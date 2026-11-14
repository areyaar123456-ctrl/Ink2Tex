package dev.ink2tex.ocr

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.*
import dev.ink2tex.MainActivity
import dev.ink2tex.R
import java.util.UUID

object WorkNotifications {
 fun foreground(context: Context, id: UUID, number: Int, title: String, type: Int): ForegroundInfo {
  val cancel=WorkManager.getInstance(context).createCancelPendingIntent(id)
  val open=PendingIntent.getActivity(context,0,Intent(context,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  val notification=NotificationCompat.Builder(context,"work").setSmallIcon(R.drawable.ic_ink).setContentTitle(title).setContentText("On this device · tap to open Ink2TeX").setContentIntent(open).setOngoing(true).setProgress(0,0,true).addAction(0,"Cancel",cancel).build()
  return ForegroundInfo(number,notification,type)
 }
}
