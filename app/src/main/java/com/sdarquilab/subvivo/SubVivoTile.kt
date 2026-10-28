package com.sdarquilab.subvivo

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * 빠른 설정(화면 위에서 끌어내리는 메뉴)의 'SubVivo' 타일.
 * 자막이 켜져 있으면 누르는 즉시 끈다. 꺼져 있으면 앱을 연다.
 */
class SubVivoTile : TileService() {
    companion object {
        fun refresh(ctx: Context) {
            runCatching { requestListeningState(ctx, ComponentName(ctx, SubVivoTile::class.java)) }
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        update()
    }

    private fun update() {
        val t = qsTile ?: return
        val on = LiveService.running
        t.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = "SubVivo"
        if (Build.VERSION.SDK_INT >= 29) t.subtitle = if (on) "눌러서 끄기" else "꺼짐"
        t.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        if (LiveService.running) {
            LiveService.stop(this)
            qsTile?.let {
                it.state = Tile.STATE_INACTIVE
                if (Build.VERSION.SDK_INT >= 29) it.subtitle = "꺼짐"
                it.updateTile()
            }
            return
        }
        val open = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (Build.VERSION.SDK_INT >= 34) {
            val pi = PendingIntent.getActivity(this, 7, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(open)
        }
    }
}
