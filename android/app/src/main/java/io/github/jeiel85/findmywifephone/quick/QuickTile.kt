package io.github.jeiel85.findmywifephone.quick

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.TileService
import android.util.Log
import io.github.jeiel85.findmywifephone.R

/** Android 13+ can offer to add our Quick Settings tile in one dialog; older versions need the edit menu. */
object QuickTile {
    fun canRequest() = Build.VERSION.SDK_INT >= 33

    fun request(context: Context) {
        if (Build.VERSION.SDK_INT < 33) return
        runCatching {
            context.getSystemService(StatusBarManager::class.java).requestAddTileService(
                ComponentName(context, RingTileService::class.java),
                context.getString(R.string.tile_ring),
                Icon.createWithResource(context, R.drawable.ic_tile_ring),
                context.mainExecutor,
            ) { result -> Log.i("QuickTile", "add tile result $result") }
        }.onFailure { Log.w("QuickTile", "add tile request failed", it) }
    }

    /**
     * Pairs changed: asks the system to bind the tile now, so its name ("○○ 폰 울리기") is right the
     * next time the shade opens instead of one opening later. Harmless when the tile was never added.
     */
    fun refresh(context: Context) {
        runCatching { TileService.requestListeningState(context, ComponentName(context, RingTileService::class.java)) }
            .onFailure { Log.w("QuickTile", "tile refresh failed", it) }
    }
}
