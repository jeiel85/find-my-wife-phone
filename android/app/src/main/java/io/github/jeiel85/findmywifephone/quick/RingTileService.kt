package io.github.jeiel85.findmywifephone.quick

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.jeiel85.findmywifephone.core.Hub

/**
 * Quick Settings tile "○○ 폰 울리기": pull down the shade from any app and tap. With one paired
 * phone it rings it right away (through [ShortcutActivity], which also opens the progress card);
 * with none or several it just opens the app to choose.
 */
class RingTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        Hub.init(this)
        val peers = Hub.store.peers.value
        val tile = qsTile ?: return
        tile.label = if (peers.size == 1) "${peers[0].name} 폰 울리기" else "폰 울리기"
        tile.state = if (peers.isEmpty()) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        Hub.init(this)
        if (isLocked) unlockAndRun(::open) else open()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun open() {
        val peers = Hub.store.peers.value
        val intent = Intent(this, ShortcutActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (peers.size == 1) intent.setAction(Shortcuts.ACTION_RING).putExtra(Shortcuts.EXTRA_PEER, peers[0].id)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION") startActivityAndCollapse(intent)
        }
    }
}
