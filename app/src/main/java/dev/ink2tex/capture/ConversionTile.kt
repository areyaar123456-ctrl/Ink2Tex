package dev.ink2tex.capture

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.ink2tex.MainActivity

class ConversionTile: TileService() {
 // API 31–33 have no PendingIntent overload. The deprecated call is strictly guarded below.
 override fun onStartListening() { super.onStartListening();qsTile?.apply { state=if(CaptureService.active.value) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE;updateTile() } }
 @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
 override fun onClick() {
  super.onClick()
  val intent=Intent(this,MainActivity::class.java).setAction("dev.ink2tex.START").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
  if(Build.VERSION.SDK_INT>=34) startActivityAndCollapse(PendingIntent.getActivity(this,4,intent,PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
  else @Suppress("DEPRECATION") startActivityAndCollapse(intent)
 }
}
